package com.example.signalberry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_PEER_TEXT  = 1;
    private static final int VIEW_ME_TEXT    = 2;
    private static final int VIEW_PEER_IMAGE = 3;
    private static final int VIEW_ME_IMAGE   = 4;

    interface OnImageClickListener { void onImageClick(int position); }

    private final List<MessageItem> data;
    private final String restBase;
    private final ImageLoader loader;
    private OnImageClickListener imageClickListener;

    ChatAdapter(List<MessageItem> data, String restBase, Context ctx) {
        this.data = data;
        this.restBase = restBase;
        this.loader = new ImageLoader(ctx);
    }

    void setOnImageClickListener(OnImageClickListener l) { this.imageClickListener = l; }

    @Override public int getItemViewType(int position) {
        MessageItem m = data.get(position);
        boolean me = "me".equals(m.from);
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
            default:
                return new PeerImageVH(inf.inflate(R.layout.item_chat_peer_image, parent, false));
        }
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        MessageItem m = data.get(pos);
        if (h instanceof MeTextVH) ((MeTextVH) h).bind(m);
        else if (h instanceof PeerTextVH) ((PeerTextVH) h).bind(m);
        else if (h instanceof MeImageVH)  ((MeImageVH) h).bind(m, loader, restBase, pos, imageClickListener);
        else if (h instanceof PeerImageVH)((PeerImageVH) h).bind(m, loader, restBase, pos, imageClickListener);
    }

    @Override public int getItemCount() { return data.size(); }

    // ---- text VHs ----
    static class PeerTextVH extends RecyclerView.ViewHolder {
        final TextView tvMessage;
        PeerTextVH(@NonNull View v) { super(v); tvMessage = v.findViewById(R.id.tvMessage); }
        void bind(MessageItem m) { tvMessage.setText(m.text == null ? "" : m.text); }
    }
    static class MeTextVH extends RecyclerView.ViewHolder {
        final TextView tvMessage, tvStatus;
        MeTextVH(@NonNull View v) {
            super(v);
            tvMessage = v.findViewById(R.id.tvMessage);
            tvStatus  = v.findViewById(R.id.tvStatus);
        }
        void bind(MessageItem m) {
            tvMessage.setText(m.text == null ? "" : m.text);
            tvStatus.setText(statusMark(m.status));
        }
    }

    // ---- image VHs ----
    static class PeerImageVH extends RecyclerView.ViewHolder {
        final ImageView iv;
        final TextView tvCaption;
        PeerImageVH(@NonNull View v) {
            super(v);
            iv = v.findViewById(R.id.ivImage);
            tvCaption = v.findViewById(R.id.tvCaption);
        }
        void bind(MessageItem m, ImageLoader loader, String base, int pos, OnImageClickListener l) {
            String src = m.localUri != null ? m.localUri : base + "/v1/attachments/" + m.attachmentId;
            iv.setImageDrawable(null);
            iv.setTag(src);
            loader.load(src, iv);
            iv.setOnClickListener(l != null ? v -> l.onImageClick(pos) : null);
            if (m.caption != null && !m.caption.isEmpty()) {
                tvCaption.setText(m.caption);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
        }
    }
    static class MeImageVH extends RecyclerView.ViewHolder {
        final ImageView iv;
        final TextView tvStatus;
        final TextView tvCaption;
        MeImageVH(@NonNull View v) {
            super(v);
            iv = v.findViewById(R.id.ivImage);
            tvStatus = v.findViewById(R.id.tvStatus);
            tvCaption = v.findViewById(R.id.tvCaption);
        }
        void bind(MessageItem m, ImageLoader loader, String base, int pos, OnImageClickListener l) {
            String src = m.localUri != null ? m.localUri : base + "/v1/attachments/" + m.attachmentId;
            iv.setImageDrawable(null);
            iv.setTag(src);
            loader.load(src, iv);
            iv.setOnClickListener(l != null ? v -> l.onImageClick(pos) : null);
            tvStatus.setText(statusMark(m.status));
            if (m.caption != null && !m.caption.isEmpty()) {
                tvCaption.setText(m.caption);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
        }
    }

    private static String statusMark(int st) {
        switch (st) {
            case Chat.ST_PENDING:   return "🕒";
            case Chat.ST_SENT:      return "✓";
            case Chat.ST_DELIVERED: return "✓✓";
            case Chat.ST_READ:      return "✓✓";
            default:                return "";
        }
    }

    // ---- image loader with in-memory cache — handles http:// and content:// ----
    static class ImageLoader {
        private final LruCache<String, Bitmap> cache;
        private final Context ctx;

        ImageLoader(Context ctx) {
            this.ctx = ctx.getApplicationContext();
            final int maxKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
            cache = new LruCache<String, Bitmap>(maxKb / 16) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }

        void load(final String src, final ImageView target) {
            Bitmap cached = cache.get(src);
            if (cached != null) {
                if (src.equals(target.getTag())) target.setImageBitmap(cached);
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    Bitmap bmp = fetch(src);
                    if (bmp != null) cache.put(src, bmp);
                    target.post(new Runnable() {
                        @Override public void run() {
                            if (src.equals(target.getTag())) target.setImageBitmap(bmp);
                        }
                    });
                }
            }).start();
        }

        private Bitmap fetch(String src) {
            try {
                if (src.startsWith("content://")) {
                    InputStream is = ctx.getContentResolver().openInputStream(Uri.parse(src));
                    return is != null ? BitmapFactory.decodeStream(is) : null;
                }
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                try (InputStream is = c.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
