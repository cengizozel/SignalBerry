package com.example.signalberry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class ServerConnect extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.server_connect);
        setTitle("SignalBerry");

        Button connectBtn = findViewById(R.id.btn_connect);
        connectBtn.setOnClickListener(v -> {
            // always go to Messages for now
            startActivity(new Intent(ServerConnect.this, Messages.class));
        });
    }
}
