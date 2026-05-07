package com.guessmobot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoPlayService extends AccessibilityService {

    private static final String TAG = "GuessMoBot";
    private static final long INTERVAL_MS = 15000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isProcessing = false;
    private Runnable periodicChecker;

    static boolean botRunning = false; // Controlled by MainActivity button
    static AutoPlayService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Service connected ✅");
        startPeriodicChecker();
    }

    private void startPeriodicChecker() {
        periodicChecker = new Runnable() {
            @Override
            public void run() {
                if (botRunning && !isProcessing) {
                    Log.d(TAG, "Bot running - capturing screen...");
                    isProcessing = true;
                    captureAndAnswer();
                }
                mainHandler.postDelayed(this, INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(periodicChecker, INTERVAL_MS);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void captureAndAnswer() {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult result) {
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                            result.getHardwareBuffer(),
                            result.getColorSpace()
                    ).copy(Bitmap.Config.ARGB_8888, false);
                    result.getHardwareBuffer().close();

                    SharedPreferences prefs = getSharedPreferences("guessmobot", MODE_PRIVATE);
                    String geminiKey = prefs.getString("gemini_api_key", "");
                    String claudeKey = prefs.getString("claude_api_key", "");
                    String apiKey = !geminiKey.isEmpty() ? geminiKey : claudeKey;

                    if (apiKey.isEmpty()) {
                        Log.e(TAG, "No API key!");
                        updateLog("❌ No API key saved!");
                        isProcessing = false;
                        return;
                    }

                    final String key = apiKey;
                    executor.execute(() -> {
                        try {
                            updateLog("📸 Analyzing screen...");
                            String answer = GeminiVisionHelper.getAnswer(bitmap, key);
                            bitmap.recycle();
                            Log.d(TAG, "Answer: CHOICE " + answer);

                            mainHandler.post(() -> {
                                updateLog("Last answer: CHOICE " + answer + " ✅");
                                tapChoice(answer);
                                mainHandler.postDelayed(() -> {
                                    tapSubmit();
                                    mainHandler.postDelayed(() -> {
                                        isProcessing = false;
                                    }, 2000);
                                }, 800);
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error: " + e.getMessage());
                            updateLog("❌ Error: " + e.getMessage());
                            isProcessing = false;
                        }
                    });
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, "Screenshot failed: " + errorCode);
                    updateLog("❌ Screenshot failed!");
                    isProcessing = false;
                }
            });
    }

    private void tapChoice(String choice) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("CHOICE " + choice);
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(0);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                recycleAll(nodes);
                tapAt(bounds.centerX(), bounds.centerY());
                Log.d(TAG, "Tapped CHOICE " + choice + " via text");
                return;
            }
            // Fallback: tap by screen position
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            int w = screen.width();
            int h = screen.height();
            int y = (int)(h * 0.75f);
            int x = choice.equals("A") ? (int)(w * 0.27f) : (int)(w * 0.73f);
            tapAt(x, y);
            Log.d(TAG, "Tapped by position: " + x + ", " + y);
        } finally {
            root.recycle();
        }
    }

    private void tapSubmit() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Choose the correct option");
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(0);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                recycleAll(nodes);
                tapAt(bounds.centerX(), bounds.centerY());
                return;
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                Rect b = new Rect();
                r.getBoundsInScreen(b);
                tapAt(b.centerX(), (int)(b.height() * 0.83f));
                r.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private void tapAt(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                    .build();
            dispatchGesture(gesture, null, null);
        }
    }

    private void updateLog(String text) {
        mainHandler.post(() -> {
            if (MainActivity.answerLog != null) MainActivity.answerLog.setText(text);
        });
    }

    private void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (periodicChecker != null) mainHandler.removeCallbacks(periodicChecker);
        executor.shutdown();
    }
}
