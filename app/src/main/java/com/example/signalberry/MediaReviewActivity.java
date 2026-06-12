package com.example.signalberry;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Signal-style pre-send review: swipe through the picked media full-screen,
 * rotate/crop images, give each item its own message, drop items from the
 * batch, then send everything. Returns processed uris + per-item captions;
 * Chat does the actual sending. Untouched items pass through unmodified.
 */
public class MediaReviewActivity extends AppCompatActivity {

    static final String EXTRA_URIS         = "uris";
    static final String EXTRA_OUT_URIS     = "out_uris";
    static final String EXTRA_OUT_CAPTIONS = "out_captions";

    private static final int PREVIEW_DIM = 800;   // on-screen decode bound
    private static final int OUTPUT_DIM  = 2048;  // re-encode bound for edited images

    private static class MItem {
        final Uri uri;
        final boolean video;
        int rotation = 0;     // 0/90/180/270, clockwise
        RectF crop = null;    // normalized on the rotated image, null = full
        boolean stamp = false; // "Sent from my BlackBerry" watermark
        String caption = "";
        Bitmap preview;       // rotation+crop applied
        MItem(Uri u, boolean v) { uri = u; video = v; }
        boolean edited() { return rotation != 0 || crop != null || stamp; }
    }

    private final ArrayList<MItem> items = new ArrayList<>();
    private LockableViewPager pager;
    private PreviewPagerAdapter pagerAdapter;
    private RecyclerView rail;
    private TextView tvCounter, btnRotate, btnCrop, btnStamp, btnSend, btnApply, btnCancelCrop;
    private EditText captionInput;
    private CropOverlayView cropOverlay;
    private LinearLayout editBar, cropBar;
    private boolean sending = false;
    private final java.util.concurrent.ExecutorService exec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        ArrayList<Uri> in = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        if (in == null || in.isEmpty()) { finish(); return; }
        for (Uri u : in) {
            String mime = getContentResolver().getType(u);
            if (mime == null) mime = Utils.guessMime(u.toString());
            items.add(new MItem(u, mime != null && mime.startsWith("video/")));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        // ---- top bar: back | 1/4 | Rotate Crop | Send ----
        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_media_previous);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.MATCH_PARENT));
        back.setOnClickListener(v -> finish());
        editBar.addView(back);
        tvCounter = barText("1/" + items.size(), false);
        tvCounter.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        editBar.addView(tvCounter);
        btnRotate = barText("Rotate", true);
        btnRotate.setOnClickListener(v -> rotateCurrent());
        editBar.addView(btnRotate);
        btnCrop = barText("Crop", true);
        btnCrop.setOnClickListener(v -> enterCropMode());
        editBar.addView(btnCrop);
        btnStamp = barText("BB", true);
        btnStamp.setOnClickListener(v -> toggleStamp());
        editBar.addView(btnStamp);
        btnSend = barText("Send", true);
        btnSend.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSend.setOnClickListener(v -> doSend());
        editBar.addView(btnSend);
        root.addView(editBar);

        // ---- crop-mode bar (swapped in over the edit bar) ----
        cropBar = new LinearLayout(this);
        cropBar.setOrientation(LinearLayout.HORIZONTAL);
        cropBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        cropBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView cropTitle = barText("Drag the corners", false);
        cropTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        cropTitle.setPadding(dp(16), 0, 0, 0);
        cropBar.addView(cropTitle);
        btnCancelCrop = barText("Cancel", true);
        btnCancelCrop.setOnClickListener(v -> exitCropMode());
        cropBar.addView(btnCancelCrop);
        btnApply = barText("Apply", true);
        btnApply.setTypeface(null, android.graphics.Typeface.BOLD);
        btnApply.setOnClickListener(v -> applyCrop());
        cropBar.addView(btnApply);
        cropBar.setVisibility(View.GONE);
        root.addView(cropBar);

        // ---- pager + crop overlay ----
        FrameLayout stage = new FrameLayout(this);
        pager = new LockableViewPager(this);
        pagerAdapter = new PreviewPagerAdapter();
        pager.setAdapter(pagerAdapter);
        stage.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cropOverlay = new CropOverlayView(this);
        cropOverlay.setVisibility(View.GONE);
        stage.addView(cropOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- caption for the current item ----
        captionInput = new EditText(this);
        captionInput.setHint("Add a message to this item");
        captionInput.setTextColor(Color.WHITE);
        captionInput.setHintTextColor(0xFF888888);
        captionInput.setTextSize(15);
        captionInput.setMaxLines(2);
        captionInput.setPadding(dp(16), dp(8), dp(16), dp(8));
        captionInput.setBackgroundColor(0xFF1E1E1E);
        root.addView(captionInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- thumbnail rail ----
        rail = new RecyclerView(this);
        rail.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        rail.setAdapter(new RailAdapter());
        rail.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(rail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);

        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            private int last = 0;
            @Override public void onPageSelected(int position) {
                if (last < items.size()) items.get(last).caption =
                        captionInput.getText().toString();
                last = position;
                onPageShown(position);
            }
        });
        onPageShown(0);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private TextView barText(String label, boolean clickable) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(clickable ? Utils.ACCENT : Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), 0, dp(12), 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private void onPageShown(int position) {
        MItem it = items.get(position);
        tvCounter.setText((position + 1) + "/" + items.size());
        captionInput.setText(it.caption);
        captionInput.setSelection(it.caption.length());
        captionInput.setHint(it.video ? "Add a message to this video"
                                      : "Add a message to this photo");
        boolean editable = !it.video;
        btnRotate.setVisibility(editable ? View.VISIBLE : View.GONE);
        btnCrop.setVisibility(editable ? View.VISIBLE : View.GONE);
        btnStamp.setVisibility(editable ? View.VISIBLE : View.GONE);
        btnStamp.setTextColor(it.stamp ? 0xFF7ED321 : Utils.ACCENT);
        ((RailAdapter) rail.getAdapter()).setCurrent(position);
        rail.scrollToPosition(position);
    }

    private void toggleStamp() {
        MItem it = items.get(pager.getCurrentItem());
        if (it.video) return;
        it.stamp = !it.stamp;
        btnStamp.setTextColor(it.stamp ? 0xFF7ED321 : Utils.ACCENT);
        refreshPreview(it, pager.getCurrentItem());
    }

    // -------------------- edit ops --------------------

    private void rotateCurrent() {
        MItem it = items.get(pager.getCurrentItem());
        if (it.video) return;
        it.rotation = (it.rotation + 90) % 360;
        if (it.crop != null) {
            // rotate the crop window with the image (90 degrees clockwise)
            RectF c = it.crop;
            it.crop = new RectF(1f - c.bottom, c.left, 1f - c.top, c.right);
        }
        refreshPreview(it, pager.getCurrentItem());
    }

    private void enterCropMode() {
        MItem it = items.get(pager.getCurrentItem());
        if (it.video || it.preview == null) return;
        cropOverlay.setImage(it.preview.getWidth(), it.preview.getHeight());
        cropOverlay.setVisibility(View.VISIBLE);
        pager.locked = true;
        editBar.setVisibility(View.GONE);
        cropBar.setVisibility(View.VISIBLE);
        captionInput.setEnabled(false);
    }

    private void exitCropMode() {
        cropOverlay.setVisibility(View.GONE);
        pager.locked = false;
        editBar.setVisibility(View.VISIBLE);
        cropBar.setVisibility(View.GONE);
        captionInput.setEnabled(true);
    }

    private void applyCrop() {
        MItem it = items.get(pager.getCurrentItem());
        RectF rel = cropOverlay.getNormalizedRect();
        if (rel != null && (rel.width() < 0.999f || rel.height() < 0.999f)) {
            if (it.crop == null) {
                it.crop = rel;
            } else {
                // compose: rel is relative to the already-cropped preview
                RectF c = it.crop;
                it.crop = new RectF(
                        c.left + rel.left * c.width(),
                        c.top + rel.top * c.height(),
                        c.left + rel.right * c.width(),
                        c.top + rel.bottom * c.height());
            }
            refreshPreview(it, pager.getCurrentItem());
        }
        exitCropMode();
    }

    private void refreshPreview(final MItem it, final int position) {
        exec.execute(() -> {
            final Bitmap b = renderItem(it, PREVIEW_DIM);
            runOnUiThread(() -> {
                if (b != null) it.preview = b;
                ImageView page = pagerAdapter.pageViews.get(position);
                if (page != null && b != null) page.setImageBitmap(b);
                rail.getAdapter().notifyItemChanged(position);
            });
        });
    }

    /** Decode bounded, then rotation, then crop. Null on failure. */
    private Bitmap renderItem(MItem it, int maxDim) {
        try {
            Bitmap b;
            if (it.video) {
                android.media.MediaMetadataRetriever r =
                        new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(this, it.uri);
                    b = r.getFrameAtTime();
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
                return b; // no edits for video
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(it.uri)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxDim
                    || o.outHeight / (sample * 2) >= maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream is = getContentResolver().openInputStream(it.uri)) {
                b = BitmapFactory.decodeStream(is, null, o2);
            }
            if (b == null) return null;
            if (it.rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(it.rotation);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            if (it.crop != null) {
                int w = b.getWidth(), h = b.getHeight();
                int cl = Math.max(0, (int) (it.crop.left * w));
                int ct = Math.max(0, (int) (it.crop.top * h));
                int cw = Math.min(w - cl, Math.max(1, (int) (it.crop.width() * w)));
                int ch = Math.min(h - ct, Math.max(1, (int) (it.crop.height() * h)));
                Bitmap c = Bitmap.createBitmap(b, cl, ct, cw, ch);
                if (c != b) b.recycle();
                b = c;
            }
            if (it.stamp) {
                Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true);
                b.recycle();
                b = m;
                drawBlackBerryStamp(b);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parody "Sent from my BlackBerry" badge, maximum Frutiger Aero: aqua
     *  glass pill, gloss highlight, floating bubbles, sparkle. All Canvas —
     *  no assets, no font glyph risk. */
    private static void drawBlackBerryStamp(Bitmap b) {
        Canvas c = new Canvas(b);
        int w = b.getWidth(), h = b.getHeight();
        float bw = Math.min(w * 0.56f, h * 1.6f);  // badge width
        float bh = bw * 0.20f;                      // badge height
        float margin = bh * 0.45f;
        RectF r = new RectF(w - bw - margin, h - bh - margin, w - margin, h - margin);
        float rad = bh / 2f;

        // soft drop shadow
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x44000000);
        RectF rs = new RectF(r);
        rs.offset(bh * 0.05f, bh * 0.10f);
        c.drawRoundRect(rs, rad, rad, shadow);

        // aqua glass body
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new android.graphics.LinearGradient(r.left, r.top, r.left, r.bottom,
                new int[]{0xFFAEEBFF, 0xFF38A8EC, 0xFF0A5CA8, 0xFF2FA0E0},
                new float[]{0f, 0.45f, 0.85f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, body);

        // floating bubbles (drawn under the gloss)
        Paint bub = new Paint(Paint.ANTI_ALIAS_FLAG);
        bub.setColor(0x55FFFFFF);
        c.drawCircle(r.left + bw * 0.16f, r.bottom - bh * 0.30f, bh * 0.13f, bub);
        c.drawCircle(r.left + bw * 0.24f, r.bottom - bh * 0.18f, bh * 0.07f, bub);
        c.drawCircle(r.right - bw * 0.07f, r.top + bh * 0.62f, bh * 0.10f, bub);
        c.drawCircle(r.right - bw * 0.12f, r.bottom - bh * 0.22f, bh * 0.06f, bub);

        // glass gloss on the top half
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new android.graphics.LinearGradient(r.left, r.top, r.left, r.centerY(),
                0xB8FFFFFF, 0x14FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        RectF gr = new RectF(r.left + rad * 0.35f, r.top + bh * 0.07f,
                r.right - rad * 0.35f, r.centerY() + bh * 0.02f);
        c.drawRoundRect(gr, rad * 0.9f, rad * 0.9f, gloss);

        // crisp glass border
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1.5f, bh * 0.035f));
        border.setColor(0xCCFFFFFF);
        c.drawRoundRect(r, rad, rad, border);

        // mini BlackBerry-ish logo: glossy disc with 7 dark drupelets
        float lcx = r.left + bh * 0.62f, lcy = r.centerY(), lr = bh * 0.34f;
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setShader(new android.graphics.LinearGradient(lcx, lcy - lr, lcx, lcy + lr,
                0xFFFFFFFF, 0xFFB8DFF5, android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(lcx, lcy, lr, disc);
        Paint seed = new Paint(Paint.ANTI_ALIAS_FLAG);
        seed.setColor(0xFF15436B);
        float sr = lr * 0.16f, sx = lr * 0.40f, sy = lr * 0.44f;
        // 2-3-2 drupelet cluster
        c.drawCircle(lcx - sx, lcy - sy, sr, seed);
        c.drawCircle(lcx + sx, lcy - sy, sr, seed);
        c.drawCircle(lcx - sx * 1.4f, lcy, sr, seed);
        c.drawCircle(lcx, lcy, sr, seed);
        c.drawCircle(lcx + sx * 1.4f, lcy, sr, seed);
        c.drawCircle(lcx - sx, lcy + sy, sr, seed);
        c.drawCircle(lcx + sx, lcy + sy, sr, seed);

        // italic label with soft shadow
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setFakeBoldText(true);
        tp.setTextSkewX(-0.15f);
        tp.setShadowLayer(bh * 0.10f, 0, bh * 0.05f, 0x99000000);
        String label = "Sent from my BlackBerry®";
        float textLeft = lcx + lr + bh * 0.28f;
        float maxText = r.right - rad * 0.55f - textLeft;
        float size = bh * 0.40f;
        tp.setTextSize(size);
        while (tp.measureText(label) > maxText && size > 6f) {
            size *= 0.94f;
            tp.setTextSize(size);
        }
        float baseline = r.centerY() - (tp.ascent() + tp.descent()) / 2f;
        c.drawText(label, textLeft, baseline, tp);

        // four-point sparkle off the badge's top-left corner
        float spx = r.left + bw * 0.035f, spy = r.top - bh * 0.05f, sl = bh * 0.34f;
        android.graphics.Path star = new android.graphics.Path();
        star.moveTo(spx, spy - sl);
        star.quadTo(spx + sl * 0.12f, spy - sl * 0.12f, spx + sl, spy);
        star.quadTo(spx + sl * 0.12f, spy + sl * 0.12f, spx, spy + sl);
        star.quadTo(spx - sl * 0.12f, spy + sl * 0.12f, spx - sl, spy);
        star.quadTo(spx - sl * 0.12f, spy - sl * 0.12f, spx, spy - sl);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(0xEEFFFFFF);
        c.drawPath(star, sp);
        c.drawCircle(spx, spy, sl * 0.10f, sp);
    }

    // -------------------- send --------------------

    private void doSend() {
        if (sending) return;
        sending = true;
        items.get(pager.getCurrentItem()).caption = captionInput.getText().toString();
        btnSend.setText("…");
        exec.execute(() -> {
            final ArrayList<Uri> outUris = new ArrayList<>();
            final ArrayList<String> outCaps = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                MItem it = items.get(i);
                Uri out = it.uri;
                if (!it.video && it.edited()) {
                    Bitmap full = renderItem(it, OUTPUT_DIM);
                    if (full != null) {
                        try {
                            File f = new File(getCacheDir(),
                                    "edit-" + System.currentTimeMillis() + "-" + i + ".jpg");
                            try (FileOutputStream fos = new FileOutputStream(f)) {
                                full.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                            }
                            out = Uri.fromFile(f);
                        } catch (Exception ignored) {
                        } finally {
                            full.recycle();
                        }
                    }
                }
                outUris.add(out);
                String cap = it.caption == null ? "" : it.caption.trim();
                outCaps.add(cap);
            }
            runOnUiThread(() -> {
                Intent res = new Intent();
                res.putParcelableArrayListExtra(EXTRA_OUT_URIS, outUris);
                res.putStringArrayListExtra(EXTRA_OUT_CAPTIONS, outCaps);
                setResult(RESULT_OK, res);
                finish();
            });
        });
    }

    // -------------------- pager --------------------

    private static class LockableViewPager extends ViewPager {
        boolean locked = false;
        LockableViewPager(android.content.Context c) { super(c); }
        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            return !locked && super.onInterceptTouchEvent(ev);
        }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            return !locked && super.onTouchEvent(ev);
        }
    }

    private class PreviewPagerAdapter extends PagerAdapter {
        final android.util.SparseArray<ImageView> pageViews = new android.util.SparseArray<>();

        @Override public int getCount() { return items.size(); }
        @Override public boolean isViewFromObject(View view, Object o) { return view == o; }
        @Override public int getItemPosition(Object o) { return POSITION_NONE; }

        @Override public Object instantiateItem(ViewGroup container, final int position) {
            final MItem it = items.get(position);
            FrameLayout f = new FrameLayout(MediaReviewActivity.this);
            final ImageView iv = new ImageView(MediaReviewActivity.this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (it.video) {
                TextView play = new TextView(MediaReviewActivity.this);
                play.setText("▶");
                play.setTextColor(Color.WHITE);
                play.setTextSize(42);
                play.setShadowLayer(8, 0, 0, Color.BLACK);
                f.addView(play, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
            }
            pageViews.put(position, iv);
            if (it.preview != null) {
                iv.setImageBitmap(it.preview);
            } else {
                exec.execute(() -> {
                    final Bitmap b = renderItem(it, PREVIEW_DIM);
                    if (b == null) return;
                    runOnUiThread(() -> {
                        it.preview = b;
                        iv.setImageBitmap(b);
                        rail.getAdapter().notifyItemChanged(position);
                    });
                });
            }
            container.addView(f);
            return f;
        }

        @Override public void destroyItem(ViewGroup container, int position, Object o) {
            pageViews.remove(position);
            container.removeView((View) o);
        }
    }

    // -------------------- thumbnail rail --------------------

    private class RailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private int current = 0;
        void setCurrent(int c) {
            int old = current;
            current = c;
            notifyItemChanged(old);
            notifyItemChanged(c);
        }

        @Override public int getItemCount() { return items.size(); }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int vt) {
            FrameLayout f = new FrameLayout(MediaReviewActivity.this);
            int side = dp(64);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(side, side);
            lp.setMargins(dp(3), 0, dp(3), 0);
            f.setLayoutParams(lp);
            ImageView iv = new ImageView(MediaReviewActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF333333);
            f.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView x = new TextView(MediaReviewActivity.this);
            x.setText("✕");
            x.setTextColor(Color.WHITE);
            x.setTextSize(11);
            x.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0xCC000000);
            x.setBackground(circle);
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(18), dp(18),
                    Gravity.TOP | Gravity.END);
            xl.setMargins(0, dp(2), dp(2), 0);
            f.addView(x, xl);
            return new RecyclerView.ViewHolder(f) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            FrameLayout f = (FrameLayout) h.itemView;
            ImageView iv = (ImageView) f.getChildAt(0);
            View x = f.getChildAt(1);
            MItem it = items.get(position);
            iv.setImageBitmap(it.preview);
            iv.setAlpha(position == current ? 1f : 0.6f);
            f.setBackgroundColor(position == current ? Utils.ACCENT : Color.TRANSPARENT);
            f.setPadding(dp(2), dp(2), dp(2), dp(2));
            f.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos >= 0) pager.setCurrentItem(pos, true);
            });
            x.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos < 0) return;
                items.remove(pos);
                if (items.isEmpty()) { finish(); return; }
                pagerAdapter.notifyDataSetChanged();
                notifyDataSetChanged();
                int show = Math.min(pos, items.size() - 1);
                pager.setCurrentItem(show, false);
                onPageShown(show);
            });
        }
    }

    // -------------------- crop overlay --------------------

    /** Free-form crop rectangle with draggable corners over the fit-centered
     *  preview. Coordinates are mapped back to the preview bitmap. */
    private static class CropOverlayView extends View {
        private final Paint dim = new Paint();
        private final Paint line = new Paint();
        private final Paint handle = new Paint();
        private RectF imageRect = null; // displayed image bounds in view coords
        private RectF rect = null;      // crop rect in view coords
        private int bw, bh;             // preview bitmap dims
        private int dragCorner = -1;    // 0 tl, 1 tr, 2 bl, 3 br, 4 move
        private float lastX, lastY;

        CropOverlayView(android.content.Context c) {
            super(c);
            dim.setColor(0x99000000);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3);
            handle.setColor(Color.WHITE);
        }

        void setImage(int bitmapW, int bitmapH) {
            bw = bitmapW; bh = bitmapH;
            computeRects();
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            computeRects();
        }

        private void computeRects() {
            if (bw <= 0 || bh <= 0 || getWidth() == 0 || getHeight() == 0) return;
            float scale = Math.min(getWidth() / (float) bw, getHeight() / (float) bh);
            float dw = bw * scale, dh = bh * scale;
            float l = (getWidth() - dw) / 2f, t = (getHeight() - dh) / 2f;
            imageRect = new RectF(l, t, l + dw, t + dh);
            rect = new RectF(imageRect);
        }

        /** Crop rect normalized to the displayed image, or null. */
        RectF getNormalizedRect() {
            if (rect == null || imageRect == null) return null;
            return new RectF(
                    (rect.left - imageRect.left) / imageRect.width(),
                    (rect.top - imageRect.top) / imageRect.height(),
                    (rect.right - imageRect.left) / imageRect.width(),
                    (rect.bottom - imageRect.top) / imageRect.height());
        }

        @Override protected void onDraw(Canvas c) {
            if (rect == null) return;
            // dim everything outside the crop rect
            c.drawRect(0, 0, getWidth(), rect.top, dim);
            c.drawRect(0, rect.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, rect.top, rect.left, rect.bottom, dim);
            c.drawRect(rect.right, rect.top, getWidth(), rect.bottom, dim);
            c.drawRect(rect, line);
            float r = 14;
            c.drawCircle(rect.left, rect.top, r, handle);
            c.drawCircle(rect.right, rect.top, r, handle);
            c.drawCircle(rect.left, rect.bottom, r, handle);
            c.drawCircle(rect.right, rect.bottom, r, handle);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (rect == null) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragCorner = hitCorner(x, y);
                    if (dragCorner < 0 && rect.contains(x, y)) dragCorner = 4;
                    lastX = x; lastY = y;
                    return dragCorner >= 0;
                case MotionEvent.ACTION_MOVE:
                    if (dragCorner < 0) return false;
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    float min = 48;
                    if (dragCorner == 4) {
                        float nl = Math.max(imageRect.left,
                                Math.min(rect.left + dx, imageRect.right - rect.width()));
                        float nt = Math.max(imageRect.top,
                                Math.min(rect.top + dy, imageRect.bottom - rect.height()));
                        rect.offsetTo(nl, nt);
                    } else {
                        if (dragCorner == 0 || dragCorner == 2)
                            rect.left = clamp(rect.left + dx, imageRect.left, rect.right - min);
                        else
                            rect.right = clamp(rect.right + dx, rect.left + min, imageRect.right);
                        if (dragCorner == 0 || dragCorner == 1)
                            rect.top = clamp(rect.top + dy, imageRect.top, rect.bottom - min);
                        else
                            rect.bottom = clamp(rect.bottom + dy, rect.top + min, imageRect.bottom);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragCorner = -1;
                    return true;
            }
            return false;
        }

        private int hitCorner(float x, float y) {
            float grab = 56;
            if (Math.hypot(x - rect.left, y - rect.top) < grab) return 0;
            if (Math.hypot(x - rect.right, y - rect.top) < grab) return 1;
            if (Math.hypot(x - rect.left, y - rect.bottom) < grab) return 2;
            if (Math.hypot(x - rect.right, y - rect.bottom) < grab) return 3;
            return -1;
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
