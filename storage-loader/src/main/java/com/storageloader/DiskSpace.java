package com.storageloader;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** 某个路径所在磁盘的容量信息。 */
final class DiskSpace {

    final long total;
    final long usable;

    private DiskSpace(long total, long usable) {
        this.total = total;
        this.usable = usable;
    }

    static DiskSpace query(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        return new DiskSpace(store.getTotalSpace(), store.getUsableSpace());
    }

    long used() {
        return Math.max(0L, total - usable);
    }

    long free() {
        return usable;
    }

    /** 使用率百分比；容量未知时返回 -1。 */
    double usagePercent() {
        if (total <= 0L) {
            return -1.0;
        }
        return used() * 100.0 / total;
    }
}
