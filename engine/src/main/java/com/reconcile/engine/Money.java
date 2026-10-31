package com.reconcile.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is held as paise in a long. Parsing goes through BigDecimal so no binary floating point is involved. */
public final class Money {
    private Money() {}

    public static long parse(String text) {
        return new BigDecimal(text.trim().replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long a = Math.abs(paise);
        return sign + (a / 100) + "." + String.format("%02d", a % 100);
    }
}
