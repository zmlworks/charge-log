package com.zmlworks.chargelog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * One charging session: raw battery samples plus the derived numbers (normalized current, power, integrated mAh/mWh).
 * Pure Java so the normalization and integration can be unit-tested.
 *
 * <p>CURRENT_NOW differs between vendors: µA (documented) or mA, and positive or negative while charging. Both are
 * decided from the whole session so far and the totals are rebuilt when a decision changes:
 * <ul>
 * <li>unit: µA once any |raw| ≥ 20000 (20 A in mA is impossible for a phone), otherwise mA;</li>
 * <li>sign: the sum of raw values over samples whose status is CHARGING must be positive, otherwise everything is
 * negated.</li>
 * </ul>
 */
final class Session {
    static final int MISSING = Integer.MIN_VALUE;
    static final int STATUS_CHARGING = 2;   // BatteryManager.BATTERY_STATUS_CHARGING
    static final int UA_THRESHOLD = 20000;

    static final class Sample {
        final long t;         // ms since session start
        final int level;      // %
        final int raw;        // CURRENT_NOW as reported, MISSING if unsupported
        final int mv;         // voltage mV
        final int deciC;      // temperature 0.1 °C
        final int counter;    // CHARGE_COUNTER µAh, MISSING if unsupported
        final int status;     // BatteryManager.BATTERY_STATUS_*

        Sample(long t, int level, int raw, int mv, int deciC, int counter, int status) {
            this.t = t; this.level = level; this.raw = raw; this.mv = normVoltage(mv);
            this.deciC = deciC; this.counter = counter > 0 ? counter : MISSING; this.status = status;
        }
    }

    /** Some devices report EXTRA_VOLTAGE in volts. */
    static int normVoltage(int v) { return v > 0 && v < 100 ? v * 1000 : v; }

    final long start;   // wall clock ms
    final int plug;     // BatteryManager.BATTERY_PLUGGED_*
    final ArrayList<Sample> samples = new ArrayList<>();

    private boolean microAmps, anyCurrent;
    private int firstCounter = MISSING;
    private int sign = 1;
    private long chargingRawSum;
    private float[] ma = new float[64], watt = new float[64], acc = new float[64];
    private double mah, mwh;

    Session(long start, int plug) { this.start = start; this.plug = plug; }

    void add(Sample s) {
        samples.add(s);
        if (firstCounter == MISSING) firstCounter = s.counter;
        boolean rebuild = false;
        if (s.raw != MISSING) {
            if (!anyCurrent) { anyCurrent = true; rebuild = true; }
            if (!microAmps && Math.abs((long) s.raw) >= UA_THRESHOLD) { microAmps = true; rebuild = true; }
            if (s.status == STATUS_CHARGING) {
                chargingRawSum += s.raw;
                int ns = chargingRawSum < 0 ? -1 : 1;
                if (ns != sign) { sign = ns; rebuild = true; }
            }
        }
        int n = samples.size();
        if (n > ma.length) {
            ma = Arrays.copyOf(ma, n * 2); watt = Arrays.copyOf(watt, n * 2); acc = Arrays.copyOf(acc, n * 2);
        }
        if (rebuild) { mah = mwh = 0; for (int i = 0; i < n; i++) step(i); }
        else step(n - 1);
    }

    private void step(int i) {
        Sample s = samples.get(i);
        ma[i] = s.raw == MISSING ? Float.NaN : (float) (sign * (microAmps ? s.raw / 1000.0 : s.raw));
        watt[i] = ma[i] * s.mv / 1e6f;
        if (i > 0 && !Float.isNaN(ma[i]) && !Float.isNaN(ma[i - 1])) {
            double h = (s.t - samples.get(i - 1).t) / 3_600_000.0;
            mah += (ma[i] + ma[i - 1]) / 2.0 * h;
            mwh += (watt[i] + watt[i - 1]) / 2.0 * 1000 * h;
        }
        acc[i] = (float) (hasCurrent() ? mah : counterMahAt(i));
    }

