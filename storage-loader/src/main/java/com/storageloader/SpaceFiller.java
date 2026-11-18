package com.storageloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** 写真实文件占用磁盘空间。 */
final class SpaceFiller {

    /** 写块大小，1MB 在机械盘和 NVMe 上都够用。 */
    private static final int BLOCK_SIZE = 1 << 20;
    /** 每写这么多字节检查一次剩余空间。 */
    private static final long FREE_CHECK_BYTES = 256L << 20;

    private final StorageConfig config;
    private final long startNanos = System.nanoTime();

    private long written;
    private long lastReportNanos = System.nanoTime();
    private long lastReportBytes;
    private int fileCount;

    private SpaceFiller(StorageConfig config) {
        this.config = config;
    }

    static int run(StorageConfig config) throws Exception {
        return new SpaceFiller(config).fill();
    }

    private int fill() throws Exception {
        Path probe = config.path;
        if (!Files.exists(probe)) {
            if (config.dryRun) {
                probe = nearestExistingAncestor(probe);
            } else {
                Files.createDirectories(probe);
            }
        }
        DiskSpace before = DiskSpace.query(probe);

        long requested = computeRequested(before);
        long cap = computeCap(before);
        long need = Math.min(requested, cap);
        boolean capped = need < requested;
        if (need < 0L) {
            need = 0L;
        }

        printPlan(before, requested, need, capped);

        if (need <= 0L) {
            if (requested <= 0L) {
                Log.out("当前使用率已经达到或高于目标，无需写入。");
                return 0;
            }
            Log.out("可用空间不足或已达安全上限，无法再写入。");
            Log.out("可调小 --reserve 或提高 --max-usage（不建议），也可换一个空间更大的磁盘。");
            return 1;
        }
        if (config.dryRun) {
            Log.out("（--dry-run：只显示计划，未写入任何数据）");
            Log.blank();
            return 0;
        }

        Files.createDirectories(config.dataDir());
        boolean complete = writeFiles(need);
        printResult(complete);
        return complete ? 0 : 1;
    }

