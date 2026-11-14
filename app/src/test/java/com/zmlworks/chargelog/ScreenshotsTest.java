package com.zmlworks.chargelog;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Renders the screens with SYNTHETIC sessions to PNG (build/reports/screens/), stamped "サンプルデータ". */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotsTest {
    static final int W = 1233, H = 2673;
    static final long T0 = 1_794_398_400_000L;

    /** Fast charge 18 % → ~90 %: constant current, then tapering above 55 %. */
    static Session charge(int minutes) {
        Session s = new Session(T0, true, 1, Interp.DEFAULT);
        s.chargerUv = 9_000_000;
        s.chargerUa = 2_000_000;
        double level = 18, counter = 900_000;
        Random rnd = new Random(1);
        for (int i = 0; i <= minutes * 60; i++) {
            double ma = level < 55 ? 4200 : level < 80 ? 4200 - (level - 55) * 70 : Math.max(400, 2450 - (level - 80) * 170);
            ma += rnd.nextGaussian() * 60;
            level = Math.min(100, level + ma / 3600.0 / 4500 * 100);
            counter += ma / 3.6;
            s.add(new Session.Sample(i * 1000L, (int) level, (int) (ma * 1000), (int) (3700 + level * 6.5),
                    (int) (300 + Math.min(level, 70) * 1.3), (int) counter, Session.STATUS_CHARGING, true));
            if (i % 5 == 0) addChargeTemps(s, i * 1000L, (int) (300 + Math.min(level, 70) * 1.3));
        }
        return s;
    }

    /** Synthetic thermal-zone history for the screenshots. cpu-* always match and rise the most. */
    static void addChargeTemps(Session s, long t, int batteryDeciC) {
        // battery follows the existing EXTRA_TEMPERATURE curve (~30 → 39 ℃)
        int bat = batteryDeciC * 100;
        // 0→1 over the session, derived from elapsed time (70 min = 4200 s)
        float p = Math.min(1f, t / 4_200_000f);
        int cpu = 34000 + Math.round(11000 * p);       // 34 → 45, always the hottest (smooth max-temp line)
        int tsens = 31000 + Math.round(10000 * p);      // 31 → 41
        int xo = 30000 + Math.round(3000 * p);          // 30 → 33
        int skin = 27000 + Math.round(4000 * p);        // 27 → 31
        int quiet = 29000;                              // unchanged
        s.addTemps(t, new Thermals.Reading[]{
                new Thermals.Reading("battery", "バッテリー", bat),
                new Thermals.Reading("tz:thermal_zone0", "cpu-0", cpu),
                new Thermals.Reading("tz:thermal_zone1", "cpu-1", cpu),
                new Thermals.Reading("tz:thermal_zone2", "cpu-2", cpu),
                new Thermals.Reading("tz:thermal_zone3", "tsens_tz_sensor0", tsens),
                new Thermals.Reading("tz:thermal_zone4", "xo-therm", xo),
                new Thermals.Reading("tz:thermal_zone5", "skin", skin),
                new Thermals.Reading("tz:thermal_zone6", "quiet-therm", quiet),
                new Thermals.Reading("pm.status", "熱状態", "問題なし"),
                new Thermals.Reading("pm.headroom", "熱ヘッドルーム", "0.85"),
        });
    }

    static void addDischargeTemps(Session s, long t, int batteryDeciC) {
        float p = Math.min(1f, t / 2_700_000f);
        int bat = batteryDeciC * 100;
        int cpu = 36000 - Math.round(3000 * p);
        int skin = 30000 - Math.round(1000 * p);
        s.addTemps(t, new Thermals.Reading[]{
                new Thermals.Reading("battery", "バッテリー", bat),
                new Thermals.Reading("tz:thermal_zone0", "cpu-0", cpu),
                new Thermals.Reading("tz:thermal_zone1", "cpu-1", cpu),
                new Thermals.Reading("tz:thermal_zone5", "skin", skin),
                new Thermals.Reading("pm.status", "熱状態", "問題なし"),
        });
    }

    /** Discharge 92 % → ~80 % with bursts of heavy use. */
    static Session discharge(int minutes) {
        Session s = new Session(T0, false, 0, Interp.DEFAULT);
        double level = 92, counter = 4_100_000;
        Random rnd = new Random(2);
        for (int i = 0; i <= minutes * 60; i++) {
            double ma = 380 + (i / 300 % 3 == 1 ? 900 : 0) + rnd.nextGaussian() * 80;
            level -= ma / 3600.0 / 4500 * 100;
            counter -= ma / 3.6;
            s.add(new Session.Sample(i * 1000L, (int) Math.ceil(level), (int) (-ma * 1000), (int) (3750 + level * 4.5),
                    330, (int) counter, 3, false));
            if (i % 5 == 0) addDischargeTemps(s, i * 1000L, 330);
        }
        return s;
    }

    @After public void clear() { ChargeService.live = null; ChargeService.finished = null; ChargeService.running = false; }

    @Test public void screens() throws Exception {
        File dir = new File("build/reports/screens");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        ChargeService.running = true;

        ChargeService.live = charge(70);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.getSharedPreferences("settings", 0).edit().putInt(Prefs.CAPACITY, 5000).commit();
        a.update();
        png(a, new File(dir, "main-charging.png"));
        scrollToChildBottom(a, SessionView.class);
        png(a, new File(dir, "main-charging-numbers.png"));
        scrollToStart(a);

        ChargeService.live = discharge(45);
        a.update();
        png(a, new File(dir, "main-discharging.png"));

        a.getSharedPreferences("settings", 0).edit().putInt(Prefs.STORED_UNIT, Interp.UNIT_UA)
                .putInt(Prefs.STORED_SIGN, Interp.SIGN_AS_IS).commit();
        // sample raw readings for the settings screen
        android.os.BatteryManager bm = a.getSystemService(android.os.BatteryManager.class);
        org.robolectric.Shadows.shadowOf(bm).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -341_000);
        org.robolectric.Shadows.shadowOf(bm).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 3_652_000);
        a.sendStickyBroadcast(new android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
                .putExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 4112).putExtra(android.os.BatteryManager.EXTRA_LEVEL, 81)
                .putExtra(android.os.BatteryManager.EXTRA_SCALE, 100).putExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0));
        Activity st = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        png(st, new File(dir, "settings.png"));

        // scroll the charging screen to the sensor list
        ChargeService.live = charge(70);
        a.update();
        scrollToEnd(a);
        png(a, new File(dir, "main-charging-temps.png"));

        iconPreview(a, new File(dir, "notif-icon.png"));
        iconIsSilhouette(a);
    }

    /** Render the status-bar icon on dark and light, and a mock notification row. */
    static void iconPreview(Activity a, File f) throws Exception {
        Drawable d = a.getDrawable(R.drawable.ic_stat);
        int cell = 192, pad = 24;
        Bitmap bmp = Bitmap.createBitmap(cell * 2 + pad * 3, cell + 160, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF303030);
        d.setBounds(pad, pad, pad + cell, pad + cell);
        d.draw(c);
        c.drawColor(0, android.graphics.PorterDuff.Mode.SRC_OVER); // no-op keep
        // light cell
        Paint bg = new Paint();
        bg.setColor(0xFFF2F2F2);
        c.drawRect(pad * 2 + cell, pad, pad * 2 + cell * 2, pad + cell, bg);
        // On a light notification shade Android tints the alpha-only icon dark.
        d.setColorFilter(0xFF202124, android.graphics.PorterDuff.Mode.SRC_IN);
        d.setBounds(pad * 2 + cell, pad, pad * 2 + cell * 2, pad + cell);
        d.draw(c);
        d.setColorFilter(null);
        // mock notification on dark grey
        int ny = pad + cell + 20;
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(0xFF2A2C30);
        c.drawRect(0, ny, bmp.getWidth(), bmp.getHeight(), bar);
        d.setBounds(28, ny + 28, 28 + 72, ny + 28 + 72);
        d.draw(c);
        Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        tx.setColor(0xFFE4E5E7);
        tx.setTextSize(36);
        c.drawText("充電中 91% (+73)", 28 + 72 + 24, ny + 56, tx);
        tx.setColor(0xFF9AA0A6);
        tx.setTextSize(28);
        c.drawText("1:10:00 ・ 3307.7 mAh ・ 2.27 W", 28 + 72 + 24, ny + 96, tx);
        try (FileOutputStream o = new FileOutputStream(f)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** The small icon must be a white-on-transparent hollow battery with a graph, not a solid brick. */
    static void iconIsSilhouette(Activity a) {
        Drawable d = a.getDrawable(R.drawable.ic_stat);
        int n = 96;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.TRANSPARENT);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        int opaque = 0, trans = 0, coloured = 0, innerTrans = 0, innerOpaque = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int p = bmp.getPixel(x, y);
            int al = Color.alpha(p);
            if (al < 16) trans++;
            else {
                opaque++;
                int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                if (Math.abs(r - g) > 8 || Math.abs(g - b) > 8) coloured++;
            }
            // interior of the battery body, where a solid brick would be fully opaque
            if (x > n * 0.35 && x < n * 0.65 && y > n * 0.35 && y < n * 0.80) {
                if (al < 16) innerTrans++;
                else if (al > 200) innerOpaque++;
            }
        }
        assertTrue("icon has some filled pixels", opaque > 100);
        assertTrue("icon has a transparent background", trans > 1000);
        assertEquals("icon must be white (alpha-only), no colour", 0, coloured);
        assertTrue("body is hollow (not a solid brick)", innerTrans > 50);
        assertTrue("line graph is visible inside the body", innerOpaque > 30);
        assertNotEquals(n * n, opaque);
    }

    static android.widget.ScrollView scroll(View v) {
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                android.widget.ScrollView s = scroll(((android.view.ViewGroup) v).getChildAt(i));
                if (s != null) return s;
            }
        return null;
    }

    static void scrollToEnd(Activity a) throws Exception {
        png(a, null);   // lay out first
        scroll(a.getWindow().getDecorView()).scrollTo(0, 100_000);
    }

    /** Scroll so {@code type}'s bottom sits on the ScrollView's bottom (the numbers grid, not the sensor list). */
    static void scrollToChildBottom(Activity a, Class<? extends View> type) throws Exception {
        png(a, null);
        android.widget.ScrollView sv = scroll(a.getWindow().getDecorView());
        View child = find(sv, type);
        int y = child == null ? 0 : child.getBottom() - sv.getHeight();
        sv.scrollTo(0, Math.max(0, y));
    }

    static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
                T f = find(((android.view.ViewGroup) v).getChildAt(i), type);
                if (f != null) return f;
            }
        return null;
    }

    static void scrollToStart(Activity a) { scroll(a.getWindow().getDecorView()).scrollTo(0, 0); }

    static void png(Activity a, File f) throws Exception {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        if (f == null) return;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        root.draw(c);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xCCFF5050);
        p.setTextSize(40);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("サンプルデータ (架空・JVM描画)", W - 30, H - 30, p);
        try (FileOutputStream o = new FileOutputStream(f)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }
}
