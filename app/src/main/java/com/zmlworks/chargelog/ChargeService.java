package com.zmlworks.chargelog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that waits for a charger. While plugged in it samples the battery every second into a
 * {@link Session} (kept in memory for the screen and appended to a file); unplugging ends the session and it waits
 * for the next connection until the user stops it.
 */
public final class ChargeService extends Service {
    static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "charge";
    private static final int NOTIF = 1;
    private static final long PERIOD_MS = 1000;

    /** Read by the activities on the main thread (the service also runs there). */
    static boolean running;
    static Session live;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BatteryManager bm;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private Intent battery;
    private BufferedWriter out;
    private File file;
    private long t0, lastNotif;
    private boolean registered;

    static void start(Context c) { c.startForegroundService(new Intent(c, ChargeService.class)); }

    static void stop(Context c) { c.startService(new Intent(c, ChargeService.class).setAction(ACTION_STOP)); }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) battery = i;
            else battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            check();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sample();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        bm = getSystemService(BatteryManager.class);
        nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF, notification());
        } catch (RuntimeException e) {   // e.g. ForegroundServiceStartNotAllowedException on a system restart
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!registered) {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            battery = registerReceiver(receiver, f, Build.VERSION.SDK_INT >= 33 ? RECEIVER_NOT_EXPORTED : 0);
            registered = true;
        }
        running = true;
        check();
        return START_STICKY;
    }

    private int plugged() { return battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0); }

    private void check() {
        if (!running) return;
        int p = plugged();
        if (p != 0 && live == null) begin(p);
        else if (p == 0 && live != null) end();
    }

    private void begin(int plug) {
        live = new Session(System.currentTimeMillis(), plug);
        t0 = SystemClock.elapsedRealtime();
        file = Store.file(this, live.start);
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            out.write(live.header());
        } catch (IOException e) {
            out = null;
        }
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargelog:session");
        wake.setReferenceCounted(false);
        wake.acquire(24 * 3600_000L);   // only while plugged in; released on unplug
        lastNotif = 0;
        handler.post(tick);
    }

    private void sample() {
        if (live == null || battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        Session.Sample s = new Session.Sample(SystemClock.elapsedRealtime() - t0,
                scale > 0 ? Math.round(level * 100f / scale) : level,
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0));
        live.add(s);
        if (out != null) {
            try {
                out.write(Session.line(s));
                out.flush();
            } catch (IOException e) {
                out = null;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotif >= 5000) { lastNotif = now; notifyNow(); }
    }

    private void end() {
        handler.removeCallbacks(tick);
        if (out != null) {
            try { out.close(); } catch (IOException ignored) { /* nothing to save */ }
            out = null;
        }
        if (live != null && live.size() < 3 && file != null && !file.delete()) file.deleteOnExit();
        live = null;
        if (wake != null && wake.isHeld()) wake.release();
        notifyNow();
    }

    private void shutdown() {
        if (live != null) end();
        if (registered) { unregisterReceiver(receiver); registered = false; }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void notifyNow() {
        if (running) nm.notify(NOTIF, notification());
    }

    private Notification notification() {
        Session s = live;
        String title, text;
        if (s == null) {
            title = getString(R.string.notif_waiting);
            text = getString(R.string.notif_waiting_text);
        } else {
            Session.Sample l = s.last();
            title = getString(R.string.notif_charging, l == null ? 0 : l.level, s.levelGain());
            text = getString(R.string.notif_charging_text, Session.duration(s.elapsedMs()), Session.num(s.mah(), "%.0f"),
                    Session.num(s.currentW(), "%.1f"));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ChargeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
