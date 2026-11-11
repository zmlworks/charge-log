package com.zmlworks.chargelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionTest {
    static final int CH = Session.STATUS_CHARGING, DIS = 3;
    static final int M = Session.MISSING;

    static Session.Sample charging(long t, int level, int raw, int mv) { return new Session.Sample(t, level, raw, mv, 300, M, CH, true); }
    static Session.Sample unplugged(long t, int level, int raw, int mv) { return new Session.Sample(t, level, raw, mv, 300, M, DIS, false); }

    /** One hour at 1 Hz with a constant raw current. */
    static Session hour(boolean charge, int raw, int mv, Interp in) {
        Session s = new Session(0, charge, charge ? 1 : 0, in);
        for (int i = 0; i <= 3600; i++) s.add(charge ? charging(i * 1000L, 50, raw, mv) : unplugged(i * 1000L, 50, raw, mv));
        return s;
    }

    @Test public void microAmpsCharging() {
        Session s = hour(true, 1_000_000, 4000, Interp.DEFAULT);
        assertTrue(s.microAmps());
        assertEquals(1000, s.shownMah(), 1e-6);
        assertEquals(4.0, s.shownWh(), 1e-6);
        assertEquals(4.0, s.avgW(), 1e-6);
        assertEquals(4.0, s.maxW(), 1e-6);
        assertEquals(1.0, s.shownA(), 1e-6);
        assertEquals(3601, s.size());
    }

    @Test public void milliAmps() {
        Session s = hour(true, 1000, 4000, Interp.DEFAULT);
        assertFalse(s.microAmps());
        assertEquals(1000, s.shownMah(), 1e-6);
        assertEquals(Interp.UNIT_MA, s.confidentUnit());
    }

    @Test public void negativeWhileChargingIsFlipped() {
        Session s = hour(true, -2_000_000, 4000, Interp.DEFAULT);
        assertTrue(s.inverted());
        assertEquals(2000, s.shownMah(), 1e-6);
    }

    @Test public void dischargeShownPositive() {
        Session s = hour(false, -500_000, 3800, Interp.DEFAULT);   // standard: discharge negative
        assertFalse(s.inverted());
        assertEquals(-500, s.mah(), 1e-6);
        assertEquals(500, s.shownMah(), 1e-6);
        assertEquals(1.9, s.shownWh(), 1e-6);
        assertEquals(1.9, s.maxW(), 1e-6);
        assertEquals(Interp.SIGN_AS_IS, s.confidentSign());
        assertEquals(Interp.UNIT_UA, s.confidentUnit());
    }

    @Test public void dischargePositiveVendorIsInverted() {
        Session s = hour(false, 400, 3800, Interp.DEFAULT);         // mA, discharge reported positive
        assertTrue(s.inverted());
        assertEquals(400, s.shownMah(), 1e-6);
        assertEquals(Interp.SIGN_INVERT, s.confidentSign());
        assertEquals(Interp.AUTO, s.confidentUnit());               // unplugged mA readings are not proof of mA
    }

    @Test public void storedUnitWinsAtLowCurrent() {
        // µA device near full: 15000 µA would read as 15 A without the stored decision
        Interp stored = new Interp(0, 0, 0, Interp.UNIT_UA, 0);
        Session s = hour(true, 15_000, 4400, stored);
        assertTrue(s.microAmps());
        assertEquals(15, s.shownMah(), 1e-6);
        Session a = hour(true, 15_000, 4400, Interp.DEFAULT);
        assertFalse(a.microAmps());
    }

    @Test public void settingOverridesAndRebuilds() {
        Session s = hour(true, 1_000_000, 4000, Interp.DEFAULT);
        s.setInterp(new Interp(Interp.UNIT_MA, Interp.SIGN_INVERT, Interp.VOLT_MV, 0, 0));
        assertEquals(-1_000_000, s.mah(), 1e-3);
        s.setInterp(Interp.DEFAULT);
        assertEquals(1000, s.mah(), 1e-6);
    }

    @Test public void unitDecisionRebuildsEarlierSamples() {
        Session s = new Session(0, true, 2, Interp.DEFAULT);
        for (int i = 0; i < 10; i++) s.add(charging(i * 1000L, 99, 15_000, 4400));
        assertFalse(s.microAmps());
        s.add(charging(10_000, 99, 500_000, 4400));
        assertTrue(s.microAmps());
        assertEquals((9 * 15.0 + (15.0 + 500.0) / 2) / 3600.0, s.mah(), 1e-9);
    }

    @Test public void sleepGapUsesCounter() {
        Session s = new Session(0, false, 0, Interp.DEFAULT);
        s.add(new Session.Sample(0, 80, -600_000, 4000, 300, 3_000_000, DIS, false));
        s.add(new Session.Sample(1000, 80, -600_000, 4000, 300, 2_999_833, DIS, false));
        // asleep for 10 minutes: only 30 mAh used (screen off), not 600 mA × 10 min = 100 mAh
        s.add(new Session.Sample(601_000, 79, -600_000, 4000, 300, 2_969_833, DIS, false));
        double expected = 600.0 / 3600 + 30;
        assertEquals(expected, s.shownMah(), 1e-6);
        assertEquals(expected * 4.0 / 1000, s.shownWh(), 1e-6);
    }

    @Test public void counterFallbackWithoutCurrent() {
        Session s = new Session(0, true, 1, Interp.DEFAULT);
        s.add(new Session.Sample(0, 20, M, 3800, 250, 1_000_000, CH, true));
        s.add(new Session.Sample(1000, 20, M, 3800, 250, 0, CH, true));   // 0 = unsupported read
        s.add(new Session.Sample(2000, 21, M, 3800, 250, 1_050_000, CH, true));
        assertFalse(s.hasCurrent());
        assertEquals(50, s.shownMah(), 1e-9);
        assertTrue(Double.isNaN(s.shownWh()));
        assertTrue(Double.isNaN(s.maxW()));
    }

    @Test public void voltageUnits() {
        assertEquals(4000, Interp.DEFAULT.mv(4));
        assertEquals(4200, Interp.DEFAULT.mv(4200));
        assertEquals(4200, Interp.DEFAULT.mv(4_200_000));
        assertEquals(4, new Interp(0, 0, Interp.VOLT_MV, 0, 0).mv(4));
    }

    @Test public void estimatedCapacity() {
        Session s = new Session(0, true, 1, Interp.DEFAULT);
        s.add(charging(0, 69, 2_000_000, 4000));
        s.add(charging(3_600_000, 73, 2_000_000, 4000));
        assertTrue(Double.isNaN(s.estCapacity()));                 // 4 % is too little
        s.add(charging(3_960_000, 74, 2_000_000, 4000));
        assertEquals(2200 * 100.0 / 5, s.estCapacity(), 1e-6);
    }

    @Test public void chargerText() {
        assertEquals("AC 9.0V 2.00A (急速・高電圧)", Session.charger(1, 9_000_000, 2_000_000));
        assertEquals("AC 5.0V 3.00A (急速)", Session.charger(1, 5_000_000, 3_000_000));
        assertEquals("USB 5.0V 0.50A (通常)", Session.charger(2, 5_000_000, 500_000));
        assertEquals("AC (電圧 不明)", Session.charger(1, M, M));
        assertEquals("—", Session.charger(0, 9_000_000, 2_000_000));
    }

    @Test public void formatting() {
        assertEquals("1:02:03", Session.duration(3_723_000));
        assertEquals("—", Session.num(Double.NaN, "%.1f"));
        assertEquals(25f, GraphView.nice(21), 0);
        assertEquals(5000f, GraphView.nice(3200), 0);
        assertEquals(2.5f, GraphView.nice(2.5f), 0);
        assertEquals(900_000, GraphView.timeStepMs(90 * 60_000L));
        assertEquals("1:30", GraphView.timeLabel(90 * 60_000L, 900_000));
        assertEquals("0:45", GraphView.timeLabel(45_000, 15_000));
    }
}
