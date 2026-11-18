package com.storageloader;

/** 统一输出，保证进度行和提示不会互相穿插。 */
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
