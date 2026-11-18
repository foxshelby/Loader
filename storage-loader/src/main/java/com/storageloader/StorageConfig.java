package com.storageloader;

import java.nio.file.Path;

/** 不可变的运行配置。 */
final class StorageConfig {

    /** 数据文件放在哪个目录下（即 --path 指定的目录）。 */
    final Path path;
    /** 目标磁盘使用率（%），与 sizeBytes 二选一。 */
    final Double targetPercent;
    /** 要写入的字节数，与 targetPercent 二选一。 */
    final Long sizeBytes;
    /** 单个文件的最大字节数。 */
    final long fileSizeBytes;
    /** 必须保留的可用空间。 */
    final long reserveBytes;
    /** 使用率硬上限（%）。 */
    final double maxUsagePercent;
    /** 进度输出间隔（秒）。 */
    final int statusIntervalSeconds;
    /** 只显示计划，不写入。 */
    final boolean dryRun;
    /** 清理模式。 */
    final boolean clean;
    /** 不输出进度。 */
    final boolean quiet;

    StorageConfig(Path path, Double targetPercent, Long sizeBytes, long fileSizeBytes, long reserveBytes,
                  double maxUsagePercent, int statusIntervalSeconds, boolean dryRun, boolean clean, boolean quiet) {
        this.path = path;
        this.targetPercent = targetPercent;
        this.sizeBytes = sizeBytes;
        this.fileSizeBytes = fileSizeBytes;
        this.reserveBytes = reserveBytes;
        this.maxUsagePercent = maxUsagePercent;
        this.statusIntervalSeconds = statusIntervalSeconds;
        this.dryRun = dryRun;
        this.clean = clean;
        this.quiet = quiet;
    }

    /** 本工具实际写文件的子目录，清理时也只动这里。 */
    Path dataDir() {
        return path.resolve("storage-loader-data");
    }
}