    int size() { return samples.size(); }
    Sample last() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }
    long elapsedMs() { return samples.isEmpty() ? 0 : last().t; }
    int levelStart() { return samples.isEmpty() ? 0 : samples.get(0).level; }
    int levelGain() { return samples.isEmpty() ? 0 : last().level - levelStart(); }
    boolean hasCurrent() { return anyCurrent; }
    boolean microAmps() { return microAmps; }
    boolean inverted() { return sign < 0; }

    /** Charged mAh: integrated current, or the CHARGE_COUNTER difference when the device has no current reading. */
    double mah() { return hasCurrent() ? mah : counterMah(); }
    /** Energy into the battery (V×I integrated), NaN without a current reading. */
    double wh() { return hasCurrent() ? mwh / 1000.0 : Double.NaN; }
    double avgW() { double h = elapsedMs() / 3_600_000.0; return h > 0 ? wh() / h : Double.NaN; }
    float maAt(int i) { return ma[i]; }
    float wAt(int i) { return watt[i]; }
    float accAt(int i) { return acc[i]; }
    double currentMa() { return samples.isEmpty() ? Double.NaN : ma[samples.size() - 1]; }
    double currentW() { return samples.isEmpty() ? Double.NaN : watt[samples.size() - 1]; }

    /** CHARGE_COUNTER difference (µAh → mAh) between the first and the last sample that have it; NaN if none. */
    double counterMah() { return counterMahAt(samples.size() - 1); }

    private double counterMahAt(int i) {
        int lastC = MISSING;
        for (int k = i; k >= 0 && lastC == MISSING; k--) lastC = samples.get(k).counter;
        return firstCounter == MISSING || lastC == MISSING ? Double.NaN : (lastC - firstCounter) / 1000.0;
    }

    // ---- file format: "key=value" header lines, then one CSV line per sample ----
    static final String COLUMNS = "t_ms,level,current_raw,voltage_mv,temp_decic,counter_uah,status";

    String header() {
        return "# chargelog 1\nstart=" + start + "\nplug=" + plug + "\n" + COLUMNS + "\n";
    }

    static String line(Sample s) {
        return String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%s,%d\n", s.t, s.level, s.raw == MISSING ? "" : Integer.toString(s.raw),
                s.mv, s.deciC, s.counter == MISSING ? "" : Integer.toString(s.counter), s.status);
    }

    static Session read(Reader r) throws IOException {
        BufferedReader in = new BufferedReader(r);
        long start = 0;
        int plug = 0;
        Session s = null;
        String l;
        while ((l = in.readLine()) != null) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (s == null) {
                if (l.startsWith("start=")) start = Long.parseLong(l.substring(6));
                else if (l.startsWith("plug=")) plug = Integer.parseInt(l.substring(5));
                else if (l.equals(COLUMNS)) s = new Session(start, plug);
                continue;
            }
            String[] f = l.split(",", -1);
            if (f.length < 7) break;   // torn last line (killed while writing)
            try {
                s.add(new Sample(Long.parseLong(f[0]), Integer.parseInt(f[1]), f[2].isEmpty() ? MISSING : Integer.parseInt(f[2]),
                        Integer.parseInt(f[3]), Integer.parseInt(f[4]), f[5].isEmpty() ? MISSING : Integer.parseInt(f[5]),
                        Integer.parseInt(f[6])));
            } catch (NumberFormatException e) {
                break;
            }
        }
        if (s == null) throw new IOException("not a chargelog file");
        return s;
    }

    // ---- formatting ----
    static String duration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    static String num(double v, String fmt) { return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, fmt, v); }

    static String plugName(int p) {
        switch (p) {
            case 1: return "AC";
            case 2: return "USB";
            case 4: return "ワイヤレス";
            case 8: return "ドック";
            default: return "—";
        }
    }
}
