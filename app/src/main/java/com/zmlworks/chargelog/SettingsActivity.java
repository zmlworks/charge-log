package com.zmlworks.chargelog;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Live raw readings, how they are interpreted, record interval and nominal capacity. */
public class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView raw, stored;
    private LinearLayout col;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateRaw();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.sp(this);
        int pad = Ui.dp(this, 12);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        TextView title = text(getString(R.string.settings_title), 20, Ui.TEXT);
        col.addView(title);

        heading(R.string.s_raw);
        raw = text("", 13, Ui.TEXT);
        raw.setTypeface(Typeface.MONOSPACE);
        col.addView(raw);

        radios(R.string.s_unit, new String[]{"自動", "mA", "µA"}, Prefs.UNIT, null);
        radios(R.string.s_sign, new String[]{"自動", "そのまま", "反転"}, Prefs.SIGN, null);
        radios(R.string.s_volt, new String[]{"自動", "mV", "V", "µV"}, Prefs.VOLT, null);

        heading(R.string.s_stored);
        stored = text("", 14, Ui.TEXT);
        col.addView(stored);
        Button forget = new Button(this);
        forget.setText(R.string.s_forget);
        forget.setOnClickListener(v -> { prefs.edit().remove(Prefs.STORED_UNIT).remove(Prefs.STORED_SIGN).apply(); updateRaw(); });
        col.addView(forget);

        radios(R.string.s_interval, new String[]{"0.5秒", "1秒", "2秒", "5秒", "10秒"}, Prefs.INTERVAL, Prefs.INTERVALS);

        heading(R.string.s_capacity);
        EditText cap = new EditText(this);
        cap.setInputType(InputType.TYPE_CLASS_NUMBER);
        cap.setTextColor(Ui.TEXT);
        cap.setHint(R.string.s_capacity_hint);
        cap.setHintTextColor(Ui.DIM);
        int c = Prefs.capacity(this);
        if (c > 0) cap.setText(String.valueOf(c));
        cap.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int d) {}
            @Override public void afterTextChanged(Editable e) {
                int v;
                try { v = Integer.parseInt(e.toString().trim()); } catch (NumberFormatException x) { v = 0; }
                prefs.edit().putInt(Prefs.CAPACITY, Math.max(0, v)).apply();
            }
        });
        col.addView(cap);
        col.addView(text(getString(R.string.s_note), 12, Ui.DIM));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void heading(int res) {
        TextView t = text(getString(res), 14, GraphView.C_LEVEL);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        col.addView(t);
    }

    /** A row of choices stored as an index, or as values[index] when values is given. */
    private void radios(int title, String[] labels, String key, int[] values) {
        heading(title);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        int cur = prefs.getInt(key, values == null ? 0 : 1000);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Ui.TEXT);
            rb.setId(i + 1);
            g.addView(rb);
            if ((values == null ? i : values[i]) == cur) rb.setChecked(true);
        }
        g.setOnCheckedChangeListener((gr, id) -> {
            int i = id - 1;
            prefs.edit().putInt(key, values == null ? i : values[i]).apply();
            updateRaw();
        });
        col.addView(g);
    }

    private void updateRaw() {
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = getSystemService(BatteryManager.class);
        int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        int cnt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int v = bi == null ? 0 : bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int muv = bi == null ? Session.MISSING : bi.getIntExtra("max_charging_voltage", Session.MISSING);
        int mua = bi == null ? Session.MISSING : bi.getIntExtra("max_charging_current", Session.MISSING);
        Interp in = Prefs.interp(this);
        Session live = ChargeService.live;
        boolean ua = in.fixedUnit() != Interp.AUTO ? in.fixedUnit() == Interp.UNIT_UA
                : live != null ? live.microAmps() : Math.abs((long) cur) >= Session.UA_THRESHOLD;
        boolean inv = in.fixedSign() != Interp.AUTO ? in.fixedSign() == Interp.SIGN_INVERT : live != null && live.inverted();
        String curTxt = cur == Session.MISSING ? "非対応"
                : String.format(Locale.ROOT, "%d → %+.1f mA (%s)", cur, (inv ? -1 : 1) * (ua ? cur / 1000.0 : cur),
                (inv ? -cur : cur) > 0 ? "充電" : "放電");
        raw.setText(String.format(Locale.ROOT, "CURRENT_NOW     %s\nEXTRA_VOLTAGE   %d → %.3f V\nCHARGE_COUNTER  %s\n"
                        + "max_charging_voltage  %s\nmax_charging_current  %s\n→ 電圧種類: %s",
                curTxt, v, in.mv(v) / 1000.0, cnt <= 0 || cnt == Session.MISSING ? "非対応" : cnt + " µAh",
                muv == Session.MISSING ? "なし" : String.valueOf(muv), mua == Session.MISSING ? "なし" : String.valueOf(mua),
                Session.charger(bi == null ? 0 : bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0), muv, mua)));
        String[] units = {"なし", "mA", "µA"}, signs = {"なし", "そのまま", "反転"};
        stored.setText(getString(R.string.s_stored_value, units[Math.min(2, in.storedUnit)], signs[Math.min(2, in.storedSign)]));
    }
}
