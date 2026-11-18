package com.cpuload;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;

/**
 * 系统指标读取。
 *
 * <p>JDK 不同版本的可选管理接口各不相同（例如 {@code getCpuLoad()} 是 JDK 14 才有的，
 * JDK 8 只有 {@code getSystemCpuLoad()}），因此这里全部通过反射调用，缺失时优雅降级。</p>
 */
final class SysMetrics {

    private final Object osBean;
    private final Method processCpuTimeMethod;
    private final Method systemCpuLoadMethod;
    private final Object threadBean;
    private final boolean threadCpuTimeSupported;

    SysMetrics() {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        Object candidate = null;
        Method processCpuTime = null;
        Method systemCpuLoad = null;
        try {
            Class<?> iface = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (iface.isInstance(bean)) {
                candidate = bean;
                processCpuTime = findMethod(iface, "getProcessCpuTime");
                // JDK 14+ 用 getCpuLoad()，JDK 8~13 用 getSystemCpuLoad()
                systemCpuLoad = findMethod(iface, "getCpuLoad");
                if (systemCpuLoad == null) {
                    systemCpuLoad = findMethod(iface, "getSystemCpuLoad");
                }
            }
        } catch (Throwable ignored) {
            candidate = null;
        }
        this.osBean = candidate;
        this.processCpuTimeMethod = processCpuTime;
        this.systemCpuLoadMethod = systemCpuLoad;

        Object tb = ManagementFactory.getThreadMXBean();
        boolean supported = false;
        try {
            ThreadMXBean threadBean = (ThreadMXBean) tb;
            if (threadBean.isCurrentThreadCpuTimeSupported()) {
                if (!threadBean.isThreadCpuTimeEnabled()) {
                    threadBean.setThreadCpuTimeEnabled(true);
                }
                supported = threadBean.isThreadCpuTimeEnabled();
            }
        } catch (Throwable ignored) {
            supported = false;
        }
        this.threadBean = tb;
        this.threadCpuTimeSupported = supported;
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 当前进程累计消耗的 CPU 时间（纳秒），不可用时返回 -1。 */
    long processCpuTimeNanos() {
        if (osBean == null || processCpuTimeMethod == null) {
            return -1L;
        }
        try {
            Object value = processCpuTimeMethod.invoke(osBean);
            if (value instanceof Long) {
                long nanos = (Long) value;
                return nanos < 0L ? -1L : nanos;
            }
        } catch (Throwable ignored) {
            // 忽略，返回 -1
        }
        return -1L;
    }

    /** 整机 CPU 使用率（0~1），不可用时返回 -1。 */
    double systemCpuLoad() {
        if (osBean != null && systemCpuLoadMethod != null) {
            try {
                Object value = systemCpuLoadMethod.invoke(osBean);
                if (value instanceof Double) {
                    double load = (Double) value;
                    if (load >= 0.0) {
                        return Math.min(load, 1.0);
                    }
                }
            } catch (Throwable ignored) {
                // 继续尝试回退方案
            }
        }
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            double average = bean.getSystemLoadAverage();
            if (average >= 0.0) {
                return Math.min(1.0, average / Runtime.getRuntime().availableProcessors());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return -1.0;
    }

    /** 当前线程累计消耗的 CPU 时间（纳秒），不可用时返回 -1。 */
    long currentThreadCpuTime() {
        if (!threadCpuTimeSupported) {
            return -1L;
        }
        try {
            long nanos = ((ThreadMXBean) threadBean).getCurrentThreadCpuTime();
            return nanos < 0L ? -1L : nanos;
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}
