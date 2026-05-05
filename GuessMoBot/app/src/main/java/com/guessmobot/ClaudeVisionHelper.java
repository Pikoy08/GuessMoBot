package com.guessmobot;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClaudeVisionHelper {

    private static final String TAG = "GuessMoBot";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001"; // Fast and cheap for this task

    private static final String PROMPT =
            "This is a screenshot of the 'Find the Emoji' game on guessmopay.pro.\n" +
            "The screen shows a CHALLENGE text and two emoji answer buttons: CHOICE A and CHOICE B.\n\n" +
            "Read the challenge carefully and determine which choice correctly answers it.\n" +
            "For example: if the challenge says 'Find the food emoji like apple' and CHOICE A is grapes and CHOICE B is a robot, answer A.\n\n" +
            "Reply with ONLY a single letter: A or B. Nothing else. No punctuation, no explanation.";

    /**
     * Sends the screenshot to Claude Vision API and returns "A" or "B".
     */
    public static String getAnswer(Bitmap bitmap, String apiKey) throws Exception {
        // Scale down and compress to JPEG to reduce payload size
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 540, 1196, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        scaled.recycle();

        // Build request JSON
        JSONObject imageSource = new JSONObject();
        imageSource.put("type", "base64");
        imageSource.put("media_type", "image/jpeg");
        imageSource.put("data", base64Image);

        JSONObject imageBlock = new JSONObject();
        imageBlock.put("type", "image");
        imageBlock.put("source", imageSource);

        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", PROMPT);

        JSONArray content = new JSONArray();
        content.put(imageBlock);
        content.put(textBlock);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        JSONArray messages = new JSONArray();
        messages.put(userMessage);

        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        payload.put("max_tokens", 5);
        payload.put("messages", messages);

        // HTTP POST
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new Scanner(stream).useDelimiter("\\A").next();

        if (code != 200) {
            throw new Exception("Claude API error " + code + ": " + responseBody);
        }

        // Parse response
        JSONObject json = new JSONObject(responseBody);
        String text = json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .toUpperCase();

        Log.d(TAG, "Claude raw response: " + text);

        // Return A or B
        if (text.startsWith("A")) return "A";
        if (text.startsWith("B")) return "B";
        if (text.contains("A")) return "A";
        if (text.contains("B")) return "B";
        return "A"; // fallback
    }
}
