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

    MessagesAdapter(Context ctx, List<Map<String, String>> data, AvatarCache avatarCache) {
        this.ctx = ctx;
        this.data = data;
        this.avatarCache = avatarCache;
    }

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

        String name   = item.get("name");
        String number = item.get("number");

        tvName.setText(name);
        tvSnippet.setText(item.get("snippet"));
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
        ivAvatar.setImageBitmap(initialsCircle(name, sizePx));
        ivAvatar.setTag(number);

        if (avatarCache != null && number != null && !number.isEmpty()) {
            final String tag = number;
            new Thread(() -> {
                Bitmap raw = avatarCache.fetch(number);
                if (raw == null) return;
                Bitmap circle = circleCrop(raw, sizePx);
                handler.post(() -> {
                    if (tag.equals(ivAvatar.getTag())) {
                        ivAvatar.setImageBitmap(circle);
                    }
                });
            }).start();
        }

        return convertView;
    }

    private int dpToPx(int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private Bitmap initialsCircle(String name, int size) {
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

    private static Bitmap circleCrop(Bitmap src, int size) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }
}
