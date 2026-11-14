package com.zmlworks.chargelog;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads every temperature source that is available without root or hidden APIs:
 * BatteryManager.EXTRA_TEMPERATURE, sysfs thermal zones, hwmon temp inputs,
 * power_supply temp, {@link SensorManager} ambient / legacy temperature sensors,
 * and {@link PowerManager} thermal status / headroom (the last two are not Celsius).
 *
 * <p>HardwarePropertiesManager is intentionally unused (it is a privileged API).
 */
final class Thermals {
    static final int MISSING = Integer.MIN_VALUE;
    /** Values outside this window are treated as unreadable. */
    static final int MIN_MILLI = -50_000, MAX_MILLI = 150_000;

    static final File THERMAL_DIR = new File("/sys/class/thermal");
    static final File HWMON_DIR = new File("/sys/class/hwmon");
    static final File PSY_DIR = new File("/sys/class/power_supply");

    private Thermals() {}

    /** One reading: either a temperature in milli-°C, or a non-temperature text value. */
    static final class Reading {
        final String id, name;
        final int milliC;
        final String text;

        Reading(String id, String name, int milliC) {
            this.id = id; this.name = name; this.milliC = milliC; this.text = null;
        }

        Reading(String id, String name, String text) {
            this.id = id; this.name = name; this.milliC = MISSING; this.text = text;
        }

        boolean isTemp() { return text == null && milliC != MISSING; }
    }

    /** Grouped row for the on-screen list (same-valued sensors collapsed). */
    static final class Row {
        final String label;
        final String[] ids;
        final int milliC, startMilliC, deltaMilliC;

        Row(String label, String[] ids, int milliC, int startMilliC) {
            this.label = label; this.ids = ids; this.milliC = milliC;
            this.startMilliC = startMilliC; this.deltaMilliC = milliC - startMilliC;
        }
    }

    /**
     * Keeps {@link SensorManager} listeners so {@link #read(Context, Probe)} can use the
     * latest ambient / legacy temperature (those sensors only push events).
     */
    static final class Probe implements SensorEventListener {
        private SensorManager sm;
        volatile Integer ambientMilli, legacyMilli;

        void start(Context c) {
            sm = c.getSystemService(SensorManager.class);
            if (sm == null) return;
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
            if (a != null) sm.registerListener(this, a, SensorManager.SENSOR_DELAY_NORMAL);
            Sensor t = sm.getDefaultSensor(Sensor.TYPE_TEMPERATURE);
            if (t != null) sm.registerListener(this, t, SensorManager.SENSOR_DELAY_NORMAL);
        }

        void stop() {
            if (sm != null) sm.unregisterListener(this);
            sm = null;
        }

        @Override public void onSensorChanged(SensorEvent e) {
            if (e == null || e.values == null || e.values.length == 0) return;
            int m = fromCelsius(e.values[0]);
            if (m == MISSING) return;
            int type = e.sensor.getType();
            if (type == Sensor.TYPE_AMBIENT_TEMPERATURE) ambientMilli = m;
            else if (type == Sensor.TYPE_TEMPERATURE) legacyMilli = m;
        }

        @Override public void onAccuracyChanged(Sensor s, int a) {}
    }

    static int fromCelsius(float c) {
        if (!Float.isFinite(c)) return MISSING;
        int m = Math.round(c * 1000f);
        return m < MIN_MILLI || m > MAX_MILLI ? MISSING : m;
    }

    static int fromDeciC(int deci) {
        long m = (long) deci * 100L;
        return m < MIN_MILLI || m > MAX_MILLI ? MISSING : (int) m;
    }

    /** Kernel thermal / hwmon: millidegrees C. */
    static int parseMilliC(String raw) {
        Long v = parseLong(raw);
        if (v == null || v < MIN_MILLI || v > MAX_MILLI) return MISSING;
        return v.intValue();
    }

    /** power_supply {@code temp} and BatteryManager: tenths of a degree C. */
    static int parseDeciC(String raw) {
        Long v = parseLong(raw);
        if (v == null) return MISSING;
        return fromDeciC(v.intValue());
    }

