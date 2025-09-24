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
import java.net.URLEncoder;
import java.net.URL;
import java.util.*;

public class NewChat extends AppCompatActivity {

    private final List<Map<String, String>> rows = new ArrayList<>();
    private android.widget.SimpleAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.new_chat);
        setTitle("New chat");

        ListView list = findViewById(R.id.list_people);
        EditText search = findViewById(R.id.search);

        adapter = new android.widget.SimpleAdapter(
                this, rows, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            Map<String, String> item = (Map<String, String>) parent.getItemAtPosition(position);
            String peerName   = item.get("name");
            String peerNumber = item.get("number");
            String peerUuid   = item.get("uuid");

            if ((peerNumber == null || peerNumber.trim().isEmpty()) &&
                    (peerUuid   == null || peerUuid.trim().isEmpty())) {
                Toast.makeText(this, "No identifier for this contact", Toast.LENGTH_SHORT).show();
                return;
            }

            startActivity(new android.content.Intent(NewChat.this, Chat.class)
                    .putExtra("peer_name",   peerName)
                    .putExtra("peer_number", peerNumber)
                    .putExtra("peer_uuid",   peerUuid));
            finish();
        });

        // Build list = contacts WITHOUT any messages in bridge
        new Thread(() -> {
            try {
                String host   = getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", "");
                String number = getSharedPreferences("signalberry", MODE_PRIVATE).getString("number", "");
                if (isEmpty(host) || isEmpty(number)) {
                    runOnUiThread(() -> Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show());
                    return;
                }

                String base  = normalizeBase(host);
                String bbase = bridgeBase(host);

                String contactsJson = httpGet(base + "/v1/contacts/" + URLEncoder.encode(number, "UTF-8"));
                JSONArray contacts = new JSONArray(contactsJson);

                List<Map<String, String>> out = new ArrayList<>();
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);
                    String display = chooseDisplayName(c);
                    String num     = c.optString("number", "");
                    String uuid    = c.optString("uuid", "");
                    if (isEmpty(num) && isEmpty(uuid)) continue;

                    String peer = !isEmpty(num) ? num : uuid;
                    // Ask bridge if ANY message exists (limit=1)
                    boolean hasAny = false;
                    try {
                        String url = bbase + "/messages?peer=" + URLEncoder.encode(peer, "UTF-8")
                                + "&after=0&limit=1";
                        String json = httpGet(url);
                        JSONObject obj = new JSONObject(json);
                        JSONArray items = obj.optJSONArray("items");
                        hasAny = (items != null && items.length() > 0);
                    } catch (Exception ignored) {}

                    if (!hasAny) {
                        Map<String, String> row = new HashMap<>();
                        row.put("name", display);
                        row.put("snippet", ""); // empty
                        row.put("time", "");    // empty
                        row.put("number", num);
                        row.put("uuid",   uuid);
                        out.add(row);
                    }
                }

                // Sort A→Z
                Collections.sort(out, new Comparator<Map<String,String>>() {
                    @Override public int compare(Map<String, String> a, Map<String, String> b) {
                        return a.get("name").compareToIgnoreCase(b.get("name"));
                    }
                });

                final List<Map<String, String>> finalOut = out;
                runOnUiThread(() -> {
                    rows.clear();
                    rows.addAll(finalOut);
                    adapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load contacts", Toast.LENGTH_SHORT).show());
            }
        }).start();

        // filter
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    // ---- same helpers as Messages (duplicated for simplicity) ----
    private String chooseDisplayName(JSONObject c) {
        JSONObject nick = c.optJSONObject("nickname");
        String nickName = firstNonEmpty(
                nick != null ? nick.optString("name", null) : null,
                joinNames(nick != null ? nick.optString("given_name", null) : null,
                        nick != null ? nick.optString("family_name", null) : null)
        );
        if (!isEmpty(nickName)) return nickName;

        String contactName = c.optString("name", "");
        if (!isEmpty(contactName)) return contactName;

        String profileName = c.optString("profile_name", "");
        if (!isEmpty(profileName)) return profileName;

        JSONObject profile = c.optJSONObject("profile");
        String profComposed = joinNames(
                profile != null ? profile.optString("given_name", null) : null,
                profile != null ? profile.optString("lastname",   null) : null
        );
        if (!isEmpty(profComposed)) return profComposed;

        String username = c.optString("username", "");
        if (!isEmpty(username)) return "@" + username;

        String number = c.optString("number", "");
        if (!isEmpty(number)) return number;

        String uuid = c.optString("uuid", "");
        if (!isEmpty(uuid)) return "Signal user " + shortUuid(uuid);

        return "Unknown";
    }

    private static void filterList(android.widget.SimpleAdapter adapter, ListView listView, List<Map<String,String>> src, String q) {
        q = q.toLowerCase(Locale.US).trim();
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> m : src) {
            String name = m.get("name");
            if (name != null && name.toLowerCase(Locale.US).contains(q)) filtered.add(m);
        }
        android.widget.SimpleAdapter newAdapter = new android.widget.SimpleAdapter(
                listView.getContext(), filtered, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        listView.setAdapter(newAdapter);
    }

    private void filter(String q) {
        filterList(adapter, (ListView) findViewById(R.id.list_people), rows, q);
    }

    private static String joinNames(String given, String family) {
        given = safeTrim(given);
        family = safeTrim(family);
        if (!isEmpty(given) && !isEmpty(family)) return given + " " + family;
        if (!isEmpty(given)) return given;
        if (!isEmpty(family)) return family;
        return "";
    }

    private static String bridgeBase(String hostPort) {
        String hostOnly = hostPort;
        if (hostOnly.startsWith("http://"))  hostOnly = hostOnly.substring(7);
        if (hostOnly.startsWith("https://")) hostOnly = hostOnly.substring(8);
        if ("localhost:5000".equals(hostOnly) || "127.0.0.1:5000".equals(hostOnly)) hostOnly = "10.0.2.2:5000";
        int colon = hostOnly.indexOf(':');
        if (colon > 0) hostOnly = hostOnly.substring(0, colon);
        return "http://" + hostOnly + ":9099";
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
        } finally { c.disconnect(); }
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safeTrim(String s) { return s == null ? null : s.trim(); }
    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }
    private static String shortUuid(String uuid) {
        uuid = safeTrim(uuid);
        if (isEmpty(uuid)) return "";
        int cut = uuid.indexOf('-');
        if (cut > 0) return uuid.substring(0, cut);
        return (uuid.length() > 8) ? uuid.substring(0, 8) : uuid;
    }
}
