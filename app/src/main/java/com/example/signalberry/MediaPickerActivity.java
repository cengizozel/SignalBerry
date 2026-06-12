package com.example.signalberry;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * In-app multi-select media picker. The stock Android 4.3 / BB10 gallery
 * ignores EXTRA_ALLOW_MULTIPLE, so the attach button uses this grid instead:
 * taps toggle selection (numbered in tap order), Send returns the batch.
 * "Browse" falls through to the system single picker for other providers.
 */
public class MediaPickerActivity extends AppCompatActivity {

    static final String EXTRA_RESULT_URIS = "picked_uris";
    private static final int COLS = 4;
    private static final int REQ_SYSTEM = 1;

    private static class Item {
        final Uri uri; final long id; final boolean video;
        Item(Uri u, long i, boolean v) { uri = u; id = i; video = v; }
        String key() { return (video ? "v" : "i") + id; }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Uri> picked = new ArrayList<>();
    private TextView btnSend;
    private RecyclerView grid;
    private final java.util.concurrent.ExecutorService thumbExec =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    // ~8MB of MINI_KIND thumbs — small enough for the Q10 heap
    private final android.util.LruCache<String, Bitmap> thumbs =
            new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // top bar: back | title | Browse | Send (n)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_media_previous);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.MATCH_PARENT));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("Select media");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        TextView browse = new TextView(this);
        browse.setText("Browse");
        browse.setTextColor(Utils.ACCENT);
        browse.setTextSize(15);
        browse.setGravity(Gravity.CENTER);
        browse.setPadding(dp(12), 0, dp(12), 0);
        browse.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browse.setOnClickListener(v -> launchSystemPicker());
        bar.addView(browse);

        btnSend = new TextView(this);
        btnSend.setTextSize(15);
        btnSend.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSend.setGravity(Gravity.CENTER);
        btnSend.setPadding(dp(12), 0, dp(16), 0);
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        btnSend.setOnClickListener(v -> {
            if (picked.isEmpty()) return;
            Intent res = new Intent();
            res.putParcelableArrayListExtra(EXTRA_RESULT_URIS, picked);
            setResult(RESULT_OK, res);
            finish();
        });
        bar.addView(btnSend);
        updateSendLabel();
        root.addView(bar);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLS));
        grid.setAdapter(new PickAdapter());
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        new Thread(this::loadMedia).start();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        thumbExec.shutdownNow();
    }

    private void updateSendLabel() {
        btnSend.setText(picked.isEmpty() ? "Send" : "Send (" + picked.size() + ")");
        btnSend.setTextColor(picked.isEmpty() ? 0xFF888888 : Utils.ACCENT);
    }

    private void launchSystemPicker() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(pick, "Select photo or video"), REQ_SYSTEM);
    }

    /** Forward a system-picker result to Chat unchanged (single or ClipData). */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SYSTEM) return;
        if (resultCode == RESULT_OK && data != null) {
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void loadMedia() {
        final ArrayList<long[]> raw = new ArrayList<>(); // [id, date, isVideo]
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
            if (c != null) {
                while (c.moveToNext()) raw.add(new long[]{c.getLong(0), c.getLong(1), 0});
                c.close();
            }
            c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
            if (c != null) {
                while (c.moveToNext()) raw.add(new long[]{c.getLong(0), c.getLong(1), 1});
                c.close();
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(raw, (a, b) -> Long.compare(b[1], a[1])); // newest first
        final ArrayList<Item> fresh = new ArrayList<>(raw.size());
        for (long[] r : raw) {
            boolean video = r[2] == 1;
            Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                             : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            fresh.add(new Item(Uri.withAppendedPath(base, String.valueOf(r[0])), r[0], video));
        }
        runOnUiThread(() -> {
            if (fresh.isEmpty()) {
                Toast.makeText(this, "No photos or videos on this device",
                        Toast.LENGTH_SHORT).show();
                launchSystemPicker();
                return;
            }
            items.clear();
            items.addAll(fresh);
            grid.getAdapter().notifyDataSetChanged();
        });
    }

    // -------------------- grid --------------------

    private class Cell extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView badge;   // selection order number
        final TextView playGlyph;
        Cell(FrameLayout f, ImageView iv, TextView b, TextView p) {
            super(f); image = iv; badge = b; playGlyph = p;
        }
    }

    private class PickAdapter extends RecyclerView.Adapter<Cell> {
        @Override public int getItemCount() { return items.size(); }

        @Override public Cell onCreateViewHolder(ViewGroup parent, int viewType) {
            int side = parent.getWidth() > 0 ? parent.getWidth() / COLS
                    : getResources().getDisplayMetrics().widthPixels / COLS;
            FrameLayout f = new FrameLayout(MediaPickerActivity.this);
            f.setLayoutParams(new RecyclerView.LayoutParams(side, side));
            int pad = dp(1);
            f.setPadding(pad, pad, pad, pad);

            ImageView iv = new ImageView(MediaPickerActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF333333);
            f.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView play = new TextView(MediaPickerActivity.this);
            play.setText("▶");
            play.setTextColor(Color.WHITE);
            play.setTextSize(18);
            play.setShadowLayer(4, 0, 0, Color.BLACK);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            f.addView(play, pl);

            TextView badge = new TextView(MediaPickerActivity.this);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(13);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(Utils.ACCENT);
            badge.setBackground(circle);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.TOP | Gravity.END);
            bl.setMargins(0, dp(4), dp(4), 0);
            f.addView(badge, bl);

            return new Cell(f, iv, badge, play);
        }

        @Override public void onBindViewHolder(Cell h, int position) {
            final Item it = items.get(position);
            h.playGlyph.setVisibility(it.video ? View.VISIBLE : View.GONE);

            int order = picked.indexOf(it.uri);
            h.badge.setVisibility(order >= 0 ? View.VISIBLE : View.GONE);
            if (order >= 0) h.badge.setText(String.valueOf(order + 1));
            h.image.setAlpha(order >= 0 ? 0.55f : 1f);

            h.image.setImageBitmap(null);
            h.image.setTag(it.key());
            Bitmap cached = thumbs.get(it.key());
            if (cached != null) {
                h.image.setImageBitmap(cached);
            } else {
                final ImageView iv = h.image;
                thumbExec.execute(() -> {
                    try {
                        Bitmap t = it.video
                                ? MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(),
                                        it.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                                : MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),
                                        it.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        if (t == null) return;
                        thumbs.put(it.key(), t);
                        final Bitmap fb = t;
                        runOnUiThread(() -> {
                            if (it.key().equals(iv.getTag())) iv.setImageBitmap(fb);
                        });
                    } catch (Exception ignored) {}
                });
            }

            h.itemView.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos < 0) return;
                Item tapped = items.get(pos);
                int idx = picked.indexOf(tapped.uri);
                if (idx >= 0) {
                    picked.remove(idx);
                    // renumber everything after the removed one
                    notifyDataSetChanged();
                } else {
                    picked.add(tapped.uri);
                    notifyItemChanged(pos);
                }
                updateSendLabel();
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
