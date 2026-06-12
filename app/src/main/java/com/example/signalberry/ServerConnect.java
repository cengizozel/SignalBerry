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
        final android.widget.CheckBox hasTokenBox = findViewById(R.id.cb_has_token);
        final android.view.View cfSection = findViewById(R.id.cf_section);
        final android.widget.TextView cfToggle = findViewById(R.id.cf_toggle);

        // Prefill from last run
        ipField.setText(savedIp);
        numberField.setText(savedNumber);
        bridgeUrlField.setText(prefs.getString("bridge_url_pref", ""));
        tokenField.setText(prefs.getString("bridge_token", ""));
        cfIdField.setText(prefs.getString("cf_access_id", ""));
        cfSecretField.setText(prefs.getString("cf_access_secret", ""));

        // the bridge token field appears only when the user says they have one
        boolean hasToken = notEmpty(prefs.getString("bridge_token", ""));
        hasTokenBox.setChecked(hasToken);
        tokenField.setVisibility(hasToken ? android.view.View.VISIBLE : android.view.View.GONE);
        hasTokenBox.setOnCheckedChangeListener((b, checked) ->
                tokenField.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE));

        findViewById(R.id.token_info).setOnClickListener(t ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Bridge token")
                        .setMessage("A password you set on your server (SB_AUTH_TOKEN) so only "
                                + "you can reach it. Strongly recommended when your server is "
                                + "exposed to the internet.\n\n"
                                + "Set it on the server, then check this box and enter the same "
                                + "value here. On a trusted home network you can leave it off.")
                        .setPositiveButton("Got it", null)
                        .show());

        boolean hasCf = notEmpty(prefs.getString("cf_access_id", ""));
        wireSection(cfToggle, cfSection, "Cloudflare Access (optional)", hasCf);

        connectBtn.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            String numberInput = numberField.getText().toString().trim();
            String bridgeUrl = bridgeUrlField.getText().toString().trim();

            // Hard-require the three addressing/identity fields (you cannot send
            // a request without them). The token's correctness is decided by the
            // server's actual 401 below, never by a client-side guess: client
            // checks are UX, the server is the security boundary.
            ipField.setError(null); numberField.setError(null);
            bridgeUrlField.setError(null); tokenField.setError(null);
            android.widget.EditText firstBad = null;
            if (ip.isEmpty())          { ipField.setError("Required"); firstBad = ipField; }
            if (numberInput.isEmpty()) { numberField.setError("Required"); if (firstBad == null) firstBad = numberField; }
            if (bridgeUrl.isEmpty())   { bridgeUrlField.setError("Required"); if (firstBad == null) firstBad = bridgeUrlField; }
            if (firstBad != null) { firstBad.requestFocus(); return; }

            // a token is sent only when the box is checked
            String token = hasTokenBox.isChecked() ? tokenField.getText().toString().trim() : "";
            prefs.edit()
                    .putString("bridge_url_pref", bridgeUrl)
                    .putString("bridge_token", token)
                    .putString("cf_access_id", cfIdField.getText().toString().trim())
                    .putString("cf_access_secret", cfSecretField.getText().toString().trim())
                    .apply();
            Auth.load(prefs);

            connectBtn.setEnabled(false);
            final String base = normalizeBase(ip);
            final String bridgeBase = normalizeBase(bridgeUrl);
            new Thread(() -> {
                String errField = null, errMsg = null;
                String canonicalNumber = null;

                // 1) signal-cli reachable + authorized (401 = bad/missing token)
                int health;
                try { health = httpCodeGet(base + "/v1/health"); } catch (Exception e) { health = -1; }
                if (health == 401 || health == 403) { errField = "token"; errMsg = "Unauthorized, check token"; }
                else if (health != 200 && health != 204) { errField = "ip"; errMsg = "Server not reachable"; }

                // 2) number linked on the server
                if (errField == null) {
                    try {
                        canonicalNumber = findMatchingAccount(httpGet(base + "/v1/accounts"), numberInput);
                        if (canonicalNumber == null) { errField = "number"; errMsg = "Number not linked on server"; }
                    } catch (Exception e) { errField = "ip"; errMsg = "Server not reachable"; }
                }

                // 3) bridge reachable + authorized. Catches both a wrong token
                //    (401) and a wrong/missing bridge address (unreachable), so
                //    there is no need to guess whether the field was "required".
                if (errField == null) {
                    int bh;
                    try { bh = httpCodeGet(bridgeBase + "/health"); } catch (Exception e) { bh = -1; }
                    if (bh == 401 || bh == 403) { errField = "token"; errMsg = "Unauthorized, check token"; }
                    else if (bh != 200) { errField = "bridge"; errMsg = "Bridge not reachable"; }
                }

                final String fErr = errField;
                final String[] fMsg2 = { errMsg };
                final String canon = canonicalNumber;
                runOnUiThread(() -> {
                    connectBtn.setEnabled(true);
                    if (fErr == null) {
                        prefs.edit()
                                .putString("ip", ip)
                                .putString("number", canon != null ? canon : numberInput)
                                .putString("bridge", bridgeBase)
                                .apply();
                        startActivity(new Intent(ServerConnect.this, Messages.class));
                        return;
                    }
                    // a 401 with the box unchecked means the server wants a token
                    // the user has not provided, so reveal the field to fix it
                    if ("token".equals(fErr) && !hasTokenBox.isChecked()) {
                        hasTokenBox.setChecked(true);
                        fMsg2[0] = "Your server requires a token";
                    }
                    android.widget.EditText f = "token".equals(fErr) ? tokenField
                            : "number".equals(fErr) ? numberField
                            : "bridge".equals(fErr) ? bridgeUrlField : ipField;
                    f.setError(fMsg2[0]);
                    f.requestFocus();
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
