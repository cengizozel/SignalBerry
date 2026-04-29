package com.example.signalberry;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import static com.example.signalberry.Utils.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

public class ServerConnect extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.server_connect);
        setTitle("SignalBerry");

        EditText ipField = findViewById(R.id.input_ip);
        EditText numberField = findViewById(R.id.input_number);
        Button connectBtn = findViewById(R.id.btn_connect);

        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);

        // Prefill from last run
        ipField.setText(prefs.getString("ip", ""));
        numberField.setText(prefs.getString("number", ""));

        connectBtn.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            String numberInput = numberField.getText().toString().trim();
            if (ip.isEmpty() || numberInput.isEmpty()) {
                Toast.makeText(this, "Enter server IP and your Signal number", Toast.LENGTH_SHORT).show();
                return;
            }

            connectBtn.setEnabled(false);
            new Thread(() -> {
                boolean ok = false;
                String canonicalNumber = null;
                try {
                    String base = normalizeBase(ip);

                    // 1) Health
                    int health = httpCodeGet(base + "/v1/health");
                    if (health != 200 && health != 204) throw new RuntimeException("Health check failed: " + health);

                    // 2) Accounts
                    String accountsJson = httpGet(base + "/v1/accounts");
                    canonicalNumber = findMatchingAccount(accountsJson, numberInput);
                    if (canonicalNumber == null) {
                        throw new RuntimeException("Number not linked on server");
                    }

                    ok = true;
                } catch (Exception ignored) {
                    ok = false;
                }

                String finalCanonical = canonicalNumber;
                boolean finalOk = ok;
                runOnUiThread(() -> {
                    connectBtn.setEnabled(true);
                    if (finalOk) {
                        prefs.edit()
                                .putString("ip", ip)
                                .putString("number", finalCanonical != null ? finalCanonical : numberInput)
                                .putString("bridge", deriveBridgeBase(ip))
                                .apply();
                        startActivity(new Intent(ServerConnect.this, Messages.class));
                    } else {
                        Toast.makeText(this, "Could not verify server/number", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });
    }

    private static String findMatchingAccount(String accountsJson, String userInput) {
        String userDigits = digits(userInput);
        if (userDigits.isEmpty()) return null;

        try {
            JSONArray arr = new JSONArray(accountsJson);
            for (int i = 0; i < arr.length(); i++) {
                String candidate;
                if (arr.get(i) instanceof JSONObject) {
                    candidate = ((JSONObject) arr.get(i)).optString("number", "");
                } else {
                    candidate = arr.optString(i, "");
                }
                String candDigits = digits(candidate);
                if (!candDigits.isEmpty() && candDigits.equals(userDigits)) {
                    return candidate;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}
