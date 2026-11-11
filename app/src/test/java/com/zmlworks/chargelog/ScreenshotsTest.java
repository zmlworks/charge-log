package com.zmlworks.chargelog;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

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
        }
        return s;
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
        scrollToEnd(a);
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
