# CPU Loader

一个用 **Java 8** 写的 CPU 使用率工具：把整机 CPU 使用率稳定维持在你指定的百分比，用来做压测、温控测试、监控告警验证、功耗测试等。

单 jar、零第三方依赖、Windows / Linux / macOS 通用。

```
[00:05] 目标 50.0% | 本进程 49.7% | 系统 62.1% | 占空比 0.498/0.500 | 工作量 25.9G
```

## 快速开始

```bat
build.bat
run.bat --target 60
```

Linux / macOS：

```bash
chmod +x build.sh run.sh
./build.sh
./run.sh --target 60
```

只要装了 JDK 8 及以上即可，不需要 Maven。产物是 `build/cpu-loader.jar`，可以直接拷到别的机器上：

```bash
java -jar cpu-loader.jar --target 60 --duration 10m
```

> 也可以用 Maven：`mvn package` 生成 `target/cpu-loader.jar`（首次构建需要能访问 Maven 中央仓库）。

## 参数

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `-t, --target <百分比>` | 目标**整机** CPU 使用率，1-100 | `100` |
| `-n, --threads <数量>` | 工作线程数，`0` 表示按逻辑核心数自动 | `0` |
| `-d, --duration <时长>` | 运行时长，如 `30s`、`5m`、`2h`；`0`/`inf` 表示一直运行 | `inf` |
| `-p, --period <毫秒>` | 占空比控制周期 | `100` |
| `-w, --workload <类型>` | 负载类型：`mix` / `int` / `float` / `mem` / `crypto` | `mix` |
| `--status-interval <秒>` | 状态输出间隔，`0` 表示关闭 | `2` |
| `-q, --quiet` | 不输出周期性状态 | 关 |
| `-c, --config <文件>` | 读取 properties 配置文件，命令行参数优先 | 无 |
| `-v, --verbose` | 输出调试信息（含控制器的观测值） | 关 |
| `-h, --help` | 显示帮助 | - |
| `--version` | 显示版本号 | - |

参数也支持 `--target=60` 这种等号写法。

### 负载类型

| 类型 | 特点 | 适用场景 |
| --- | --- | --- |
| `mix`（默认） | 整数 + 位运算 + 浮点混合 | 最接近普通业务负载 |
| `int` | 纯整数位运算 | 测 ALU / 单核性能 |
| `float` | `sin`/`cos`/`sqrt` 密集 | 测 FPU、数学库 |
| `mem` | 1MB 缓冲区来回读写 | 测缓存与内存带宽 |
| `crypto` | 循环 SHA-256 | 测加解密类负载，附带分配压力 |

## 实测精度

24 核机器上，用操作系统层面的进程 CPU 时间独立核对（目标 → 实测）：

| 目标 | 10% | 25% | 50% | 75% | 90% |
| --- | --- | --- | --- | --- | --- |
| 实测 | 10.0% | 24.9% | 49.7% | 74.8% | 87.2%\* |

\* 该机器上另有约 10% 的后台负载，90% 以上时物理上就拿不到满额——工具能抢到的份额就是"剩余算力"。同理，`--target 100` 也不可能真的到 100%，除非机器上没别的东西在跑。

## 工作原理

1. **占空比（duty cycle）**：每个线程按固定周期（默认 100ms）工作，先满载计算 `duty × 周期` 的时间，剩下的时间不占 CPU。目标 50% 就是"干 50ms、歇 50ms"。
   `每线程占空比 = 目标% × 逻辑核心数 ÷ 线程数`，所以 24 核机器上目标 50% 用 24 个线程，每线程占空比正好 0.5。

2. **睡眠债务（sleep debt）**：空闲时间不立即 `sleep`，而是记账。因为 Windows 的 `Thread.sleep` 会按约 15.6ms 的时间片取整，直接睡 10ms 可能实际睡 16ms，误差会累积成明显的偏差。记账后攒够再睡，并**按实际睡掉的时长扣账**：这次睡多了，下一轮就少睡——长期累计空闲时间严格等于请求值。

