package com.guessmobot;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private EditText apiKeyInput;
    private TextView statusText;
    private Button startStopButton;
    static TextView answerLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiKeyInput = findViewById(R.id.api_key_input);
        statusText = findViewById(R.id.status_text);
        answerLog = findViewById(R.id.answer_log);
        Button saveButton = findViewById(R.id.save_button);
        Button accessibilityButton = findViewById(R.id.accessibility_button);
        startStopButton = findViewById(R.id.start_stop_button);

        SharedPreferences prefs = getSharedPreferences("guessmobot", MODE_PRIVATE);
        String savedKey = prefs.getString("gemini_api_key", "");
        if (savedKey.isEmpty()) savedKey = prefs.getString("claude_api_key", "");
        if (!savedKey.isEmpty()) apiKeyInput.setText(savedKey);

        saveButton.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (TextUtils.isEmpty(key)) {
                Toast.makeText(this, "Enter your API key", Toast.LENGTH_SHORT).show();
                return;
            }
            if (key.startsWith("sk-ant")) {
                prefs.edit().putString("claude_api_key", key).apply();
            } else {
                prefs.edit().putString("gemini_api_key", key).apply();
            }
            Toast.makeText(this, "✅ API key saved!", Toast.LENGTH_SHORT).show();
        });

        accessibilityButton.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        startStopButton.setOnClickListener(v -> {
            AutoPlayService.botRunning = !AutoPlayService.botRunning;
            updateStartStopButton();
            if (AutoPlayService.botRunning) {
                Toast.makeText(this, "🤖 Bot started! Switch to the game now.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "⏹ Bot stopped.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateStartStopButton() {
        if (startStopButton == null) return;
        if (AutoPlayService.botRunning) {
            startStopButton.setText("⏹ STOP BOT");
            startStopButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#e53935")));
        } else {
            startStopButton.setText("▶ START BOT");
            startStopButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#43a047")));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAccessibilityEnabled()) {
            statusText.setText("✅ ACTIVE — Press START BOT then open the game");
        } else {
            statusText.setText("❌ Not enabled — complete Step 2 below");
        }
        updateStartStopButton();
    }

    private boolean isAccessibilityEnabled() {
        String services = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null &&
                services.contains(getPackageName() + "/" + AutoPlayService.class.getName());
    }
}
