package com.cpuload;

/** 不可变的运行配置。 */
final class LoadConfig {

    /** 目标整机 CPU 使用率，1-100。 */
    final double targetPercent;
    /** 工作线程数。 */
    final int threads;
    /** 运行时长（毫秒），0 表示一直运行。 */
    final long durationMillis;
    /** 占空比控制周期（毫秒）。 */
    final int periodMillis;
    /** 负载类型。 */
    final WorkloadType workload;
    /** 状态输出间隔（秒），0 表示关闭。 */
    final int statusIntervalSeconds;
    /** 是否输出调试信息。 */
    final boolean verbose;
    /** 逻辑核心数。 */
    final int cpuCount;

    LoadConfig(double targetPercent, int threads, long durationMillis, int periodMillis,
               WorkloadType workload, int statusIntervalSeconds, boolean verbose, int cpuCount) {
        this.targetPercent = targetPercent;
        this.threads = threads;
        this.durationMillis = durationMillis;
        this.periodMillis = periodMillis;
        this.workload = workload;
        this.statusIntervalSeconds = statusIntervalSeconds;
        this.verbose = verbose;
        this.cpuCount = cpuCount;
    }

    long periodNanos() {
        return periodMillis * 1_000_000L;
    }

    /** 每个工作线程需要保持的 CPU 占空比（0~1）。 */
    double perThreadDuty() {
        double duty = (targetPercent / 100.0) * cpuCount / threads;
        if (duty < 0.0) {
            return 0.0;
        }
        if (duty > 1.0) {
            return 1.0;
        }
        return duty;
    }

    /** 在给定线程数下，本工具最多能贡献的整机使用率（百分比）。 */
    double maxAchievablePercent() {
        return Math.min(1.0, threads / (double) cpuCount) * 100.0;
    }
}
