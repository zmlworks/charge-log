package com.zmlworks.chargelog;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings (SharedPreferences "settings"). */
final class Prefs {
    private Prefs() {}

    static final String UNIT = "unit", SIGN = "sign", VOLT = "volt", STORED_UNIT = "stored_unit", STORED_SIGN = "stored_sign",
            INTERVAL = "interval_ms", CAPACITY = "capacity_mah";
    static final int[] INTERVALS = {500, 1000, 2000, 5000, 10000};

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    static Interp interp(Context c) {
        SharedPreferences p = sp(c);
        return new Interp(p.getInt(UNIT, 0), p.getInt(SIGN, 0), p.getInt(VOLT, 0), p.getInt(STORED_UNIT, 0), p.getInt(STORED_SIGN, 0));
    }

    static int intervalMs(Context c) { return Math.max(500, sp(c).getInt(INTERVAL, 1000)); }

    static int capacity(Context c) { return sp(c).getInt(CAPACITY, 0); }
}