    private static Long parseLong(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    /** Display tenths of a degree, used to decide whether two sensors "show the same number". */
    static int deci(int milliC) { return Math.round(milliC / 100f); }

    static String fmtC(int milliC) {
        return milliC == MISSING ? "—" : String.format(Locale.ROOT, "%.1f ℃", milliC / 1000.0);
    }

    static String fmtDelta(int deltaMilli) {
        return String.format(Locale.ROOT, "%+.1f ℃", deltaMilli / 1000.0);
    }

    static int maxMilliC(Reading[] rs) {
        int max = MISSING;
        if (rs == null) return max;
        for (Reading r : rs) if (r.isTemp() && (max == MISSING || r.milliC > max)) max = r.milliC;
        return max;
    }

    static Snapshot read(Context c, Probe probe) {
        return read(c, probe, THERMAL_DIR, HWMON_DIR, PSY_DIR);
    }

    static final class Snapshot {
        final Reading[] all;
        Snapshot(Reading[] all) { this.all = all; }
        int maxMilliC() { return Thermals.maxMilliC(all); }
    }

    static Snapshot read(Context c, Probe probe, File thermal, File hwmon, File psy) {
        ArrayList<Reading> out = new ArrayList<>();
        Intent bi = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null && bi.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            int m = fromDeciC(bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
            if (m != MISSING) out.add(new Reading("battery", "バッテリー", m));
        }
        addThermalZones(thermal, out);
        addHwmon(hwmon, out);
        addPowerSupply(psy, out);
        if (probe != null && probe.ambientMilli != null)
            out.add(new Reading("sensor:ambient", "周囲温度", probe.ambientMilli));
        if (probe != null && probe.legacyMilli != null)
            out.add(new Reading("sensor:legacy", "温度センサー", probe.legacyMilli));
        addPowerManager(c, out);
        return new Snapshot(out.toArray(new Reading[0]));
    }

    static void addThermalZones(File root, List<Reading> out) {
        File[] zs = list(root);
        if (zs == null) return;
        for (File z : zs) {
            String n = z.getName();
            if (!n.startsWith("thermal_zone")) continue;
            int m = parseMilliC(readTrim(new File(z, "temp")));
            if (m == MISSING) continue;
            String type = readTrim(new File(z, "type"));
            String name = type == null || type.isEmpty() ? n : type;
            out.add(new Reading("tz:" + n, name, m));
        }
    }

    static void addHwmon(File root, List<Reading> out) {
        File[] hs = list(root);
        if (hs == null) return;
        for (File h : hs) {
            if (!h.getName().startsWith("hwmon")) continue;
            String chip = readTrim(new File(h, "name"));
            if (chip == null || chip.isEmpty()) chip = h.getName();
            File[] files = h.listFiles();
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                String fn = f.getName();
                if (!fn.startsWith("temp") || !fn.endsWith("_input")) continue;
                int m = parseMilliC(readTrim(f));
                if (m == MISSING) continue;
                String prefix = fn.substring(0, fn.length() - 6);   // "temp1"
                String label = readTrim(new File(h, prefix + "_label"));
                String name = label == null || label.isEmpty() ? chip + " " + prefix : chip + " " + label;
                out.add(new Reading("hwmon:" + h.getName() + ":" + prefix, name, m));
            }
        }
    }

    static void addPowerSupply(File root, List<Reading> out) {
        File[] ps = list(root);
        if (ps == null) return;
        for (File p : ps) {
            int m = parseDeciC(readTrim(new File(p, "temp")));
            if (m == MISSING) continue;
            String n = p.getName();
            String name = "battery".equalsIgnoreCase(n) ? "バッテリー" : "電源 " + n;
            out.add(new Reading("psy:" + n, name, m));
        }
    }

