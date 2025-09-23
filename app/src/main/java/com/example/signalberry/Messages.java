package com.example.signalberry;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

public class Messages extends AppCompatActivity {

    private final List<Map<String, String>> all = new ArrayList<>();
    private android.widget.SimpleAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);
        setTitle("Messages");

        ListView list = findViewById(R.id.list_people);
        EditText search = findViewById(R.id.search);

        adapter = new android.widget.SimpleAdapter(
                this, all, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            Map<String, String> item = (Map<String, String>) parent.getItemAtPosition(position);
            String peerName = item.get("name");
            String peerNumber = item.get("number"); // may be empty for some entries
            if (peerNumber == null || peerNumber.trim().isEmpty()) {
                Toast.makeText(this, "No number for this contact", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new android.content.Intent(Messages.this, Chat.class)
                    .putExtra("peer_name", peerName)
                    .putExtra("peer_number", peerNumber)
                    .putExtra("peer_uuid",   item.get("uuid")));
        });

        // Load contacts using saved IP + number from ServerConnect
        new Thread(() -> {
            try {
                String host = getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", "");
                String number = getSharedPreferences("signalberry", MODE_PRIVATE).getString("number", "");
                if (host == null || host.isEmpty() || number == null || number.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show());
                    return;
                }

                String base = normalizeBase(host);
                String contactsJson = httpGet(base + "/v1/contacts/" + URLEncoder.encode(number, "UTF-8"));
                JSONArray contacts = new JSONArray(contactsJson);

                List<Map<String, String>> rows = new ArrayList<>();
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);
                    String name = firstNonEmpty(
                            c.optString("name", null),
                            c.optString("profile_name", null),
                            c.optString("username", null),
                            c.optString("number", null),
                            "Unknown"
                    );
                    Map<String, String> row = new HashMap<>();
                    row.put("name", name);
                    row.put("snippet", ""); // placeholder for latest message
                    row.put("time", "");    // placeholder for time
                    row.put("number", c.optString("number", ""));
                    row.put("uuid",   c.optString("uuid", ""));
                    rows.add(row);
                }

                // Simple sort (A→Z) for now
                Collections.sort(rows, (a, b) -> a.get("name").compareToIgnoreCase(b.get("name")));

                runOnUiThread(() -> {
                    all.clear();
                    all.addAll(rows);
                    adapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load contacts", Toast.LENGTH_SHORT).show());
            }
        }).start();

        // local filter on name/snippet
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void filter(String q) {
        q = q.toLowerCase(Locale.US).trim();
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> m : all) {
            String name = m.get("name");
            String snip = m.get("snippet");
            if ((name != null && name.toLowerCase(Locale.US).contains(q)) ||
                    (snip != null && snip.toLowerCase(Locale.US).contains(q))) {
                filtered.add(m);
            }
        }
        android.widget.SimpleAdapter newAdapter = new android.widget.SimpleAdapter(
                this, filtered, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        ((ListView) findViewById(R.id.list_people)).setAdapter(newAdapter);
    }

    private static String normalizeBase(String hostPort) {
        if ("localhost:5000".equals(hostPort) || "127.0.0.1:5000".equals(hostPort))
            hostPort = "10.0.2.2:5000";
        if (!hostPort.startsWith("http://") && !hostPort.startsWith("https://"))
            hostPort = "http://" + hostPort;
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        return hostPort;
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
            String out = sb.toString();
            return out.isEmpty() ? "[]" : out;
        } finally {
            c.disconnect();
        }
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }
}
