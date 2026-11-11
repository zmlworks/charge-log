package com.zmlworks.chargelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;

import org.junit.Test;

public class SessionTest {
    static final int CH = Session.STATUS_CHARGING;

    /** One hour at 1 Hz with a constant raw current. */
    static Session constant(int raw, int mv) {
        Session s = new Session(0, 1);
        for (int i = 0; i <= 3600; i++) s.add(new Session.Sample(i * 1000L, 50, raw, mv, 300, Session.MISSING, CH));
        return s;
    }

    @Test public void microAmpsPositive() {
        Session s = constant(1_000_000, 4000);
        assertTrue(s.microAmps());
        assertEquals(1000, s.mah(), 1e-6);
        assertEquals(4.0, s.wh(), 1e-6);
        assertEquals(4.0, s.avgW(), 1e-6);
        assertEquals(1000, s.currentMa(), 1e-3);
    }

    @Test public void milliAmps() {
        Session s = constant(1000, 4000);
        assertFalse(s.microAmps());
        assertEquals(1000, s.mah(), 1e-6);
    }

    @Test public void negativeWhileChargingIsFlipped() {
        Session s = constant(-2_000_000, 4000);
        assertTrue(s.inverted());
        assertEquals(2000, s.mah(), 1e-6);
        assertEquals(8.0, s.wh(), 1e-6);
    }

    @Test public void unitDecisionRebuildsEarlierSamples() {
        // a µA device that starts with a small trickle: 15000 µA reads as mA until a large value proves µA
        Session s = new Session(0, 2);
        for (int i = 0; i < 10; i++) s.add(new Session.Sample(i * 1000L, 99, 15_000, 4400, 300, Session.MISSING, CH));
        assertFalse(s.microAmps());
        s.add(new Session.Sample(10_000, 99, 500_000, 4400, 300, Session.MISSING, CH));
        assertTrue(s.microAmps());
        double expected = (9 * 15.0 + (15.0 + 500.0) / 2) / 3600.0;
        assertEquals(expected, s.mah(), 1e-9);
        assertEquals(expected, s.accAt(10), 1e-6);
    }

    @Test public void dischargeWhileFullIsNegative() {
        // charging positive at first, then "full" with the phone drawing a little from the battery
        Session s = new Session(0, 1);
        for (int i = 0; i < 100; i++) s.add(new Session.Sample(i * 1000L, 99, 1_000_000, 4300, 300, Session.MISSING, CH));
        for (int i = 100; i < 200; i++) s.add(new Session.Sample(i * 1000L, 100, -100_000, 4300, 300, Session.MISSING, 5));
        assertFalse(s.inverted());
        assertTrue(s.currentMa() < 0);
    }

    @Test public void counterFallbackWithoutCurrent() {
        Session s = new Session(0, 1);
        s.add(new Session.Sample(0, 20, Session.MISSING, 3800, 250, 1_000_000, CH));
        s.add(new Session.Sample(1000, 20, Session.MISSING, 3800, 250, 0, CH));   // 0 = unsupported read
        s.add(new Session.Sample(2000, 21, Session.MISSING, 3800, 250, 1_050_000, CH));
        assertFalse(s.hasCurrent());
        assertEquals(50, s.mah(), 1e-9);
        assertTrue(Double.isNaN(s.wh()));
        assertEquals(50, s.counterMah(), 1e-9);
    }

    @Test public void voltageInVolts() {
        assertEquals(4000, Session.normVoltage(4));
        assertEquals(4200, Session.normVoltage(4200));
    }

    @Test public void fileRoundTrip() throws Exception {
        Session s = new Session(1_700_000_000_000L, 4);
        for (int i = 0; i < 50; i++)
            s.add(new Session.Sample(i * 1000L, 30 + i / 10, i % 7 == 0 ? Session.MISSING : -1_500_000 + i * 1000, 4100, 310, 2_000_000 + i * 400, CH));
        StringBuilder b = new StringBuilder(s.header());
        for (Session.Sample x : s.samples) b.append(Session.line(x));
        b.append("51000,3");   // torn last line is ignored
        Session r = Session.read(new StringReader(b.toString()));
        assertEquals(s.start, r.start);
        assertEquals(4, r.plug);
        assertEquals(50, r.size());
        assertEquals(s.mah(), r.mah(), 1e-9);
        assertEquals(s.wh(), r.wh(), 1e-9);
        assertEquals(s.counterMah(), r.counterMah(), 1e-9);
    }

    @Test public void formatting() {
        assertEquals("1:02:03", Session.duration(3_723_000));
        assertEquals("—", Session.num(Double.NaN, "%.1f"));
        assertEquals(25f, GraphView.nice(21), 0);
        assertEquals(1f, GraphView.nice(0), 0);
        assertEquals(5000f, GraphView.nice(3200), 0);
        assertEquals(15, GraphView.timeStep(90 * 60_000L));
    }
}
