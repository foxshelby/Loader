package com.cpuload;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 各种“烧 CPU”的负载实现。 */
final class Workloads {

    private Workloads() {
    }

    static Workload create(WorkloadType type) {
        switch (type) {
            case INT:
                return new IntWorkload();
            case FLOAT:
                return new FloatWorkload();
            case MEM:
                return new MemoryWorkload();
            case CRYPTO:
                return new CryptoWorkload();
            case MIX:
            default:
                return new MixedWorkload();
        }
    }

    /** 防止 JIT 把“没有副作用”的计算优化掉。 */
    private static final class Sink {
        static volatile long l;
        static volatile double d;
    }

    /** 每轮内层循环的迭代次数：足够大以摊薄 nanoTime() 的开销，又足够小以保证截止时间的精度。 */
    private static final int CHUNK = 4096;

    /** 整数位运算，纯 ALU。 */
    private static final class IntWorkload implements Workload {
        private long state = 0x2545F4914F6CDD1DL;

        @Override
        public long runUntil(long deadlineNanos) {
            long x = state;
            long units = 0L;
            do {
                for (int i = 0; i < CHUNK; i++) {
                    x ^= x >>> 12;
                    x ^= x << 25;
                    x ^= x >>> 27;
                    x *= 0x2545F4914F6CDD1DL;
                }
                units += CHUNK;
            } while (System.nanoTime() < deadlineNanos);
            state = x;
            Sink.l = x;
            return units;
        }
    }

    /** 混合：整数 LCG + 位混淆 + 浮点运算，最接近通用业务负载。 */
    private static final class MixedWorkload implements Workload {
        private long x = 0x9E3779B97F4A7C15L;
        private double y = 1.234567;

        @Override
        public long runUntil(long deadlineNanos) {
            long lx = x;
            double ly = y;
            long units = 0L;
            do {
                for (int i = 0; i < CHUNK; i++) {
                    lx = lx * 6364136223846793005L + 1442695040888963407L;
                    long t = lx;
                    t ^= t >>> 33;
                    t *= 0xFF51AFD7ED558CCDL;
                    t ^= t >>> 33;
                    ly = ly * 1.0000001 + (t & 0xFFFF) * 1.0e-6;
                    if ((i & 511) == 0) {
                        ly = Math.sqrt(Math.abs(ly)) + 0.5;
                    }
                }
                units += CHUNK;
            } while (System.nanoTime() < deadlineNanos);
            x = lx;
            y = ly;
            Sink.l = lx ^ (long) ly;
            return units;
        }
    }

    /** 浮点三角函数，主要打 FPU / 数学库。 */
    private static final class FloatWorkload implements Workload {
        private double y = 0.7853981633974483;

        @Override
        public long runUntil(long deadlineNanos) {
            double ly = y;
            long units = 0L;
            do {
                for (int i = 0; i < 512; i++) {
                    ly = Math.sin(ly) + Math.cos(ly * 0.5) + Math.sqrt(Math.abs(ly) + 1.0) * 0.5;
                    if (ly > 1.0e6 || ly < -1.0e6 || Double.isNaN(ly)) {
                        ly = 0.5;
                    }
                }
                units += 512;
            } while (System.nanoTime() < deadlineNanos);
            y = ly;
            Sink.d = ly;
            return units;
        }
    }

    /** 内存读写，制造缓存/内存带宽压力。 */
    private static final class MemoryWorkload implements Workload {
        private final byte[] buffer = new byte[1024 * 1024];
        private long checksum;

        @Override
        public long runUntil(long deadlineNanos) {
            byte[] b = buffer;
            long sum = checksum;
            long units = 0L;
            do {
                for (int i = 0; i < b.length; i += 64) {
                    b[i] = (byte) (i + units);
                }
                for (int i = 0; i < b.length; i += 64) {
                    sum += b[i];
                }
                units += b.length / 64;
            } while (System.nanoTime() < deadlineNanos);
            checksum = sum;
            Sink.l = sum;
            return units;
        }
    }

    /** SHA-256 摘要，同时带来少量内存分配。 */
    private static final class CryptoWorkload implements Workload {
        private final MessageDigest digest;
        private final byte[] block = new byte[4096];
        private long counter;

        CryptoWorkload() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
            }
        }

        @Override
        public long runUntil(long deadlineNanos) {
            MessageDigest md = digest;
            byte[] b = block;
            long n = counter;
            long units = 0L;
            int last = 0;
            do {
                b[(int) (n & (b.length - 1))] = (byte) n;
                md.update(b);
                byte[] out = md.digest();
                last = out[0] & 0xFF;
                n++;
                units += b.length;
            } while (System.nanoTime() < deadlineNanos);
            counter = n;
            Sink.l = n ^ last;
            return units;
        }
    }
}
