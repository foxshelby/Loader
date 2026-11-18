package com.cpuload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** 组织工作线程、状态输出与退出流程。 */
final class LoadRunner {

    private final LoadConfig config;
    private final SysMetrics metrics = new SysMetrics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final LongAdder workDone = new LongAdder();
    private final List<Thread> workers = new ArrayList<Thread>();
    private final List<CpuBurner> burners = new ArrayList<CpuBurner>();

    private volatile long startWallNanos;
    private volatile long startProcessCpuNanos = -1L;

    LoadRunner(LoadConfig config) {
        this.config = config;
    }

    int run() {
        printBanner();

        if (config.verbose) {
            Log.out("进程 CPU 时间统计: " + (metrics.processCpuTimeNanos() >= 0 ? "可用" : "不可用，改用墙钟估算"));
            Log.out("线程 CPU 时间统计: " + (metrics.currentThreadCpuTime() >= 0 ? "可用" : "不可用"));
            Log.blank();
        }

        for (int i = 0; i < config.threads; i++) {
            CpuBurner burner = new CpuBurner(config, Workloads.create(config.workload), running, workDone, metrics,
                    config.verbose && i == 0);
            Thread thread = new Thread(burner, "cpu-loader-" + (i + 1));
            thread.setDaemon(true);
            workers.add(thread);
            burners.add(burner);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, "cpu-loader-shutdown"));

        startWallNanos = System.nanoTime();
        startProcessCpuNanos = metrics.processCpuTimeNanos();
        for (int i = 0; i < workers.size(); i++) {
            workers.get(i).start();
        }

        if (config.statusIntervalSeconds > 0) {
            StatusReporter reporter = new StatusReporter(config, metrics, running, burners, workDone,
                    startWallNanos, startProcessCpuNanos);
            Thread thread = new Thread(reporter, "cpu-loader-status");
            thread.setDaemon(true);
            thread.start();
        }

        waitForDuration();
        finish();
        return 0;
    }

    private void waitForDuration() {
        if (config.durationMillis <= 0L) {
            while (running.get()) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            return;
        }
        long endAt = System.nanoTime() + config.durationMillis * 1_000_000L;
        while (running.get()) {
            long remaining = endAt - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            long sleepMillis = Math.min(200L, Math.max(1L, remaining / 1_000_000L));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 幂等的收尾流程：停止线程、统计、打印汇总。正常结束与 Ctrl+C 都会走这里。 */
    void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        for (int i = 0; i < workers.size(); i++) {
            workers.get(i).interrupt();
        }
        for (int i = 0; i < workers.size(); i++) {
            try {
                workers.get(i).join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long endWall = System.nanoTime();
        long endCpu = metrics.processCpuTimeNanos();
        printSummary(endWall, endCpu);
    }

    private void printSummary(long endWallNanos, long endProcessCpuNanos) {
        long wallNanos = Math.max(0L, endWallNanos - startWallNanos);
        double achieved = -1.0;
        if (startProcessCpuNanos >= 0 && endProcessCpuNanos >= 0 && wallNanos > 0) {
            achieved = (endProcessCpuNanos - startProcessCpuNanos) / (double) wallNanos / config.cpuCount;
        }
        Log.blank();
        Log.out("==== 运行汇总 ====");
        Log.out("运行时长   : " + Fmt.seconds(wallNanos / 1_000_000L) + " 秒");
        Log.out("目标使用率 : " + Fmt.pct(config.targetPercent / 100.0) + "（整机）");
        Log.out("本进程实测 : " + (achieved < 0 ? "n/a" : Fmt.pct(achieved)) + "（整机均值）");
        Log.out("工作线程   : " + config.threads + " 个，每线程占空比 " + Fmt.duty(config.perThreadDuty())
                + "，控制周期 " + config.periodMillis + " ms");
        Log.out("完成工作量 : " + Fmt.count(workDone.sum()) + " 单位（负载类型 " + config.workload.id + "）");
        Log.blank();
    }

    private void printBanner() {
        Log.blank();
        Log.out("CPU Loader " + Main.VERSION + "  (Java " + System.getProperty("java.version")
                + ", " + config.cpuCount + " 个逻辑核心)");
        Log.out("目标整机使用率: " + Fmt.pct(config.targetPercent / 100.0)
                + "   工作线程: " + config.threads
                + "   每线程占空比: " + Fmt.duty(config.perThreadDuty()));
        Log.out("负载类型: " + config.workload.id + "（" + config.workload.description + "）"
                + "   控制周期: " + config.periodMillis + " ms"
                + "   时长: " + (config.durationMillis > 0L
                        ? Fmt.seconds(config.durationMillis) + " 秒" : "持续运行"));
        double maxAchievable = config.maxAchievablePercent();
        if (config.targetPercent > maxAchievable + 0.5) {
            Log.out("提示: " + config.threads + " 个线程最多只能贡献 " + Fmt.pct(maxAchievable / 100.0)
                    + " 的整机使用率；要达到 " + Fmt.pct(config.targetPercent / 100.0)
                    + "，请把 --threads 调到至少 "
                    + (int) Math.ceil(config.targetPercent / 100.0 * config.cpuCount) + "。");
        }
        if (config.threads > config.cpuCount * 4) {
            Log.out("提示: 线程数远大于核心数，线程切换开销会明显增加。");
        }
        Log.out("按 Ctrl+C 可随时停止。");
        Log.blank();
    }
}
