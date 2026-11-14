package com.zmlworks.chargelog;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Temperature-sensor list at the bottom of the main screen, already grouped and sorted. */
final class SensorView extends LinearLayout {
    SensorView(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    void show(Session s) {
        removeAllViews();
        Context c = getContext();
        int pad = Ui.dp(c, 5);
        List<Thermals.Row> rows = s == null ? java.util.Collections.emptyList() : Thermals.group(s.temps);
        TextView h = heading(c.getString(R.string.sensors_title), pad);
        addView(h);
        if (rows.isEmpty()) {
            TextView empty = value(c.getString(R.string.sensors_none), pad);
            empty.setTextColor(Ui.DIM);
            empty.setTextSize(14);
            addView(empty);
        } else {
            for (Thermals.Row r : rows) addView(row(r.label, Thermals.fmtC(r.milliC) + "  " + Thermals.fmtDelta(r.deltaMilliC), pad));
        }
        Session.TempSample last = s == null ? null : s.lastTemps();
        if (last != null && last.extras != null && last.extras.length > 0) {
            addView(heading(c.getString(R.string.sensors_other), pad));
            for (Thermals.Reading e : last.extras) addView(row(e.name, e.text, pad));
        }
    }

    private TextView heading(String s, int pad) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(GraphView.C_TEMP);
        t.setTextSize(14);
        t.setPadding(pad, pad, pad, pad / 2);
        return t;
    }

    private TextView value(String s, int pad) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Ui.TEXT);
        t.setPadding(pad, pad / 2, pad, pad / 2);
        return t;
    }

    private LinearLayout row(String name, String val, int pad) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(pad, pad / 2, pad, pad / 2);
        TextView n = new TextView(getContext());
        n.setText(name);
        n.setTextColor(Ui.TEXT);
        n.setTextSize(14);
        TextView v = new TextView(getContext());
        v.setText(val);
        v.setTextColor(Ui.TEXT);
        v.setTextSize(14);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(Ui.dp(getContext(), 8));
        r.addView(n, lp);
        r.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }
}
