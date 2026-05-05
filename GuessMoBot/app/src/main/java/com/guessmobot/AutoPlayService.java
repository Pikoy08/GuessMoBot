package com.guessmobot;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
    private static final long DEBOUNCE_MS = 4000; // Min 4s between attempts
    private static final long CLICK_DELAY_MS = 600; // Delay before submitting
    private static final long RESET_DELAY_MS = 2000; // Delay before allowing next attempt

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean isProcessing = false;
    private long lastAttemptTime = 0;

    // ─── Accessibility Event Entry Point ──────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isProcessing) return;

        long now = System.currentTimeMillis();
        if (now - lastAttemptTime < DEBOUNCE_MS) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (isGameActive(root) && isChallengeReady(root)) {
                lastAttemptTime = now;
                isProcessing = true;
                Log.d(TAG, "Challenge detected! Capturing screen...");
                captureAndAnswer();
            }
        } finally {
            root.recycle();
        }
    }

    // ─── Detection Helpers ────────────────────────────────────────────────────

    /** True if CHOICE A and CHOICE B buttons are both present on screen */
    private boolean isGameActive(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> choiceA = root.findAccessibilityNodeInfosByText("CHOICE A");
        List<AccessibilityNodeInfo> choiceB = root.findAccessibilityNodeInfosByText("CHOICE B");
        boolean active = (choiceA != null && !choiceA.isEmpty()) &&
                         (choiceB != null && !choiceB.isEmpty());
        recycleAll(choiceA);
        recycleAll(choiceB);
        return active;
    }

    /** True only when loading overlay is gone and challenge text is visible */
    private boolean isChallengeReady(AccessibilityNodeInfo root) {
        // Reject if loading spinner is visible
        List<AccessibilityNodeInfo> loading = root.findAccessibilityNodeInfosByText("Generating challenge");
        boolean isLoading = loading != null && !loading.isEmpty();
        recycleAll(loading);
        if (isLoading) return false;

        // Reject if submit button says "Generating"
        List<AccessibilityNodeInfo> submit = root.findAccessibilityNodeInfosByText("Choose the correct option");
        boolean hasSubmit = submit != null && !submit.isEmpty();
        recycleAll(submit);
        return hasSubmit;
    }

    // ─── Screenshot + Claude Vision + Click ──────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void captureAndAnswer() {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {

                @Override
                public void onSuccess(@NonNull ScreenshotResult result) {
                    // Copy hardware bitmap to software bitmap for JPEG compression
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                            result.getHardwareBuffer(),
                            result.getColorSpace()
                    ).copy(Bitmap.Config.ARGB_8888, false);
                    result.getHardwareBuffer().close();

                    // Get API key
                    SharedPreferences prefs = getSharedPreferences("guessmobot", MODE_PRIVATE);
                    String apiKey = prefs.getString("claude_api_key", "");

                    if (apiKey.isEmpty()) {
                        Log.e(TAG, "No API key saved. Open the app and set your Claude API key.");
                        isProcessing = false;
                        return;
                    }

                    // Call Claude Vision in background thread
                    executor.execute(() -> {
                        try {
                            String answer = ClaudeVisionHelper.getAnswer(bitmap, apiKey);
                            bitmap.recycle();
                            Log.d(TAG, "✅ Claude answered: CHOICE " + answer);

                            // Click on main thread
                            mainHandler.post(() -> {
                                updateAnswerLog("Last answer: CHOICE " + answer);
                                clickChoice(answer);

                                // Small delay then submit
                                mainHandler.postDelayed(() -> {
                                    clickSubmit();
                                    // Reset after challenge processes
                                    mainHandler.postDelayed(() -> {
                                        isProcessing = false;
                                        Log.d(TAG, "Ready for next challenge.");
                                    }, RESET_DELAY_MS);
                                }, CLICK_DELAY_MS);
                            });

                        } catch (Exception e) {
                            Log.e(TAG, "Claude API error: " + e.getMessage());
                            isProcessing = false;
                        }
                    });
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, "Screenshot failed with code: " + errorCode);
                    isProcessing = false;
                }
            });
    }

    // ─── UI Interaction Helpers ───────────────────────────────────────────────

    /** Finds and clicks the CHOICE A or CHOICE B button */
    private void clickChoice(String choice) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("CHOICE " + choice);
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "Could not find CHOICE " + choice + " button");
                return;
            }
            AccessibilityNodeInfo node = nodes.get(0);
            // Try direct click first, then parent
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                }
            }
            recycleAll(nodes);
        } finally {
            root.recycle();
        }
    }

    /** Clicks the "Choose the correct option" submit button */
    private void clickSubmit() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Choose the correct option");
            if (nodes != null && !nodes.isEmpty()) {
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                recycleAll(nodes);
                Log.d(TAG, "Submitted answer.");
            }
        } finally {
            root.recycle();
        }
    }

    /** Updates the answer log in MainActivity if it's visible */
    private void updateAnswerLog(String text) {
        if (MainActivity.answerLog != null) {
            MainActivity.answerLog.setText(text);
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) n.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "GuessMo Bot service connected ✅");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
