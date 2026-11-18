package com.cpuload;

/** 一段可被“烧”指定时长的计算负载。每个工作线程持有独立实例。 */
interface Workload {

    /**
     * 持续执行计算，直到 {@code System.nanoTime()} 达到 {@code deadlineNanos}。
     *
     * @return 本次完成的工作量（单位由实现自行定义，仅用于展示进度）
     */
    long runUntil(long deadlineNanos);
}
