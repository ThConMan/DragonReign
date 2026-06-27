package com.smp.dragonreign.util;

/** Tiny human-readable duration formatter for the egg's reset timers. */
public final class Times {

    private Times() {
    }

    /**
     * Format a millisecond duration as a short, friendly string: {@code "5d 3h"}, {@code "2h 14m"},
     * {@code "45s"}, {@code "now"} at zero, or {@code "—"} for a negative value (means "not ticking").
     */
    public static String human(long millis) {
        if (millis < 0) {
            return "—";
        }
        long s = millis / 1000L;
        if (s <= 0) {
            return "now";
        }
        long d = s / 86400L; s %= 86400L;
        long h = s / 3600L;  s %= 3600L;
        long m = s / 60L;    s %= 60L;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}
