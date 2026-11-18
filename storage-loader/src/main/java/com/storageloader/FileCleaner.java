package com.storageloader;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 删除本工具生成的数据文件。 */
final class FileCleaner {

    private static final String PATTERN = "storage-loader-*.bin";

    private FileCleaner() {
    }

    static int run(StorageConfig config) throws IOException {
        Path dir = config.dataDir();
        Log.blank();
        Log.out("==== 清理 " + dir + " ====");

        if (!Files.isDirectory(dir)) {
            Log.out("目录不存在，没有需要清理的文件。");
            Log.blank();
            return 0;
        }

        long totalBytes = 0L;
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PATTERN)) {
            for (Path file : stream) {
                long size = Files.size(file);
                if (config.dryRun) {
                    Log.out("将删除: " + file.getFileName() + "  (" + Fmt.size(size) + ")");
                } else {
                    Files.delete(file);
                    Log.out("已删除: " + file.getFileName() + "  (" + Fmt.size(size) + ")");
                }
                totalBytes += size;
                count++;
            }
        }

        if (!config.dryRun && count > 0) {
            try {
                Files.deleteIfExists(dir);
            } catch (DirectoryNotEmptyException ignored) {
                // 目录里还有别的文件，保留
            }
        }

        Log.out((config.dryRun ? "（演练）可释放 " : "已释放 ") + Fmt.size(totalBytes) + "，共 " + count + " 个文件");
        if (count == 0) {
            Log.out("没有找到本工具生成的文件（只匹配 " + PATTERN + "）。");
        }
        if (!config.dryRun) {
            try {
                DiskSpace space = DiskSpace.query(config.path);
                if (space.total > 0L) {
                    Log.out("当前使用率 : " + Fmt.pct(space.usagePercent())
                            + "（剩余 " + Fmt.size(space.free()) + "）");
                }
            } catch (IOException ignored) {
                // 容量读不到就不显示
            }
        }
        Log.blank();
        return 0;
    }
}
