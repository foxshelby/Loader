package com.storageloader;

/** 程序入口。 */
public final class Main {

    public static final String VERSION = "1.0.0";

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = execute(args);
        } catch (ConfigException e) {
            Log.err("参数错误: " + e.getMessage());
            Log.err("运行 java -jar storage-loader.jar --help 查看用法。");
            exitCode = 2;
        } catch (Throwable t) {
            Log.err("运行失败: " + t);
            t.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static int execute(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options.version) {
            Log.out("Storage Loader " + VERSION);
            return 0;
        }
        if (options.help || args.length == 0) {
            Log.out(CliOptions.helpText());
            return 0;
        }
        StorageConfig config = options.toConfig();
        if (config.clean) {
            return FileCleaner.run(config);
        }
        return SpaceFiller.run(config);
    }
}
