package com.zmlworks.chargelog;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The max-sensor temperature series is drawn on the lower (power) panel, not the level / mAh panel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GraphViewTest {

    @Test public void maxTempSitsOnPowerPanel() {
        Session s = new Session(0, true, 1, Interp.DEFAULT);
        for (int i = 0; i <= 120; i++) {
            // steadily rising current so the power curve is not flat, and a rising max temp
            s.add(new Session.Sample(i * 1000L, 40 + i / 10, 1_000_000 + i * 2000, 4000, 300, Session.MISSING,
                    Session.STATUS_CHARGING, true));
            int max = 30_000 + i * 100;   // 30 → 42 ℃
            s.addTemps(i * 1000L, new Thermals.Reading[]{
                    new Thermals.Reading("battery", "バッテリー", 28_000),
                    new Thermals.Reading("tz:cpu", "cpu-0", max),
            });
        }
        float[] range = GraphView.tempRange(s);
        assertNotNull(range);
        assertTrue(range[1] - range[0] >= 5f);

        Context ctx = RuntimeEnvironment.getApplication();
        GraphView g = new GraphView(ctx);
        g.setSession(s);
        int w = 1080, h = 720;
        g.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        g.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        g.draw(new Canvas(bmp));

        // Same geometry as GraphView.onDraw: upper panel then 42 dp gap then lower (power) panel.
        float dp = ctx.getResources().getDisplayMetrics().density;
        float top = 22 * dp, gap = 42 * dp, bottom = h - 20 * dp;
        float ph = (bottom - top - gap) / 2;
        int upperBottom = Math.round(top + ph);
        int lowerTop = Math.round(top + ph + gap);

        int upper = 0, lower = 0, midGap = 0;
        for (int y = 0; y < h; y++) {
            int row = 0;
            for (int x = 0; x < w; x++) if (isPurple(bmp.getPixel(x, y))) row++;
            if (row == 0) continue;
            if (y <= upperBottom) upper += row;
            else if (y >= lowerTop) lower += row;
            else midGap += row;
        }
        assertTrue("expected a purple max-temp stroke, upper=" + upper + " lower=" + lower + " gap=" + midGap,
                lower > 50);
        assertTrue("max-temp should not sit on the level graph, upper=" + upper + " lower=" + lower,
                lower > upper * 4);
    }

    /** Close to GraphView.C_TEMP (0xFFD28BFF), allowing for anti-alias blends. */
    static boolean isPurple(int p) {
        if (Color.alpha(p) < 80) return false;
        int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
        return r > 150 && b > 150 && g < 190 && r - g > 25 && b - g > 25;
    }
}
