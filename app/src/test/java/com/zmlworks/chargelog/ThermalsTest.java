package com.zmlworks.chargelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ThermalsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static Thermals.Reading t(String id, String name, int milli) { return new Thermals.Reading(id, name, milli); }

    static Session hist(Thermals.Reading[]... steps) {
        Session s = new Session(0, true, 1, Interp.DEFAULT);
        long t = 0;
        for (Thermals.Reading[] r : steps) { s.addTemps(t, r); t += 5000; }
        return s;
    }

    @Test public void parseUnits() {
        assertEquals(38000, Thermals.parseMilliC("38000\n"));
        assertEquals(39100, Thermals.parseDeciC("391"));
        assertEquals(30000, Thermals.fromDeciC(300));
        assertEquals(25125, Thermals.fromCelsius(25.125f));
        assertEquals(Thermals.MISSING, Thermals.parseMilliC(""));
        assertEquals(Thermals.MISSING, Thermals.parseMilliC("x"));
        assertEquals(Thermals.MISSING, Thermals.parseMilliC("400000"));   // 400 ℃ is not a phone
        assertEquals(Thermals.MISSING, Thermals.parseDeciC("9000"));
        assertEquals(380, Thermals.deci(38049));
        assertEquals(381, Thermals.deci(38050));
        assertEquals("38.0 ℃", Thermals.fmtC(38000));
        assertEquals("+4.1 ℃", Thermals.fmtDelta(4100));
    }

    @Test public void maxOfReadings() {
        Thermals.Reading[] r = {
                t("a", "A", 30000), t("b", "B", 44100), new Thermals.Reading("s", "熱状態", "問題なし")
        };
        assertEquals(44100, Thermals.maxMilliC(r));
    }

    @Test public void groupIdenticalAndSortByDelta() {
        // cpu-0/1/2 always match and rise the most; skin rises less; quiet never moves; xo appears later
        Session s = hist(
                new Thermals.Reading[]{t("cpu0", "cpu-0", 32000), t("cpu1", "cpu-1", 32000), t("cpu2", "cpu-2", 32000),
                        t("skin", "skin", 27000), t("quiet", "quiet-therm", 29000)},
                new Thermals.Reading[]{t("cpu0", "cpu-0", 38000), t("cpu1", "cpu-1", 38000), t("cpu2", "cpu-2", 38000),
                        t("skin", "skin", 29000), t("quiet", "quiet-therm", 29000), t("xo", "xo-therm", 30000)},
                new Thermals.Reading[]{t("cpu0", "cpu-0", 44000), t("cpu1", "cpu-1", 44000), t("cpu2", "cpu-2", 44000),
                        t("skin", "skin", 31000), t("quiet", "quiet-therm", 29000), t("xo", "xo-therm", 33000)}
        );
        List<Thermals.Row> rows = Thermals.group(s.temps);
        assertEquals(4, rows.size());
        assertEquals("cpu-0 / cpu-1 / cpu-2", rows.get(0).label);
        assertEquals(12000, rows.get(0).deltaMilliC);
        assertEquals(44000, rows.get(0).milliC);
        assertEquals("skin", rows.get(1).label);
        assertEquals(4000, rows.get(1).deltaMilliC);
        assertEquals("xo-therm", rows.get(2).label);
        assertEquals(3000, rows.get(2).deltaMilliC);
        assertEquals("quiet-therm", rows.get(3).label);
        assertEquals(0, rows.get(3).deltaMilliC);
    }

    @Test public void divergeSplitsGroup() {
        Session s = hist(
                new Thermals.Reading[]{t("a", "cpu", 30000), t("b", "cpu", 30000)},
                new Thermals.Reading[]{t("a", "cpu", 31000), t("b", "cpu", 40000)}
        );
        List<Thermals.Row> rows = Thermals.group(s.temps);
        assertEquals(2, rows.size());
        // same display name, disambiguated by id
        assertEquals(10000, rows.get(0).deltaMilliC);
        assertEquals(1000, rows.get(1).deltaMilliC);
        assertTrue(rows.get(0).label.contains("b") || rows.get(0).ids[0].equals("b"));
    }

    @Test public void negativeDeltaSortsByAbsolute() {
        Session s = hist(
                new Thermals.Reading[]{t("hot", "hot", 40000), t("cool", "cool", 40000)},
                new Thermals.Reading[]{t("hot", "hot", 44000), t("cool", "cool", 33000)}
        );
        List<Thermals.Row> rows = Thermals.group(s.temps);
        assertEquals("cool", rows.get(0).label);
        assertEquals(-7000, rows.get(0).deltaMilliC);
        assertEquals("hot", rows.get(1).label);
        assertEquals(4000, rows.get(1).deltaMilliC);
    }

    @Test public void displayRoundedEqualityGroups() {
        // 38.04 ℃ and 38.0 ℃ display as 38.0 and should group if they stay that way
        Session s = hist(
                new Thermals.Reading[]{t("a", "battery", 38000), t("b", "バッテリー", 38040)},
                new Thermals.Reading[]{t("a", "battery", 38100), t("b", "バッテリー", 38120)}
        );
        List<Thermals.Row> rows = Thermals.group(s.temps);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).label.contains("battery") || rows.get(0).label.contains("バッテリー"));
    }

    @Test public void readsSysfsZonesHwmonAndPowerSupply() throws Exception {
        File tz = tmp.newFolder("thermal", "thermal_zone0");
        write(new File(tz, "type"), "cpu-0");
        write(new File(tz, "temp"), "42150\n");
        File tzBad = tmp.newFolder("thermal", "thermal_zone1");
        write(new File(tzBad, "type"), "unreadable");
        // no temp file → skipped
        File denied = tmp.newFolder("thermal", "thermal_zone2");
        write(new File(denied, "type"), "pmic");
        write(new File(denied, "temp"), "nope");

        File hw = tmp.newFolder("hwmon", "hwmon0");
        write(new File(hw, "name"), "k10temp");
        write(new File(hw, "temp1_input"), "38900");
        write(new File(hw, "temp1_label"), "Tctl");

        File psy = tmp.newFolder("psy", "battery");
        write(new File(psy, "temp"), "391");

        ArrayList<Thermals.Reading> out = new ArrayList<>();
        Thermals.addThermalZones(new File(tmp.getRoot(), "thermal"), out);
        Thermals.addHwmon(new File(tmp.getRoot(), "hwmon"), out);
        Thermals.addPowerSupply(new File(tmp.getRoot(), "psy"), out);

        assertEquals(3, out.size());
        assertEquals("tz:thermal_zone0", out.get(0).id);
        assertEquals("cpu-0", out.get(0).name);
        assertEquals(42150, out.get(0).milliC);
        assertEquals("hwmon:hwmon0:temp1", out.get(1).id);
        assertEquals("k10temp Tctl", out.get(1).name);
        assertEquals(38900, out.get(1).milliC);
        assertEquals("psy:battery", out.get(2).id);
        assertEquals("バッテリー", out.get(2).name);
        assertEquals(39100, out.get(2).milliC);
    }

    @Test public void sessionMaxFollowsHottest() {
        Session s = hist(
                new Thermals.Reading[]{t("a", "A", 30000), t("b", "B", 35000)},
                new Thermals.Reading[]{t("a", "A", 40000), t("b", "B", 36000)}
        );
        assertEquals(2, s.tempSize());
        assertEquals(35000, s.maxMilliCAt(0));
        assertEquals(40000, s.maxMilliCAt(1));
        assertEquals(5000, s.tempT(1));
    }

    static void write(File f, String s) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }
}
