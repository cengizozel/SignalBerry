package com.example.signalberry;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Full-screen viewer: swipe between images, pinch/double-tap zoom (Matrix-based,
 * no libraries), save to gallery, share. Decodes are capped at 2048px — above
 * the GL texture limit a hardware-accelerated ImageView renders BLANK on old
 * GPUs, and a full 12MP decode would eat the Q10 heap.
 */
public class ImageViewerActivity extends AppCompatActivity {

    static final String EXTRA_SOURCES    = "sources";
    static final String EXTRA_POSITION   = "position";
    /** Optional, parallel to sources: serverTs per image. Enables "View in chat". */
    static final String EXTRA_TIMESTAMPS = "timestamps";
    /** Result extra: timestamp of the message the user wants to see in context. */
    static final String EXTRA_RESULT_TS  = "jump_ts";

    private static final int MAX_DIM = 2048;

    private ArrayList<String> sources;
    private long[] timestamps;
    private ViewPager pager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sources = getIntent().getStringArrayListExtra(EXTRA_SOURCES);
        timestamps = getIntent().getLongArrayExtra(EXTRA_TIMESTAMPS);
        int startPos = getIntent().getIntExtra(EXTRA_POSITION, 0);
        if (sources == null || sources.isEmpty()) { finish(); return; }
        if (timestamps != null && timestamps.length != sources.size()) timestamps = null;

        android.widget.RelativeLayout root = new android.widget.RelativeLayout(this);
        root.setBackgroundColor(0xFF000000);

        pager = new ViewPager(this);
        pager.setId(View.generateViewId());
        android.widget.RelativeLayout.LayoutParams pagerLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(pager, pagerLp);

        TextView counter = new TextView(this);
        counter.setTextColor(0xFFFFFFFF);
        counter.setTextSize(14);
        counter.setPadding(0, 48, 32, 0);
        android.widget.RelativeLayout.LayoutParams counterLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        counterLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        counterLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        root.addView(counter, counterLp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(22);
        close.setPadding(32, 48, 32, 16);
        android.widget.RelativeLayout.LayoutParams closeLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        closeLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        root.addView(close, closeLp);
        close.setOnClickListener(v -> finish());

        // bottom action bar: Save / Share
        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER);
        actions.setBackgroundColor(0x66000000);
        TextView save = actionButton("Save");
        TextView share = actionButton("Share");
        actions.addView(save);
        actions.addView(share);
        if (timestamps != null) {
            TextView toChat = actionButton("View in chat");
            actions.addView(toChat);
            toChat.setOnClickListener(v -> {
                long ts = timestamps[pager.getCurrentItem()];
                if (ts <= 0) return;
                setResult(RESULT_OK, new android.content.Intent().putExtra(EXTRA_RESULT_TS, ts));
                finish();
            });
        }
        android.widget.RelativeLayout.LayoutParams actionsLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        root.addView(actions, actionsLp);

        save.setOnClickListener(v -> withCurrentImage(this::saveToGallery));
        share.setOnClickListener(v -> withCurrentImage(this::shareImage));

        setContentView(root);

