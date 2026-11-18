package com.zmlworks.chargelog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.widget.Button;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeServiceTest {

    @Before public void silenceExit() { MainActivity.exitProcessOnQuit = false; }

    @After public void clear() {
        ChargeService.live = null;
        ChargeService.finished = null;
        ChargeService.running = false;
        ChargeService.userQuit = false;
        MainActivity.exitProcessOnQuit = true;
    }

    @Test public void recordsWhileUnpluggedAndHoldsWakeLock() {
        Application app = RuntimeEnvironment.getApplication();
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 80)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                .putExtra(BatteryManager.EXTRA_STATUS, 3)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 300));

        ServiceController<ChargeService> c = Robolectric.buildService(ChargeService.class,
                new Intent(app, ChargeService.class));
        ChargeService svc = c.create().startCommand(0, 1).get();
        assertTrue(ChargeService.running);
        assertNotNull(ChargeService.live);
        assertFalse(ChargeService.live.charge);

        ShadowPowerManager spm = Shadows.shadowOf(app.getSystemService(PowerManager.class));
        assertTrue("discharge must keep a wake lock so sampling does not pause", spm.getLatestWakeLock().isHeld());

        NotificationManager nm = app.getSystemService(NotificationManager.class);
        assertNotNull(Shadows.shadowOf(nm).getNotification(1));

        c.destroy();
    }

    @Test public void quitStopsServiceAndRemovesNotification() {
        Application app = RuntimeEnvironment.getApplication();
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 50)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 1)
                .putExtra(BatteryManager.EXTRA_STATUS, Session.STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 300));

        ServiceController<ChargeService> c = Robolectric.buildService(ChargeService.class,
                new Intent(app, ChargeService.class));
        ChargeService svc = c.create().startCommand(0, 1).get();
        assertTrue(ChargeService.running);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        assertNotNull(Shadows.shadowOf(nm).getNotification(1));

        c.withIntent(new Intent(app, ChargeService.class).setAction(ChargeService.ACTION_STOP)).startCommand(0, 2);
        assertFalse(ChargeService.running);
        assertTrue(ChargeService.userQuit);
        assertTrue(Shadows.shadowOf(svc).isStoppedBySelf());
        org.junit.Assert.assertNull(Shadows.shadowOf(nm).getNotification(1));
    }

    @Test public void closeButtonQuitsActivityAndService() {
        Application app = RuntimeEnvironment.getApplication();
        ChargeService.running = true;
        ChargeService.live = new Session(0, true, 1, Interp.DEFAULT);

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.update();
        Button close = findButton(a, a.getString(R.string.close));
        assertNotNull("閉じる button should be on the main screen", close);
        close.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(a.isFinishing());
        assertTrue(ChargeService.userQuit);
        assertFalse(ChargeService.running);
    }

    @Test public void quitIntentFinishesWithoutStartingANewSession() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                        .setAction(MainActivity.ACTION_QUIT)).setup().get();
        assertTrue(a.isFinishing());
        assertTrue(ChargeService.userQuit);
        assertFalse(ChargeService.running);
    }

    @Test public void notificationActionOpensQuit() {
        Application app = RuntimeEnvironment.getApplication();
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 50)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 1)
                .putExtra(BatteryManager.EXTRA_STATUS, Session.STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 300));
        ServiceController<ChargeService> c = Robolectric.buildService(ChargeService.class,
                new Intent(app, ChargeService.class));
        c.create().startCommand(0, 1).get();
        Notification n = Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification(1);
        assertNotNull(n);
        assertTrue(n.actions != null && n.actions.length == 1);
        assertTrue(n.actions[0].title.toString().contains("閉じる"));
        c.destroy();
    }

    static Button findButton(Activity a, String label) {
        return findButton(a.getWindow().getDecorView(), label);
    }

    static Button findButton(android.view.View v, String label) {
        if (v instanceof Button && label.contentEquals(((Button) v).getText())) return (Button) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findButton(g.getChildAt(i), label);
                if (b != null) return b;
            }
        }
        return null;
    }
}
