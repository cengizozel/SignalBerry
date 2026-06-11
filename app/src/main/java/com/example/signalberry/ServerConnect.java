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

        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        String savedIp     = prefs.getString("ip", "");
        String savedNumber = prefs.getString("number", "");

        if (!savedIp.isEmpty() && !savedNumber.isEmpty()) {
            startActivity(new Intent(this, Messages.class));
            finish();
            return;
        }

        setContentView(R.layout.server_connect);
        setTitle("SignalBerry");

        EditText ipField = findViewById(R.id.input_ip);
        EditText numberField = findViewById(R.id.input_number);
        Button connectBtn = findViewById(R.id.btn_connect);
        EditText bridgeUrlField = findViewById(R.id.input_bridge_url);
        EditText tokenField     = findViewById(R.id.input_bridge_token);
        EditText cfIdField      = findViewById(R.id.input_cf_id);
        EditText cfSecretField  = findViewById(R.id.input_cf_secret);
        final android.view.View remoteSection = findViewById(R.id.remote_section);
        final android.widget.TextView remoteToggle = findViewById(R.id.remote_toggle);

        // Prefill from last run
        ipField.setText(savedIp);
        numberField.setText(savedNumber);
        bridgeUrlField.setText(prefs.getString("bridge_url_pref", ""));
        tokenField.setText(prefs.getString("bridge_token", ""));
        cfIdField.setText(prefs.getString("cf_access_id", ""));
        cfSecretField.setText(prefs.getString("cf_access_secret", ""));
        boolean hasRemote = notEmpty(prefs.getString("bridge_token", ""))
                || notEmpty(prefs.getString("cf_access_id", ""))
                || notEmpty(prefs.getString("bridge_url_pref", ""));
        remoteSection.setVisibility(hasRemote ? android.view.View.VISIBLE : android.view.View.GONE);
        remoteToggle.setText((hasRemote ? "▼" : "▶") + "  Remote access (optional)");
        findViewById(R.id.remote_info).setOnClickListener(t ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Remote access")
                        .setMessage("Optional. Leave blank to use SignalBerry on your home "
                                + "WiFi; nothing here is needed for that.\n\n"
                                + "To use it away from home, your server has to be reachable "
                                + "over the internet. A Cloudflare Tunnel is the practical way "
                                + "on BlackBerry (which can't run a VPN), with no open ports.\n\n"
                                + "These fields secure that connection:\n"
                                + "• Bridge URL: your server's bridge address\n"
                                + "• Bridge token: the shared secret that guards your server\n"
                                + "• CF Access ID/Secret: optional extra Cloudflare gate\n\n"
                                + "Without a token anyone who finds your address could read and "
                                + "send your messages, so it's required once you go remote.")
                        .setPositiveButton("Got it", null)
                        .show());

        remoteToggle.setOnClickListener(t -> {
            boolean show = remoteSection.getVisibility() != android.view.View.VISIBLE;
            remoteSection.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            remoteToggle.setText((show ? "▼" : "▶") + "  Remote access (optional)");
        });

        connectBtn.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            String numberInput = numberField.getText().toString().trim();
            if (ip.isEmpty() || numberInput.isEmpty()) {
                Toast.makeText(this, "Enter server IP and your Signal number", Toast.LENGTH_SHORT).show();
                return;
            }

            // persist + activate creds first — the verify calls below go through
            // Cloudflare for remote and need the headers
            String bridgeUrl = bridgeUrlField.getText().toString().trim();
            prefs.edit()
                    .putString("bridge_url_pref", bridgeUrl)
                    .putString("bridge_token", tokenField.getText().toString().trim())
                    .putString("cf_access_id", cfIdField.getText().toString().trim())
                    .putString("cf_access_secret", cfSecretField.getText().toString().trim())
                    .apply();
            Auth.load(prefs);

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
                        String bridgeBase = notEmpty(bridgeUrl)
                                ? normalizeBase(bridgeUrl) : deriveBridgeBase(ip);
                        prefs.edit()
                                .putString("ip", ip)
                                .putString("number", finalCanonical != null ? finalCanonical : numberInput)
                                .putString("bridge", bridgeBase)
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
