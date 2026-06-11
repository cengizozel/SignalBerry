package com.example.signalberry;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.signalberry.Utils.*;

/**
 * Per-chat media overview (tap the contact name in a chat): Media / Audio /
 * Links / Files tabs, month sections, download-state overlays. Everything is
 * driven by the existing message DB and AttachmentStore — kept deliberately
 * lean for the Q10 (one RecyclerView, the shared bounded ImageLoader).
 */
public class MediaGalleryActivity extends AppCompatActivity {

    static final String EXTRA_PEER_KEY  = "peer_key";
    static final String EXTRA_PEER_NAME = "peer_name";

    private static final int COLS = 4;
    private static final Pattern URL_RE = Pattern.compile("https?://\\S+");

    private String peerKey;
    private String baseSignal;
    private Repo repo;
    private AttachmentStore store;
    private ChatAdapter.ImageLoader loader;

    private RecyclerView grid;
    private GalleryAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private final TextView[] tabViews = new TextView[4];
    private int activeTab = 0; // 0 media, 1 audio, 2 links, 3 files

    /** One grid/list entry: either a month header or an item. */
    private static class Row {
        final String header;        // non-null for section headers
        final MessageItem item;     // media/audio/file rows
        final String link;          // links tab
        Row(String h, MessageItem m, String l) { header = h; item = m; link = l; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peerKey = getIntent().getStringExtra(EXTRA_PEER_KEY);
        String name = getIntent().getStringExtra(EXTRA_PEER_NAME);
        if (isEmpty(peerKey)) { finish(); return; }
        setTitle("");

        repo = Repo.get(this);
        store = AttachmentStore.get(this);
        loader = new ChatAdapter.ImageLoader(this);
        baseSignal = normalizeBase(
                getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", ""));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // header: name + tab row
        TextView title = new TextView(this);
        title.setText(name == null ? "Media" : name);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(8));
        root.addView(title);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Media", "Audio", "Links", "Files"};
        for (int i = 0; i < 4; i++) {
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(10), 0, dp(10));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final int idx = i;
            tv.setOnClickListener(v -> selectTab(idx));
            tabs.addView(tv);
            tabViews[i] = tv;
        }
        root.addView(tabs);

