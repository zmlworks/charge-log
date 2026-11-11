package com.zmlworks.chargelog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Locale;

/**
 * Two stacked charts over elapsed time: battery % with the accumulated mAh, and power (W) with current (A).
 * Samples are averaged into about one point per 2 px so long sessions stay cheap to draw.
 */
final class GraphView extends View {
    static final int C_LEVEL = 0xFF00A2E8, C_MAH = 0xFFF2B33D, C_POWER = 0xFFFF6B5B, C_CURRENT = 0xFF7BD88F;

    private Session session;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    GraphView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(Ui.GRID);
        grid.setStrokeWidth(1);
        text.setColor(Ui.DIM);
        text.setTextSize(11 * dp);
    }

    void setSession(Session s) { session = s; invalidate(); }

    interface Series { float at(Session s, int i); }

    @Override protected void onDraw(Canvas c) {
        float l = 44 * dp, r = getWidth() - 44 * dp, top = 22 * dp, gap = 46 * dp, bottom = getHeight() - 22 * dp;
        float ph = (bottom - top - gap) / 2;
        Session s = session;
        if (s == null || s.size() < 2) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(getContext().getString(R.string.no_data), getWidth() / 2f, getHeight() / 2f, text);
            return;
        }
        long dur = Math.max(1, s.elapsedMs());
        // top: % (0-100, left) and accumulated mAh (right)
        float mahMax = nice(maxOf(s, Session::accAt));
        panel(c, s, l, top, r, top + ph, dur, 100, "%", mahMax, "mAh",
                getContext().getString(R.string.legend_level), C_LEVEL, (ss, i) -> ss.samples.get(i).level,
                getContext().getString(R.string.legend_mah), C_MAH, Session::accAt);
        // bottom: W (left) and A (right)
        float wMax = nice(maxOf(s, Session::wAt));
        float aMax = nice(maxOf(s, (ss, i) -> ss.maAt(i) / 1000f));
        panel(c, s, l, top + ph + gap, r, bottom, dur, wMax, "W", aMax, "A",
                getContext().getString(R.string.legend_power), C_POWER, Session::wAt,
                getContext().getString(R.string.legend_current), C_CURRENT, (ss, i) -> ss.maAt(i) / 1000f);
    }

    private void panel(Canvas c, Session s, float l, float t, float r, float b, long dur,
                       float lMax, String lUnit, float rMax, String rUnit,
                       String lName, int lColor, Series ls, String rName, int rColor, Series rs) {
        // grid + axis labels
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
        long stepMin = timeStep(dur);
        text.setTextAlign(Paint.Align.CENTER);
        for (long m = 0; m * 60_000 <= dur; m += stepMin) {
            float x = l + (r - l) * (m * 60_000f / dur);
            c.drawLine(x, t, x, b, grid);
            c.drawText(m < 60 ? m + "分" : String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60), x, b + 14 * dp, text);
        }
        // legend
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(lColor);
        c.drawText(lName + " (" + lUnit + ")", l, t - 7 * dp, text);
        text.setColor(rColor);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(rName + " (" + rUnit + ")", r, t - 7 * dp, text);
        text.setColor(Ui.DIM);
        curve(c, s, l, t, r, b, dur, lMax, ls, lColor, true);
        curve(c, s, l, t, r, b, dur, rMax, rs, rColor, false);
    }

    private void curve(Canvas c, Session s, float l, float t, float r, float b, long dur, float max, Series ser, int color, boolean filled) {
        int n = s.size(), pts = Math.max(2, Math.min(n, (int) ((r - l) / (2 * dp))));
        Path p = new Path();
        float firstX = 0, lastX = 0;
        boolean started = false;
        for (int k = 0; k < pts; k++) {
            int i0 = (int) ((long) n * k / pts), i1 = Math.max(i0 + 1, (int) ((long) n * (k + 1) / pts));
            double sum = 0;
            int cnt = 0;
            for (int i = i0; i < i1; i++) {
                float v = ser.at(s, i);
                if (!Float.isNaN(v)) { sum += v; cnt++; }
            }
            if (cnt == 0) continue;
            float x = l + (r - l) * (s.samples.get((i0 + i1 - 1) / 2).t / (float) dur);
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

    static float maxOf(Session s, Series ser) {
        float m = 0;
        for (int i = 0; i < s.size(); i++) { float v = ser.at(s, i); if (v > m) m = v; }
        return m;
    }

    /** Axis maximum: 1, 2, 2.5 or 5 × 10^k at or above v (at least 1). */
    static float nice(float v) {
        if (!(v > 1)) return 1;
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * e >= v) return (float) (m * e);
        return (float) (10 * e);
    }

    /** Minutes between time grid lines, giving at most 6 lines. */
    static long timeStep(long durMs) {
        for (long m : new long[]{1, 2, 5, 10, 15, 30, 60, 120, 180, 360}) if (durMs / 60_000 / m <= 6) return m;
        return 720;
    }

    private static String fmt(float v) {
        String t = String.format(Locale.ROOT, "%.2f", v);
        return t.replaceAll("\\.?0+$", "");
    }
}
