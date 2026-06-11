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

    /** Contacts without an existing thread. */
    private final List<Map<String, String>> rows = new ArrayList<>();
    /** What's currently displayed (filtered, possibly + the number row). */
    private final List<Map<String, String>> visible = new ArrayList<>();
    private MessagesAdapter adapter;
    private AvatarCache avatarCache;
    private String restBase;
    private String myNumber;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.new_chat);
        setTitle("New chat");

        ListView list = findViewById(R.id.list_people);
        EditText search = findViewById(R.id.search);

        String host0 = getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", "");
        String num0  = getSharedPreferences("signalberry", MODE_PRIVATE).getString("number", "");
        avatarCache = new AvatarCache(getCacheDir(), normalizeBase(host0), num0);
        adapter = new MessagesAdapter(this, visible, avatarCache, false);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            Map<String, String> item = (Map<String, String>) parent.getItemAtPosition(position);
            String peerNumber = item.get("number");
            String peerUuid   = item.get("uuid");

            if ("lookup".equals(item.get("action"))) {
                lookupNumber(item.get("number"));
                return;
            }
            if ((peerNumber == null || peerNumber.trim().isEmpty()) &&
                    (peerUuid   == null || peerUuid.trim().isEmpty())) {
                Toast.makeText(this, "No identifier for this contact", Toast.LENGTH_SHORT).show();
                return;
            }
            openChat(item.get("name"), peerNumber, peerUuid);
        });

        String host = getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", "");
        myNumber    = getSharedPreferences("signalberry", MODE_PRIVATE).getString("number", "");
        restBase    = normalizeBase(host);

        // Build list = contacts WITHOUT any local thread (DB check — the old
        // version made one bridge HTTP call per contact)
        new Thread(() -> {
            try {
                if (isEmpty(host) || isEmpty(myNumber)) {
                    runOnUiThread(() -> Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show());
                    return;
                }

                String contactsJson = httpGet(restBase + "/v1/contacts/" + URLEncoder.encode(myNumber, "UTF-8"));
                JSONArray contacts = new JSONArray(contactsJson);

                Set<String> withThread = new HashSet<>();
                for (android.util.Pair<String, String[]> s :
                        Repo.get(this).db.getConversationSummaries())
                    withThread.add(s.first);

                PeerKeys peerKeys = PeerKeys.get(this);
                List<Map<String, String>> out = new ArrayList<>();
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);
                    String display = chooseDisplayName(c);
                    String num     = c.optString("number", "");
                    String uuid    = c.optString("uuid", "");
                    if (isEmpty(num) && isEmpty(uuid)) continue;
                    if (withThread.contains(peerKeys.resolve(num, uuid))) continue;

                    JSONObject prof = c.optJSONObject("profile");
                    boolean hasAvatar = prof != null && prof.optBoolean("has_avatar", false);
                    Map<String, String> row = new HashMap<>();
                    row.put("name", display);
                    row.put("snippet", "");
                    row.put("time", "");
                    row.put("number", num);
                    row.put("uuid",   uuid);
                    row.put("avatar_path", hasAvatar ? uuid : "");
                    out.add(row);
                }

                Collections.sort(out, (a, b) ->
                        a.get("name").compareToIgnoreCase(b.get("name")));

                runOnUiThread(() -> {
                    rows.clear();
                    rows.addAll(out);
                    filter("");
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load contacts", Toast.LENGTH_SHORT).show());
            }
        }).start();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void openChat(String name, String number, String uuid) {
        startActivity(new android.content.Intent(NewChat.this, Chat.class)
                .putExtra("peer_name",   name)
                .putExtra("peer_number", number)
                .putExtra("peer_uuid",   uuid));
        finish();
    }

    /** GET /v1/search: is this number on Signal? Then open a thread with it. */
    private void lookupNumber(String number) {
        Toast.makeText(this, "Checking " + number + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String url = restBase + "/v1/search/" + URLEncoder.encode(myNumber, "UTF-8")
                        + "?numbers=" + URLEncoder.encode(number, "UTF-8");
                JSONArray res = new JSONArray(httpGet(url));
                boolean registered = false;
                for (int i = 0; i < res.length(); i++) {
                    JSONObject r = res.optJSONObject(i);
                    if (r != null && r.optBoolean("registered", false)) registered = true;
                }
                final boolean ok = registered;
                runOnUiThread(() -> {
                    if (ok) openChat(number, number, null);
                    else Toast.makeText(this, number + " is not on Signal", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Lookup failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void filter(String q) {
        String query = q.trim();
        String ql = query.toLowerCase(Locale.US);
        visible.clear();
        for (Map<String, String> m : rows) {
            String name = m.get("name");
            if (name != null && name.toLowerCase(Locale.US).contains(ql)) visible.add(m);
        }
        // typed something number-shaped? offer a registration lookup
        String d = digits(query);
        if (d.length() >= 7) {
            String e164 = query.startsWith("+") ? "+" + d : "+" + d;
            Map<String, String> row = new HashMap<>();
            row.put("name", "Message " + e164);
            row.put("snippet", "Check if this number is on Signal");
            row.put("time", "");
            row.put("number", e164);
            row.put("action", "lookup");
            visible.add(0, row);
        }
        adapter.notifyDataSetChanged();
    }
}
