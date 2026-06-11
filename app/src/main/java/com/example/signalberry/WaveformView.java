package com.example.signalberry;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Voice-note waveform: amplitude bars with a played/unplayed split, doubling
 * as the seek surface (drag or tap when seekable). Cheap enough for a Q10 —
 * one drawRect per bar, no allocation in onDraw.
 */
public class WaveformView extends View {

    interface OnSeekListener { void onSeek(float fraction); }

    private float[] levels;
    private float progress;
    private boolean seekable;
    private OnSeekListener seekListener;
    private int playedColor = Utils.ACCENT;
    private int restColor   = 0x66888888;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context c) { super(c); }
    public WaveformView(Context c, AttributeSet a) { super(c, a); }

    void setLevels(float[] l)              { levels = l; invalidate(); }
    void setProgress(float p)              { progress = p; invalidate(); }
    void setSeekable(boolean s)            { seekable = s; }
    void setOnSeekListener(OnSeekListener l) { seekListener = l; }
    void setColors(int played, int rest)   { playedColor = played; restColor = rest; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int n = levels != null ? levels.length : 24;
        float w = getWidth(), h = getHeight();
        if (n <= 0 || w <= 0) return;
        float slot = w / n;
        float bw = Math.max(2f, slot * 0.55f);
        for (int i = 0; i < n; i++) {
            float lv = levels != null ? levels[i]
                    : 0.3f + 0.2f * (float) Math.sin(i * 1.7);  // idle placeholder
            float bh = Math.max(h * 0.12f, h * lv);
            float top = (h - bh) / 2f;
            float x = i * slot + (slot - bw) / 2f;
            paint.setColor((i + 0.5f) / n <= progress ? playedColor : restColor);
            canvas.drawRect(x, top, x + bw, top + bh, paint);
        }
    }

    @SuppressWarnings("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (!seekable) return false;
        float f = Math.max(0f, Math.min(1f, ev.getX() / Math.max(1f, getWidth())));
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                setProgress(f);
                return true;
            case MotionEvent.ACTION_MOVE:
                setProgress(f);
                return true;
            case MotionEvent.ACTION_UP:
                setProgress(f);
                if (seekListener != null) seekListener.onSeek(f);
                performClick();
                return true;
        }
        return super.onTouchEvent(ev);
    }
}
