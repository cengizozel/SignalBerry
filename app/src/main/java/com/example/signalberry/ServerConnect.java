package com.example.signalberry;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
        numberField.setText(prefs.getString("number", "")); // previously used number if any

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
                        // Save only on success
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

    // ----- Helpers -----

    private static String normalizeBase(String hostPort) {
        if (hostPort.equals("localhost:5000") || hostPort.equals("127.0.0.1:5000"))
            hostPort = "10.0.2.2:5000";
        if (!hostPort.startsWith("http://") && !hostPort.startsWith("https://"))
            hostPort = "http://" + hostPort;
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        return hostPort;
    }

    private static int httpCodeGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                (code >= 400 ? c.getErrorStream() : c.getInputStream())))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // returns the canonical number from API if the user's input matches any account, else null
    private static String findMatchingAccount(String accountsJson, String userInput) {
        String userDigits = onlyDigits(userInput);
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
                String candDigits = onlyDigits(candidate);
                if (!candDigits.isEmpty() && candDigits.equals(userDigits)) {
                    return candidate; // return canonical form from server (usually with +)
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static String onlyDigits(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }

    private static String deriveBridgeBase(String ipOrBase) {
        // normalize to host (strip scheme/port)
        String base = ipOrBase.trim();
        if (base.startsWith("http://"))  base = base.substring(7);
        else if (base.startsWith("https://")) base = base.substring(8);
        // base now like 192.168.1.24:5000 -> take host before first ':'
        int colon = base.indexOf(':');
        String host = (colon > 0) ? base.substring(0, colon) : base;
        return "http://" + host + ":9099";
    }
}
