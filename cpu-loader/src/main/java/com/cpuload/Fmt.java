package com.cpuload;

import java.util.Locale;

/** 输出格式辅助。 */
final class Fmt {
    private Fmt() {
    }

    /** 0.5 -> "50.0%" */
    static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    /** 0.5 -> "0.500" */
    static String duty(double duty) {
        return String.format(Locale.ROOT, "%.3f", duty);
    }

    /** 12345678 -> "12.3M" */
    static String count(long n) {
        if (n < 1_000L) {
            return Long.toString(n);
        }
        if (n < 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fK", n / 1.0e3);
        }
        if (n < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", n / 1.0e6);
        }
        if (n < 1_000_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fG", n / 1.0e9);
        }
        return String.format(Locale.ROOT, "%.1fT", n / 1.0e12);
    }

    /** 毫秒 -> "mm:ss" 或 "h:mm:ss"。 */
    static String clock(long millis) {
        long total = millis / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** 毫秒 -> 秒，保留一位小数。 */
    static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
