package com.example.signalberry;

import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * Minimal video player. Plays app-private files through a FileProvider
 * content:// URI — on Android 4.3 the mediaserver process cannot read
 * file:// paths under getFilesDir(), and file:// across apps throws on 24+.
 * Codec reality: Signal video is H.264 Main/High; API 18 guarantees only
 * Baseline, so graceful failure with an "open externally" path is first-class.
 */
public class PlayerActivity extends AppCompatActivity {

    static final String EXTRA_FILE = "file";   // absolute path inside our files dir
    static final String EXTRA_URI  = "uri";    // pre-existing content:// (local picks)

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");

        Uri uri = null;
        String path = getIntent().getStringExtra(EXTRA_FILE);
        String rawUri = getIntent().getStringExtra(EXTRA_URI);
        if (path != null) {
            try {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".files", new File(path));
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open video", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else if (rawUri != null) {
            uri = Uri.parse(rawUri);
        }
        if (uri == null) { finish(); return; }

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        VideoView video = new VideoView(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER);
        root.addView(video, lp);
        setContentView(root);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);

        final Uri playUri = uri;
        video.setOnErrorListener((mp, what, extra) -> {
            // likely an unsupported H.264 profile on this device's decoder
            new android.app.AlertDialog.Builder(this)
                    .setMessage("This video can't be played here (codec not supported on this device).")
                    .setPositiveButton("Open with…", (d, w) -> {
                        try {
                            android.content.Intent send = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW);
                            send.setDataAndType(playUri, "video/*");
                            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(send);
                        } catch (Exception e) {
                            Toast.makeText(this, "No video player available", Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .show();
            return true;
        });
        video.setOnCompletionListener(mp -> finish());
        video.setVideoURI(uri);
        video.start();
    }
}