        grid = new RecyclerView(this);
        GridLayoutManager lm = new GridLayoutManager(this, COLS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return COLS;
                Row r = rows.get(position);
                // headers, links, audio and file rows span the full width
                return (r.header != null || r.link != null
                        || (r.item != null && !isVisualTab())) ? COLS : 1;
            }
        });
        grid.setLayoutManager(lm);
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        selectTab(0);
    }

    private boolean isVisualTab() { return activeTab == 0; }

    private void selectTab(int idx) {
        activeTab = idx;
        for (int i = 0; i < 4; i++) {
            tabViews[i].setTypeface(null, i == idx
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabViews[i].setTextColor(i == idx ? 0xFF2196F3 : 0xFF888888);
        }
        new Thread(() -> {
            final List<Row> fresh = buildRows(idx);
            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(fresh);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private List<Row> buildRows(int tab) {
        List<MessageItem> thread = repo.getThreadFull(peerKey);
        List<Row> out = new ArrayList<>();
        String lastMonth = null;
        // newest first, like the official client
        for (int i = thread.size() - 1; i >= 0; i--) {
            MessageItem m = thread.get(i);
            if (m.serverTs <= 0 || m.status == MessageDatabase.ST_REMOTE_DELETED) continue;
            boolean matches;
            switch (tab) {
                case 0:  matches = ("image".equals(m.msgType) || "video".equals(m.msgType))
                        && (notEmpty(m.attachmentId) || notEmpty(m.localUri)); break;
                case 1:  matches = "audio".equals(m.msgType) && notEmpty(m.attachmentId); break;
                case 3:  matches = "file".equals(m.msgType) && notEmpty(m.attachmentId); break;
                default: matches = false;
            }
            if (tab == 2) {
                String text = m.text;
                if (text != null && text.contains("http")) {
                    Matcher mt = URL_RE.matcher(text);
                    while (mt.find()) {
                        String month = monthLabel(m.serverTs);
                        if (!month.equals(lastMonth)) {
                            out.add(new Row(month, null, null));
                            lastMonth = month;
                        }
                        out.add(new Row(null, null, mt.group()));
                    }
                }
                continue;
            }
            if (!matches) continue;
            String month = monthLabel(m.serverTs);
            if (!month.equals(lastMonth)) {
                out.add(new Row(month, null, null));
                lastMonth = month;
            }
            out.add(new Row(null, m, null));
        }
        return out;
    }

    private static String monthLabel(long ts) {
        return new java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date(ts));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ── item actions ─────────────────────────────────────────────────────────

    private void openItem(MessageItem m) {
        if ("image".equals(m.msgType)) {
            ArrayList<String> sources = new ArrayList<>();
            int pos = 0, idx = 0;
            for (Row r : rows) {
                if (r.item == null || !"image".equals(r.item.msgType)) continue;
                String src = r.item.localUri != null ? r.item.localUri
                        : baseSignal + "/v1/attachments/" + r.item.attachmentId;
                if (r.item == m) pos = idx;
                sources.add(src);
                idx++;
            }
            Intent i = new Intent(this, ImageViewerActivity.class);
            i.putStringArrayListExtra(ImageViewerActivity.EXTRA_SOURCES, sources);
            i.putExtra(ImageViewerActivity.EXTRA_POSITION, pos);
            startActivity(i);
            return;
        }
        // video / audio / file: ensure cached, then play or hand off
        final String attId = m.attachmentId;
        if (isEmpty(attId)) {
            if (m.localUri != null && "video".equals(m.msgType)) {
                Intent play = new Intent(this, PlayerActivity.class);
                if (m.localUri.startsWith("/")) play.putExtra(PlayerActivity.EXTRA_FILE, m.localUri);
                else play.putExtra(PlayerActivity.EXTRA_URI, m.localUri);
                startActivity(play);
            }
            return;
        }
        final String mime = isEmpty(m.mime) ? "*/*" : m.mime;
        final boolean isVideo = "video".equals(m.msgType);
        if (!store.has(attId)) Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.io.File f = store.fetch(baseSignal, attId);
            runOnUiThread(() -> {
                if (f == null) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                adapter.notifyDataSetChanged(); // overlay + size refresh
                if (isVideo) {
                    Intent play = new Intent(this, PlayerActivity.class);
                    play.putExtra(PlayerActivity.EXTRA_FILE, f.getAbsolutePath());
                    startActivity(play);
                } else {
                    try {
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                this, getPackageName() + ".files", f);
                        Intent view = new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(uri, mime);
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(view);
                    } catch (Exception e) {
                        Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }).start();
    }

    private static String formatBytes(long b) {
        if (b >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576f);
        return (b / 1024) + " kB";
    }

    // ── adapter ──────────────────────────────────────────────────────────────

    private class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int T_HEADER = 0, T_CELL = 1, T_ROW = 2;

        @Override public int getItemViewType(int position) {
            Row r = rows.get(position);
            if (r.header != null) return T_HEADER;
            if (r.link != null || !isVisualTab()) return T_ROW;
            return T_CELL;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == T_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setTextSize(14);
                tv.setPadding(dp(10), dp(14), dp(10), dp(6));
                return new RecyclerView.ViewHolder(tv) {};
            }
            if (type == T_ROW) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(14);
                tv.setPadding(dp(12), dp(12), dp(12), dp(12));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                return new RecyclerView.ViewHolder(tv) {};
            }
            FrameLayout cell = new FrameLayout(parent.getContext());
            int size = parent.getWidth() > 0 ? parent.getWidth() / COLS : dp(90);
            cell.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            ImageView iv = new ImageView(parent.getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setId(android.R.id.icon);
            cell.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView overlay = new ImageView(parent.getContext());
            overlay.setId(android.R.id.icon1);
            overlay.setBackgroundColor(0x66000000);
            FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
            cell.addView(overlay, olp);
            TextView meta = new TextView(parent.getContext());
            meta.setId(android.R.id.text1);
            meta.setTextSize(10);
            meta.setTextColor(Color.WHITE);
            meta.setBackgroundColor(0x88000000);
            meta.setPadding(dp(3), 0, dp(3), 0);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            cell.addView(meta, mlp);
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            Row r = rows.get(position);
            if (r.header != null) {
                ((TextView) h.itemView).setText(r.header);
                return;
            }
            if (r.link != null) {
                TextView tv = (TextView) h.itemView;
                tv.setText("🔗 " + r.link);
                tv.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.link)));
                    } catch (Exception e) {
                        Toast.makeText(MediaGalleryActivity.this, "Cannot open link",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            MessageItem m = r.item;
            if (!isVisualTab()) {
                TextView tv = (TextView) h.itemView;
                String label = ("audio".equals(m.msgType) ? "🎤 Audio" : "📎 " +
                        (isEmpty(m.attachmentId) ? "File" : m.attachmentId));
                String extra = formatShortTime(m.serverTs);
                if (notEmpty(m.attachmentId) && store.has(m.attachmentId))
                    extra += " · " + formatBytes(store.fileFor(m.attachmentId).length());
                tv.setText(label + "  ·  " + extra);
                tv.setOnClickListener(v -> openItem(m));
                return;
            }
            FrameLayout cell = (FrameLayout) h.itemView;
            ImageView iv = cell.findViewById(android.R.id.icon);
            ImageView overlay = cell.findViewById(android.R.id.icon1);
            TextView meta = cell.findViewById(android.R.id.text1);
            boolean isVideo = "video".equals(m.msgType);
            boolean cached = notEmpty(m.attachmentId) && store.has(m.attachmentId);
            boolean local = notEmpty(m.localUri);

            String tag;
            if (isVideo) tag = local ? "vthumb-local:" + m.localUri
                    : "vthumb:" + (m.attachmentId == null ? "" : m.attachmentId);
            else tag = local ? m.localUri
                    : "att:" + (m.attachmentId == null ? "" : m.attachmentId);
            iv.setImageDrawable(null);
            iv.setBackgroundColor(0x22000000);
            iv.setTag(tag);
            loader.load(tag, iv, baseSignal);

            if (isVideo) {
                overlay.setImageResource(android.R.drawable.ic_media_play);
                overlay.setVisibility(View.VISIBLE);
            } else if (!cached && !local) {
                overlay.setImageResource(android.R.drawable.stat_sys_download);
                overlay.setVisibility(View.VISIBLE);
            } else {
                overlay.setVisibility(View.GONE);
            }
            if (cached) {
                meta.setText(formatBytes(store.fileFor(m.attachmentId).length()));
                meta.setVisibility(View.VISIBLE);
            } else {
                meta.setVisibility(View.GONE);
            }
            cell.setOnClickListener(v -> openItem(m));
        }

        @Override public int getItemCount() { return rows.size(); }
    }
}
