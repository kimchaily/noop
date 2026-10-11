# NOOP Code Wiki

> **NOOP** 是一个独立的、完全离线的 WHOOP 手环（4.0 和 5.0/MG）伴侣应用。它直接通过蓝牙低功耗（BLE）与手环配对，将所有数据存储在本地 SQLite 数据库中，**无需 WHOOP 帐户和云端**，并在设备本地计算恢复度（recovery）、负荷（strain）、HRV 和睡眠分析。

**项目主页**: https://github.com/NoopApp/noop  
**许可证**: PolyForm Noncommercial 1.0.0 (非商业使用免费)  
**语言**: Swift (macOS/iOS), Kotlin (Android)  
**平台**: macOS 13+, iOS 16+, Android 8.0+ (API 26+)

---

## 📋 目录

- [1. 项目概述](#1-项目概述)
- [2. 整体架构](#2-整体架构)
- [3. 模块职责详解](#3-模块职责详解)
  - [3.1 WhoopProtocol](#31-whoopprotocol)
  - [3.2 WhoopStore](#32-whoopstore)
  - [3.3 StrandAnalytics](#33-strandanalytics)
  - [3.4 StrandImport](#34-strandimport)
  - [3.5 StrandDesign](#35-stranddesign)
  - [3.6 Strand App (macOS)](#36-strand-app-macos)
  - [3.7 Android App](#37-android-app)
- [4. 关键类与函数](#4-关键类与函数)
- [5. 数据流向](#5-数据流向)
- [6. 数据模型与数据库 schema](#6-数据模型与数据库-schema)
- [7. 依赖关系](#7-依赖关系)
- [8. 并发模型](#8-并发模型)
- [9. BLE 连接生命周期](#9-ble-连接生命周期)
- [10. 跨平台设计](#10-跨平台设计)
- [11. 构建与运行](#11-构建与运行)
- [12. 功能特性](#12-功能特性)
- [13. 致谢与版权](#13-致谢与版权)

---

## 1. 项目概述

NOOP 的核心设计原则：

1. **完全离线** - 没有服务器，没有遥测，没有帐户。所有数据都保存在用户设备上
2. **数据所有权** - 用户拥有自己的数据，可以导入 WHOOP CSV 和 Apple Health 导出
3. **透明计算** - 所有评分算法基于公开文献，结果都是近似值而非专有模型
4. **纯函数核心** - 协议解码、存储、分析、导入都作为独立的跨平台 Swift 包，可以单独重用
5. **安全修剪** - 只有当数据在本地持久化后才确认 ack，确保不会丢失数据

**支持设备**:

| 设备 | 状态 |
|------|------|
| WHOOP 4.0 | ✅ 完整支持，经过验证 |
| WHOOP 5.0 / MG | 🧪 实时心率工作，深度评分仍在逆向工程中 |
| Oura Ring Gen 3/4/5 | 🧪 协议基础完成，开发中 |
| Xiaomi Band | 📥 导入支持 |

> **重要声明**: NOOP 是独立的非商业项目，**不隶属于 WHOOP**。"WHOOP" 仅用于标识硬件。**NOOP 不是医疗器械**，所有派生指标都是近似值，不用于诊断或治疗。

---

## 2. 整体架构

### 2.1 高层次数据流

```
 ┌─────────────┐
 │ WHOOP 手环   │
 └──────┬──────┘
        │ BLE GATT
        ▼
 ┌─────────────┐
 │ BLEManager  │  CoreBluetooth / BluetoothGatt
 │ FrameRouter │  → 解码 → 更新 LiveState
 └──────┬──────┘
        │
        ├─► 实时路径: Collector
        │
        └─► 历史回灌路径: Backfiller
                       
                        ▼
         ┌───────────────────────────┐
         │   WhoopProtocol.decode    │  → 纯 Swift，无 BLE 依赖
         └───────────┬───────────────┘
                     │
                     ▼
       ┌───────────────────────────────┐
       │  Streams (typed sample rows)   │
       └───────────────┬───────────────┘
                       │
                       ▼
         ┌───────────────────────────┐
         │     WhoopStore.insert     │  actor，GRDB/SQLite 持久化
         └───────────────┬───────────┘
                         │
         ┌───────────────┴───────────────┐
         ▼                               ▼
 [CSV/Apple Health Import] → StrandImport → WhoopStore
         all imports converge to the same DB
                         │
                         ▼
         ┌───────────────────────────┐
         │  StrandAnalytics.analyze  │  纯函数，计算 recovery/strain/sleep
         └───────────────┬───────────┘
                         │
                         ▼
         ┌───────────────────────────┐
         │  Repository (read model) │  缓存每日指标
         └───────────────┬───────────┘
                         │
                         ▼
         ┌───────────────────────────┐
         │  SwiftUI / Jetpack Compose  │  UI 层
         │  (StrandDesign / NoopTheme) │
         └───────────────────────────┘
```

所有数据处理都在用户设备上完成，**没有任何数据上传**。

### 2.2 仓库目录结构

```
noop/
├── Strand/                    # macOS SwiftUI 参考应用
│   ├── App/                   # StrandApp, AppModel, RootView
│   ├── BLE/                   # CoreBluetooth 管理器，帧路由，命令
│   ├── Collect/               # Backfiller, Collector, 时钟关联
│   ├── Data/                  # Repository, 导入胶水, Profile
│   ├── Screens/               # SwiftUI 各功能屏幕
│   ├── MenuBar/               # 菜单栏 extra
│   ├── System/                # macOS 集成 (锁屏，快捷指令)
│   └── Resources/             # Info.plist,  entitlements, 资源
├── StrandiOS/                # iOS SwiftUI 应用壳 (源码构建)
├── StrandiOSShared/          # iOS 共享代码
├── StrandiOSWidgets/         # iOS WidgetKit + Live Activity
├── NOOPWatch/                # watchOS 应用 (Apple Watch)
├── Packages/                 # 跨平台 Swift 包 (macOS + iOS)
│   ├── WhoopProtocol/        # BLE 帧解析，CRC，命令/事件/包解码
│   ├── WhoopStore/           # GRDB/SQLite 持久化
│   ├── StrandAnalytics/       # HRV/恢复/负荷/睡眠/相关性计算
│   ├── StrandImport/         # WHOOP CSV + Apple Health 导入
│   ├── StrandDesign/         # SwiftUI 设计系统 (调色板，组件，图表)
│   ├── WhoopStore/           # 数据存储
│   ├── OuraProtocol/         # Oura Ring BLE 协议 (新增)
│   └── ...
├── android/                  # Android 原生应用 (Kotlin + Jetpack Compose)
│   ├── app/
│   │   └── src/main/java/com/noop/
│   │       ├── protocol/      # WhoopProtocol Kotlin 移植
│   │       ├── data/          # Room 数据库实体和 DAO
│   │       ├── analytics/     # StrandAnalytics Kotlin 移植
│   │       ├── ble/           # Android BLE 管理
│   │       ├── ingest/        # 导入器 (CSV, Apple Health, Health Connect)
│   │       ├── ui/            # Compose 屏幕
│   │       └── oura/          # Oura 协议支持
│   └── build.gradle.kts
├── Tools/
│   ├── Backfill/             # CLI 工具，重新导入数据到 DB
│   └── linux-capture/        # Linux 命令行抓包工具
├── docs/                     # 项目文档
└── ...
```

---

## 3. 模块职责详解

### 3.1 WhoopProtocol

**路径**: `Packages/WhoopProtocol/`  
**依赖**: 无 (纯 Foundation)  
**平台**: iOS 16+, macOS 13+

**职责**: 逆向工程核心 - 将原始 BLE 字节解码为带类型的记录。**不导入 CoreBluetooth**，所以可以在任何平台运行。

**关键源文件**:

| 文件 | 职责 |
|------|------|
| `Framing.swift` | SOF、长度、CRC8/CRC16/CRC32 校验，`Reassembler` 碎片重组 |
| `DeviceFamily.swift` | 设备系列 (whoop4/whoop5)，GATT UUID 字符串，CLIENT_HELLO |
| `Schema.swift` | 加载 JSON 协议 schema (`whoop_protocol.json`) |
| `Interpreter.swift` | `parseFrame` - 驱动型字段解码 |
| `Values.swift` | 解码值类型 (int, double, string, intArray) |
| `Streams.swift` | `extractStreams` - 从解析后的帧提取实时流样本 |
| `HistoricalStreams.swift` | `extractHistoricalStreams` - 提取历史离线流 |
| `HistoricalMeta.swift` | `classifyHistoricalMeta` - 分类 METADATA 帧 (START/END/COMPLETE) |
| `PostHooks.swift` | 特定包类型的不规则字段解码 |

**关键公开 API**:

```swift
// CRC 计算
public func crc8(_ bytes: [UInt8]) -> UInt8
public func crc32(_ bytes: [UInt8]) -> UInt32
public func crc16Modbus(_ bytes: [UInt8]) -> UInt16

// 帧验证
public func verifyFrame(_ frame: [UInt8], family: DeviceFamily) -> FrameCheck

// 碎片重组
public final class Reassembler {
    public init()
    public func feed(_ fragment: [UInt8]) -> [[UInt8]]
}

// 帧解析
public func parseFrame(_ frame: [UInt8], family: DeviceFamily) -> ParsedFrame

// 提取流样本
public func extractStreams(_ parsed: [ParsedFrame], 
    deviceClockRef: Int, wallClockRef: Int) -> Streams
public func extractHistoricalStreams(_ parsed: [ParsedFrame], 
    deviceClockRef: Int, wallClockRef: Int) -> Streams

// 设备系列
public enum DeviceFamily: String, Sendable, CaseIterable {
    case whoop4   // CRC8 头检查，服务 61080001-...
    case whoop5   // CRC16-Modbus 头检查，"puffin" 包类型，服务 fd4b0001-...
}
```

**协议框架**:

**WHOOP 4.0**:
```
 ┌─────┬─────────┬──────┬───────────┬──────────┐
 │ 0xAA│length u16│crc8 │ type seq cmd│ payload  │crc32 u32│
 │ [0] │ [1..3]   │ [3]  │ [4..len)  │ [7..len) │[len..+4)  │
 └─────┴─────────┴──────┴───────────┴──────────┴──────────┘
 total = length + 4
```

**WHOOP 5.0 / MG**:
```
 ┌─────┬────────┬────────────┬──────────┬─────────────┬─────────┬──────────┐
 │ 0xAA│format │declLength u16│header[2]│crc16 u16 LE│type seq cmd│ payload  │crc32 u32│
 │ [0] │ [1]   │ [2..4]      │ [4..6]  │ [6..8]      │ [8..]    │[8..decl+4]│tail (4)│
 └─────┴────────┴────────────┴──────────┴─────────────┴─────────┴──────────┴──────────┘
 total = declLength + 8
```

**更多协议细节**: 参见 [docs/PROTOCOL.md](docs/PROTOCOL.md)

---

### 3.2 WhoopStore

**路径**: `Packages/WhoopStore/`  
**依赖**: `WhoopProtocol`, `GRDB.swift` (≥ 6.0.0)  
**平台**: iOS 16+, macOS 13+

**职责**: 基于 GRDB/SQLite 的本地持久化。**是一个 actor**，所有读写都在 actor 的串行执行器上运行，脱离主线程。

**关键源文件**:

| 文件 | 职责 |
|------|------|
| `WhoopStore.swift` | `actor WhoopStore` - 根入口，打开数据库，配置 PRAGMA |
| `Database.swift` | 迁移器定义，当前 schema 版本 9 |
| `StreamStore.swift` | 插入解码流，idempotent upsert |
| `Reads.swift` | 范围读取方法 (hrSamples, rrIntervals, etc.) |
| `RawOutbox.swift` | 原始帧输出箱 (压缩，可修剪) |
| `Cursors.swift` | 游标/高水位存储 |
| `MetricsCache.swift` | 每日指标和睡眠会话缓存 |
| `JournalWorkoutAppleCache.swift` | 日志、锻炼、Apple Daily 缓存 |
| `MetricSeriesStore.swift` | 通用长格式 (EAV) 指标存储 |

**数据库配置**:

| PRAGMA | 值 | 原因 |
|--------|-----|------|
| `journal_mode` | `WAL` | 允许读写同时进行，不死锁 |
| `synchronous` | `NORMAL` | 配合 WAL，性能平衡 |
| `cache_size` | `-16000` | ~16 MB 页缓存 |
| `mmap_size` | `268435456` | 256 MB 内存映射 I/O |
| `temp_store` | `MEMORY` | 临时表放内存 |
| `busyMode` | `.timeout(5)` | 5 秒忙等待超时 |

**关键公开 API**:

```swift
// 打开/创建
public init(path: String) async throws
public static func inMemory() async throws -> WhoopStore

// 设备 upsert
public func upsertDevice(id: String, mac: String?, name: String?) async throws

// 插入解码流 (返回实际插入行数)
@discardableResult
public func insert(_ streams: Streams, deviceId: String) async throws 
    -> (hr: Int, rr: Int, events: Int, battery: Int,
        spo2: Int, skinTemp: Int, resp: Int, gravity: Int)

// 范围读取
public func hrSamples(deviceId: String, from: Int, to: Int, limit: Int) async throws -> [HRSample]
public func rrIntervals(...) async throws -> [RRInterval]
// ... 其他流类似

// 原始输出箱
public func enqueueRawBatch(_ meta: RawBatchMeta, frames: [[UInt8]]) async throws
public func pruneRaw(now: Int, keepWindowSeconds: Int, maxUnsyncedBytes: Int) async throws -> Int

// 缓存 upsert
public func upsertDailyMetrics(_ metrics: [DailyMetric], deviceId: String) async throws
public func upsertSleepSessions(_ sessions: [CachedSleepSession], deviceId: String) async throws
public func upsertMetricSeries(_ points: [MetricPoint], deviceId: String) async throws
```

**更多数据模型细节**: 参见 [docs/DATA_MODEL.md](docs/DATA_MODEL.md)

---

### 3.3 StrandAnalytics

**路径**: `Packages/StrandAnalytics/`  
**依赖**: `WhoopProtocol` (类型), `WhoopStore` (类型)  
**平台**: iOS 16+, macOS 13+

**职责**: 纯函数式的本地生理分析。**不触碰数据库**，所有计算都是确定性的 - 相同输入总是得到相同输出。

**关键源文件**:

| 文件 | 职责 |
|------|------|
| `HRVAnalyzer.swift` | 从 R-R 间隔计算 RMSSD + SDNN (Task Force 1996) |
| `RecoveryScorer.swift` | 计算 0-100 恢复分数 (HRV 主导 z-score + logistic 复合) |
| `StrainScorer.swift` | 计算 0-21 对数负荷刻度 (Edwards/Banister TRIMP) |
| `SleepStager.swift` | 睡眠检测和四阶段分期 (清醒/浅/深/REM) |
| `HRZones.swift` | HR 分区计算，Tanaka HRmax 估算 |
| `Baselines.swift` | 滚动个人基线 (Winsorized EWMA) |
| `WorkoutDetector.swift` | 从 HR + 重力检测锻炼时段 |
| `CorrelationEngine.swift` | Pearson 相关系数计算 |
| `AnalyticsEngine.swift` | 编排器 - `analyzeDay(...)` → `DayResult` 整合所有分析 |

**关键公开 API**:

```swift
// HRV 分析
HRVAnalyzer.analyze(_ rrIntervals: [RRInterval], 
    windowStart: Int, windowEnd: Int) -> HRVResult
// → HRVResult(rmssd: Double?, sdnn: Double?, ...)

// 恢复评分
RecoveryScorer.recovery(hrv: [Double], restingHr: [Double], 
    baselines: Baselines) -> Double?  // 0-100

// 负荷评分
StrainScorer.strain(_ hrSamples: [HRSample], 
    maxHR: Int, restingHR: Int) -> Double  // 0-21

// 睡眠分期
SleepStager.detectSleep(hr: [HRSample], rr: [RRInterval], 
    resp: [RespSample], gravity: [GravitySample]) -> [SleepSession]

// 全日分析
let dayResult = AnalyticsEngine.analyzeDay(
    day: "2026-06-07",
    hr: hrSamples, rr: rrIntervals, ...,
    profile: UserProfile(...),
    baselines: ...
)
// → dayResult.recovery, dayResult.strain, dayResult.sleepSessions, ...
```

**算法来源**: 所有算法都基于已发表的运动科学方法，结果是**近似值**，不是 WHOOP 专有模型的复现。

---

### 3.4 StrandImport

**路径**: `Packages/StrandImport/`  
**依赖**: `WhoopProtocol`, `WhoopStore`, `ZIPFoundation` (≥ 0.9.0)  
**平台**: iOS 16+, macOS 13+

**职责**: 解析用户已有的导出数据: WHOOP CSV 导出 和 Apple Health `export.xml`。**只负责解析**，返回规范化模型数组，不触碰数据库。

**关键源文件**:

| 文件 | 职责 |
|------|------|
| `ImportCoordinator.swift` | 顶层入口，自动检测类型 |
| `WhoopExportImporter.swift` | 解析 WHOOP CSV 导出 (physiological_cycles.csv, sleeps.csv, ...) |
| `AppleHealthImporter.swift` | 流解析 Apple Health export.xml |
| `AppleHealthAggregator.swift` | 将样本聚合到每日 |
| `CSVParsing.swift` | 容错 CSV 解析 |

**关键公开 API**:

```swift
public struct ImportCoordinator {
    public func detectKind(of url: URL) throws -> DataSourceKind
    public func detectAndImport(from url: URL) throws -> DetectedImport
    
    public func importWhoopExport(from url: URL) throws -> WhoopImportResult
    public func importAppleHealth(from url: URL) throws -> AppleHealthImportResult
}
```

**支持导入**:
- WHOOP CSV 导出 (zip 或解压文件夹) - 支持 4.0/5.0/MG
- Apple Health export (zip 或 export.xml) - 流式解析，支持数百 MB 文件不 OOM
- 营养 CSV (Cronometer / MacroFactor) - 每日营养数据

---

### 3.5 StrandDesign

**路径**: `Packages/StrandDesign/`  
**依赖**: 只有 SwiftUI  
**平台**: iOS 16+, macOS 13+

**职责**: SwiftUI 设计系统 - 调色板、排版、动画、可复用组件和图表。

**关键组件**:

| 组件 | 用途 |
|------|------|
| `RecoveryRing` | 240° 开放弧形刻度 - 恢复分数显示 |
| `StrainGauge` | 0-21 负荷仪表 |
| `Hypnogram` | 睡眠阶段时间线 |
| `TrendChart` | 趋势图表 |
| `Sparkline` | 迷你折线图 |
| `YearHeatStrip` | 年度分数热力条 |
| `StrandCard` | 卡片容器 |
| `StatePill` | 状态标签 |

**标记**:
- `StrandPalette` - 语义化颜色标记
- `StrandFont` - 字体比例尺
- `StrandMotion` - 动画预设

---

### 3.6 Strand App (macOS)

**路径**: `Strand/`  
**架构**: `@MainActor` 根状态持有所有顶层对象

**关键顶层类**:

| 类 | 职责 |
|----|------|
| `StrandApp` | `@main` 入口，创建 `AppModel`，注入环境 |
| `AppModel` | `@MainActor @ObservableObject` 根状态 - 拥有 BLEManager, Repository, IntelligenceEngine |
| `RootView` | `NavigationSplitView` 壳 + 侧边栏导航 |
| `ContentView` | 内容区域 |

**子模块**:

| 模块 | 职责 |
|------|------|
| `BLE/` | `BLEManager` - CoreBluetooth 委托，扫描→连接→绑定→流；`FrameRouter` - 解码→LiveState；`Commands` - 安全命令集合 |
| `Collect/` | `Collector` - 实时帧缓冲；`Backfiller` - 历史离线状态机；`ClockCorrelation` - 设备时钟 ↔ 墙上时钟关联 |
| `Data/` | `Repository` - 基于 WhoopStore 的读模型；`WhoopImporter` - 结果→存储；`Profile` - 用户配置 |
| `Screens/` | 每个功能一个 SwiftUI 屏幕 (Today, Live, Breathe, Sleep, Trends, 等等) |
| `MenuBar/` | 菜单栏 extra - 概览实时心率 |
| `System/` | macOS 集成 - 锁屏，快捷指令，更新检查 |

---

### 3.7 Android App

**路径**: `android/`  
**架构**: 原生 Kotlin + Jetpack Compose，是 Swift 参考实现的**值对值移植**，不共享二进制。

**目录结构**:

```
android/app/src/main/java/com/noop/
├── protocol/          # WhoopProtocol Kotlin 移植 - CRC, Framing, ParseFrame, Schema
├── data/              # Room 数据库 - Entities, DAOs, NoopDatabase
├── analytics/         # StrandAnalytics 移植 - Hrv, RecoveryScorer, StrainScorer
├── ble/               # Android BLE - WhoopBleClient, Backfiller, SourceCoordinator
├── ingest/            # 导入器 - WhoopCsvImporter, AppleHealthImporter, HealthConnect
├── ui/                # Compose 屏幕 - MainActivity, TodayScreen, LiveScreen, ...
├── oura/              # Oura Ring 协议支持
├── ai/                # AI Coach 支持
└── alarm/             # 智能闹钟调度
```

**与 Swift 的一致性**:
- 协议 schema `whoop_protocol.json` 同一个源
- SQLite schema 与 GRDB 迁移保持一致
- 所有算法公式、常量、阈值完全匹配
- 单元测试使用相同测试用例

**更多细节**: 参见 [docs/ANDROID.md](docs/ANDROID.md)

---

## 4. 关键类与函数

### 4.1 WhoopProtocol

| 类型 | 说明 |
|------|------|
| `DeviceFamily` | 区分 whoop4 / whoop5，提供 GATT UUID 字符串 |
| `Reassembler` | 累积 BLE MTU 碎片，输出完整帧 |
| `FrameCheck` | `verifyFrame` 返回值，携带 CRC 检查结果 |
| `ParsedFrame` | 解码结果，`ok`, `typeName`, `parsed: [String: ParsedValue]` |
| `ParsedValue` | 解码值枚举 - `.int`, `.double`, `.string`, `.intArray`, ... |
| `Streams` | 聚合所有解码样本类型 (`hr`, `rr`, `events`, `battery`, `spo2`, ...) |
| `HRSample` / `RRInterval` / `BatterySample` / ... | 单个样本结构体，都有 `ts` (unix 秒) |

### 4.2 WhoopStore

| 类型 | 说明 |
|------|------|
| `actor WhoopStore` | 所有数据库操作都在这里，串行执行 |
| `DailyMetric` | 每日聚合指标 - `recovery`, `strain`, `totalSleepMin`, `restingHr`, `avgHrv`, ... |
| `CachedSleepSession` | 睡眠会话缓存 - `startTs`, `endTs`, `efficiency`, `stagesJSON` |
| `MetricPoint` | 通用指标点 - `(deviceId, day, key) → value` |

### 4.3 StrandAnalytics

| 类型 | 说明 |
|------|------|
| `HRVResult` | `rmssd`, `sdnn`, `meanNN`, `pNN50` |
| `HRVAnalyzer` | 包含 Malik 20% 局部中位数异位剔除 |
| `AnalyticsEngine` | 全日编排 - 协调所有分析器 |
| `UserProfile` | `age`, `sex`, `weightKg`, `heightCm`, `maxHRoverride` |

### 4.4 App 层 (Swift/macOS)

| 类型 | 说明 |
|------|------|
| `AppModel` | `@MainActor` 共享根，`static weak var shared` 给 AppIntents 访问 |
| `BLEManager` | 核心 CBCentralManager 委托，管理连接生命周期 |
| `LiveState` | `@MainActor @Observable` 连接/生物测量快照，UI 观察 |
| `FrameRouter` | 解码帧 → 更新 LiveState，触发同步 |
| `Repository` | `@MainActor` 读模型，持有缓存的 `days` 和 `sleeps` |
| `IntelligenceEngine` | 编排每日重新分析 |
| `Backfiller` | 历史离线状态机，实现**安全修剪不变性** |

**安全修剪不变性**:
```
decode → await store.insert → [await enqueueRaw] → await setCursor → ackTrim
```
块只有在数据本地持久化后才确认 ack， strap 才会修剪。如果中断，下次会话从持久化游标恢复。

---

## 5. 数据流向

### 5.1 实时路径

```
CoreBluetooth notify → Reassembler.feed(fragment) → 完整帧
   → verifyFrame → parseFrame → FrameRouter.handle
   → 更新 LiveState (UI)
   → Collector.ingest → 缓冲
   → 当达到 64 帧或 30 秒 → 刷新:
      extractStreams → await WhoopStore.insert
```

### 5.2 历史回灌路径 (离线)

```
SEND_HISTORICAL_DATA → HISTORY_START
   → 累积 type-47 记录
   → HISTORY_END(unix:trim) → finishChunk:
      decode → extractHistoricalStreams → await store.insert
      → [await enqueueRawBatch] → await setCursor("strap_trim", trim)
      → ackTrim (withResponse)
   → 直到 HISTORY_COMPLETE → 完成
```

### 5.3 导入路径

```
用户选择文件 → ImportCoordinator.detectAndImport
   → 解析 → 归一化模型 → 应用层调用 WhoopStore.upsertXXX
   → Repository.refresh → IntelligenceEngine.reanalyze
   → UI 更新
```

---

## 6. 数据模型与数据库 schema

### 6.1 表分组

| 分组 | 表 | 说明 |
|------|------|------|
| 设备注册 | `device` | 已见过的手环 |
| 解码流 | `hrSample`, `rrInterval`, `event`, `battery`, `spo2Sample`, `skinTempSample`, `respSample`, `gravitySample` | 持久化原始测量 |
| 原始输出箱 | `rawBatch` | 压缩原始帧，可修剪 |
| 簿记 | `cursors` | 命名高水位 |
| 指标缓存 | `dailyMetric`, `sleepSession`, `journal`, `workout`, `appleDaily`, `metricSeries` | 派生和导入数据 |

### 6.2 主键约定

- 所有时间戳 `ts` 都是 **unix 秒** (整数)
- `deviceId` 是分区键 - 每个数据源一个 id
- 自然键复合主键，使用 `ON CONFLICT DO NOTHING` 去重

**关键表**:

```sql
-- 心率样本
CREATE TABLE hrSample (
  deviceId TEXT,
  ts INTEGER,
  bpm INTEGER,
  PRIMARY KEY(deviceId, ts)
);

-- R-R 间隔 (HRV 来源)
CREATE TABLE rrInterval (
  deviceId TEXT,
  ts INTEGER,
  rrMs INTEGER,
  PRIMARY KEY(deviceId, ts, rrMs)
);

-- 每日指标缓存
CREATE TABLE dailyMetric (
  deviceId TEXT,
  day TEXT,  -- YYYY-MM-DD
  recovery DOUBLE,
  strain DOUBLE,
  totalSleepMin DOUBLE,
  restingHr INTEGER,
  avgHrv DOUBLE,
  ...
  PRIMARY KEY(deviceId, day)
);

-- 通用指标序列 (EAV)
CREATE TABLE metricSeries (
  deviceId TEXT,
  day TEXT,
  key TEXT,
  value DOUBLE NOT NULL,
  PRIMARY KEY(deviceId, day, key)
);
```

完整文档: 参见 [docs/DATA_MODEL.md](docs/DATA_MODEL.md)

---

## 7. 依赖关系

### 7.1 内部依赖图 (Swift 包)

```
WhoopProtocol  (无内部依赖)
      │
      ▼
WhoopStore ────────────► GRDB.swift
      │
      ▼
StrandAnalytics ◄───────┘  (依赖 WhoopProtocol + WhoopStore 类型)

StrandImport   ──► WhoopProtocol + WhoopStore + ZIPFoundation

StrandDesign    (独立 - 仅 SwiftUI，无内部依赖)
```

### 7.2 外部依赖 (Swift Package Manager)

| 包 | 版本 | 用途 |
|----|------|------|
| `groue/GRDB.swift` | ≥ 6.0.0 | SQLite 持久化 |
| `weichsel/ZIPFoundation` | ≥ 0.9.0 | 解压 zip 导出 |

都是通过 SPM 自动解析，无需手动安装。

### 7.3 Android 依赖 (Gradle)

- Kotlin 1.9.24
- Jetpack Compose BOM 2024.06.00
- Room 2.6.1 (SQLite ORM)
- 所有依赖通过 Gradle 自动下载

---

## 8. 并发模型

NOOP 故意将并发分离为两个隔离域:

| 组件 | 隔离 | 原因 |
|------|------|------|
| `WhoopStore` | **actor** | GRDB `DatabaseQueue` 调用会阻塞; actor 将阻塞从主线程移到自己的串行执行器 |
| `AppModel`, `LiveState`, `Repository`, `BLEManager`, `FrameRouter`, `Collector`, `Backfiller` | **@MainActor** | CoreBluetooth 委托回调已经在主线程; 这些观察/发布 UI 状态 |
| 历史帧 draining | **serial Task queue` | 一帧一帧 await，保证顺序不会乱 |

**关键不变量**:
- 帧在委托回调顺序中**同步缓冲**
- 只有慢工作 (解码 + await store.insert)  crosses 到 store actor
- WAL 日志模式 + 5 秒 busy timeout → 读写不会死锁

---

## 9. BLE 连接生命周期

### 9.1 WHOOP 4.0

```
scan(service: 61080001-...)
  → didDiscover → connect
  → didDiscoverServices → didDiscoverCharacteristics
  ┌─► BOND: write GET_BATTERY_LEVEL with .withResponse → 确认写入 → 绑定完成
  │
  └─► subscribe notify on cmd/event/data + 0x2A37 (HR) + 0x2A19 (battery)
      ─► didWriteValueFor (bond ack)
           ┌───────────────────────────────────────────────────┐
           │  连接握手 **正好运行一次** (guarded by connectHandshakeDone)  │
           └───────────────────────────────────────────────────┘
           1. GET_HELLO_HARVARD
           2. GET_ADVERTISING_NAME_HARVARD
           3. SET_CLOCK → 设置 strap RTC 为 UTC
           4. GET_CLOCK → 建立 ClockRef (设备时钟 ↔ 墙上时钟)
           5. SEND_R10_R11_REALTIME [0x00] → 停止 type-43 洪水
           6. GET_DATA_RANGE → 获取 strap 存储范围
           7. ~1.5 秒延迟 → requestSync(.connect) → 开始历史回灌
           8. 启动周期回灌计时器 (900 秒 = 15 分钟)
           9. 启动保活计时器 (30 秒)
```

### 9.2 WHOOP 5.0 / MG 差异

- 发现后立即发送静态 `CLIENT_HELLO` 帧
- 使用 CRC16-Modbus 头校验
- "puffin" 包类型别名到 4.0 语义 (38 → COMMAND_RESPONSE, 56 → METADATA)
- 其他握手相同

### 9.3 配对须知

- WHOOP 手环一次只**保持一个 bond**
- 如果官方 app 还持有 bond，配对会被拒绝
- 配对前关闭官方 app，关闭手机蓝牙，让手环可配对

**更多**: 参见 [PROTOCOL](docs/PROTOCOL.md)

---

## 10. 跨平台设计

### 10.1 三个客户端

| 客户端 | 语言 / UI | 代码位置 | 共享 |
|--------|-----------|----------|------|
| **macOS** | Swift + SwiftUI | `Strand/` + `Packages/` | 参考实现，与 iOS 共享 5 个 Swift 包 |
| **iOS** | Swift + SwiftUI | `StrandiOS/` + 大部分 `Strand/` | 源码级共享几乎一切，自建 |
| **Android** | Kotlin + Jetpack Compose | `android/` | 值对值移植，不共享二进制，保持行为 parity |

### 10.2 共享规则

- **macOS ↔ iOS**: 真实共享源码 - 五个 Swift 包同时声明两个平台
  - 框架差异用 `#if canImport(AppKit) / #if canImport(UIKit)` 保护
  - `Platform.swift` 提供桥接类型 (PlatformImage = NSImage/UIImage)

- **macOS/iOS ↔ Android**: parity 而非共享代码
  - 协议事实来自同一个 `whoop_protocol.json`
  - SQLite schema 匹配 GRDB 迁移
  - 公式/常量/阈值完全匹配
  - 两边都有单元测试用相同测试用例

更多: 参见 [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md)

---

## 11. 构建与运行

### 11.1 macOS (参考实现)

**前提**:

- macOS 13+
- Xcode 15+ (Swift 5.9 / 6.3 工具链)
- XcodeGen 2.45+ (`brew install xcodegen`)

**构建步骤**:

```bash
git clone https://github.com/NoopApp/noop.git
cd noop
cd Strand
xcodegen generate
# 打开 Strand.xcodeproj
open Strand.xcodeproj
# 选择 scheme "Strand" → 运行 (⌘R)
# 产物名为 NOOP.app
```

**快速命令行构建**:

```bash
xcodebuild \
  -project Strand.xcodeproj \
  -scheme Strand \
  -destination 'platform=macOS' \
  CODE_SIGN_IDENTITY="-" \
  build
```

产物在 `build/Build/Products/Debug/NOOP.app`。

### 11.2 iOS (源码构建)

```bash
cd Strand
xcodegen generate
xcodebuild \
  -project Strand.xcodeproj \
  -scheme NOOPiOS \
  -destination 'generic/platform=iOS' \
  build
```

- 需要物理 iPhone (模拟器没有 BLE)
- 需要签名身份 (免费 Apple ID 可用)
- BLE 需要真机

### 11.3 Android

**前提**:

- JDK 17
- Android SDK API 34
- Android Studio (当前稳定版)

**构建**:

```bash
cd android
./gradlew :app:testDebugUnitTest  # 运行单元测试 (不需要设备)
./gradlew assembleDebug          # 构建 debug APK
# 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleFullRelease     # 构建完整发布版
```

更多: 参见 [docs/ANDROID.md](docs/ANDROID.md)

### 11.4 运行测试

```bash
# Swift 包测试
cd Packages/WhoopProtocol && swift test
cd Packages/WhoopStore    && swift test
cd Packages/StrandAnalytics && swift test
cd Packages/StrandImport  && swift test

# Android 单元测试
cd android
./gradlew :app:testDebugUnitTest
```

---

## 12. 功能特性

### 12.1 主屏幕

| 屏幕 | 功能 |
|------|------|
| **Today (控制中心)** | 主页仪表板: Charge 环，今日综合，关键指标网格，最近锻炼 |
| **Live** | 实时心率，连接状态，BLE 日志 |
| **Breathe** | HRV 触觉呼吸生物反馈 - 手环震动计时 |
| **Intervals** | 静音触觉 HIIT 计时器 |
| **Explore** | 指标浏览器 - 任意指标趋势，相关性 |
| **Compare** | 多指标叠加对比，相关性计算 |
| **Insights** | 行为效应分析，指标关系 |
| **Sleep** | 睡眠会话浏览，分期分解 |
| **Trends** | 长期趋势，年度热力条 |
| **Workouts** | 锻炼日志 |
| **Health** | 实时心率 + 最近生命体征 |
| **Stress** | 日间压力监测 |
| **Mind** | 每日心情记录，相关性分析 |
| **Apple Health** | 导入的 Apple Health 数据浏览 |
| **Data Sources** | 导入中心 - WHOOP CSV, Apple Health, 营养 CSV |
| **Automations** | 自动化 - 双击→Mac 动作，佩戴状态，心率区教练 |
| **Settings** | 配置，个人资料，单位，关于 |

### 12.2 自动化 (macOS)

- **双击 → Mac 动作** - 双击手环锁定 Mac，运行快捷指令
- **佩戴检测** - 取下手环锁定 Mac，戴上运行快捷指令
- **HR 区教练** - 进入/离开目标区震动提醒
- **智能闹钟** - 使用手环固件闹钟，到点震动

### 12.3 AI Coach (可选)

- 可选，用户提供自己的 API key
- 支持 Anthropic, OpenAI, Gemini, 自定义端点
- 支持本地/Ollama 模型
- 只发送简短摘要，不发送原始数据
- 默认关闭

完整功能列表: 参见 [docs/FEATURES.md](docs/FEATURES.md)

---

## 13. 致谢与版权

### 13.1 社区逆向工程贡献

NOOP 建立在社区工作之上:

- **johnmiddleton12/my-whoop** — WHOOP 4.0 BLE 协议
- **b-nnett/goose** — WHOOP 5.0 / MG BLE 协议
- **LogosIsLife/open_ring** — Oura Ring 协议
- 更多: 参见 [ATTRIBUTION.md](ATTRIBUTION.md)

### 13.2 许可证

- NOOP 自有代码: **PolyForm Noncommercial 1.0.0**
  - 非商业使用免费
  - 商业使用不授权
- 依赖保持自己的许可证: GRDB.swift (MIT), ZIPFoundation (MIT)

### 13.3 声明

> **NOOP 不隶属于 WHOOP**，是独立的非商业项目。"WHOOP" 仅用于标识硬件。  
> **NOOP 不是医疗器械**，所有派生指标都是近似值，不用于诊断、治疗或健康决策。

---

## 14. 可复用库

NOOP 的五个 Swift 包设计为可独立重用:

| 包 | 可重用性 |
|----|----------|
| `WhoopProtocol` | ✅ 纯 Foundation，可以在任何项目中解码 WHOOP BLE 帧 |
| `WhoopStore` | ✅ 如果你需要持久化解码结果，基于 GRDB |
| `StrandAnalytics` | ✅ 纯计算，可以独立使用 HRV/恢复/负荷/睡眠分析 |
| `StrandImport` | ✅ 解析 WHOOP/Apple Health 导出 |
| `StrandDesign` | ✅ SwiftUI 设计系统，可重用组件 |

更多: 参见 [docs/LIBRARY.md](docs/LIBRARY.md)

---

*本文档生成于 2026-07-13*
