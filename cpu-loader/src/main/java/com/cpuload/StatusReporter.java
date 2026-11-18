package com.cpuload;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** 周期性打印运行状态。 */
final class StatusReporter implements Runnable {

    private final LoadConfig config;
    private final SysMetrics metrics;
    private final AtomicBoolean running;
    private final List<CpuBurner> burners;
    private final LongAdder workDone;
    private final long startWallNanos;
    private final long startProcessCpuNanos;
    private final long intervalNanos;

    StatusReporter(LoadConfig config, SysMetrics metrics, AtomicBoolean running, List<CpuBurner> burners,
                   LongAdder workDone, long startWallNanos, long startProcessCpuNanos) {
        this.config = config;
        this.metrics = metrics;
        this.running = running;
        this.burners = burners;
        this.workDone = workDone;
        this.startWallNanos = startWallNanos;
        this.startProcessCpuNanos = startProcessCpuNanos;
        this.intervalNanos = config.statusIntervalSeconds * 1_000_000_000L;
    }

    @Override
    public void run() {
        while (running.get()) {
            if (!sleepQuietly(intervalNanos)) {
                return;
            }
            if (!running.get()) {
                return;
            }
            report();
        }
    }

    private void report() {
        long nowWall = System.nanoTime();
        long nowCpu = metrics.processCpuTimeNanos();

        double processLoad = -1.0;
        if (startProcessCpuNanos >= 0 && nowCpu >= 0 && nowWall > startWallNanos) {
            processLoad = (nowCpu - startProcessCpuNanos) / (double) (nowWall - startWallNanos) / config.cpuCount;
        }
        double systemLoad = metrics.systemCpuLoad();

        double dutyTarget = 0.0;
        double dutyActual = 0.0;
        boolean saturated = false;
        for (int i = 0; i < burners.size(); i++) {
            CpuBurner burner = burners.get(i);
            dutyTarget += burner.targetDuty();
            dutyActual += burner.achievedDuty();
            saturated = saturated || burner.saturated();
        }
        if (!burners.isEmpty()) {
            dutyTarget /= burners.size();
            dutyActual /= burners.size();
        }

        StringBuilder line = new StringBuilder(120);
        line.append('[').append(Fmt.clock((nowWall - startWallNanos) / 1_000_000L)).append("] ");
        line.append("目标 ").append(Fmt.pct(config.targetPercent / 100.0));
        line.append(" | 本进程 ").append(processLoad < 0 ? "n/a" : Fmt.pct(processLoad));
        line.append(" | 系统 ").append(systemLoad < 0 ? "n/a" : Fmt.pct(systemLoad));
        line.append(" | 占空比 ").append(Fmt.duty(dutyActual)).append('/').append(Fmt.duty(dutyTarget));
        line.append(" | 工作量 ").append(Fmt.count(workDone.sum()));
        if (saturated) {
            line.append(" | 提示: 占空比已拉满，实测仍低于目标");
        }
        Log.out(line.toString());
    }

    private static boolean sleepQuietly(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return true;
            }
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
