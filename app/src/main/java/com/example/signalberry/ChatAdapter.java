package com.example.signalberry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    private final List<MessageItem> data;
    private final String restBase; // http://IP:PORT of Signal REST
    private final ImageLoader loader = new ImageLoader();

    ChatAdapter(List<MessageItem> data, String restBase) {
        this.data = data;
        this.restBase = restBase;
    }

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
        else if (h instanceof MeImageVH)  ((MeImageVH) h).bind(m, loader, restBase);
        else if (h instanceof PeerImageVH)((PeerImageVH) h).bind(m, loader, restBase);
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
        PeerImageVH(@NonNull View v) { super(v); iv = v.findViewById(R.id.ivImage); }
        void bind(MessageItem m, ImageLoader loader, String base) {
            String url = base + "/v1/attachments/" + m.attachmentId;
            iv.setImageDrawable(null);
            iv.setTag(url);
            loader.load(url, iv);
        }
    }
    static class MeImageVH extends RecyclerView.ViewHolder {
        final ImageView iv;
        final TextView tvStatus;
        MeImageVH(@NonNull View v) {
            super(v);
            iv = v.findViewById(R.id.ivImage);
            tvStatus = v.findViewById(R.id.tvStatus);
        }
        void bind(MessageItem m, ImageLoader loader, String base) {
            String url = base + "/v1/attachments/" + m.attachmentId;
            iv.setImageDrawable(null);
            iv.setTag(url);
            loader.load(url, iv);
            tvStatus.setText(statusMark(m.status));
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

    // ---- super tiny image loader with in-memory cache (API 12+) ----
    static class ImageLoader {
        private final LruCache<String, Bitmap> cache;
        ImageLoader() {
            final int maxKb = (int)(Runtime.getRuntime().maxMemory() / 1024);
            final int cacheKb = maxKb / 16; // ~6% of heap
            cache = new LruCache<String, Bitmap>(cacheKb) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }
        void load(final String url, final ImageView target) {
            Bitmap cached = cache.get(url);
            if (cached != null) {
                if (url.equals(target.getTag())) target.setImageBitmap(cached);
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    Bitmap bmp = fetch(url);
                    if (bmp != null) cache.put(url, bmp);
                    target.post(new Runnable() {
                        @Override public void run() {
                            if (url.equals(target.getTag())) target.setImageBitmap(bmp);
                        }
                    });
                }
            }).start();
        }
        private Bitmap fetch(String urlStr) {
            HttpURLConnection c = null;
            try {
                URL url = new URL(urlStr);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) return null;
                InputStream is = c.getInputStream();
                return BitmapFactory.decodeStream(is);
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }
}
