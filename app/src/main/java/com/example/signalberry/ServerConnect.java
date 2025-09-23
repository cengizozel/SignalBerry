package com.example.signalberry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ServerConnect extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.server_connect);
        setTitle("SignalBerry");

        EditText ipField = findViewById(R.id.input_ip);
        EditText tokenField = findViewById(R.id.input_token);
        Button connectBtn = findViewById(R.id.btn_connect);

        connectBtn.setOnClickListener(v -> {
            String ip = ipField.getText().toString().trim();
            String secret = tokenField.getText().toString().trim();
            if (ip.isEmpty() || secret.isEmpty()) {
                Toast.makeText(this, "Enter IP and secret", Toast.LENGTH_SHORT).show();
                return;
            }
            connectBtn.setEnabled(false);

            new Thread(() -> {
                boolean ok = verify(ip, secret);
                runOnUiThread(() -> {
                    connectBtn.setEnabled(true);
                    if (ok) {
                        startActivity(new Intent(ServerConnect.this, Messages.class));
                    } else {
                        Toast.makeText(this, "Could not verify with server", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });
    }

    private boolean verify(String ipOrHostPort, String secret) {
        try {
            // build http://<host:port>/verify
            String base = ipOrHostPort.startsWith("http://") || ipOrHostPort.startsWith("https://")
                    ? ipOrHostPort : "http://" + ipOrHostPort;
            String urlStr = base.endsWith("/") ? base + "verify" : base + "/verify";

            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);

            String body = "{\"secret\":\"" + secret.replace("\"","\\\"") + "\"}";
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = c.getResponseCode();
            c.disconnect();
            // Flask returns 200 when ok, 401 when not — so HTTP 200 is enough
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
