package com.example.signalberry;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.*;

public class Messages extends AppCompatActivity {

    private List<Map<String, String>> all;
    private android.widget.SimpleAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);

        ListView list = findViewById(R.id.list_people);
        EditText search = findViewById(R.id.search);

        // Dummy data (new → old)
        String[] names = { "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank" };
        String[] msgs  = {
                "Hey! You around?", "Got the files.", "Call me later.",
                "Lunch tomorrow?", "New code pushed.", "Thanks!"
        };
        String[] times = { "22:10", "21:44", "20:03", "19:20", "18:55", "18:02" };

        all = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Map<String, String> row = new HashMap<>();
            row.put("name", names[i]);
            row.put("snippet", msgs[i % msgs.length]);
            row.put("time", times[i % times.length]);
            all.add(row);
        }

        adapter = new android.widget.SimpleAdapter(
                this,
                all,
                R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time}
        );
        list.setAdapter(adapter);

        // (bonus) quick filter by name/message
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void filter(String q) {
        q = q.toLowerCase(Locale.US).trim();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> current = (List<Map<String, String>>) ((ArrayList<Map<String, String>>) all).clone();
        if (q.isEmpty()) {
            adapter = new android.widget.SimpleAdapter(
                    this, all, R.layout.row_chat,
                    new String[]{"name", "snippet", "time"},
                    new int[]{R.id.name, R.id.snippet, R.id.time});
        } else {
            List<Map<String, String>> filtered = new ArrayList<>();
            for (Map<String, String> m : current) {
                String name = m.get("name");
                String snip = m.get("snippet");
                if ((name != null && name.toLowerCase(Locale.US).contains(q)) ||
                        (snip != null && snip.toLowerCase(Locale.US).contains(q))) {
                    filtered.add(m);
                }
            }
            adapter = new android.widget.SimpleAdapter(
                    this, filtered, R.layout.row_chat,
                    new String[]{"name", "snippet", "time"},
                    new int[]{R.id.name, R.id.snippet, R.id.time});
        }
        ListView list = findViewById(R.id.list_people);
        list.setAdapter(adapter);
    }
}
