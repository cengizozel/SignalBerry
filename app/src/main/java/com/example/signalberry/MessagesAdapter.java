package com.example.signalberry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

class MessagesAdapter extends BaseAdapter {

    private static final int[] PALETTE = {
        0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A, 0xFF00838F,
        0xFFAD1457, 0xFF4527A0, 0xFF00695C, 0xFFE65100
    };

    private final Context ctx;
    private final List<Map<String, String>> data;
    private final AvatarCache avatarCache;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean demoMode;

    MessagesAdapter(Context ctx, List<Map<String, String>> data, AvatarCache avatarCache, boolean demoMode) {
        this.ctx = ctx;
        this.data = data;
        this.avatarCache = avatarCache;
        this.demoMode = demoMode;
    }

    void setDemoMode(boolean demoMode) { this.demoMode = demoMode; }

    @Override public int getCount()          { return data.size(); }
    @Override public Object getItem(int pos) { return data.get(pos); }
    @Override public long getItemId(int pos) { return pos; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null)
            convertView = LayoutInflater.from(ctx).inflate(R.layout.row_chat, parent, false);

        Map<String, String> item = data.get(position);

        TextView  tvName    = convertView.findViewById(R.id.name);
        TextView  tvSnippet = convertView.findViewById(R.id.snippet);
        TextView  tvTime    = convertView.findViewById(R.id.time);
        TextView  tvBadge   = convertView.findViewById(R.id.badge);
        ImageView ivAvatar  = convertView.findViewById(R.id.avatar);

        String name       = item.get("name");
        String number     = item.get("number");
        String uuid       = item.get("uuid");
        String avatarPath = item.get("avatar_path");

        String snippet = item.get("snippet");
        if (demoMode) {
            name    = DemoData.NAMES[position % DemoData.NAMES.length];
            snippet = DemoData.fakeSnippetByIndex(position);
            avatarPath = "";
        }

        tvName.setText(name);
        tvSnippet.setText(snippet);
        tvTime.setText(item.get("time"));

        int count = 0;
        try { count = Integer.parseInt(item.get("unread")); } catch (Exception ignored) {}
        if (count > 0) {
            tvBadge.setVisibility(View.VISIBLE);
            tvBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            tvName.setTypeface(null, Typeface.BOLD);
            tvSnippet.setTypeface(null, Typeface.BOLD);
        } else {
            tvBadge.setVisibility(View.GONE);
            tvName.setTypeface(null, Typeface.NORMAL);
            tvSnippet.setTypeface(null, Typeface.NORMAL);
        }

        int sizePx = dpToPx(44);
        if ("1".equals(item.get("is_group"))) {
            Bitmap grp = BIND_CACHE.get("groupav|" + sizePx);
            if (grp == null) {
                grp = groupCircle(sizePx);
                BIND_CACHE.put("groupav|" + sizePx, grp);
            }
            ivAvatar.setImageBitmap(grp);
            ivAvatar.setTag("groupav");
            return convertView;
        }
        if ("1".equals(item.get("is_self"))) {
            Bitmap note = BIND_CACHE.get("noteself|" + sizePx);
            if (note == null) {
                note = noteToSelfCircle(sizePx);
                BIND_CACHE.put("noteself|" + sizePx, note);
            }
            ivAvatar.setImageBitmap(note);
            ivAvatar.setTag("noteself");
            return convertView;
        }
        String initialsKey = (name == null || name.isEmpty() ? "?" : name.substring(0, 1)) + "|"
                + Math.abs((name == null ? 0 : name.hashCode()) % PALETTE.length);
        Bitmap initials = BIND_CACHE.get("init|" + initialsKey);
        if (initials == null) {
            initials = initialsCircle(name, sizePx);
            BIND_CACHE.put("init|" + initialsKey, initials);
        }
        // gate on having an avatar to FETCH (avatar_path = the contact's uuid),
        // not on a phone number — number-privacy contacts have no number but
        // still have a fetchable stock/uploaded photo. Cache + view tag key off
        // that uuid so number-less contacts don't all collide on one "av|" key.
        final String path = avatarPath;
        ivAvatar.setImageBitmap(initials);
        ivAvatar.setTag(path);

        if (!demoMode && avatarCache != null && path != null && !path.isEmpty()) {
            Bitmap cachedCircle = BIND_CACHE.get("av|" + path);
            if (cachedCircle != null) {
                ivAvatar.setImageBitmap(cachedCircle);
            } else {
                final String num = number;
                BIND_EXEC.execute(() -> {
                    Bitmap raw = avatarCache.fetch(num, path);
                    if (raw == null) return;
                    Bitmap circle = circleCrop(raw, sizePx);
                    BIND_CACHE.put("av|" + path, circle);
                    handler.post(() -> {
                        if (path.equals(ivAvatar.getTag())) {
                            ivAvatar.setImageBitmap(circle);
                        }
                    });
                });
            }
        }

        return convertView;
    }

    /** Row binds fire constantly while scrolling — never allocate or thread there. */
    private static final android.util.LruCache<String, Bitmap> BIND_CACHE =
            new android.util.LruCache<String, Bitmap>(2 * 1024) {
                @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount() / 1024; }
            };
    private static final java.util.concurrent.ExecutorService BIND_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Note-to-Self avatar: a notepad drawn in code — no glyph/font dependence. */
    static Bitmap noteToSelfCircle(int size) {
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFF607D8B); // blue-gray: distinct from the contact palette
        c.drawCircle(size / 2f, size / 2f, size / 2f, fill);

        Paint page = new Paint(Paint.ANTI_ALIAS_FLAG);
        page.setColor(Color.WHITE);
        float l = size * 0.32f, t = size * 0.26f, r = size * 0.68f, b = size * 0.74f;
        c.drawRoundRect(new android.graphics.RectF(l, t, r, b), size * 0.04f, size * 0.04f, page);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xFF607D8B);
        line.setStrokeWidth(Math.max(1f, size * 0.035f));
        float inset = size * 0.06f;
        for (int i = 1; i <= 3; i++) {
            float y = t + (b - t) * i / 4f;
            c.drawLine(l + inset, y, r - inset, y, line);
        }
        return bm;
    }

    /** Group avatar: two-person silhouette drawn in code (no glyph risk). */
    static Bitmap groupCircle(int size) {
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFF7E57C2); // purple: distinct from contacts + Note to Self
        c.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        // back person (smaller, offset right)
        c.drawCircle(size * 0.62f, size * 0.40f, size * 0.105f, p);
        c.drawOval(new android.graphics.RectF(size * 0.48f, size * 0.52f,
                size * 0.78f, size * 0.80f), p);
        // front person
        p.setColor(0xFF7E57C2);
        c.drawCircle(size * 0.40f, size * 0.42f, size * 0.155f, p);
        p.setColor(Color.WHITE);
        c.drawCircle(size * 0.40f, size * 0.42f, size * 0.125f, p);
        c.drawOval(new android.graphics.RectF(size * 0.22f, size * 0.56f,
                size * 0.58f, size * 0.88f), p);
        return bm;
    }

    private int dpToPx(int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static Bitmap initialsCircle(String name, int size) {
        int color = PALETTE[Math.abs((name == null ? 0 : name.hashCode()) % PALETTE.length)];
        String letter = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";

        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(size * 0.4f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float y = size / 2f - (text.ascent() + text.descent()) / 2f;
        canvas.drawText(letter, size / 2f, y, text);

        return bm;
    }

    static Bitmap circleCrop(Bitmap src, int size) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }
}