        final int total = sources.size();
        pager.setAdapter(new ImagePagerAdapter(this, sources));
        pager.setCurrentItem(startPos, false);
        counter.setText((startPos + 1) + " / " + total);

        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) {
                counter.setText((position + 1) + " / " + total);
            }
        });
    }

    private TextView actionButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setPadding(48, 24, 48, 24);
        return tv;
    }

    private interface BitmapAction { void run(Bitmap bm); }

    private void withCurrentImage(BitmapAction action) {
        String src = sources.get(pager.getCurrentItem());
        new Thread(() -> {
            Bitmap bm = loadBounded(this, src);
            runOnUiThread(() -> {
                if (bm == null) Toast.makeText(this, "Image not loaded", Toast.LENGTH_SHORT).show();
                else action.run(bm);
            });
        }).start();
    }

    private void saveToGallery(Bitmap bm) {
        new Thread(() -> {
            boolean ok = false;
            String name = "SignalBerry_" + System.currentTimeMillis() + ".jpg";
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalBerry");
                    Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            ok = bm.compress(Bitmap.CompressFormat.JPEG, 92, os);
                        }
                    }
                } else {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "SignalBerry");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File out = new File(dir, name);
                    try (OutputStream os = new FileOutputStream(out)) {
                        ok = bm.compress(Bitmap.CompressFormat.JPEG, 92, os);
                    }
                    if (ok) {
                        android.media.MediaScannerConnection.scanFile(
                                this, new String[]{out.getAbsolutePath()}, null, null);
                    }
                }
            } catch (Exception ignored) {}
            final boolean fOk = ok;
            runOnUiThread(() -> Toast.makeText(this,
                    fOk ? "Saved to Pictures/SignalBerry" : "Save failed (storage unavailable?)",
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    private void shareImage(Bitmap bm) {
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "share.jpg");
                try (OutputStream os = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".files", f);
                android.content.Intent send = new android.content.Intent(
                        android.content.Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        startActivity(android.content.Intent.createChooser(send, "Share image"));
                    } catch (Exception e) {
                        Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ── bounded loading (shared with the pager) ─────────────────────────────

    private static Bitmap loadBounded(Context ctx, String src) {
        try {
            if (src.startsWith("content://") || src.startsWith("file://")) {
                Uri u = Uri.parse(src);
                return decodeBounded(ctx.getContentResolver().openInputStream(u),
                        ctx.getContentResolver().openInputStream(u));
            }
            if (src.startsWith("/")) {
                return decodeBounded(new java.io.FileInputStream(src),
                        new java.io.FileInputStream(src));
            }
            // attachment URLs go through the shared disk cache: it sends the
            // auth headers (Cloudflare Access rejects bare requests — the old
            // "viewer is black for received photos" bug) and skips a second
            // download over the radio when the bubble already cached the file
            int at = src.indexOf("/v1/attachments/");
            if (at > 0) {
                File f = AttachmentStore.get(ctx).fetch(src.substring(0, at),
                        Uri.decode(src.substring(at + "/v1/attachments/".length())));
                if (f == null) return null;
                return decodeBounded(new java.io.FileInputStream(f),
                        new java.io.FileInputStream(f));
            }
            // other http(s): cache through a temp file so we can double-pass decode
            HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            Auth.apply(c);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            File tmp = File.createTempFile("viewer", null, ctx.getCacheDir());
            try (InputStream is = c.getInputStream();
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            Bitmap out = decodeBounded(new java.io.FileInputStream(tmp),
                    new java.io.FileInputStream(tmp));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return out;
        } catch (Exception e) { return null; }
    }

    private static Bitmap decodeBounded(InputStream boundsIn, InputStream dataIn) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(boundsIn, null, o);
            try { if (boundsIn != null) boundsIn.close(); } catch (Exception ignored) {}
            // sample until the decode fits MAX_DIM — dividing by (sample * 2)
            // let anything under 2*MAX_DIM through unsampled, past the GL cap
            int sample = 1;
            while (o.outWidth / sample > MAX_DIM || o.outHeight / sample > MAX_DIM)
                sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap out = BitmapFactory.decodeStream(dataIn, null, o2);
            try { if (dataIn != null) dataIn.close(); } catch (Exception ignored) {}
            return out;
        } catch (Exception e) { return null; }
    }

    // ── pager ────────────────────────────────────────────────────────────────

    private static class ImagePagerAdapter extends PagerAdapter {
        private final Context ctx;
        private final ArrayList<String> sources;

        ImagePagerAdapter(Context ctx, ArrayList<String> sources) {
            this.ctx = ctx;
            this.sources = sources;
        }

        @Override public int getCount() { return sources.size(); }
        @Override public boolean isViewFromObject(View view, Object object) { return view == object; }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            android.widget.FrameLayout page = new android.widget.FrameLayout(ctx);
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page.setBackgroundColor(0xFF000000);

            ZoomImageView iv = new ZoomImageView(ctx);
            page.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView err = new TextView(ctx);
            err.setText("Couldn't load image");
            err.setTextColor(0xFFAAAAAA);
            err.setTextSize(16);
            err.setVisibility(View.GONE);
            android.widget.FrameLayout.LayoutParams errLp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            errLp.gravity = android.view.Gravity.CENTER;
            page.addView(err, errLp);

            container.addView(page);

            final String src = sources.get(position);
            new Thread(() -> {
                Bitmap bm = loadBounded(ctx, src);
                iv.post(() -> {
                    if (bm != null) iv.setImageBitmap(bm);
                    else err.setVisibility(View.VISIBLE);
                });
            }).start();

            return page;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }
    }

    /**
     * Matrix-based pinch/double-tap zoom + pan. Plays nice with ViewPager:
     * requests parent disallow-intercept only while zoomed in.
     */
    static class ZoomImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector tapDetector;
        private float scale = 1f;
        private static final float MAX_SCALE = 5f;
        private final PointF last = new PointF();
        private boolean dragging = false;

        ZoomImageView(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(ctx,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    applyScale(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            tapDetector = new android.view.GestureDetector(ctx,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.01f) resetMatrix();
                    else applyScale(2.5f, e.getX(), e.getY());
                    return true;
                }
            });
        }

        @Override public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            post(this::resetMatrix);
        }

        private void resetMatrix() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d == null || getWidth() == 0) return;
            matrix.reset();
            float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            float vw = getWidth(), vh = getHeight();
            float s = Math.min(vw / iw, vh / ih);
            matrix.postScale(s, s);
            matrix.postTranslate((vw - iw * s) / 2f, (vh - ih * s) / 2f);
            scale = 1f;
            setImageMatrix(matrix);
        }

        private void applyScale(float factor, float fx, float fy) {
            float newScale = Math.max(1f, Math.min(scale * factor, MAX_SCALE));
            float actual = newScale / scale;
            scale = newScale;
            matrix.postScale(actual, actual, fx, fy);
            if (scale <= 1.01f) resetMatrix();
            else { clampPan(); setImageMatrix(matrix); }
        }

        private void clampPan() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d == null) return;
            android.graphics.RectF rect = new android.graphics.RectF(
                    0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(rect);
            float dx = 0, dy = 0;
            if (rect.width() <= getWidth()) dx = (getWidth() - rect.width()) / 2f - rect.left;
            else if (rect.left > 0) dx = -rect.left;
            else if (rect.right < getWidth()) dx = getWidth() - rect.right;
            if (rect.height() <= getHeight()) dy = (getHeight() - rect.height()) / 2f - rect.top;
            else if (rect.top > 0) dy = -rect.top;
            else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
            matrix.postTranslate(dx, dy);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            boolean zoomed = scale > 1.01f;
            getParent().requestDisallowInterceptTouchEvent(zoomed || ev.getPointerCount() > 1);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last.set(ev.getX(), ev.getY());
                    dragging = zoomed;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && zoomed && ev.getPointerCount() == 1
                            && !scaleDetector.isInProgress()) {
                        matrix.postTranslate(ev.getX() - last.x, ev.getY() - last.y);
                        clampPan();
                        setImageMatrix(matrix);
                    }
                    last.set(ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
            }
            return true;
        }
    }
}
