package com.storageloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令行参数解析。 */
final class CliOptions {

    private static final Set<String> FLAGS = new HashSet<String>(
            Arrays.asList("help", "version", "clean", "dry-run", "quiet"));

    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "path", "target", "size", "file-size", "reserve", "max-usage", "status-interval",
            "clean", "dry-run", "quiet", "help", "version"));

    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();

    static {
        ALIASES.put("p", "path");
        ALIASES.put("t", "target");
        ALIASES.put("s", "size");
        ALIASES.put("f", "file-size");
        ALIASES.put("h", "help");
        ALIASES.put("q", "quiet");
        ALIASES.put("dir", "path");
    }

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(b|k|kb|m|mb|g|gb|t|tb)$");

    private final Map<String, String> values = new LinkedHashMap<String, String>();
    boolean help;
    boolean version;

    private CliOptions() {
    }

    static CliOptions parse(String[] args) throws ConfigException {
        CliOptions result = new CliOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            if (!arg.startsWith("-")) {
                throw new ConfigException("无法识别的参数 \"" + arg + "\"，选项需以 -- 或 - 开头（用 --help 查看用法）");
            }
            String body = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
            String name;
            String value = null;
            int equals = body.indexOf('=');
            if (equals >= 0) {
                name = body.substring(0, equals);
                value = body.substring(equals + 1);
            } else {
                name = body;
            }
            name = canonical(name);
            if (!KNOWN.contains(name)) {
                throw new ConfigException("未知选项 \"" + arg + "\"（用 --help 查看用法）");
            }
            if (FLAGS.contains(name)) {
                if (value != null) {
                    throw new ConfigException("选项 --" + name + " 不接受取值");
                }
                if ("help".equals(name)) {
                    result.help = true;
                } else if ("version".equals(name)) {
                    result.version = true;
                } else {
                    result.values.put(name, "true");
                }
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    throw new ConfigException("选项 --" + name + " 缺少取值");
                }
                value = args[++i];
            }
            result.values.put(name, value.trim());
        }
        return result;
    }

    private static String canonical(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        String alias = ALIASES.get(name);
        return alias != null ? alias : name;
    }

    StorageConfig toConfig() throws ConfigException {
        String rawPath = values.get("path");
        if (rawPath == null || rawPath.isEmpty()) {
            throw new ConfigException("缺少 --path 参数：指定数据放在哪个目录，例如 --path D:\\disk-test");
        }
        Path path = Paths.get(rawPath).toAbsolutePath().normalize();
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new ConfigException("--path 不是目录: " + path);
        }

        boolean clean = values.containsKey("clean");
        boolean dryRun = values.containsKey("dry-run");
        boolean quiet = values.containsKey("quiet");
        int statusInterval = parseInt(values.get("status-interval"), "status-interval", 1, 3600, 3);
        long fileSize = values.containsKey("file-size")
                ? parseSize(values.get("file-size"), "file-size") : (1L << 30);
        long reserve = values.containsKey("reserve")
                ? parseSize(values.get("reserve"), "reserve") : (1L << 30);
        double maxUsage = values.containsKey("max-usage")
                ? parsePercent(values.get("max-usage"), "max-usage") : 95.0;

        Double target = null;
        Long size = null;
        if (!clean) {
            boolean hasTarget = values.containsKey("target");
            boolean hasSize = values.containsKey("size");
            if (hasTarget && hasSize) {
                throw new ConfigException("--target 和 --size 只能二选一");
            }
            if (!hasTarget && !hasSize) {
                throw new ConfigException("需要 --target 或 --size 之一；清理请用 --clean");
            }
            if (hasTarget) {
                target = parsePercent(values.get("target"), "target");
                if (target.doubleValue() > maxUsage) {
                    throw new ConfigException("--target（" + target + "%）不能大于 --max-usage（" + maxUsage + "%）");
                }
            } else {
                size = parseSize(values.get("size"), "size");
            }
        }

        return new StorageConfig(path, target, size, fileSize, reserve, maxUsage,
                statusInterval, dryRun, clean, quiet);
    }

    private static double parsePercent(String raw, String field) throws ConfigException {
        String value = raw.trim();
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        double percent;
        try {
            percent = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("--" + field + " 需要一个 0-100 的百分比，收到: " + raw);
        }
        if (percent <= 0.0 || percent > 100.0) {
            throw new ConfigException("--" + field + " 必须在 0 到 100 之间，收到: " + raw);
        }
        return percent;
    }

    private static long parseSize(String raw, String field) throws ConfigException {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = SIZE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new ConfigException("--" + field + " 需要一个带单位的大小，例如 500M、20G、1.5T，收到: " + raw);
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2);
        long factor;
        if (unit.startsWith("t")) {
            factor = 1L << 40;
        } else if (unit.startsWith("g")) {
            factor = 1L << 30;
        } else if (unit.startsWith("m")) {
            factor = 1L << 20;
        } else if (unit.startsWith("k")) {
            factor = 1L << 10;
        } else {
            factor = 1L;
        }
        long bytes = Math.round(amount * factor);
        if (bytes <= 0L) {
            throw new ConfigException("--" + field + " 必须大于 0");
        }
        return bytes;
    }

    private static int parseInt(String raw, String field, int min, int max, int defaultValue) throws ConfigException {
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("--" + field + " 需要一个整数，收到: " + raw);
        }
        if (value < min || value > max) {
            throw new ConfigException("--" + field + " 必须在 " + min + " 到 " + max + " 之间，收到: " + raw);
        }
        return value;
    }

    static String helpText() {
        List<String> lines = new ArrayList<String>();
        lines.add("");
        lines.add("Storage Loader " + Main.VERSION + " - 用真实文件占用磁盘空间，把存储使用率抬到指定水平（Java 8+）");
        lines.add("");
        lines.add("用法:");
        lines.add("  java -jar storage-loader.jar --path <目录> --target <百分比> [选项]");
        lines.add("  java -jar storage-loader.jar --path <目录> --size <大小>     [选项]");
        lines.add("  java -jar storage-loader.jar --path <目录> --clean");
        lines.add("");
        lines.add("选项:");
        lines.add("  -p, --path <目录>          数据放在哪个目录（必填），文件写入 <目录>/storage-loader-data/");
        lines.add("  -t, --target <百分比>      把所在磁盘的使用率提升到该百分比");
        lines.add("  -s, --size <大小>          写入指定大小的数据，如 500M、20G、1.5T");
        lines.add("  -f, --file-size <大小>     单个文件的最大大小，默认 1G");
        lines.add("      --reserve <大小>       至少保留的可用空间，默认 1G");
        lines.add("      --max-usage <百分比>   使用率硬上限，默认 95");
        lines.add("      --status-interval <秒> 进度输出间隔，默认 3");
        lines.add("      --dry-run              只显示计划，不实际写入");
        lines.add("      --clean                删除本工具生成的文件并退出");
        lines.add("  -q, --quiet                不输出进度");
        lines.add("  -h, --help                 显示本帮助");
        lines.add("      --version              显示版本号");
        lines.add("");
        lines.add("示例:");
        lines.add("  java -jar storage-loader.jar --path D:\\disk-test --target 80");
        lines.add("  java -jar storage-loader.jar --path /data/disk-test --size 50G");
        lines.add("  java -jar storage-loader.jar --path D:\\disk-test --target 80 --dry-run");
        lines.add("  java -jar storage-loader.jar --path D:\\disk-test --clean");
        lines.add("");
        lines.add("说明:");
        lines.add("  --target 与 --size 二选一。工具始终保证使用率不超过 --max-usage，");
        lines.add("  并且至少保留 --reserve 的可用空间，避免把系统盘写死。");
        lines.add("  写入的是真实数据（不是稀疏文件），因此占用会真实反映在磁盘上。");
        lines.add("  中途 Ctrl+C 可以停止，已写入的文件会保留，用 --clean 清理。");
        lines.add("");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
