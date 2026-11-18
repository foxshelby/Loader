package com.cpuload;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令行参数解析（同时支持从 properties 配置文件读取）。 */
final class CliOptions {

    private static final Set<String> FLAGS = new HashSet<String>(
            Arrays.asList("help", "version", "verbose", "quiet"));

    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "target", "threads", "duration", "period", "workload", "status-interval",
            "config", "help", "version", "verbose", "quiet"));

    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();

    static {
        ALIASES.put("t", "target");
        ALIASES.put("n", "threads");
        ALIASES.put("d", "duration");
        ALIASES.put("p", "period");
        ALIASES.put("w", "workload");
        ALIASES.put("c", "config");
        ALIASES.put("h", "help");
        ALIASES.put("q", "quiet");
        ALIASES.put("v", "verbose");
        ALIASES.put("status", "status-interval");
        ALIASES.put("workers", "threads");
        ALIASES.put("time", "duration");
    }

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours)?$");

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

    LoadConfig toConfig() throws ConfigException {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        Map<String, String> merged = defaults();

        String configPath = values.get("config");
        if (configPath != null && !configPath.isEmpty()) {
            loadConfigFile(merged, configPath);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!"config".equals(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (values.containsKey("quiet")) {
            merged.put("status-interval", "0");
        }

        double target = parsePercent(merged.get("target"));
        int threads = parseThreads(merged.get("threads"), cpuCount);
        long duration = parseDuration(merged.get("duration"));
        int period = parseInt(merged.get("period"), "period", 10, 5000);
        WorkloadType workload = WorkloadType.parse(merged.get("workload"));
        int statusInterval = parseInt(merged.get("status-interval"), "status-interval", 0, 3600);
        boolean verbose = Boolean.parseBoolean(merged.get("verbose"));

        return new LoadConfig(target, threads, duration, period, workload, statusInterval, verbose, cpuCount);
    }

    private static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("target", "100");
        map.put("threads", "0");
        map.put("duration", "inf");
        map.put("period", "100");
        map.put("workload", "mix");
        map.put("status-interval", "2");
        map.put("verbose", "false");
        return map;
    }

    private static void loadConfigFile(Map<String, String> target, String path) throws ConfigException {
        Path file = Paths.get(path);
        if (!Files.isReadable(file)) {
            throw new ConfigException("配置文件不存在或不可读: " + path);
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // 用 Reader 读取，保证 UTF-8 中文注释不会乱码
            props.load(reader);
        } catch (Exception e) {
            throw new ConfigException("读取配置文件失败: " + path + "（" + e.getMessage() + "）");
        }
        for (String key : props.stringPropertyNames()) {
            String name = canonical(key);
            if ("config".equals(name) || "help".equals(name) || "version".equals(name) || !KNOWN.contains(name)) {
                throw new ConfigException("配置文件包含未知配置项: " + key);
            }
            target.put(name, props.getProperty(key).trim());
        }
    }

    private static double parsePercent(String raw) throws ConfigException {
        if (raw == null) {
            throw new ConfigException("缺少目标使用率");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if ("max".equals(value) || "full".equals(value) || "all".equals(value)) {
            return 100.0;
        }
        double percent;
        try {
            percent = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("--target 需要一个 1-100 的数字，收到: " + raw);
        }
        if (percent <= 0.0 || percent > 100.0) {
            throw new ConfigException("--target 必须在 1 到 100 之间，收到: " + raw);
        }
        return percent;
    }

    private static int parseThreads(String raw, int cpuCount) throws ConfigException {
        String value = raw == null ? "0" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "0".equals(value) || "auto".equals(value)) {
            return cpuCount;
        }
        int threads;
        try {
            threads = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("--threads 需要一个整数（0 表示按核心数自动），收到: " + raw);
        }
        if (threads < 1) {
            throw new ConfigException("--threads 必须大于 0");
        }
        if (threads > 4096) {
            throw new ConfigException("--threads 过大（上限 4096）");
        }
        return threads;
    }

    private static long parseDuration(String raw) throws ConfigException {
        String value = raw == null ? "inf" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "0".equals(value) || "inf".equals(value) || "infinite".equals(value)
                || "forever".equals(value) || "none".equals(value) || "off".equals(value)) {
            return 0L;
        }
        Matcher matcher = DURATION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new ConfigException("无法识别的时长 \"" + raw + "\"，示例: 30s、5m、2h、0（一直运行）");
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "s" : matcher.group(2);
        double millis;
        if (unit.startsWith("ms")) {
            millis = amount;
        } else if (unit.startsWith("s")) {
            millis = amount * 1000.0;
        } else if (unit.startsWith("h")) {
            millis = amount * 3_600_000.0;
        } else {
            millis = amount * 60_000.0;
        }
        long rounded = Math.round(millis);
        return rounded <= 0L ? 0L : rounded;
    }

    private static int parseInt(String raw, String field, int min, int max) throws ConfigException {
        if (raw == null) {
            throw new ConfigException("缺少参数 " + field);
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
        lines.add("CPU Loader " + Main.VERSION + " - 可配置目标使用率的 Java CPU 负载工具（Java 8+）");
        lines.add("");
        lines.add("用法:");
        lines.add("  java -jar cpu-loader.jar [选项]");
        lines.add("");
        lines.add("选项:");
        lines.add("  -t, --target <百分比>       目标整机 CPU 使用率，1-100，默认 100");
        lines.add("  -n, --threads <数量>        工作线程数，0 表示按逻辑核心数自动，默认 0");
        lines.add("  -d, --duration <时长>       运行时长，如 30s、5m、2h；0 或 inf 表示一直运行，默认 inf");
        lines.add("  -p, --period <毫秒>         占空比控制周期，默认 100");
        lines.add("  -w, --workload <类型>       负载类型: mix(默认) / int / float / mem / crypto");
        lines.add("      --status-interval <秒>  状态输出间隔，0 表示关闭，默认 2");
        lines.add("  -q, --quiet                 不输出周期性状态");
        lines.add("  -c, --config <文件>         读取 properties 配置文件，命令行参数优先于文件");
        lines.add("  -v, --verbose               输出更多调试信息");
        lines.add("  -h, --help                  显示本帮助");
        lines.add("      --version               显示版本号");
        lines.add("");
        lines.add("示例:");
        lines.add("  java -jar cpu-loader.jar --target 60");
        lines.add("  java -jar cpu-loader.jar -t 45 -n 8 -d 10m");
        lines.add("  java -jar cpu-loader.jar -t 80 -w crypto -d 5m");
        lines.add("  java -jar cpu-loader.jar --config config.properties");
        lines.add("");
        lines.add("说明:");
        lines.add("  target 指整机 CPU 使用率。每线程占空比 = target% × 逻辑核心数 ÷ 线程数，");
        lines.add("  因此当 target 超过“线程数 ÷ 核心数 × 100%”时无法达到，程序会给出提示。");
        lines.add("  运行中按 Ctrl+C 可安全停止，程序会打印本次运行的实测使用率。");
        lines.add("");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
