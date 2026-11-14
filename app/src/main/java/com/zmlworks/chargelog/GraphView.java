package com.zmlworks.chargelog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Locale;

/**
 * Two stacked charts over elapsed time: battery % with the accumulated mAh, and power (W) with current (A).
 * Shows the whole session by default; pinch to zoom, drag to scroll, double tap to fit again. While the right edge
 * is at the latest sample the view follows new samples.
 */
final class GraphView extends View {
    static final int C_LEVEL = 0xFF4FB3FF, C_MAH = 0xFFF2B33D, C_POWER = 0xFFFF6B5B, C_CURRENT = 0xFF7BD88F,
            C_TEMP = 0xFFD28BFF;

    private Session session;
    private float zoom = 1;      // 1 = whole session
    private long offset;         // ms between the right edge and the latest sample (0 = follow)
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float l, r;          // plot x range of the last draw
    private boolean moved;       // this touch scrolled or zoomed (not a click)

    interface Series { float at(Session s, int i); }

    GraphView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(Ui.GRID);
        grid.setStrokeWidth(1);
        text.setTextSize(11 * dp);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                long dur = duration();
                if (dur <= 0 || r <= l) return true;
                float fx = Math.max(l, Math.min(r, d.getFocusX()));
                double tf = startT(dur) + (fx - l) / (r - l) * span(dur);
                zoom = Math.max(1, Math.min(maxZoom(dur), zoom * d.getScaleFactor()));
                double end = tf + (r - fx) / (r - l) * span(dur);
                offset = (long) (dur - end);
                clamp(dur);
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                long dur = duration();
                moved = true;
                if (r > l) offset += (long) (dx / (r - l) * span(dur));
                clamp(dur);
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { fit(); return true; }
        });
    }

    void setSession(Session s) {
        if (s != session) { zoom = 1; offset = 0; }
        session = s;
        invalidate();
    }

    void fit() { zoom = 1; offset = 0; invalidate(); }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) moved = false;
        scaler.onTouchEvent(e);
        if (scaler.isInProgress()) moved = true; else gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved) performClick();
        return true;
    }

    private long duration() { return session == null ? 0 : Math.max(1, session.elapsedMs()); }
    private double span(long dur) { return dur / zoom; }
    private double startT(long dur) { return dur - offset - span(dur); }
    private static float maxZoom(long dur) { return Math.max(1, dur / 30_000f); }   // at most 30 s across

    private void clamp(long dur) { offset = Math.max(0, Math.min(offset, (long) (dur - span(dur)))); }

    @Override protected void onDraw(Canvas c) {
        l = 44 * dp;
        r = getWidth() - 44 * dp;
        float top = 22 * dp, gap = 42 * dp, bottom = getHeight() - 20 * dp;
        float ph = (bottom - top - gap) / 2;
        Session s = session;
        if (s == null || s.size() < 2) {
            text.setColor(Ui.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(getContext().getString(R.string.no_data), getWidth() / 2f, getHeight() / 2f, text);
            return;
        }
        long dur = duration();
        clamp(dur);
        double t0 = startT(dur), sp = span(dur);
        Context ctx = getContext();
        float mahMax = nice(maxOf(s, Session::shownAccAt));
        panel(c, s, top, top + ph, t0, sp, 100, "%", mahMax, "mAh",
                ctx.getString(R.string.legend_level), C_LEVEL, (ss, i) -> ss.samples.get(i).level,
                ctx.getString(s.charge ? R.string.legend_mah : R.string.legend_used), C_MAH, Session::shownAccAt);
        // Highest temperature across sensors is a slow session-long trend, like level / mAh,
        // so it is overlaid here (own scale, range in the centre legend) rather than on the
        // noisier power / current panel where a nearly-flat line would be hard to read.
        float[] tr = tempRange(s);
        if (tr != null) tempCurve(c, s, top, top + ph, t0, sp, tr[0], tr[1], ctx);
        float wMax = nice(maxOf(s, Session::shownWAt));
        float aMax = nice(maxOf(s, Session::shownAAt));
        panel(c, s, top + ph + gap, bottom, t0, sp, wMax, "W", aMax, "A",
                ctx.getString(s.charge ? R.string.legend_power : R.string.legend_dpower), C_POWER, Session::shownWAt,
                ctx.getString(R.string.legend_current), C_CURRENT, Session::shownAAt);
        if (zoom > 1.01f) {
            text.setColor(Ui.DIM);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.format(Locale.ROOT, "×%.1f  ", zoom) + ctx.getString(R.string.zoom_hint), getWidth() / 2f, top - 7 * dp, text);
        }
    }

    private void panel(Canvas c, Session s, float t, float b, double t0, double sp,
                       float lMax, String lUnit, float rMax, String rUnit,
                       String lName, int lColor, Series ls, String rName, int rColor, Series rs) {
        for (int k = 0; k <= 4; k++) {
            float y = b - (b - t) * k / 4;
            c.drawLine(l, y, r, y, grid);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(lColor);
            c.drawText(fmt(lMax * k / 4), l - 4 * dp, y + 4 * dp, text);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(rColor);
            c.drawText(fmt(rMax * k / 4), r + 4 * dp, y + 4 * dp, text);
        }
        text.setColor(Ui.DIM);
        text.setTextAlign(Paint.Align.CENTER);
        long step = timeStepMs((long) sp);
        for (long m = (long) Math.ceil(t0 / step) * step; m <= t0 + sp; m += step) {
            float x = (float) (l + (r - l) * ((m - t0) / sp));
            c.drawLine(x, t, x, b, grid);
            c.drawText(timeLabel(m, step), x, b + 14 * dp, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(lColor);
        c.drawText(lName + " (" + lUnit + ")", l, t - 7 * dp, text);
        text.setColor(rColor);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(rName + " (" + rUnit + ")", r, t - 7 * dp, text);
        c.save();
        c.clipRect(l, t - 2 * dp, r, b + 2 * dp);
        curve(c, s, t, b, t0, sp, lMax, ls, lColor, true);
        curve(c, s, t, b, t0, sp, rMax, rs, rColor, false);
        c.restore();
    }

    private void curve(Canvas c, Session s, float t, float b, double t0, double sp, float max, Series ser, int color, boolean filled) {
        int lo = Math.max(0, firstAtOrAfter(s, t0) - 1), hi = Math.min(s.size(), firstAtOrAfter(s, t0 + sp) + 1);
        int n = hi - lo;
        if (n < 1) return;
        int pts = Math.max(1, Math.min(n, (int) ((r - l) / (2 * dp))));
        Path p = new Path();
        float firstX = 0, lastX = 0;
        boolean started = false;
        for (int k = 0; k < pts; k++) {
            int i0 = lo + (int) ((long) n * k / pts), i1 = Math.max(i0 + 1, lo + (int) ((long) n * (k + 1) / pts));
            double sum = 0;
            int cnt = 0;
            for (int i = i0; i < i1; i++) {
                float v = ser.at(s, i);
                if (!Float.isNaN(v)) { sum += v; cnt++; }
            }
            if (cnt == 0) continue;
            float x = (float) (l + (r - l) * ((s.samples.get((i0 + i1 - 1) / 2).t - t0) / sp));
            float v = (float) Math.max(0, Math.min(max, sum / cnt));
            float y = b - (b - t) * v / max;
            if (!started) { p.moveTo(x, y); firstX = x; started = true; } else p.lineTo(x, y);
            lastX = x;
        }
        if (!started) return;
        if (filled) {
            Path f = new Path(p);
            f.lineTo(lastX, b);
            f.lineTo(firstX, b);
            f.close();
            fill.setColor((color & 0x00FFFFFF) | 0x30000000);
            c.drawPath(f, fill);
        }
        line.setColor(color);
        c.drawPath(p, line);
    }

    private static int firstAtOrAfter(Session s, double t) {
        int lo = 0, hi = s.size();
        while (lo < hi) { int m = (lo + hi) >>> 1; if (s.samples.get(m).t < t) lo = m + 1; else hi = m; }
        return lo;
    }

    private void tempCurve(Canvas c, Session s, float t, float b, double t0, double sp, float lo, float hi, Context ctx) {
        float range = hi - lo;
        if (range <= 0) return;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(C_TEMP);
        String label = ctx.getString(R.string.legend_max_temp) + String.format(java.util.Locale.ROOT, "  %.0f–%.0f℃", lo, hi);
        c.drawText(label, (l + r) / 2f, t - 7 * dp, text);
        int n = s.tempSize();
        if (n < 1) return;
        // Same point budget as the other series: one vertex per ~2 dp, averaged in buckets.
        int loI = 0, hiI = n;
        while (loI < n && s.tempT(loI) < t0) loI++;
        loI = Math.max(0, loI - 1);
        while (hiI > loI && s.tempT(hiI - 1) > t0 + sp) hiI--;
        hiI = Math.min(n, hiI + 1);
        int count = hiI - loI;
        if (count < 1) return;
        int pts = Math.max(1, Math.min(count, (int) ((r - l) / (2 * dp))));
        Path p = new Path();
        boolean started = false;
        c.save();
        c.clipRect(l, t - 2 * dp, r, b + 2 * dp);
        for (int k = 0; k < pts; k++) {
            int i0 = loI + (int) ((long) count * k / pts), i1 = Math.max(i0 + 1, loI + (int) ((long) count * (k + 1) / pts));
            double sum = 0;
            int cnt = 0;
            long midT = 0;
            for (int i = i0; i < i1; i++) {
                int m = s.maxMilliCAt(i);
                if (m == Session.MISSING) continue;
                sum += m / 1000.0;
                midT += s.tempT(i);
                cnt++;
            }
            if (cnt == 0) continue;
            float x = (float) (l + (r - l) * ((midT / (double) cnt - t0) / sp));
            float v = (float) Math.max(lo, Math.min(hi, sum / cnt));
            float y = b - (b - t) * (v - lo) / range;
            if (!started) { p.moveTo(x, y); started = true; } else p.lineTo(x, y);
        }
        if (started) {
            line.setColor(C_TEMP);
            c.drawPath(p, line);
        }
        c.restore();
    }

    /** Vertical range for the max-temperature overlay: tight around the data, at least 5 ℃. */
    static float[] tempRange(Session s) {
        if (s == null) return null;
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < s.tempSize(); i++) {
            int m = s.maxMilliCAt(i);
            if (m == Session.MISSING) continue;
            float v = m / 1000f;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        if (!(hi >= lo) || lo == Float.POSITIVE_INFINITY) return null;
        return niceRange(lo, hi);
    }

    /** Padded range with half-degree ends and a span of at least 5 ℃. */
    static float[] niceRange(float lo, float hi) {
        if (hi - lo < 5f) {
            float mid = (lo + hi) / 2f;
            lo = mid - 2.5f;
            hi = mid + 2.5f;
        }
        float pad = Math.max(0.5f, (hi - lo) * 0.1f);
        lo -= pad;
        hi += pad;
        lo = (float) (Math.floor(lo * 2) / 2);
        hi = (float) (Math.ceil(hi * 2) / 2);
        if (hi - lo < 5f) hi = lo + 5f;
        return new float[]{lo, hi};
    }

    static float maxOf(Session s, Series ser) {
        float m = 0;
        for (int i = 0; i < s.size(); i++) { float v = ser.at(s, i); if (v > m) m = v; }
        return m;
    }

    /** Axis maximum: 1, 2, 2.5 or 5 × 10^k at or above v (at least 0.1). */
    static float nice(float v) {
        if (!(v > 0.1f)) return 0.1f;
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * e >= v * 0.9999) return (float) (m * e);
        return (float) (10 * e);
    }

    /** Spacing of the time grid, giving at most 6 lines across the visible span. */
    static long timeStepMs(long spanMs) {
        for (long s : new long[]{5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600})
            if (spanMs / (s * 1000) <= 6) return s * 1000;
        return 43_200_000;
    }

    static String timeLabel(long ms, long step) {
        long s = ms / 1000;
        if (step < 60_000) return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        long m = s / 60;
        return m < 60 ? m + "分" : String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60);
    }

    private static String fmt(float v) {
        String t = String.format(Locale.ROOT, "%.2f", v);
        return t.replaceAll("\\.?0+$", "");
    }
}