3. **线程 CPU 时间反馈**：每个控制窗口用 `ThreadMXBean.getCurrentThreadCpuTime()` 算出这个线程真实的 CPU 占用率，与目标比较后做积分修正（`duty += 0.6 × 误差`）。这能抵消计算超时、被系统抢占、CPU 降频等因素，而且稳态无静差。

4. **相位抖动**：每个线程的控制周期带 ±5% 随机抖动，启动时随机错开相位。否则所有线程会同时睡、同时醒、同时抢核心，燃烧阶段被互相抢占，实际使用率会掉下去。

## 常见用法

```bat
# 整机压到 60%，一直跑，Ctrl+C 停止
run.bat -t 60

# 只用 8 个线程（占空比会自动上调，例如 24 核上 60% 目标 → 每线程 1.0 封顶）
run.bat -t 60 -n 8

# 跑 10 分钟，每 5 秒报一次状态
run.bat -t 75 -d 10m --status-interval 5

# 用配置文件
run.bat --config config.properties

# 内存/加密混合负载
run.bat -t 80 -w crypto -d 5m

# 安静模式，只留最后的汇总
run.bat -t 50 -d 30s -q
```

## 注意事项

- **注意散热**：长时间满载会让笔记本/小型机明显升温，建议从 50%~70% 开始，观察温度再加。
- **别在生产机上长时间跑满**，尤其是共享的数据库、CI 机器。
- `--threads` 少而目标高时会达不到目标，程序启动时会提示需要多少线程。
- 线程数远大于核心数（> 4 倍）时上下文切换开销明显，一般没必要。
- 极低目标（比如 1%）时，JVM 自身的 GC/编译线程开销会占掉一部分，实测可能略高于目标，这是正常的。
- 停止方式：`--duration` 到期自动停，或按 `Ctrl+C`，两种方式都会打印本次运行的实测汇总。

## 目录结构

```
cpu-loader/
├── build.bat / build.sh      # 用 javac 编译并打包（无需 Maven）
├── run.bat / run.sh          # 运行（没构建过会自动构建）
├── config.properties         # 配置文件示例
├── pom.xml                   # 可选：mvn package
└── src/main/java/com/cpuload/
    ├── Main.java             # 入口、参数校验、退出码
    ├── CliOptions.java       # 命令行 + 配置文件解析
    ├── LoadConfig.java       # 运行配置（目标、线程数、占空比换算）
    ├── LoadRunner.java       # 线程编排、状态输出、汇总
    ├── CpuBurner.java        # 单个线程的占空比 + 睡眠债务 + 积分控制
    ├── StatusReporter.java   # 周期性状态行
    ├── Workload.java         # 负载接口
    ├── Workloads.java        # 五种负载实现
    ├── SysMetrics.java       # 进程/系统 CPU 指标（反射调用，兼容 JDK 8~21+）
    ├── WorkloadType.java     # 负载类型枚举
    ├── Fmt.java              # 格式化辅助
    ├── Log.java              # 线程安全输出
    └── ConfigException.java  # 参数错误
```

## 兼容性

- 源码和字节码均为 **Java 8**（class 文件 major version 52），已在 JRE 1.8.0_441 上实测运行。
- 用 JDK 8 编译：`build.bat` 会自动退回 `-source 1.8 -target 1.8`。
- 用 JDK 9+ 编译：使用 `--release 8`，确保不会误用到 Java 8 之外的 API。
- `SysMetrics` 通过反射读取 `com.sun.management.OperatingSystemMXBean`，在不同 JDK 上分别尝试 `getCpuLoad()`（JDK 14+）和 `getSystemCpuLoad()`（JDK 8~13），取不到就显示 `n/a`，不影响负载控制。

## 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | 正常结束 |
| 1 | 运行时异常 |
| 2 | 参数错误 |

## 配套工具

需要占用磁盘空间（把存储使用率抬到指定水平）请看 [storage-loader](../storage-loader/README.md)。