    private static void addPowerManager(Context c, List<Reading> out) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null) return;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                int st = pm.getCurrentThermalStatus();
                out.add(new Reading("pm.status", "熱状態", statusName(st)));
            } catch (RuntimeException ignored) { /* some stubs / older devices */ }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                float h = pm.getThermalHeadroom(0);
                if (Float.isFinite(h))
                    out.add(new Reading("pm.headroom", "熱ヘッドルーム", String.format(Locale.ROOT, "%.2f", h)));
            } catch (RuntimeException ignored) { /* first call can fail on some devices */ }
        }
    }

    static String statusName(int st) {
        switch (st) {
            case 0: return "問題なし";
            case 1: return "軽度";
            case 2: return "中程度";
            case 3: return "重度";
            case 4: return "危険";
            case 5: return "緊急";
            case 6: return "停止";
            default: return String.valueOf(st);
        }
    }

    /**
     * Collapse sensors that have agreed on every overlapping sample (to 0.1 ℃) into one row,
     * then sort by the absolute change since the first reading, largest first. Unchanged
     * sensors (delta 0) naturally sink to the bottom.
     */
    static List<Row> group(List<Session.TempSample> hist) {
        ArrayList<Row> rows = new ArrayList<>();
        if (hist == null || hist.isEmpty()) return rows;
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (Session.TempSample s : hist)
            for (int i = 0; i < s.ids.length; i++) names.putIfAbsent(s.ids[i], s.names[i]);
        String[] ids = names.keySet().toArray(new String[0]);
        int n = ids.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int x) {
                while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
                return x;
            }
        };
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
            if (sameAlways(hist, ids[a], ids[b])) {
                int pa = find.applyAsInt(a), pb = find.applyAsInt(b);
                if (pa != pb) parent[pb] = pa;
            }
        }
        LinkedHashMap<Integer, ArrayList<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int p = find.applyAsInt(i);
            groups.computeIfAbsent(p, k -> new ArrayList<>()).add(i);
        }
        int[] first = new int[n], last = new int[n];
        Arrays.fill(first, MISSING);
        Arrays.fill(last, MISSING);
        for (Session.TempSample s : hist) {
            for (int i = 0; i < n; i++) {
                int v = valueOf(s, ids[i]);
                if (v == MISSING) continue;
                if (first[i] == MISSING) first[i] = v;
                last[i] = v;
            }
        }
        ArrayList<ArrayList<Integer>> glist = new ArrayList<>(groups.values());
        ArrayList<Row> tmp = new ArrayList<>();
        for (ArrayList<Integer> g : glist) {
            ArrayList<String> gIds = new ArrayList<>(), labelParts = new ArrayList<>();
            int now = MISSING, start = MISSING;
            for (int i : g) {
                gIds.add(ids[i]);
                String nm = names.get(ids[i]);
                if (!labelParts.contains(nm)) labelParts.add(nm);
                if (now == MISSING) now = last[i];
                if (start == MISSING) start = first[i];
            }
            if (now == MISSING || start == MISSING) continue;
            tmp.add(new Row(String.join(" / ", labelParts), gIds.toArray(new String[0]), now, start));
        }
        Map<String, Integer> labelCount = new LinkedHashMap<>();
        for (Row r : tmp) labelCount.merge(r.label, 1, Integer::sum);
        for (Row r : tmp) {
            String label = r.label;
            if (labelCount.getOrDefault(label, 0) > 1 && r.ids.length == 1)
                label = label + " (" + shortId(r.ids[0]) + ")";
            rows.add(new Row(label, r.ids, r.milliC, r.startMilliC));
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(Math.abs(b.deltaMilliC), Math.abs(a.deltaMilliC));
            return c != 0 ? c : a.label.compareTo(b.label);
        });
        return rows;
    }

    private static String shortId(String id) {
        int c = id.lastIndexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    /** True when both sensors were present together at least once and always matched to 0.1 ℃. */
    static boolean sameAlways(List<Session.TempSample> hist, String idA, String idB) {
        int seen = 0;
        for (Session.TempSample s : hist) {
            int a = valueOf(s, idA), b = valueOf(s, idB);
            if (a == MISSING || b == MISSING) continue;
            seen++;
            if (deci(a) != deci(b)) return false;
        }
        return seen > 0;
    }

    static int valueOf(Session.TempSample s, String id) {
        for (int i = 0; i < s.ids.length; i++) if (id.equals(s.ids[i])) return s.milliC[i];
        return MISSING;
    }

    static String readTrim(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (IOException e) { return null; }
    }

    private static File[] list(File root) {
        File[] fs = root == null ? null : root.listFiles();
        if (fs != null) Arrays.sort(fs, Comparator.comparing(File::getName));
        return fs;
    }
}
