package com.example.signalberry;

import android.os.Bundle;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.*;

public class Messages extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);
        setTitle("Messages");

        ListView list = findViewById(R.id.list_people);

        // Dummy data
        String[] names = {
                "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank"
        };
        String[] snippets = {
                "Hey! You around?", "Got the files.", "Call me later.",
                "Lunch tomorrow?", "New code pushed.", "Thanks!"
        };

        // Map to simple_list_item_2 (text1 + text2)
        List<Map<String, String>> data = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Map<String, String> row = new HashMap<>();
            row.put("name", names[i]);
            row.put("snippet", snippets[i % snippets.length]);
            data.add(row);
        }

        android.widget.SimpleAdapter adapter = new android.widget.SimpleAdapter(
                this,
                data,
                android.R.layout.simple_list_item_2,
                new String[]{"name", "snippet"},
                new int[]{android.R.id.text1, android.R.id.text2}
        );
        list.setAdapter(adapter);
    }
}
