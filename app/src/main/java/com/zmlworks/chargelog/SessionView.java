package com.zmlworks.chargelog;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Numbers of one session in a three-column grid, with the graph below. */
final class SessionView extends LinearLayout {
    private static final int[] LABELS = {R.string.k_start, R.string.k_elapsed, R.string.k_level, R.string.k_mah,
            R.string.k_wh, R.string.k_avg_w, R.string.k_now_w, R.string.k_now_a, R.string.k_voltage, R.string.k_temp,
            R.string.k_plug, R.string.k_counter};
    private final TextView[] values = new TextView[LABELS.length];
    final GraphView graph;

    SessionView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        GridLayout g = new GridLayout(c);
        g.setColumnCount(3);
        int pad = Ui.dp(c, 5);
        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(VERTICAL);
            cell.setPadding(pad, pad, pad, pad);
            TextView k = new TextView(c);
            k.setText(LABELS[i]);
            k.setTextColor(Ui.DIM);
            k.setTextSize(12);
            TextView v = new TextView(c);
            v.setTextColor(Ui.TEXT);
            v.setTextSize(17);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(k);
            cell.addView(v);
            values[i] = v;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
            lp.width = 0;
            g.addView(cell, lp);
        }
        addView(g, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        graph = new GraphView(c);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 400));
        lp.topMargin = Ui.dp(c, 8);
        addView(graph, lp);
    }

    void show(Session s) {
        graph.setSession(s);
        Session.Sample l = s == null ? null : s.last();
        if (l == null) {
            for (TextView v : values) v.setText("—");
            return;
        }
        String[] v = {
                new SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(new Date(s.start)),
                Session.duration(s.elapsedMs()),
                String.format(Locale.ROOT, "%d→%d%%", s.levelStart(), l.level, s.levelGain()),
                Session.num(s.mah(), "%.0f mAh"),
                Session.num(s.wh(), "%.2f Wh"),
                Session.num(s.avgW(), "%.1f W"),
                Session.num(s.currentW(), "%.1f W"),
                Session.num(s.currentMa(), "%.0f mA"),
                String.format(Locale.ROOT, "%.2f V", l.mv / 1000.0),
                String.format(Locale.ROOT, "%.1f ℃", l.deciC / 10.0),
                Session.plugName(s.plug),
                Session.num(s.counterMah(), "%.0f mAh"),
        };
        for (int i = 0; i < v.length; i++) values[i].setText(v[i]);
    }
}
