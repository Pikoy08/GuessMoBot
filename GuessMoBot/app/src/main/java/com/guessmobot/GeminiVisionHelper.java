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

public class GeminiVisionHelper {
    private static final String TAG = "GuessMoBot";
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent";

    public static String getAnswer(Bitmap bitmap, String apiKey) throws Exception {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 540, 1196, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        scaled.recycle();

        JSONObject imagePart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", base64Image);
        imagePart.put("inline_data", inlineData);

        JSONObject textPart = new JSONObject();
        textPart.put("text", "This is a screenshot of the 'Find the Emoji' game. " +
            "There is a CHALLENGE text and two emoji buttons: CHOICE A and CHOICE B. " +
            "Read the challenge and decide which choice is correct. " +
            "Reply with ONLY the letter A or B. Nothing else.");

        JSONArray parts = new JSONArray();
        parts.put(imagePart);
        parts.put(textPart);

        JSONObject content = new JSONObject();
        content.put("parts", parts);

        JSONArray contents = new JSONArray();
        contents.put(content);

        JSONObject payload = new JSONObject();
        payload.put("contents", contents);

        URL url = new URL(API_URL + "?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        String body = new Scanner(stream).useDelimiter("\\A").next();

        if (code != 200) throw new Exception("Gemini error " + code + ": " + body);

        JSONObject json = new JSONObject(body);
        String text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .toUpperCase();

        Log.d(TAG, "Gemini answered: " + text);
        if (text.startsWith("A")) return "A";
        if (text.startsWith("B")) return "B";
        return "A";
    }
}
