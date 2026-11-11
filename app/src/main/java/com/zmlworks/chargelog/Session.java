package com.zmlworks.chargelog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * One recording (charge or discharge), in memory only: raw battery samples plus derived numbers (normalized current,
 * power, integrated mAh/mWh). Pure Java so normalization and integration can be unit-tested.
 *
 * <p>Internally current is signed "into the battery" (charging positive). Getters named {@code shown…} flip a
 * discharge session so that used mAh, used Wh and discharge W are positive.
 *
 * <p>CURRENT_NOW differs between vendors (µA or mA; charging positive or negative). Unless fixed by {@link Interp}:
 * <ul>
 * <li>unit: µA once any |raw| ≥ 20000 (20 A in mA is impossible for a phone), otherwise mA;</li>
 * <li>sign: unplugged samples must be discharging and samples charging below 90 % should be charging; if the votes
 * say the reverse, everything is negated.</li>
 * </ul>
 * When a decision changes the totals are rebuilt from the raw samples, so changing a setting re-reads the session.
 */
final class Session {
    static final int MISSING = Integer.MIN_VALUE;
    static final int STATUS_CHARGING = 2;   // BatteryManager.BATTERY_STATUS_CHARGING
    static final int UA_THRESHOLD = 20000;
    /** Longer gaps (phone asleep) are bridged with CHARGE_COUNTER when available instead of interpolating current. */
    static final long GAP_MS = 15_000;

    static final class Sample {
        final long t;         // ms since session start
        final int level;      // %
        final int raw;        // CURRENT_NOW as reported, MISSING if unsupported
        final int rawV;       // EXTRA_VOLTAGE as reported
        final int deciC;      // temperature 0.1 °C
        final int counter;    // CHARGE_COUNTER µAh, MISSING if unsupported
        final int status;     // BatteryManager.BATTERY_STATUS_*
        final boolean plugged;

        Sample(long t, int level, int raw, int rawV, int deciC, int counter, int status, boolean plugged) {
            this.t = t; this.level = level; this.raw = raw; this.rawV = rawV; this.deciC = deciC;
            this.counter = counter > 0 ? counter : MISSING; this.status = status; this.plugged = plugged;
        }
    }

    final long start;       // wall clock ms
    final boolean charge;
    final int dir;          // +1 charge, -1 discharge (for the shown values)
    int plug;               // BatteryManager.BATTERY_PLUGGED_*
    int chargerUv = MISSING, chargerUa = MISSING;   // max_charging_voltage / max_charging_current extras
    final ArrayList<Sample> samples = new ArrayList<>();

    private Interp interp;
    // auto-detection evidence (raw, independent of the interpretation)
    private boolean sawUa, anyCurrent;
    private int maVotes, signVotes, unpluggedNeg, unpluggedPos;
    private int firstCounter = MISSING;
    // derived
    private boolean microAmps;
    private int sign = 1;
    private float[] ma = new float[256], watt = new float[256], acc = new float[256];
    private int[] mv = new int[256];
    private double mah, mwh;
    private float maxW;

    Session(long start, boolean charge, int plug, Interp interp) {
        this.start = start; this.charge = charge; this.dir = charge ? 1 : -1; this.plug = plug; this.interp = interp;
    }

    void add(Sample s) {
        samples.add(s);
        if (firstCounter == MISSING) firstCounter = s.counter;
        boolean firstCurrent = !anyCurrent && s.raw != MISSING;
        if (s.raw != MISSING) {
            anyCurrent = true;
            int a = Math.abs(s.raw);
            if (a >= UA_THRESHOLD) sawUa = true;
            if (a >= 50) {
                if (!s.plugged) {
                    signVotes += s.raw < 0 ? 1 : -1;
                    if (s.raw < 0) unpluggedNeg++; else unpluggedPos++;
                } else if (s.status == STATUS_CHARGING && s.level < 90) {
                    signVotes += s.raw > 0 ? 1 : -1;
                    if (a >= 300 && a < UA_THRESHOLD) maVotes++;
                }
            }
        }
        int n = samples.size();
        if (n > ma.length) {
            ma = Arrays.copyOf(ma, n * 2); watt = Arrays.copyOf(watt, n * 2); acc = Arrays.copyOf(acc, n * 2); mv = Arrays.copyOf(mv, n * 2);
        }
        if (decide() | firstCurrent) rebuild(); else step(n - 1);
    }

    void setInterp(Interp i) {
        interp = i;
        decide();
        rebuild();
    }

    /** Applies the interpretation; true if unit or sign changed. */
    private boolean decide() {
        int fu = interp.fixedUnit(), fs = interp.fixedSign();
        boolean u = fu != Interp.AUTO ? fu == Interp.UNIT_UA : sawUa;
        int sg = fs != Interp.AUTO ? (fs == Interp.SIGN_INVERT ? -1 : 1) : (signVotes < 0 ? -1 : 1);
        boolean changed = u != microAmps || sg != sign;
        microAmps = u;
        sign = sg;
        return changed;
    }

    private void rebuild() {
        mah = mwh = 0;
        maxW = 0;
        for (int i = 0; i < samples.size(); i++) step(i);
    }

