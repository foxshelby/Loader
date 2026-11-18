package com.cpuload;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个工作线程：通过“占空比”控制自己贡献的 CPU 使用率。
 *
 * <p>核心思路：把时间切成固定周期（默认 100ms），每个周期按当前占空比 {@code duty}
 * 先满载计算 {@code duty × period}，剩余时间不占 CPU。</p>
 *
 * <p>空闲时间不立即睡眠，而是记入“睡眠债务”，攒到一定量再一次性睡掉。睡眠时统计<b>实际</b>
 * 睡掉的时长并从债务中扣除，因此操作系统对 sleep 的取整/超时会被自动补偿：某一觉睡多了，
 * 后面就少睡同样多。长期看累计空闲时间严格等于请求值，CPU 使用率不因平台睡眠粒度而偏移。</p>
 *
 * <p>每个控制窗口结束时，用 JVM 提供的“本线程 CPU 时间”算出真实达成率，再用积分控制修正
 * 占空比，抵消计算超时、被系统抢占等因素，使实测值收敛到目标值。</p>
 */
final class CpuBurner implements Runnable {

    /** 积分控制增益。 */
    private static final double GAIN = 0.6;
    /** 误差死区，避免来回抖动。 */
    private static final double DEADBAND = 0.01;
    /** 小于这个值的忙碌时间放不下，直接跳过。 */
    private static final long MIN_BURN_NANOS = 1_000_000L;
    /** 债务攒到这么多才值得睡一觉。 */
    private static final long MIN_SLEEP_NANOS = 2_000_000L;
    /** 单次睡眠上限，保证停止信号能被及时响应。 */
    private static final long MAX_SLEEP_NANOS = 250_000_000L;

    private final Workload workload;
    private final AtomicBoolean running;
    private final LongAdder workDone;
    private final SysMetrics metrics;
    private final long periodNanos;
    private final double targetDuty;
    private final boolean debug;
    private final java.util.Random random = new java.util.Random();

    private volatile double duty;
    private volatile double achievedDuty;
    private volatile boolean saturated;

    CpuBurner(LoadConfig config, Workload workload, AtomicBoolean running, LongAdder workDone,
              SysMetrics metrics, boolean debug) {
        this.workload = workload;
        this.running = running;
        this.workDone = workDone;
        this.metrics = metrics;
        // 每个线程的控制周期加 ±5% 抖动，避免所有线程长时间保持同一相位
        long base = config.periodNanos();
        this.periodNanos = base + (long) (base * 0.05 * (random.nextDouble() * 2.0 - 1.0));
        this.targetDuty = config.perThreadDuty();
        this.duty = this.targetDuty;
        this.achievedDuty = this.targetDuty;
        this.debug = debug;
    }

    double targetDuty() {
        return targetDuty;
    }

    double achievedDuty() {
        return achievedDuty;
    }

    boolean saturated() {
        return saturated;
    }

    @Override
    public void run() {
        // 随机错开起始相位：避免所有工作线程同时睡、同时醒、同时抢 CPU 核心。
        // （相位锁死会让“燃烧阶段”被抢占，实际 CPU 使用率低于目标。）
        long stagger = (long) (periodNanos * random.nextDouble());
        if (stagger > 0L) {
            sleepFor(stagger);
        }

        final long horizonNanos = Math.max(periodNanos * 5L, 800_000_000L);
        long controlWall = System.nanoTime();
        long controlCpu = metrics.currentThreadCpuTime();
        long busyWall = 0L;
        long sleepDebt = 0L;
        long units = 0L;

        while (running.get()) {
            long windowStart = System.nanoTime();
            long busyNanos = (long) (periodNanos * duty);
            if (busyNanos > periodNanos) {
                busyNanos = periodNanos;
            }
            if (busyNanos >= MIN_BURN_NANOS) {
                units += workload.runUntil(windowStart + busyNanos);
                busyWall += System.nanoTime() - windowStart;
            }

            long idleNanos = (windowStart + periodNanos) - System.nanoTime();
            if (idleNanos > 0) {
                sleepDebt += idleNanos;
                while (sleepDebt >= MIN_SLEEP_NANOS && running.get()) {
                    long nap = Math.min(sleepDebt, MAX_SLEEP_NANOS);
                    long actual = sleepFor(nap);
                    if (actual < 0L) {
                        break;
                    }
                    // 关键：按“实际睡了多久”还债，系统睡超时的部分自然会让下一轮少睡
                    sleepDebt -= actual;
                    if (sleepDebt < -periodNanos) {
                        // 意外超长的睡眠（被抢占、系统卡顿等）最多只透支一个周期，
                        // 否则接下来会连续满载补偿，干扰控制器的观测。
                        sleepDebt = -periodNanos;
                    }
                }
            } else {
                // 本周期没有空闲时间（占空比已拉满），旧的债务作废
                sleepDebt = 0L;
            }

            long now = System.nanoTime();
            if (now - controlWall >= horizonNanos) {
                long cpuNow = metrics.currentThreadCpuTime();
                double achieved;
                if (controlCpu >= 0 && cpuNow >= 0 && now > controlWall) {
                    // 用线程真实 CPU 时间衡量，不受被系统抢占的影响
                    achieved = (cpuNow - controlCpu) / (double) (now - controlWall);
                } else if (now > controlWall) {
                    achieved = busyWall / (double) (now - controlWall);
                } else {
                    achieved = targetDuty;
                }
                achieved = Math.max(0.0, Math.min(1.0, achieved));
                // 平滑一下，避免状态行里的占空比来回跳
                achievedDuty = achievedDuty * 0.7 + achieved * 0.3;
                double next = nextDuty(achieved);
                if (debug) {
                    Log.out(String.format(java.util.Locale.ROOT,
                            "[dbg] wall=%.0fms cpu=%.0fms achieved=%.3f duty=%.3f -> %.3f",
                            (now - controlWall) / 1.0e6,
                            (cpuNow >= 0 && controlCpu >= 0 ? (cpuNow - controlCpu) / 1.0e6 : -1.0),
                            achieved, duty, next));
                }
                duty = next;
                workDone.add(units);
                units = 0L;
                controlWall = now;
                controlCpu = cpuNow;
                busyWall = 0L;
            }
        }
        workDone.add(units);
    }

    /** 积分控制：占空比本身是“误差的累加器”，因此稳态无静差。 */
    private double nextDuty(double achieved) {
        double error = targetDuty - achieved;
        if (Math.abs(error) <= DEADBAND) {
            saturated = false;
            return duty;
        }
        double next = duty + GAIN * error;
        if (next >= 1.0) {
            next = 1.0;
            saturated = achieved < targetDuty - 0.02;
        } else if (next <= 0.0) {
            next = 0.0;
            saturated = false;
        } else {
            saturated = false;
        }
        return next;
    }

    /** @return 实际睡眠时长（纳秒）；被中断时返回 -1。 */
    private static long sleepFor(long nanos) {
        long start = System.nanoTime();
        long millis = nanos / 1_000_000L;
        int rest = (int) (nanos % 1_000_000L);
        try {
            Thread.sleep(millis, rest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        }
        return System.nanoTime() - start;
    }
}
