package com.zmlworks.chargelog;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Renders the screens with a synthetic charging session to PNG (build/reports/screens/). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotsTest {
    static final int W = 1233, H = 2673;

    /** ~95 min fast charge 18 % → 92 %: constant current, then tapering above 80 %. */
    static Session synthetic(long start, int minutes) {
        Session s = new Session(start, 1);
        double level = 18, counter = 900_000;
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i <= minutes * 60; i++) {
            double ma = level < 55 ? 4200 : level < 80 ? 4200 - (level - 55) * 70 : Math.max(400, 2450 - (level - 80) * 170);
            ma += rnd.nextGaussian() * 60;
            int mv = (int) (3700 + level * 6.5);
            level = Math.min(100, level + ma / 3600.0 / 4500 * 100);
            counter += ma / 3.6;
            s.add(new Session.Sample(i * 1000L, (int) level, (int) (ma * 1000), mv, (int) (300 + Math.min(level, 70) * 1.3),
                    (int) counter, Session.STATUS_CHARGING));
        }
        return s;
    }

    @After public void clear() { ChargeService.live = null; ChargeService.running = false; }

    @Test public void screens() throws Exception {
        File dir = new File("build/reports/screens");
        assertTrue(dir.isDirectory() || dir.mkdirs());

        ChargeService.running = true;
        ChargeService.live = synthetic(1_794_398_400_000L, 70);
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        png(a, new File(dir, "main-charging.png"));

        Session done = synthetic(1_794_312_600_000L, 95);
        save(a, done);
        save(a, synthetic(1_794_217_200_000L, 40));
        Activity h = Robolectric.buildActivity(HistoryActivity.class).setup().get();
        Thread.sleep(1500);
        ShadowLooper.idleMainLooper();
        png(h, new File(dir, "history.png"));

        android.content.Intent i = new android.content.Intent(a, SessionActivity.class)
                .putExtra(SessionActivity.EXTRA_FILE, done.start + ".csv");
        Activity d = Robolectric.buildActivity(SessionActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        png(d, new File(dir, "session.png"));
    }

    static void save(Activity a, Session s) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(Store.file(a, s.start)), StandardCharsets.UTF_8)) {
            w.write(s.header());
            for (Session.Sample x : s.samples) w.write(Session.line(x));
        }
    }

    static void png(Activity a, File f) throws Exception {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        try (FileOutputStream o = new FileOutputStream(f)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }
}
