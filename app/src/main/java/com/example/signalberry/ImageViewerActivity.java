package com.example.signalberry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class ImageViewerActivity extends AppCompatActivity {

    static final String EXTRA_SOURCES  = "sources";
    static final String EXTRA_POSITION = "position";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ArrayList<String> sources = getIntent().getStringArrayListExtra(EXTRA_SOURCES);
        int startPos = getIntent().getIntExtra(EXTRA_POSITION, 0);
        if (sources == null || sources.isEmpty()) { finish(); return; }

        // Full-screen black layout built in code — no XML needed
        android.widget.RelativeLayout root = new android.widget.RelativeLayout(this);
        root.setBackgroundColor(0xFF000000);

        ViewPager pager = new ViewPager(this);
        pager.setId(View.generateViewId());
        android.widget.RelativeLayout.LayoutParams pagerLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(pager, pagerLp);

        // Page counter top-right
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

        // Close button top-left
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(22);
        close.setPadding(32, 48, 0, 0);
        android.widget.RelativeLayout.LayoutParams closeLp =
                new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        closeLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        root.addView(close, closeLp);
        close.setOnClickListener(v -> finish());

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
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF000000);
            container.addView(iv);

            final String src = sources.get(position);
            new Thread(() -> {
                Bitmap bm = load(src);
                iv.post(() -> { if (bm != null) iv.setImageBitmap(bm); });
            }).start();

            return iv;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        private Bitmap load(String src) {
            try {
                if (src.startsWith("content://")) {
                    InputStream is = ctx.getContentResolver().openInputStream(Uri.parse(src));
                    return is != null ? BitmapFactory.decodeStream(is) : null;
                }
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestMethod("GET");
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                try (InputStream is = c.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) { return null; }
        }
    }
}