    private void step(int i) {
        Sample s = samples.get(i);
        mv[i] = interp.mv(s.rawV);
        ma[i] = s.raw == MISSING ? Float.NaN : (float) (sign * (microAmps ? s.raw / 1000.0 : s.raw));
        watt[i] = ma[i] * mv[i] / 1e6f;
        if (i > 0) {
            Sample p = samples.get(i - 1);
            double h = (s.t - p.t) / 3_600_000.0;
            if (s.t - p.t > GAP_MS && s.counter != MISSING && p.counter != MISSING) {
                double d = (s.counter - p.counter) / 1000.0;
                mah += d;
                mwh += d * (mv[i] + mv[i - 1]) / 2000.0;
            } else if (!Float.isNaN(ma[i]) && !Float.isNaN(ma[i - 1])) {
                mah += (ma[i] + ma[i - 1]) / 2.0 * h;
                mwh += (watt[i] + watt[i - 1]) / 2.0 * 1000 * h;
            }
        }
        acc[i] = (float) (anyCurrent ? mah : counterMahAt(i));
        if (!Float.isNaN(watt[i]) && dir * watt[i] > maxW) maxW = dir * watt[i];
    }

    // ---- auto detection worth remembering for later sessions ----
    /** UNIT_UA once a reading was clearly µA; UNIT_MA after 30 readings while charging below 90 % that are only
     *  plausible in mA (0.3–20 A, never a µA-sized value); otherwise AUTO. */
    int confidentUnit() { return sawUa ? Interp.UNIT_UA : maVotes >= 30 ? Interp.UNIT_MA : Interp.AUTO; }

    /** From unplugged readings (always a discharge): at least 30 with 90 % or more on one side. */
    int confidentSign() {
        int n = unpluggedNeg + unpluggedPos;
        if (n < 30) return Interp.AUTO;
        if (unpluggedNeg * 10 >= n * 9) return Interp.SIGN_AS_IS;
        if (unpluggedPos * 10 >= n * 9) return Interp.SIGN_INVERT;
        return Interp.AUTO;
    }

    // ---- values ----
    int size() { return samples.size(); }
    Sample last() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }
    long elapsedMs() { return samples.isEmpty() ? 0 : last().t; }
    int levelStart() { return samples.isEmpty() ? 0 : samples.get(0).level; }
    int levelGain() { return samples.isEmpty() ? 0 : last().level - levelStart(); }
    boolean hasCurrent() { return anyCurrent; }
    boolean microAmps() { return microAmps; }
    boolean inverted() { return sign < 0; }

    /** Net mAh into the battery: integrated current, or the CHARGE_COUNTER difference without a current reading. */
    double mah() { return anyCurrent ? mah : counterMah(); }
    double wh() { return anyCurrent ? mwh / 1000.0 : Double.NaN; }

    double shownMah() { return dir * mah(); }
    double shownWh() { return dir * wh(); }
    double avgW() { double h = elapsedMs() / 3_600_000.0; return h > 0 ? shownWh() / h : Double.NaN; }
    double maxW() { return anyCurrent ? maxW : Double.NaN; }
    float shownAccAt(int i) { return dir * acc[i]; }
    float shownWAt(int i) { return dir * watt[i]; }
    float shownAAt(int i) { return dir * ma[i] / 1000f; }
    double shownW() { return samples.isEmpty() ? Double.NaN : shownWAt(samples.size() - 1); }
    double shownA() { return samples.isEmpty() ? Double.NaN : shownAAt(samples.size() - 1); }
    int mvNow() { return samples.isEmpty() ? 0 : mv[samples.size() - 1]; }

    /** CHARGE_COUNTER difference (µAh → mAh), signed into the battery; NaN if unsupported. */
    double counterMah() { return counterMahAt(samples.size() - 1); }

    private double counterMahAt(int i) {
        int lastC = MISSING;
        for (int k = i; k >= 0 && lastC == MISSING; k--) lastC = samples.get(k).counter;
        return firstCounter == MISSING || lastC == MISSING ? Double.NaN : (lastC - firstCounter) / 1000.0;
    }

    /** Battery capacity implied by this session (mAh per 100 %), once the level moved by 5 % or more. */
    double estCapacity() {
        int d = Math.abs(levelGain());
        double m = Math.abs(mah());
        return d < 5 || Double.isNaN(m) ? Double.NaN : m * 100.0 / d;
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

    /** Charger description from the plug type and the max_charging_voltage (µV) / max_charging_current (µA) extras. */
    static String charger(int plug, int uv, int ua) {
        if (plug == 0) return "—";
        double v = uv == MISSING || uv <= 0 ? 0 : uv > 100_000 ? uv / 1e6 : uv > 100 ? uv / 1e3 : uv;
        double a = ua == MISSING || ua <= 0 ? 0 : ua > 20_000 ? ua / 1e6 : ua / 1e3;
        if (v == 0 && a == 0) return plugName(plug) + " (電圧 不明)";
        StringBuilder b = new StringBuilder(plugName(plug));
        if (v > 0) b.append(String.format(Locale.ROOT, " %.1fV", v));
        if (a > 0) b.append(String.format(Locale.ROOT, " %.2fA", a));
        if (v >= 8.5) b.append(" (急速・高電圧)");
        else if (v > 0 && a > 0 && v * a >= 15) b.append(" (急速)");
        else if (v > 0 && a > 0) b.append(" (通常)");
        return b.toString();
    }
}
