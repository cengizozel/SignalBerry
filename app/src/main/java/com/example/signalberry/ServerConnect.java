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
        final android.view.View cfSection = findViewById(R.id.cf_section);
        final android.widget.TextView cfToggle = findViewById(R.id.cf_toggle);

        // Prefill from last run
        ipField.setText(savedIp);
        numberField.setText(savedNumber);
        bridgeUrlField.setText(prefs.getString("bridge_url_pref", ""));
        tokenField.setText(prefs.getString("bridge_token", ""));
        cfIdField.setText(prefs.getString("cf_access_id", ""));
        cfSecretField.setText(prefs.getString("cf_access_secret", ""));

        boolean hasRemote = notEmpty(prefs.getString("bridge_token", ""))
                || notEmpty(prefs.getString("bridge_url_pref", ""));
        boolean hasCf = notEmpty(prefs.getString("cf_access_id", ""));
        wireSection(remoteToggle, remoteSection, "Remote access", hasRemote);
        wireSection(cfToggle, cfSection, "Cloudflare Access (optional)", hasCf);

        connectBtn.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            String numberInput = numberField.getText().toString().trim();
            String bridgeUrl = bridgeUrlField.getText().toString().trim();

            // inline required-field validation (highlights the offending boxes)
            ipField.setError(null);
            numberField.setError(null);
            bridgeUrlField.setError(null);
            boolean valid = true;
            android.widget.EditText firstBad = null;
            if (ip.isEmpty())          { ipField.setError("Required");     firstBad = ipField;     valid = false; }
            if (numberInput.isEmpty()) { numberField.setError("Required"); if (firstBad == null) firstBad = numberField; valid = false; }
            // a token or an https server means remote, so a bridge address is needed
            boolean remote = notEmpty(tokenField.getText().toString())
                    || notEmpty(cfIdField.getText().toString())
                    || ip.startsWith("https://");
            if (remote && bridgeUrl.isEmpty()) {
                wireSection(remoteToggle, remoteSection, "Remote access", true); // expand it
                bridgeUrlField.setError("Required for remote");
                if (firstBad == null) firstBad = bridgeUrlField;
                valid = false;
            }
            if (!valid) { if (firstBad != null) firstBad.requestFocus(); return; }

            // persist + activate creds first; the verify calls below go through
            // Cloudflare for remote and need the headers
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

    /** Collapsible section: arrow + label toggles its body's visibility. */
    private static void wireSection(android.widget.TextView toggle, android.view.View section,
                                    String label, boolean startExpanded) {
        section.setVisibility(startExpanded ? android.view.View.VISIBLE : android.view.View.GONE);
        toggle.setText((startExpanded ? "▼" : "▶") + "  " + label);
        toggle.setOnClickListener(t -> {
            boolean show = section.getVisibility() != android.view.View.VISIBLE;
            section.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            toggle.setText((show ? "▼" : "▶") + "  " + label);
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
