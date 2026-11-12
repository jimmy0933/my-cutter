package com.jimmy.cutter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 自製時間軸：顯示關鍵幀刻度、已保留片段、目前選取範圍與播放位置，可點擊/拖曳跳轉。 */
public class TimelineView extends View {
    public interface OnSeek { void onSeek(int ms, boolean finished); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private long durationUs = 1;
    private long positionUs;
    private long selStart, selEnd;
    private long[] keyframes = new long[0];
    private final List<long[]> segments = new ArrayList<>();
    private OnSeek listener;
    private boolean compact;

    private int colTrack = 0xFF2A2E36, colKey = 0xFF5F6673, colSeg = 0x6642D392,
            colSel = 0x55FF7043, colSelEdge = 0xFFFF7043, colHead = 0xFFFFFFFF;

    public TimelineView(Context c) { super(c); }

    public void setOnSeek(OnSeek l) { listener = l; }

    public void setCompact(boolean c) { if (compact != c) { compact = c; requestLayout(); } }

    public void setData(long durationUs, long[] keyframes) {
        this.durationUs = Math.max(1, durationUs);
        this.keyframes = keyframes == null ? new long[0] : keyframes;
        invalidate();
    }

    public void setPositionMs(int ms) { positionUs = ms * 1000L; invalidate(); }

    public void setSelection(long startUs, long endUs) { selStart = startUs; selEnd = endUs; invalidate(); }

    public void setSegments(List<long[]> segs) { segments.clear(); segments.addAll(segs); invalidate(); }

    private float x(long us, float left, float w) { return left + w * Math.min(1f, Math.max(0f, us / (float) durationUs)); }

    @Override protected void onMeasure(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(resolveSize(0, w), (int) ((compact ? 44 : 64) * d));
    }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 8 * d, left = pad, w = getWidth() - 2 * pad;
        float m = (compact ? 10 : 14) * d;
        float top = m, bottom = getHeight() - m;

        p.setStyle(Paint.Style.FILL);
        p.setColor(colTrack);
        r.set(left, top, left + w, bottom);
        c.drawRoundRect(r, 8 * d, 8 * d, p);

        p.setColor(colSeg);
        for (long[] s : segments) {
            r.set(x(s[0], left, w), top, x(s[1], left, w), bottom);
            c.drawRect(r, p);
        }
        if (selEnd > selStart) {
            p.setColor(colSel);
            r.set(x(selStart, left, w), top, x(selEnd, left, w), bottom);
            c.drawRect(r, p);
            p.setColor(colSelEdge);
            c.drawRect(r.left - 1.5f * d, top - 4 * d, r.left + 1.5f * d, bottom + 4 * d, p);
            c.drawRect(r.right - 1.5f * d, top - 4 * d, r.right + 1.5f * d, bottom + 4 * d, p);
        }

        // 關鍵幀刻度（太密時抽樣，避免糊成一片）
        p.setColor(colKey);
        p.setStrokeWidth(Math.max(1f, d * 0.8f));
        float lastX = -100;
        for (long k : keyframes) {
            float kx = x(k, left, w);
            if (kx - lastX < 3 * d) continue;
            lastX = kx;
            c.drawLine(kx, bottom - 12 * d, kx, bottom - 2 * d, p);
        }

        // 播放頭
        float hx = x(positionUs, left, w);
        p.setColor(colHead);
        c.drawRect(hx - 1.5f * d, top - 8 * d, hx + 1.5f * d, bottom + 8 * d, p);
        c.drawCircle(hx, top - 8 * d, 5 * d, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 8 * d, w = getWidth() - 2 * pad;
        float f = Math.min(1f, Math.max(0f, (e.getX() - pad) / w));
        int ms = (int) (f * durationUs / 1000);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            positionUs = ms * 1000L;
            invalidate();
            if (listener != null) listener.onSeek(ms, a == MotionEvent.ACTION_UP);
        }
        return true;
    }
}
