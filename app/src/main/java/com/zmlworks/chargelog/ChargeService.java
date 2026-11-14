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
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Foreground service that records the battery in memory. Unplugged it records a discharge session; plugging in
 * throws that away and starts a charge session; unplugging keeps the finished charge as {@link #finished} and starts a
 * new discharge session. Nothing is written to storage.
 */
public final class ChargeService extends Service {
    static final String ACTION_STOP = "stop", ACTION_RESET = "reset";
    private static final String CHANNEL = "charge";
    private static final int NOTIF = 1;

    /** Read by the activities on the main thread (the service also runs there). */
    static boolean running;
    static Session live, finished;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BatteryManager bm;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private SharedPreferences prefs;
    private Interp interp = Interp.DEFAULT;
    private Intent battery;
    private long t0, lastNotif;
    private boolean registered;
    private final Thermals.Probe probe = new Thermals.Probe();

    static void start(Context c) { c.startForegroundService(new Intent(c, ChargeService.class)); }

    static void send(Context c, String action) { c.startService(new Intent(c, ChargeService.class).setAction(action)); }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) battery = i;
            else battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            check();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, k) -> {
        interp = Prefs.interp(this);
        if (live != null) live.setInterp(interp);
        if (finished != null) finished.setInterp(interp);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sample();
            handler.postDelayed(this, Prefs.intervalMs(ChargeService.this));
        }
    };

    private final Runnable tempTick = new Runnable() {
        @Override public void run() {
            sampleTemps();
            handler.postDelayed(this, Prefs.tempIntervalMs(ChargeService.this));
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        bm = getSystemService(BatteryManager.class);
        nm = getSystemService(NotificationManager.class);
        prefs = Prefs.sp(this);
        interp = Prefs.interp(this);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        probe.start(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        // 0.1.0 saved sessions as files; 0.2.0 keeps everything in memory, so remove them
        java.io.File[] old = new java.io.File(getFilesDir(), "sessions").listFiles();
        if (old != null) for (java.io.File f : old) if (!f.delete()) f.deleteOnExit();
        if (!new java.io.File(getFilesDir(), "sessions").delete()) { /* not there */ }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESET.equals(a)) {
            if (!running) { stopSelf(); return START_NOT_STICKY; }
            finished = null;
            live = null;
            check();
            return START_STICKY;
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
        if (p != 0 && (live == null || !live.charge)) begin(true, p);              // discharge record is dropped
        else if (p == 0 && (live == null || live.charge)) {
            if (live != null && live.size() >= 3) finished = live;                  // keep the charge result
            begin(false, 0);
        }
    }

    private void begin(boolean charge, int plug) {
        if (charge) finished = null;
        live = new Session(System.currentTimeMillis(), charge, plug, interp);
        t0 = SystemClock.elapsedRealtime();
        // CPU stays awake only while charging; a discharge record bridges sleep with CHARGE_COUNTER (see Session)
        if (charge) {
            if (wake == null) {
                wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargelog:charge");
                wake.setReferenceCounted(false);
            }
            wake.acquire(24 * 3600_000L);
        } else if (wake != null && wake.isHeld()) wake.release();
        lastNotif = 0;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(tempTick);
        handler.post(tick);
        handler.post(tempTick);
    }

    private void sampleTemps() {
        Session s = live;
        if (s == null) return;
        s.addTemps(SystemClock.elapsedRealtime() - t0, Thermals.read(this, probe).all);
    }

    private void sample() {
        Session s = live;
        if (s == null || battery == null) return;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plug = plugged();
        if (plug != 0) s.plug = plug;
        s.chargerUv = battery.getIntExtra("max_charging_voltage", Session.MISSING);
        s.chargerUa = battery.getIntExtra("max_charging_current", Session.MISSING);
        s.add(new Session.Sample(SystemClock.elapsedRealtime() - t0,
                scale > 0 ? Math.round(level * 100f / scale) : level,
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0), plug != 0));
        remember(s);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotif >= 5000) { lastNotif = now; notifyNow(); }
    }

    /** Stores a confident auto-detected unit/sign so later sessions start with it (reset in the settings). */
    private void remember(Session s) {
        int u = s.confidentUnit(), sg = s.confidentSign();
        SharedPreferences.Editor e = null;
        if (interp.storedUnit == Interp.AUTO && u != Interp.AUTO) (e = prefs.edit()).putInt(Prefs.STORED_UNIT, u);
        if (interp.storedSign == Interp.AUTO && sg != Interp.AUTO) (e = e == null ? prefs.edit() : e).putInt(Prefs.STORED_SIGN, sg);
        if (e != null) e.apply();
    }

    private void shutdown() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(tempTick);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        if (wake != null && wake.isHeld()) wake.release();
        live = null;
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        shutdown();
        probe.stop();
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onDestroy();
    }

    private void notifyNow() {
        if (running) nm.notify(NOTIF, notification());
    }

    private Notification notification() {
        Session s = live;
        String title, text;
        Session.Sample l = s == null ? null : s.last();
        if (l == null) {
            title = getString(R.string.app_name);
            text = getString(R.string.notif_starting);
        } else {
            title = getString(s.charge ? R.string.notif_charging : R.string.notif_discharging, l.level, s.levelGain());
            text = getString(s.charge ? R.string.notif_charging_text : R.string.notif_discharging_text,
                    Session.duration(s.elapsedMs()), Session.num(s.shownMah(), "%.1f"), Session.num(s.shownW(), "%.2f"));
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
