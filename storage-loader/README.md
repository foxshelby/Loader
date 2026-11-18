# Storage Loader

用**真实文件**占用磁盘空间，把某个卷的使用率抬到指定水平——用于测试磁盘空间告警、日志轮转、备份策略、存储满时的业务降级等场景。

和 [cpu-loader](../cpu-loader/README.md) 是配套工具：一个压 CPU，一个占磁盘，互不影响，可以同时跑。

```
==== 写入计划 ====
目标目录   : D:\disk-test\storage-loader-data
磁盘容量   : 651.6 GB
当前使用率 : 69.0%（已用 449.4 GB，剩余 202.3 GB）
目标使用率 : 80.0%
计划写入   : 72.0 GB（72 个文件，每个最大 1.0 GB）
安全限制   : 使用率不超过 95.0%，且至少保留 1.0 GB 可用空间
```

## 快速开始

```bat
build.bat
run.bat --path D:\disk-test --target 80 --dry-run
run.bat --path D:\disk-test --target 80
```

Linux / macOS：

```bash
chmod +x build.sh run.sh
./build.sh
./run.sh --path /data/disk-test --target 80
```

只用 JDK 8+，不需要 Maven。产物是 `build/storage-loader.jar`，可以拷到别的机器直接 `java -jar`。

## 参数

| 参数 | 说明 | 默认 |
| --- | --- | --- |
| `-p, --path <目录>` | 数据放哪个目录（必填），文件写入 `<目录>/storage-loader-data/` | — |
| `-t, --target <百分比>` | 把所在磁盘的使用率提升到该百分比 | — |
| `-s, --size <大小>` | 写入指定大小的数据，如 `500M`、`20G`、`1.5T` | — |
| `-f, --file-size <大小>` | 单个文件的最大大小 | `1G` |
| `--reserve <大小>` | 至少保留的可用空间 | `1G` |
| `--max-usage <百分比>` | 使用率硬上限 | `95` |
| `--status-interval <秒>` | 进度输出间隔 | `3` |
| `--dry-run` | 只显示计划，不写任何数据 | 关 |
| `--clean` | 删除本工具生成的文件并退出 | 关 |
| `-q, --quiet` | 不输出进度 | 关 |
| `-h, --help` | 显示帮助 | — |
| `--version` | 显示版本号 | — |

`--target` 和 `--size` 二选一：

- **`--target 80`**：按"卷使用率"算，会自动算出还需要写多少。适合"把磁盘压到 80%"这种说法。
- **`--size 20G`**：精确写入 20GB。适合网络盘/容量读不到的情况。

## 安全机制（重点）

写磁盘是有破坏性的操作，工具内置了几道闸：

1. **使用率硬上限** `--max-usage`（默认 95%），永远不会超过。
2. **保留空间** `--reserve`（默认 1GB），任何时候都至少留这么多可用空间，避免系统写不进日志/临时文件。
3. **写入过程中每 256MB 复查一次剩余空间**，一旦低于保留值立刻停止（而不是等文件写失败）。
4. **`--dry-run` 先看计划**，不写任何字节，专门用来确认"会写多少、写到哪"。
5. **只写自己的子目录**，`--clean` 也只删 `storage-loader-*.bin`，不会误删别的东西。
6. 目标值超过上限、或剩余空间不够时，**自动把写入量缩减**到安全值并明确提示，而不是硬写。

## 清理

```bat
run.bat --path D:\disk-test --clean
```

输出：

```
==== 清理 D:\disk-test\storage-loader-data ====
已删除: storage-loader-0001.bin  (1.0 GB)
...
已释放 72.0 GB，共 72 个文件
当前使用率 : 69.0%（剩余 202.3 GB）
```

`--clean --dry-run` 可以先看会删掉什么。

## 常见用法

```bat
# 把 D 盘占到 80%
run.bat --path D:\disk-test --target 80

# 精确写 50GB，每个文件 5GB
run.bat --path D:\disk-test --size 50G -f 5G

# 只保留 20GB 可用空间，其余全部占满（测试磁盘告警）
run.bat --path D:\disk-test --target 95 --reserve 20G

# 先看计划
run.bat --path D:\disk-test --target 80 --dry-run

# 安静模式（只要最后汇总）
run.bat --path D:\disk-test --size 10G -q

# 清理
run.bat --path D:\disk-test --clean
```

## 实现要点

- **写真实数据，不是稀疏文件**：Linux 上 `setLength`/`truncate` 产生的是稀疏文件，`du` 看得到、磁盘实际不占；这里按 1MB 块顺序写入真实字节，`df`/资源管理器立刻能看到空间减少。实测写入 200MB，系统可用空间正好减少 200.2MB。
- **防去重**：每个 1MB 块的头部写入唯一的偏移量，避免被支持去重/压缩的文件系统（ZFS、Btrfs、Windows 去重）"压缩"掉，导致占不满。
- **`force(false)` 落盘**：每个文件写完强制刷盘，避免"看着占了、重启后没了"。
- **进度**：每秒/每 N 秒输出一次已写量、百分比、速度、剩余空间和 ETA。
- **可中断**：`Ctrl+C` 随时停止，已写入的文件保留，用 `--clean` 清理。

## 注意事项

- **别在系统盘上长期保持高占用**。Windows 的更新、页面文件、事件日志都需要空间；占满系统盘等于把机器搞挂。练习请用数据盘或临时目录。
- **网络盘、同步盘要小心**：OneDrive / 百度网盘这类目录会把这几十 GB 上传到云端。真要用网络盘，请先 `--dry-run` 并确认它的容量能被正确读取（读不到容量时用 `--size`）。
- **SSD 有写入寿命**：反复填充/清理会消耗 TBW，测试频率别太高。
- **虚拟机 / 云盘**：thin-provisioned 磁盘写入后会真实占用宿主机空间，注意别把宿主机撑爆。
- 程序没有提权逻辑，能否写入取决于当前用户的权限；`Program Files` 之类的目录会被系统拒绝。
- Java 8 兼容：源码与字节码都是 Java 8（major 52），已在 JRE 1.8.0_441 上实测。

## 目录结构

```
storage-loader/
├── build.bat / build.sh      # 用 javac 编译并打包（无需 Maven）
├── run.bat / run.sh          # 运行（没构建过会自动构建）
├── pom.xml                   # 可选：mvn package
└── src/main/java/com/storageloader/
    ├── Main.java             # 入口、模式分发、退出码
    ├── CliOptions.java       # 参数解析与校验
    ├── StorageConfig.java    # 运行配置
    ├── DiskSpace.java        # 卷容量/剩余空间查询
    ├── SpaceFiller.java      # 写入计划、安全闸、写文件与进度
    ├── FileCleaner.java      # 清理
    ├── Fmt.java              # 容量/时间格式化
    └── ConfigException.java、Log.java
```

## 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | 完成、或当前已达标无需写入 |
| 1 | 空间不足 / 写入中途中止 / 运行时异常 |
| 2 | 参数错误 |
