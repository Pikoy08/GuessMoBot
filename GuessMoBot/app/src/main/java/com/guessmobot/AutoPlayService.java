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
    private static final long DEBOUNCE_MS = 6000;
    private static final long CLICK_DELAY_MS = 800;
    private static final long RESET_DELAY_MS = 3000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isProcessing = false;
    private long lastAttemptTime = 0;
    private boolean isOnGamePage = false;
    private Runnable periodicChecker;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "GuessMo Bot connected ✅");
        startPeriodicChecker();
    }

    // Periodic checker every 3 seconds - takes screenshot when on game page
    private void startPeriodicChecker() {
        periodicChecker = new Runnable() {
            @Override
            public void run() {
                if (isOnGamePage && !isProcessing) {
                    long now = System.currentTimeMillis();
                    if (now - lastAttemptTime >= DEBOUNCE_MS) {
                        Log.d(TAG, "Periodic check - taking screenshot...");
                        lastAttemptTime = now;
                        isProcessing = true;
                        captureAndAnswer();
                    }
                }
                mainHandler.postDelayed(this, 3000);
            }
        };
        mainHandler.postDelayed(periodicChecker, 3000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Detect if we are on guessmopay.pro emoji game page
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        boolean isBrowser = pkg.contains("brave") || pkg.contains("chrome") ||
                            pkg.contains("firefox") || pkg.contains("browser");

        if (!isBrowser) return;

        // Check URL bar for game URL
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            String url = getUrlFromBrowser(root);
            boolean wasOnGame = isOnGamePage;
            isOnGamePage = url != null && url.contains("guessmopay") && url.contains("emoji");

            if (isOnGamePage && !wasOnGame) {
                Log.d(TAG, "Entered game page! Starting auto-answer...");
                updateStatus("✅ ACTIVE — Game detected!");
            } else if (!isOnGamePage && wasOnGame) {
                Log.d(TAG, "Left game page.");
                updateStatus("✅ ACTIVE — Open guessmopay.pro to start earning");
            }
        } finally {
            root.recycle();
        }
    }

    private String getUrlFromBrowser(AccessibilityNodeInfo root) {
        // Try to find URL from address bar
        List<AccessibilityNodeInfo> urlBars = root.findAccessibilityNodeInfosByViewId("com.brave.browser:id/url_bar");
        if (urlBars == null || urlBars.isEmpty()) {
            urlBars = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar");
        }
        if (urlBars != null && !urlBars.isEmpty()) {
            AccessibilityNodeInfo urlBar = urlBars.get(0);
            CharSequence text = urlBar.getText();
            recycleAll(urlBars);
            return text != null ? text.toString() : null;
        }

        // Fallback: search for guessmopay text anywhere
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("guessmopay");
        if (nodes != null && !nodes.isEmpty()) {
            recycleAll(nodes);
            return "guessmopay.pro/?c=emoji";
        }
        return null;
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
                    String apiKey = prefs.getString("gemini_api_key", "");
                    if (apiKey.isEmpty()) apiKey = prefs.getString("claude_api_key", "");

                    if (apiKey.isEmpty()) {
                        Log.e(TAG, "No API key!");
                        isProcessing = false;
                        return;
                    }

                    final String key = apiKey;
                    executor.execute(() -> {
                        try {
                            String answer = GeminiVisionHelper.getAnswer(bitmap, key);
                            bitmap.recycle();
                            Log.d(TAG, "Answer: CHOICE " + answer);

                            mainHandler.post(() -> {
                                updateAnswerLog("Last answer: CHOICE " + answer);
                                tapChoice(answer);
                                mainHandler.postDelayed(() -> {
                                    tapSubmit();
                                    mainHandler.postDelayed(() -> {
                                        isProcessing = false;
                                    }, RESET_DELAY_MS);
                                }, CLICK_DELAY_MS);
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "API error: " + e.getMessage());
                            isProcessing = false;
                        }
                    });
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, "Screenshot failed: " + errorCode);
                    isProcessing = false;
                }
            });
    }

    // Tap using coordinates (more reliable in browser WebViews)
    private void tapChoice(String choice) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Try text-based click first
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("CHOICE " + choice);
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(0);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                recycleAll(nodes);
                // Tap center of the button
                tapAt(bounds.centerX(), bounds.centerY());
                return;
            }

            // Fallback: tap left half for A, right half for B based on screen position
            Rect screenBounds = new Rect();
            root.getBoundsInScreen(screenBounds);
            int screenWidth = screenBounds.width();
            int screenHeight = screenBounds.height();
            int tapY = (int)(screenHeight * 0.72f); // ~72% down the screen
            int tapX = choice.equals("A") ?
                    (int)(screenWidth * 0.28f) :  // Left side for A
                    (int)(screenWidth * 0.72f);    // Right side for B
            tapAt(tapX, tapY);
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
            // Fallback: tap bottom center
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                Rect b = new Rect();
                r.getBoundsInScreen(b);
                tapAt(b.centerX(), (int)(b.height() * 0.82f));
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
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 50);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke).build();
            dispatchGesture(gesture, null, null);
            Log.d(TAG, "Tapped at " + x + ", " + y);
        }
    }

    private void updateStatus(String text) {
        if (MainActivity.answerLog != null) {
            mainHandler.post(() -> MainActivity.answerLog.setText(text));
        }
    }

    private void updateAnswerLog(String text) {
        if (MainActivity.answerLog != null) {
            mainHandler.post(() -> MainActivity.answerLog.setText(text));
        }
    }

    private void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (periodicChecker != null) mainHandler.removeCallbacks(periodicChecker);
        executor.shutdown();
    }
}
