package com.storageloader;

import java.util.Locale;

/** 输出格式辅助。容量统一按 1024 进制显示（与 Windows 资源管理器一致）。 */
final class Fmt {
    private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB"};

    private Fmt() {
    }

    /** 字节数 -> "45.2 GB" */
    static String size(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < UNITS.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    /** 80.0 -> "80.0%" */
    static String pct(double percent) {
        return String.format(Locale.ROOT, "%.1f%%", percent);
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

    /** 毫秒 -> "0.4 秒" / "92 秒" / "3 分 12 秒" / "1 小时 5 分"。 */
    static String duration(long millis) {
        if (millis < 10_000L) {
            return String.format(Locale.ROOT, "%.1f 秒", millis / 1000.0);
        }
        long seconds = millis / 1000L;
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60L;
        long restSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + " 分 " + restSeconds + " 秒";
        }
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        return hours + " 小时 " + restMinutes + " 分";
    }
}