    /** 目录还不存在时，向上找到一个已存在的祖先来查询磁盘容量。 */
    private static Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current != null ? current : path.getRoot();
    }

    /** 期望写入多少字节。 */
    private long computeRequested(DiskSpace space) throws ConfigException {
        if (config.sizeBytes != null) {
            return config.sizeBytes.longValue();
        }
        if (space.total <= 0L) {
            throw new ConfigException("读不到 " + config.path + " 所在磁盘的容量（网络盘或权限受限），请改用 --size 指定写入量");
        }
        long targetUsed = Math.round(space.total * config.targetPercent.doubleValue() / 100.0);
        return targetUsed - space.used();
    }

    /** 安全上限：使用率不超过 --max-usage，且至少保留 --reserve。 */
    private long computeCap(DiskSpace space) {
        long capByUsage = Long.MAX_VALUE;
        if (space.total > 0L) {
            capByUsage = Math.round(space.total * config.maxUsagePercent / 100.0) - space.used();
        }
        long capByReserve = space.free() - config.reserveBytes;
        return Math.min(capByUsage, capByReserve);
    }

    private void printPlan(DiskSpace before, long requested, long need, boolean capped) {
        Log.blank();
        Log.out("==== 写入计划 ====");
        Log.out("目标目录   : " + config.dataDir());
        if (before.total > 0L) {
            Log.out("磁盘容量   : " + Fmt.size(before.total));
            Log.out("当前使用率 : " + Fmt.pct(before.usagePercent())
                    + "（已用 " + Fmt.size(before.used()) + "，剩余 " + Fmt.size(before.free()) + "）");
        } else {
            Log.out("磁盘容量   : 未知");
        }
        if (config.targetPercent != null) {
            Log.out("目标使用率 : " + Fmt.pct(config.targetPercent.doubleValue()));
        }
        if (need > 0L) {
            Log.out("计划写入   : " + Fmt.size(need) + "（" + fileCountFor(need) + " 个文件，每个最大 "
                    + Fmt.size(config.fileSizeBytes) + "）");
        } else {
            Log.out("计划写入   : 0 B");
        }
        Log.out("安全限制   : 使用率不超过 " + Fmt.pct(config.maxUsagePercent)
                + "，且至少保留 " + Fmt.size(config.reserveBytes) + " 可用空间");
        if (capped && requested > 0L) {
            Log.out("提示       : 受安全限制，写入量已由 " + Fmt.size(requested) + " 缩减为 " + Fmt.size(need) + "。");
            Log.out("             如需更多空间占用，可调小 --reserve 或提高 --max-usage。");
        }
        Log.blank();
    }

    private int fileCountFor(long bytes) {
        long per = Math.max(1L, config.fileSizeBytes);
        return (int) Math.max(1L, (bytes + per - 1L) / per);
    }

    private boolean writeFiles(long total) throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        fillPattern(block);
        long remaining = total;
        long freeCheckAt = FREE_CHECK_BYTES;
        int index = 1;

        Log.out("开始写入 ...");
        Log.blank();

        while (remaining > 0L) {
            long thisFile = Math.min(remaining, Math.max(1L, config.fileSizeBytes));
            Path file = config.dataDir().resolve(String.format(Locale.ROOT, "storage-loader-%04d.bin", index++));
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(block);
                long left = thisFile;
                while (left > 0L) {
                    int chunk = (int) Math.min((long) BLOCK_SIZE, left);
                    // 每块写入不同的头部字节，避免被文件系统去重/压缩"吃掉"
                    stamp(block, written);
                    buffer.clear();
                    buffer.limit(chunk);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    left -= chunk;
                    written += chunk;
                    remaining -= chunk;

                    long now = System.nanoTime();
                    if ((now - lastReportNanos) / 1_000_000_000L >= config.statusIntervalSeconds) {
                        report(total, now);
                    }
                    if (written >= freeCheckAt) {
                        freeCheckAt = written + FREE_CHECK_BYTES;
                        long free = DiskSpace.query(config.dataDir()).free();
                        if (free < config.reserveBytes) {
                            Log.blank();
                            Log.out("警告: 可用空间已低于保留值 " + Fmt.size(config.reserveBytes) + "，提前停止写入。");
                            channel.force(false);
                            fileCount++;
                            return false;
                        }
                    }
                }
                channel.force(false);
            }
            fileCount++;
        }
        return true;
    }

    private void report(long total, long now) {
        if (config.quiet) {
            return;
        }
        long sinceNanos = now - lastReportNanos;
        double speed = sinceNanos > 0L ? (written - lastReportBytes) / (sinceNanos / 1.0e9) : 0.0;
        long remaining = Math.max(0L, total - written);
        String eta = speed > 1.0 ? Fmt.duration((long) (remaining / speed * 1000.0)) : "--";
        String freeText;
        try {
            freeText = Fmt.size(DiskSpace.query(config.path).free());
        } catch (IOException e) {
            freeText = "?";
        }
        double progress = total > 0L ? written * 100.0 / total : 100.0;
        Log.out(String.format(Locale.ROOT, "[%s] %s / %s (%.1f%%) | %s/s | 剩余 %s | 预计 %s",
                Fmt.clock((now - startNanos) / 1_000_000L), Fmt.size(written), Fmt.size(total), progress,
                Fmt.size((long) speed), freeText, eta));
        lastReportNanos = now;
        lastReportBytes = written;
    }

    private void printResult(boolean complete) throws IOException {
        DiskSpace after = DiskSpace.query(config.path);
        double seconds = (System.nanoTime() - startNanos) / 1.0e9;
        double averageSpeed = seconds > 0.0 ? written / seconds : 0.0;

        Log.blank();
        Log.out(complete ? "==== 写入完成 ====" : "==== 写入中止 ====");
        Log.out("目录       : " + config.dataDir());
        Log.out("写入量     : " + Fmt.size(written) + "（" + fileCount + " 个文件）");
        Log.out("耗时       : " + Fmt.duration((long) (seconds * 1000.0))
                + "，平均 " + Fmt.size((long) averageSpeed) + "/s");
        if (after.total > 0L) {
            Log.out("磁盘使用率 : " + Fmt.pct(after.usagePercent())
                    + (config.targetPercent != null
                    ? "（目标 " + Fmt.pct(config.targetPercent.doubleValue()) + "）" : ""));
            Log.out("剩余空间   : " + Fmt.size(after.free()));
        }
        if (!complete) {
            Log.out("说明       : 已写入的文件仍然保留在磁盘上。");
        }
        Log.out("清理命令   : java -jar storage-loader.jar --path " + config.path + " --clean");
        Log.blank();
    }

    /** 每块写入唯一的头部，防止重复数据块被去重存储"压缩"掉空间。 */
    private static void stamp(byte[] block, long offset) {
        for (int i = 0; i < 16; i++) {
            block[i] = (byte) (offset >>> (i * 8));
        }
    }

    private static void fillPattern(byte[] block) {
        long state = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < block.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            block[i] = (byte) (state >>> 56);
        }
    }
}
