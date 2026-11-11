package com.zmlworks.chargelog;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Numbers of one session in a two-column grid. */
final class SessionView extends GridLayout {
    private static final int N = 16;
    private final TextView[] keys = new TextView[N], values = new TextView[N];

    SessionView(Context c) {
        super(c);
        setColumnCount(2);
        int pad = Ui.dp(c, 5);
        for (int i = 0; i < N; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(pad, pad, pad, pad);
            TextView k = new TextView(c);
            k.setTextColor(Ui.DIM);
            k.setTextSize(12);
            TextView v = new TextView(c);
            v.setTextColor(Ui.TEXT);
            v.setTextSize(17);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(k);
            cell.addView(v);
            keys[i] = k;
            values[i] = v;
            LayoutParams lp = new LayoutParams(spec(i / 2), spec(i % 2, 1f));
            lp.width = 0;
            addView(cell, lp);
        }
    }

    /** @param capacity nominal capacity in mAh, 0 if not set */
    void show(Session s, boolean done, int capacity) {
        Context c = getContext();
        Session.Sample l = s == null ? null : s.last();
        boolean ch = s == null || s.charge;
        int[] k = {R.string.k_state, R.string.k_start, R.string.k_elapsed, R.string.k_count, R.string.k_level,
                ch ? R.string.k_mah : R.string.k_used_mah, ch ? R.string.k_wh : R.string.k_used_wh, R.string.k_avg_w,
                R.string.k_now_w, R.string.k_max_w, R.string.k_now_a, R.string.k_voltage, R.string.k_temp,
                R.string.k_charger, R.string.k_capacity, R.string.k_counter};
        for (int i = 0; i < N; i++) keys[i].setText(k[i]);
        if (l == null) {
            for (TextView v : values) v.setText("—");
            values[0].setText(s == null ? R.string.state_none : ch ? R.string.state_charging : R.string.state_discharging);
            return;
        }
        double cap = s.estCapacity();
        String capText = Double.isNaN(cap) ? c.getString(R.string.capacity_wait)
                : capacity > 0 ? String.format(Locale.ROOT, "%.0f mAh (公称の %.0f%%)", cap, cap * 100 / capacity)
                : String.format(Locale.ROOT, "%.0f mAh", cap);
        String[] v = {
                c.getString(done ? R.string.state_done : ch ? R.string.state_charging : R.string.state_discharging),
                new SimpleDateFormat("M/d HH:mm:ss", Locale.JAPAN).format(new Date(s.start)),
                Session.duration(s.elapsedMs()),
                String.format(Locale.ROOT, "%d 回", s.size()),
                String.format(Locale.ROOT, "%d→%d%% (%+d)", s.levelStart(), l.level, s.levelGain()),
                Session.num(s.shownMah(), "%.1f mAh"),
                Session.num(s.shownWh(), "%.3f Wh"),
                Session.num(s.avgW(), "%.2f W"),
                Session.num(s.shownW(), "%.2f W"),
                Session.num(s.maxW(), "%.2f W"),
                Session.num(s.shownA(), "%.3f A"),
                String.format(Locale.ROOT, "%.3f V", s.mvNow() / 1000.0),
                String.format(Locale.ROOT, "%.1f ℃", l.deciC / 10.0),
                ch ? Session.charger(s.plug, s.chargerUv, s.chargerUa) : c.getString(R.string.charger_none),
                capText,
                Session.num(s.counterMah() * s.dir, "%.1f mAh"),
        };
        for (int i = 0; i < N; i++) values[i].setText(v[i]);
    }
}
