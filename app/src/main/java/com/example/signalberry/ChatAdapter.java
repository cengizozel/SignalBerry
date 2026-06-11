package com.example.signalberry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_PEER_TEXT   = 1;
    private static final int VIEW_ME_TEXT     = 2;
    private static final int VIEW_PEER_IMAGE  = 3;
    private static final int VIEW_ME_IMAGE    = 4;
    private static final int VIEW_DATE_HEADER = 5;
    private static final int VIEW_ME_AUDIO    = 6;
    private static final int VIEW_PEER_AUDIO  = 7;

    interface OnImageClickListener  { void onImageClick(int position); }
    interface OnLongPressListener   { void onLongPress(int position); }
    interface OnItemClickListener   { void onItemClick(int position); }

    private final List<MessageItem> data;
    private final String restBase;
    private final ImageLoader loader;
    private OnImageClickListener imageClickListener;
    private OnLongPressListener longPressListener;
    private OnItemClickListener itemClickListener;
    private java.util.Set<Long> selectedTs = new java.util.HashSet<>();
    private long highlightTs = 0;

    void setHighlightTs(long ts) { this.highlightTs = ts; }

    ChatAdapter(List<MessageItem> data, String restBase, Context ctx) {
        this.data = data;
        this.restBase = restBase;
        this.loader = new ImageLoader(ctx);
    }

    void setOnImageClickListener(OnImageClickListener l) { this.imageClickListener = l; }
    void setOnLongPressListener(OnLongPressListener l)   { this.longPressListener = l; }
    void setOnItemClickListener(OnItemClickListener l)   { this.itemClickListener = l; }
    void setSelectedTs(java.util.Set<Long> s)            { this.selectedTs = s; }

    @Override public int getItemViewType(int position) {
        MessageItem m = data.get(position);
        if (m.type == MessageItem.TYPE_DATE_HEADER) return VIEW_DATE_HEADER;
        boolean me = "me".equals(m.from);
        if ("audio".equals(m.msgType)) return me ? VIEW_ME_AUDIO : VIEW_PEER_AUDIO;
        if (m.type == MessageItem.TYPE_IMAGE) return me ? VIEW_ME_IMAGE : VIEW_PEER_IMAGE;
        return me ? VIEW_ME_TEXT : VIEW_PEER_TEXT;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_ME_TEXT:
                return new MeTextVH(inf.inflate(R.layout.item_chat_me, parent, false));
            case VIEW_PEER_TEXT:
                return new PeerTextVH(inf.inflate(R.layout.item_chat_peer, parent, false));
            case VIEW_ME_IMAGE:
                return new MeImageVH(inf.inflate(R.layout.item_chat_me_image, parent, false));
            case VIEW_ME_AUDIO:
                return new AudioVH(inf.inflate(R.layout.item_chat_me_audio, parent, false), true);
            case VIEW_PEER_AUDIO:
                return new AudioVH(inf.inflate(R.layout.item_chat_peer_audio, parent, false), false);
            case VIEW_DATE_HEADER:
                return new DateHeaderVH(inf.inflate(R.layout.item_date_header, parent, false));
            default:
                return new PeerImageVH(inf.inflate(R.layout.item_chat_peer_image, parent, false));
        }
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        MessageItem m = data.get(pos);
        if (h instanceof DateHeaderVH) { ((DateHeaderVH) h).bind(m); return; }
        h.itemView.setOnClickListener(v -> {
            if (itemClickListener != null) itemClickListener.onItemClick(pos);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) { longPressListener.onLongPress(pos); return true; }
            return false;
        });
        boolean selected = m.serverTs > 0 && selectedTs.contains(m.serverTs);
        boolean highlighted = highlightTs != 0 && m.serverTs == highlightTs;
        h.itemView.setBackgroundColor(selected ? 0x331976D2
                : highlighted ? 0x33FFC107 : android.graphics.Color.TRANSPARENT);
        if (h instanceof AudioVH)          ((AudioVH) h).bind(m);
        else if (h instanceof MeTextVH)    ((MeTextVH) h).bind(m);
        else if (h instanceof PeerTextVH)  ((PeerTextVH) h).bind(m);
        else if (h instanceof MeImageVH)   ((MeImageVH) h).bind(m, loader, restBase, pos, imageClickListener);
        else if (h instanceof PeerImageVH) ((PeerImageVH) h).bind(m, loader, restBase, pos, imageClickListener);
    }

    @Override public int getItemCount() { return data.size(); }

    // ---- date header VH ----
    static class DateHeaderVH extends RecyclerView.ViewHolder {
        final TextView tv;
        DateHeaderVH(@NonNull View v) { super(v); tv = v.findViewById(R.id.tvDate); }
        void bind(MessageItem m) { tv.setText(m.dateLabel); }
    }

    // ---- text VHs ----
    static class PeerTextVH extends RecyclerView.ViewHolder {
        final LinearLayout quoteBlock;
        final View quoteLine;
        final TextView tvQuote, tvMessage, tvReactions, tvTime, tvSender;
        PeerTextVH(@NonNull View v) {
            super(v);
            quoteBlock  = v.findViewById(R.id.quoteBlock);
            quoteLine   = v.findViewById(R.id.quoteLine);
            tvQuote     = v.findViewById(R.id.tvQuote);
            tvMessage   = v.findViewById(R.id.tvMessage);
            tvReactions = v.findViewById(R.id.tvReactions);
            tvTime      = v.findViewById(R.id.tvTime);
            tvSender    = v.findViewById(R.id.tvSender);
        }
        void bind(MessageItem m) {
            bindSender(m, tvSender);
            bindPeerTime(m, tvTime);
            if (m.status == Chat.ST_REMOTE_DELETED) { bindDeleted(tvMessage, quoteBlock, tvReactions); return; }
            tvMessage.setText(editedSpan(m.text == null ? "" : m.text, m.editHistory));
            tvMessage.setTypeface(null, android.graphics.Typeface.NORMAL);
            bindQuote(m, quoteBlock, quoteLine, tvQuote);
            bindReactions(m, tvReactions);
        }
    }

    static class MeTextVH extends RecyclerView.ViewHolder {
        final LinearLayout quoteBlock;
        final View quoteLine;
        final TextView tvQuote, tvMessage, tvStatus, tvReactions;
        MeTextVH(@NonNull View v) {
            super(v);
            quoteBlock  = v.findViewById(R.id.quoteBlock);
            quoteLine   = v.findViewById(R.id.quoteLine);
            tvQuote     = v.findViewById(R.id.tvQuote);
            tvMessage   = v.findViewById(R.id.tvMessage);
            tvStatus    = v.findViewById(R.id.tvStatus);
            tvReactions = v.findViewById(R.id.tvReactions);
        }
        void bind(MessageItem m) {
            if (m.status == Chat.ST_REMOTE_DELETED) {
                bindDeleted(tvMessage, quoteBlock, tvReactions);
                tvStatus.setText("");
                return;
            }
            tvMessage.setText(editedSpan(m.text == null ? "" : m.text, m.editHistory));
            tvMessage.setTypeface(null, android.graphics.Typeface.NORMAL);
            bindStatus(m, tvStatus);
            bindQuote(m, quoteBlock, quoteLine, tvQuote);
            bindReactions(m, tvReactions);
        }
    }

    // ---- audio VHs: inline voice-note player ----

    /** Chat supplies playback state + actions; the adapter only renders. */
    interface AudioUi {
        boolean isActive(MessageItem m);
        boolean isPlaying(MessageItem m);
        float progress(MessageItem m);
        String speedLabel();
        String durLabel(MessageItem m);
        float[] wave(MessageItem m);
        void onPlayPause(MessageItem m);
        void onSeek(MessageItem m, float fraction);
        void onCycleSpeed(MessageItem m);
    }

    private AudioUi audioUi;
    void setAudioUi(AudioUi ui) { this.audioUi = ui; }

    class AudioVH extends RecyclerView.ViewHolder {
        final LinearLayout quoteBlock;
        final View quoteLine, playerRow;
        final TextView tvQuote, tvDur, tvSpeed, tvStamp, tvReactions, tvSender;
        final ImageView btnPlay;
        final WaveformView wave;
        final boolean me;
        final int iconColor;
        AudioVH(@NonNull View v, boolean me) {
            super(v);
            this.me     = me;
            quoteBlock  = v.findViewById(R.id.quoteBlock);
            quoteLine   = v.findViewById(R.id.quoteLine);
            tvQuote     = v.findViewById(R.id.tvQuote);
            playerRow   = v.findViewById(R.id.playerRow);
            btnPlay     = v.findViewById(R.id.btnPlay);
            wave        = v.findViewById(R.id.waveform);
            tvDur       = v.findViewById(R.id.tvDur);
            tvSpeed     = v.findViewById(R.id.tvSpeed);
            tvStamp     = v.findViewById(R.id.tvStamp);
            tvReactions = v.findViewById(R.id.tvReactions);
            tvSender    = v.findViewById(R.id.tvSender);
            // the me-bubble flips light-blue (light) / dark-blue (night), so
            // the transport colors must flip with it or they wash out
            boolean night = (v.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            if (me) {
                iconColor = night ? 0xFFFFFFFF : 0xFF1565C0;
                wave.setColors(iconColor, night ? 0x66FFFFFF : 0x4D1565C0);
            } else {
                iconColor = Utils.ACCENT;
                wave.setColors(iconColor, 0x66888888);
            }
            tvSpeed.setTextColor(iconColor);
        }

        /** Icons drawn in code — the device font has no reliable pause glyph. */
        private Bitmap transportIcon(boolean pause) {
            String key = "vp|" + pause + "|" + iconColor;
            Bitmap b = BIND_ICON_CACHE.get(key);
            if (b != null) return b;
            int s = (int) (24 * itemView.getResources().getDisplayMetrics().density);
            b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(iconColor);
            if (pause) {
                float bw = s * 0.22f, gap = s * 0.18f;
                float left = (s - 2 * bw - gap) / 2f;
                c.drawRect(left, s * 0.15f, left + bw, s * 0.85f, p);
                c.drawRect(left + bw + gap, s * 0.15f, left + 2 * bw + gap, s * 0.85f, p);
            } else {
                android.graphics.Path tri = new android.graphics.Path();
                tri.moveTo(s * 0.28f, s * 0.12f);
                tri.lineTo(s * 0.88f, s * 0.50f);
                tri.lineTo(s * 0.28f, s * 0.88f);
                tri.close();
                c.drawPath(tri, p);
            }
            BIND_ICON_CACHE.put(key, b);
            return b;
        }
        void bind(MessageItem m) {
            final AudioUi ui = audioUi;
            if (m.status == Chat.ST_REMOTE_DELETED) {
                playerRow.setVisibility(View.GONE);
                tvSpeed.setVisibility(View.GONE);
                tvDur.setText("Message deleted");
                tvDur.setTypeface(null, android.graphics.Typeface.ITALIC);
                if (me) tvStamp.setText("");
                else bindPeerTime(m, tvStamp);
                quoteBlock.setVisibility(View.GONE);
                tvReactions.setVisibility(View.GONE);
                return;
            }
            bindSender(m, tvSender);
            tvDur.setTypeface(null, android.graphics.Typeface.NORMAL);
            playerRow.setVisibility(View.VISIBLE);
            boolean active = ui != null && ui.isActive(m);
            btnPlay.setImageBitmap(transportIcon(ui != null && ui.isPlaying(m)));
            wave.setLevels(ui != null ? ui.wave(m) : null);
            wave.setProgress(active ? ui.progress(m) : 0f);
            wave.setSeekable(active);
            wave.setOnSeekListener(f -> { if (audioUi != null) audioUi.onSeek(m, f); });
            tvSpeed.setVisibility(active ? View.VISIBLE : View.GONE);
            tvSpeed.setText(ui != null ? ui.speedLabel() : "1\u00D7");
            tvDur.setText(ui != null ? ui.durLabel(m) : "Voice message");
            btnPlay.setOnClickListener(v -> { if (audioUi != null) audioUi.onPlayPause(m); });
            tvSpeed.setOnClickListener(v -> { if (audioUi != null) audioUi.onCycleSpeed(m); });
            btnPlay.setOnLongClickListener(v -> itemView.performLongClick());
            if (me) bindStatus(m, tvStamp);
            else bindPeerTime(m, tvStamp);
            bindQuote(m, quoteBlock, quoteLine, tvQuote);
            bindReactions(m, tvReactions);
        }
    }

    // ---- image VHs ----
    static class PeerImageVH extends RecyclerView.ViewHolder {
        final LinearLayout quoteBlock;
        final View quoteLine;
        final TextView tvQuote;
        final ImageView iv;
        final TextView tvCaption, tvReactions;
        final ImageView ivPlay;
        final TextView tvMeta;
        PeerImageVH(@NonNull View v) {
            super(v);
            quoteBlock  = v.findViewById(R.id.quoteBlock);
            quoteLine   = v.findViewById(R.id.quoteLine);
            tvQuote     = v.findViewById(R.id.tvQuote);
            iv          = v.findViewById(R.id.ivImage);
            ivPlay      = v.findViewById(R.id.ivPlayOverlay);
            tvMeta      = v.findViewById(R.id.tvMediaMeta);
            tvCaption   = v.findViewById(R.id.tvCaption);
            tvReactions = v.findViewById(R.id.tvReactions);
            tvTime      = v.findViewById(R.id.tvTime);
            tvSender    = v.findViewById(R.id.tvSender);
        }
        final TextView tvTime;
        final TextView tvSender;
        void bind(MessageItem m, ImageLoader loader, String base, int pos, OnImageClickListener l) {
            bindSender(m, tvSender);
            bindPeerTime(m, tvTime);
            bindMedia(m, loader, base, pos, l, iv, ivPlay, tvMeta);
            if (m.caption != null && !m.caption.isEmpty()) {
                tvCaption.setText(m.caption);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
            bindQuote(m, quoteBlock, quoteLine, tvQuote);
            bindReactions(m, tvReactions);
        }
    }

    static class MeImageVH extends RecyclerView.ViewHolder {
        final LinearLayout quoteBlock;
        final View quoteLine;
        final TextView tvQuote;
        final ImageView iv;
        final TextView tvStatus, tvCaption, tvReactions;
        final ImageView ivPlay;
        final TextView tvMeta;
        MeImageVH(@NonNull View v) {
            super(v);
            quoteBlock  = v.findViewById(R.id.quoteBlock);
            quoteLine   = v.findViewById(R.id.quoteLine);
            tvQuote     = v.findViewById(R.id.tvQuote);
            iv          = v.findViewById(R.id.ivImage);
            ivPlay      = v.findViewById(R.id.ivPlayOverlay);
            tvMeta      = v.findViewById(R.id.tvMediaMeta);
            tvStatus    = v.findViewById(R.id.tvStatus);
            tvCaption   = v.findViewById(R.id.tvCaption);
            tvReactions = v.findViewById(R.id.tvReactions);
        }
        void bind(MessageItem m, ImageLoader loader, String base, int pos, OnImageClickListener l) {
            bindMedia(m, loader, base, pos, l, iv, ivPlay, tvMeta);
            bindStatus(m, tvStatus);
            if (m.caption != null && !m.caption.isEmpty()) {
                tvCaption.setText(m.caption);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
            bindQuote(m, quoteBlock, quoteLine, tvQuote);
            bindReactions(m, tvReactions);
        }
    }

    /** Shared media binding for image/video/audio/file bubbles. */
    private static void bindMedia(MessageItem m, ImageLoader loader, String base, int pos,
                                  OnImageClickListener l, ImageView iv,
                                  ImageView ivPlay, TextView tvMeta) {
        boolean isVideo = "video".equals(m.msgType);
        boolean isOther = "audio".equals(m.msgType) || "file".equals(m.msgType);
        iv.setImageDrawable(null);
        iv.setMinimumWidth(dp(iv, 200));
        iv.setMinimumHeight(dp(iv, 120));
        iv.setBackgroundColor(0x22000000);

        if (ivPlay != null) ivPlay.setVisibility(isVideo || isOther ? View.VISIBLE : View.GONE);
        if (tvMeta != null) tvMeta.setVisibility(View.GONE);

        String tag;
        if (isVideo) {
            tag = m.localUri != null ? "vthumb-local:" + m.localUri
                    : "vthumb:" + (m.attachmentId == null ? "" : m.attachmentId);
            if (tvMeta != null && m.attachmentId != null) {
                tvMeta.setText("Video");
                tvMeta.setVisibility(View.VISIBLE);
            }
        } else if (isOther) {
            tag = "none";
            if (tvMeta != null) {
                tvMeta.setText("audio".equals(m.msgType) ? "Audio" : "File");
                tvMeta.setVisibility(View.VISIBLE);
            }
        } else {
            tag = m.localUri != null ? m.localUri
                    : "att:" + (m.attachmentId == null ? "" : m.attachmentId);
        }
        iv.setTag(tag);
        if (!"none".equals(tag)) loader.load(tag, iv, base);
        iv.setOnClickListener(l != null ? v -> l.onImageClick(pos) : null);
    }

    private static int dp(View v, int dps) {
        return (int) (dps * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void bindDeleted(TextView tvMessage, LinearLayout quoteBlock, TextView tvReactions) {
        android.text.SpannableString s = new android.text.SpannableString("Message deleted");
        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFF9E9E9E),
                0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvMessage.setText(s);
        quoteBlock.setVisibility(View.GONE);
        tvReactions.setVisibility(View.GONE);
    }

    private static void bindReactions(MessageItem m, TextView tv) {
        if (m.reactions == null || m.reactions.isEmpty()) {
            tv.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String emoji : m.reactions.values()) sb.append(emoji);
        tv.setText(sb.toString());
        tv.setVisibility(View.VISIBLE);
    }

    private static void bindQuote(MessageItem m, LinearLayout block, View line, TextView tv) {
        if (m.quoteText != null && !m.quoteText.isEmpty()) {
            block.setVisibility(View.VISIBLE);
            boolean quoteFromMe = "me".equals(m.quoteAuthor);
            line.setBackgroundColor(quoteFromMe ? 0xFF4CAF50 : Utils.ACCENT);
            tv.setText((quoteFromMe ? "You: " : "") + m.quoteText);
        } else {
            block.setVisibility(View.GONE);
        }
    }

    private static android.text.SpannableString editedSpan(String text, String editHistory) {
        if (editHistory == null) return new android.text.SpannableString(text);
        String full = text + " (edited)";
        android.text.SpannableString s = new android.text.SpannableString(full);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFF888888),
                text.length(), full.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                text.length(), full.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Tick states: pending dim ellipsis, sent single, delivered double (dim),
     *  read double (accent blue), failed red cross. Text glyphs (U+2713/2715)
     *  render fine on Android 4.3; clock/emoji glyphs do not. */
    private static final android.util.LruCache<String, Bitmap> BIND_ICON_CACHE =
            new android.util.LruCache<>(8);

    private static void bindStatus(MessageItem m, TextView tv) {
        String when = com.example.signalberry.Utils.formatBubbleTime(m.serverTs);
        String at = when.isEmpty() ? "" : when + "  ";
        switch (m.status) {
            case Chat.ST_PENDING:   tv.setText("\u2026");             tv.setTextColor(0xFF9E9E9E); break;
            case Chat.ST_SENT:      tv.setText(at + "\u2713");        tv.setTextColor(0xFF9E9E9E); break;
            case Chat.ST_DELIVERED: tv.setText(at + "\u2713\u2713"); tv.setTextColor(0xFF9E9E9E); break;
            case Chat.ST_READ:      tv.setText(at + "\u2713\u2713"); tv.setTextColor(Utils.ACCENT); break;
            case Chat.ST_FAILED:    tv.setText("\u2715 failed \u2014 tap to retry"); tv.setTextColor(0xFFD32F2F); break;
            default:                tv.setText(at.trim());
        }
    }

    private static final int[] SENDER_PALETTE = {
        0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFF00ACC1,
        0xFFD81B60, 0xFF5E35B1, 0xFF00897B, 0xFFF4511E
    };

    private static void bindSender(MessageItem m, TextView tv) {
        if (tv == null) return;
        if (m.authorName == null || m.authorName.isEmpty()) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(m.authorName);
        tv.setTextColor(SENDER_PALETTE[Math.abs(m.authorName.hashCode() % SENDER_PALETTE.length)]);
        tv.setVisibility(View.VISIBLE);
    }

    private static void bindPeerTime(MessageItem m, TextView tv) {
        if (tv == null) return;
        tv.setText(m.status == Chat.ST_REMOTE_DELETED ? ""
                : com.example.signalberry.Utils.formatBubbleTime(m.serverTs));
    }

    // ---- media loader: shared executor, bounded decode, store-backed ----
    static class ImageLoader {
        // process-wide: a per-Chat executor+cache leaked 2 threads per open and
        // re-decoded everything on every reopen
        private static final java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        private static LruCache<String, Bitmap> cache;
        private final Context ctx;
        private static final int MAX_DIM = 640; // bubble cap on a 720px screen

        ImageLoader(Context ctx) {
            this.ctx = ctx.getApplicationContext();
            synchronized (ImageLoader.class) {
                if (cache == null) {
                    final int maxKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
                    cache = new LruCache<String, Bitmap>(maxKb / 8) {
                        @Override protected int sizeOf(String key, Bitmap value) {
                            return value.getByteCount() / 1024;
                        }
                    };
                }
            }
        }

        void load(final String tag, final ImageView target, final String baseSignal) {
            Bitmap cached = cache.get(tag);
            if (cached != null) {
                if (tag.equals(target.getTag())) target.setImageBitmap(cached);
                return;
            }
            exec.execute(() -> {
                final Bitmap bmp = fetch(tag, baseSignal);
                if (bmp != null) cache.put(tag, bmp);
                target.post(() -> {
                    if (!tag.equals(target.getTag())) return;
                    if (bmp != null) {
                        target.setImageBitmap(bmp);
                        target.setBackgroundColor(0x00000000);
                    } else {
                        // failed state: report-image glyph; bind sets the dim bg
                        target.setImageResource(android.R.drawable.ic_menu_report_image);
                    }
                });
            });
        }

        private Bitmap fetch(String tag, String baseSignal) {
            try {
                if (tag.startsWith("vthumb-local:")) {
                    String loc = tag.substring(13);
                    if (loc.startsWith("file://")) loc = loc.substring(7);
                    if (loc.startsWith("/"))
                        return videoThumb(new java.io.File(loc), null, false); // fd overload: mediaserver can't open our private paths
                    return videoThumb(null, loc, true); // content:// — context overload
                }
                if (tag.startsWith("vthumb:")) {
                    String attId = tag.substring(7);
                    if (attId.isEmpty()) return null;
                    AttachmentStore store = AttachmentStore.get(ctx);
                    if (!store.has(attId)) return null;   // not downloaded: keep placeholder
                    return videoThumb(store.fileFor(attId), null, false);
                }
                if (tag.startsWith("att:")) {
                    String attId = tag.substring(4);
                    if (attId.isEmpty()) return null;
                    java.io.File f = AttachmentStore.get(ctx).fetch(baseSignal, attId);
                    return f == null ? null : decodeBounded(
                            new java.io.FileInputStream(f), new java.io.FileInputStream(f));
                }
                if (tag.startsWith("content://") || tag.startsWith("file://")) {
                    Uri u = Uri.parse(tag);
                    InputStream a = ctx.getContentResolver().openInputStream(u);
                    InputStream b = ctx.getContentResolver().openInputStream(u);
                    return decodeBounded(a, b);
                }
                if (tag.startsWith("/")) {
                    return decodeBounded(new java.io.FileInputStream(tag),
                            new java.io.FileInputStream(tag));
                }
                // plain http(s) (legacy rows)
                HttpURLConnection c = (HttpURLConnection) new URL(tag).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                try {
                    java.io.File tmp = java.io.File.createTempFile("img", null, ctx.getCacheDir());
                    try (InputStream is = c.getInputStream();
                         java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384]; int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    Bitmap out = decodeBounded(new java.io.FileInputStream(tmp),
                            new java.io.FileInputStream(tmp));
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return out;
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }

        /** Two-pass decode: bounds first, then inSampleSize to the bubble cap —
         *  a full-res 12MP decode would eat half the Q10 heap per row. */
        private static Bitmap decodeBounded(InputStream boundsIn, InputStream dataIn) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(boundsIn, null, o);
                try { boundsIn.close(); } catch (Exception ignored) {}
                int sample = 1;
                while (o.outWidth / (sample * 2) >= MAX_DIM || o.outHeight / (sample * 2) >= MAX_DIM)
                    sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap out = BitmapFactory.decodeStream(dataIn, null, o2);
                try { dataIn.close(); } catch (Exception ignored) {}
                return out;
            } catch (Exception e) {
                return null;
            }
        }

        /** Thumbnail for a cached video file (persisted alongside the video). */
        private Bitmap videoThumb(java.io.File videoFile, String contentUri, boolean isContent) {
            try {
                java.io.File thumbFile = isContent
                        ? new java.io.File(ctx.getCacheDir(),
                            "vt" + contentUri.hashCode() + ".jpg")
                        : new java.io.File(videoFile.getParent(), videoFile.getName() + ".thumb.jpg");
                if (thumbFile.exists() && thumbFile.length() > 0)
                    return decodeBounded(new java.io.FileInputStream(thumbFile),
                            new java.io.FileInputStream(thumbFile));
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    if (isContent) {
                        r.setDataSource(ctx, Uri.parse(contentUri));
                    } else {
                        // fd overload: mediaserver cannot open app-private paths on 4.3
                        java.io.FileInputStream fis = new java.io.FileInputStream(videoFile);
                        r.setDataSource(fis.getFD());
                        fis.close();
                    }
                    Bitmap frame = r.getFrameAtTime(-1);
                    if (frame == null) return null;
                    // full video frames would dominate the Q10's small bitmap cache
                    if (frame.getWidth() > MAX_DIM || frame.getHeight() > MAX_DIM) {
                        float s = Math.min((float) MAX_DIM / frame.getWidth(),
                                (float) MAX_DIM / frame.getHeight());
                        Bitmap scaled = Bitmap.createScaledBitmap(frame,
                                Math.max(1, (int) (frame.getWidth() * s)),
                                Math.max(1, (int) (frame.getHeight() * s)), true);
                        frame.recycle();
                        frame = scaled;
                    }
                    try (java.io.OutputStream os = new java.io.FileOutputStream(thumbFile)) {
                        frame.compress(Bitmap.CompressFormat.JPEG, 80, os);
                    }
                    return frame;
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
