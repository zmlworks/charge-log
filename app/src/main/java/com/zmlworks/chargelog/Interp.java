package com.zmlworks.chargelog;

/**
 * How raw battery values are read. Each choice is AUTO (0) or a fixed setting; "stored" values are what auto
 * detection decided confidently in an earlier session (0 = nothing stored). Order: setting → stored → this session.
 */
final class Interp {
    static final int AUTO = 0;
    static final int UNIT_MA = 1, UNIT_UA = 2;
    static final int SIGN_AS_IS = 1, SIGN_INVERT = 2;
    static final int VOLT_MV = 1, VOLT_V = 2, VOLT_UV = 3;

    final int unit, sign, volt, storedUnit, storedSign;

    Interp(int unit, int sign, int volt, int storedUnit, int storedSign) {
        this.unit = unit; this.sign = sign; this.volt = volt; this.storedUnit = storedUnit; this.storedSign = storedSign;
    }

    static final Interp DEFAULT = new Interp(AUTO, AUTO, AUTO, AUTO, AUTO);

    /** Fixed unit (setting or stored), or AUTO. */
    int fixedUnit() { return unit != AUTO ? unit : storedUnit; }

    /** Fixed sign (setting or stored), or AUTO. */
    int fixedSign() { return sign != AUTO ? sign : storedSign; }

    /** EXTRA_VOLTAGE → mV. AUTO: under 100 is volts, over 100000 is microvolts, otherwise millivolts. */
    int mv(int raw) {
        int m = volt;
        if (m == AUTO) m = raw > 0 && raw < 100 ? VOLT_V : raw > 100_000 ? VOLT_UV : VOLT_MV;
        return m == VOLT_V ? raw * 1000 : m == VOLT_UV ? raw / 1000 : raw;
    }
}
