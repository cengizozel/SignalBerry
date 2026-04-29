package com.example.signalberry;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import static com.example.signalberry.Utils.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
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
                String bbase = deriveBridgeBase(host);

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
}
