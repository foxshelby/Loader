package com.cpuload;

/** 统一输出，避免多个线程的输出互相穿插。 */
final class Log {
    private static final Object LOCK = new Object();

    private Log() {
    }

    static void out(String message) {
        synchronized (LOCK) {
            System.out.println(message);
            System.out.flush();
        }
    }

    static void blank() {
        out("");
    }

    static void err(String message) {
        synchronized (LOCK) {
            System.err.println(message);
            System.err.flush();
        }
    }
}
