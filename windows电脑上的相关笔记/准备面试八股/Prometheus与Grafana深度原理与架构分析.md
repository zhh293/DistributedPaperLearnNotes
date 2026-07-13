# Prometheus 与 Grafana 深度原理与架构分析

> 本文从架构设计、源码分析、核心原理、数据链路流程等维度，对 Prometheus 和 Grafana 进行系统性的深度剖析。适合已经了解基本部署使用、希望深入理解内部机制的读者。

---

## 目录

- [第一部分：Prometheus 深度剖析](#第一部分prometheus-深度剖析)
  - [一、整体架构设计](#一整体架构设计)
  - [二、TSDB 时序数据库存储引擎](#二tsdb-时序数据库存储引擎)
  - [三、数据采集链路（Scrape Loop）](#三数据采集链路scrape-loop)
  - [四、PromQL 查询引擎](#四promql-查询引擎)
  - [五、服务发现机制](#五服务发现机制)
  - [六、Remote Read/Write 远程存储](#六remote-readwrite-远程存储)
  - [七、Alertmanager 告警架构](#七alertmanager-告警架构)
  - [八、源码目录结构](#八prometheus-源码目录结构)
- [第二部分：Grafana 深度剖析](#第二部分grafana-深度剖析)
  - [九、整体架构设计](#九grafana-整体架构设计)
  - [十、后端架构（Go）](#十后端架构go)
  - [十一、前端架构（React）](#十一前端架构react)
  - [十二、数据源插件机制](#十二数据源插件机制)
  - [十三、Dashboard 渲染流水线](#十三dashboard-渲染流水线)
  - [十四、查询数据流完整链路](#十四查询数据流完整链路)
  - [十五、告警系统架构（Unified Alerting）](#十五告警系统架构unified-alerting)
  - [十六、插件系统设计](#十六插件系统设计)
  - [十七、Grafana 源码目录结构](#十七grafana-源码目录结构)
- [第三部分：Prometheus + Grafana 集成原理](#第三部分prometheus--grafana-集成原理)
  - [十八、集成内部实现](#十八集成内部实现)
- [第四部分：面试高频问题与总结](#第四部分面试高频问题与总结)

---

## 第一部分：Prometheus 深度剖析

### 一、整体架构设计

#### 1.1 架构概览

Prometheus 是 CNCF 毕业项目，使用 Go 语言编写的开源监控与告警系统。其核心设计哲学是**简单、可靠、可独立运行**——单个二进制文件即可完成数据采集、存储、查询和告警的完整闭环。

整体架构分为四个层次：

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Access Layer (访问层)                          │
│   web.Handler  │  v1.API (HTTP API)  │  Prometheus UI               │
├─────────────────────────────────────────────────────────────────────┤
│                  Processing & Rules Layer (处理层)                    │
│   promql.Engine (查询引擎)  │  rules.Manager (规则管理器)             │
├─────────────────────────────────────────────────────────────────────┤
│                      Storage Layer (存储层)                           │
│   tsdb.DB (本地TSDB)  │  remote.QueueManager (远程写入)              │
├─────────────────────────────────────────────────────────────────────┤
│                    Ingestion Layer (数据摄入层)                       │
│   discovery.Manager (服务发现)  │  scrape.Manager (指标抓取)          │
│   OTLP Receiver (OpenTelemetry接收)                                  │
└─────────────────────────────────────────────────────────────────────┘
```

**数据流向总览：**

```
                    ┌──────────────┐
                    │  Targets     │  （被监控的应用/中间件）
                    │  /metrics    │
                    └──────┬───────┘
                           │ HTTP GET (Pull)
                           ▼
┌─────────────────────────────────────────────────┐
│              Prometheus Server                    │
│                                                  │
│  ┌──────────────┐    ┌──────────────────────┐   │
│  │ Service      │───→│ Scrape Manager       │   │
│  │ Discovery    │    │  └─ scrapeLoop (per   │   │
│  │ Manager      │    │     target)           │   │
│  └──────────────┘    └──────────┬────────────┘   │
│                                  │                │
│                                  ▼                │
│  ┌───────────────────────────────────────────┐   │
│  │              TSDB                          │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐   │   │
│  │  │  WAL    │  │  Head   │  │  Blocks  │   │   │
│  │  │(预写日志)│  │(内存层) │  │(磁盘层)  │   │   │
│  │  └─────────┘  └─────────┘  └─────────┘   │   │
│  └───────────────────────────────────────────┘   │
│                      │                            │
│          ┌───────────┼───────────┐               │
│          ▼           ▼           ▼               │
│  ┌────────────┐ ┌─────────┐ ┌────────────────┐  │
│  │ PromQL     │ │ Rules   │ │ Remote Write   │  │
│  │ Engine     │ │ Manager │ │ Queue Manager  │  │
│  └─────┬──────┘ └────┬────┘ └───────┬────────┘  │
│        │              │              │            │
└────────┼──────────────┼──────────────┼────────────┘
         │              │              │
         ▼              ▼              ▼
   ┌──────────┐  ┌────────────┐  ┌──────────────┐
   │ Grafana  │  │Alertmanager│  │远程存储(Thanos│
   │ /用户    │  │            │  │VictoriaMetrics)│
   └──────────┘  └────────────┘  └──────────────┘
```

#### 1.2 Pull vs Push 模型

Prometheus 采用 **Pull（拉取）模型**：由 Prometheus Server 主动向监控目标发起 HTTP 请求获取指标数据。这与很多传统监控系统的 Push 模型（由应用主动推送数据到监控服务器）形成对比。

**Pull 模型的优势：**

- 监控目标与监控系统解耦：被监控应用只需暴露 `/metrics` 端点，不需要知道谁在监控自己
- 天然的健康检查：如果 Prometheus 无法 scrape 到目标，说明目标可能已经挂了
- 易于调试：运维人员可以直接用浏览器访问 `/metrics` 查看原始指标数据
- 避免推送风暴：Push 模型下如果大量节点同时推送数据可能压垮监控服务器

**Pull 模型的局限：**

- 短生命周期的 Job（如批处理任务）可能在下一次 scrape 之前就结束了——通过 Pushgateway 组件解决
- 需要网络可达性——Prometheus 必须能访问到监控目标

#### 1.3 运行模式

Prometheus 支持两种部署模式：

- **Server Mode（默认）**：完整功能——采集、存储、查询、规则评估、告警
- **Agent Mode**：轻量转发模式——只负责采集数据并通过 Remote Write 转发到远程存储，禁用本地 Block 存储和规则评估。适用于边缘节点采集场景

#### 1.4 指标数据模型

Prometheus 的数据模型是其设计的基石。每一条时间序列由以下部分组成：

```
metric_name{label1="value1", label2="value2", ...}  timestamp  value
```

- **metric_name（指标名称）**：标识指标的含义，如 `http_requests_total`
- **labels（标签集）**：键值对集合，为指标增加维度信息，如 `{method="GET", status="200"}`
- **timestamp（时间戳）**：毫秒精度的 Unix 时间戳
- **value（值）**：64 位浮点数

**四种指标类型：**

| 类型 | 描述 | 示例 |
|------|------|------|
| Counter | 单调递增计数器，只增不减 | `http_requests_total` |
| Gauge | 可增可减的瞬时值 | `temperature_celsius` |
| Histogram | 直方图，将观测值分桶统计 | `http_request_duration_seconds_bucket` |
| Summary | 摘要，计算分位数 | `http_request_duration_seconds{quantile="0.99"}` |

---

### 二、TSDB 时序数据库存储引擎

TSDB（Time Series Database）是 Prometheus 的核心，由 Prometheus 团队自研，专门为时序数据优化设计。其设计灵感来源于 Facebook 的 Gorilla 论文（内存时序数据库）和 LSM-tree 思想。

#### 2.1 两层存储模型

TSDB 采用 **Head（内存层）+ Blocks（磁盘层）** 的两层架构：

```
┌────────────────────────────────────────────────────────────┐
│                         TSDB                                │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Head Block (内存层)                                  │  │
│  │  - 活跃数据区域，保存最近 2 小时的数据                │  │
│  │  - 所有新写入首先进入 Head                            │  │
│  │  - 数据可变、可追加                                   │  │
│  │  - 通过 WAL 保证持久化                                │  │
│  │                                                       │  │
│  │  内部结构：                                           │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  │  │
│  │  │stripeSeries │  │MemPostings   │  │ WAL        │  │  │
│  │  │(分片哈希表) │  │(内存倒排索引)│  │(预写日志)  │  │  │
│  │  └─────────────┘  └──────────────┘  └────────────┘  │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
│                           │ Compaction (压缩)               │
│                           ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Persistent Blocks (磁盘层)                           │  │
│  │  - 不可变的历史数据块                                 │  │
│  │  - 每个 Block 覆盖一段时间范围                        │  │
│  │  - 多个小 Block 可合并为大 Block                      │  │
│  │                                                       │  │
│  │  Block 目录结构：                                     │  │
│  │  ├── meta.json       (块元信息：时间范围、版本等)     │  │
│  │  ├── index           (倒排索引文件)                   │  │
│  │  ├── chunks/         (压缩的时序数据)                 │  │
│  │  │   └── 000001                                       │  │
│  │  └── tombstones      (删除标记)                       │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

#### 2.2 核心数据结构

**DB 结构体**（`tsdb/db.go`）：

```go
type DB struct {
    dir       string       // 数据目录
    head      *Head        // 内存层（最近2小时数据）
    blocks    []*Block     // 磁盘层（历史数据块列表，按时间排序）
    compactor Compactor    // 压缩器（负责 Head→Block 和 Block 合并）
    opts      *Options     // 配置选项
    // ...
}
```

**Head 结构体**（`tsdb/head.go`）：

```go
type Head struct {
    series         *stripeSeries     // 分片哈希表，存储活跃时间序列
    postings       *index.MemPostings // 内存倒排索引
    wal            *wlog.WL          // Write-Ahead Log
    wbl            *wlog.WL          // Write-Behind Log（乱序数据）
    chunkDiskMapper *chunks.ChunkDiskMapper // mmap chunk 管理
    
    minTime, maxTime atomic.Int64    // 当前 Head 的时间范围
    lastSeriesID     atomic.Uint64   // 最新的 series ID
    numSeries        atomic.Uint64   // 当前活跃序列数
    // ...
}
```

**stripeSeries（分片哈希表）**：Head 使用分片哈希表实现对时间序列的高并发访问。默认 16384 个分片（2^14），每个分片有独立的读写锁。写入时根据 series ref（序列引用ID）取模确定分片，从而实现细粒度的锁竞争。

```go
type stripeSeries struct {
    size    int                    // 分片数，默认 16384
    series  []map[chunks.HeadSeriesRef]*memSeries  // 每个分片维护一个 map
    hashes  []seriesHashmap        // 按标签哈希的映射
    locks   []stripeLock           // 每个分片的独立锁
}
```

**memSeries（内存中的单条时间序列）**：

```go
type memSeries struct {
    ref  chunks.HeadSeriesRef  // 唯一引用 ID
    lset labels.Labels         // 标签集
    
    mmapChunks []*mmapChunk    // 已 mmap 到磁盘的 chunk
    headChunks *memChunk       // 当前活跃的 chunk（正在追加数据）
    
    nextAt    int64            // 下一个 chunk 的切割时间
    lastValue float64          // 最后一个值（用于 XOR 编码）
    // ...
}
```

**MemPostings（内存倒排索引）**：提供 `label_name=label_value` → `[]SeriesRef` 的映射，实现基于标签的高效查询。

```go
type MemPostings struct {
    mtx     sync.RWMutex
    m       map[string]map[string][]storage.SeriesRef
    // 例如：m["job"]["api-server"] = [1, 5, 9, 12, ...]
    //       m["instance"]["10.0.0.1:9090"] = [1, 3, 7, ...]
}
```

#### 2.3 WAL（Write-Ahead Log）详细设计

WAL 是保证数据持久性的核心机制。当 Prometheus 接收到新的样本数据时，**先写 WAL 再更新内存**。如果进程崩溃，重启时可以通过回放 WAL 恢复 Head 中的数据。

**WAL 存储结构：**

```
data/
├── wal/                    # 有序数据的 WAL
│   ├── 00000000            # 段文件 0（最大 128MB）
│   ├── 00000001            # 段文件 1
│   ├── 00000002            # 段文件 2
│   └── checkpoint.00000001/ # 最近的 checkpoint
│       └── 00000000
└── wbl/                    # 乱序数据的 Write-Behind Log
    ├── 00000000
    └── 00000001
```

**WAL 格式细节：**

WAL 以 32KB 为页（Page）进行写入，每条记录（Record）的格式如下：

```
┌───────────┬──────────┬────────────┬───────────────┐
│ Type (1B) │ Len (2B) │ CRC32 (4B) │ Data (变长)   │
└───────────┴──────────┴────────────┴───────────────┘
```

**记录类型包括：**

| 类型编号 | 名称 | 内容描述 |
|---------|------|---------|
| 1 | Series | 新序列创建记录，包含 ref ID 和 labels |
| 2 | Samples | float64 样本值，包含 ref、timestamp、value |
| 3 | Tombstones | 删除标记，包含 ref、时间区间 |
| 4 | Exemplars | 带 trace ID 的采样点 |
| 5 | Histograms | 原生直方图样本 |
| 6 | FloatHistograms | 浮点直方图样本 |
| 7 | Metadata | 指标元数据（type、help、unit） |

**Checkpoint（检查点）机制：**

随着时间推移 WAL 段文件会不断积累，如果每次启动都从头回放所有段，启动时间会越来越长。Checkpoint 机制解决了这个问题：

1. 定期创建 Checkpoint——将仍然需要的 series 和 samples 记录写入 `checkpoint.NNNNNN` 目录
2. 删除 Checkpoint 之前的旧 WAL 段文件
3. 启动回放时：先回放最近的 Checkpoint，再回放 Checkpoint 之后的 WAL 段

**WAL 回放（Replay）过程：**

启动时的回放使用并行化策略加速：

```go
// 简化的回放流程
func (h *Head) loadWAL(r *wlog.Reader, workers int) error {
    // 1. 创建 worker pool，worker 数量 = GOMAXPROCS
    // 2. 依次读取 WAL 中的记录
    // 3. Series 记录：创建 memSeries 并注册到 stripeSeries
    // 4. Samples 记录：按 ref % workers 分发到对应 worker
    //    每个 worker 并行处理自己分片内的样本追加
    // 5. 等待所有 worker 完成
}
```

#### 2.4 数据压缩算法

Prometheus TSDB 使用针对时序数据特点优化的压缩算法（源自 Facebook Gorilla 论文）：

**时间戳压缩（Delta-of-Delta 编码）：**

大多数时间序列的采样间隔是固定的（如每 15 秒一次），因此：
- 一阶差分（Delta）几乎是常数（如都是 15000ms）
- 二阶差分（Delta-of-Delta）几乎是 0

编码规则：
- DoD == 0 → 写入 1 位 '0'
- |DoD| <= 8191 → 写入 '10' + 14位有效值
- |DoD| <= 65535 → 写入 '110' + 16位有效值
- |DoD| <= 524287 → 写入 '1110' + 20位有效值
- 其他 → 写入 '1111' + 64位原始值

**值压缩（XOR 编码）：**

相邻数据点的值通常非常接近（如 CPU 使用率连续几个采样都在 45% 左右），因此相邻值的 XOR 结果中会有大量的前导零和尾随零：

- XOR == 0（值完全相同）→ 写入 1 位 '0'
- 前导零和尾随零的数量与前一个 XOR 相同 → 写入 '10' + 有效位
- 其他 → 写入 '11' + 5位前导零数 + 6位有效位长度 + 有效位

**压缩效果：** 在典型的 15 秒采样间隔下，平均每个样本只需要约 1.37 字节（相比原始的 16 字节：8 字节时间戳 + 8 字节值）。

#### 2.5 Compaction（压缩合并）

Compaction 是将 Head 中的数据持久化为不可变 Block，以及合并多个小 Block 为大 Block 的过程。

**Head → Block 压缩（Head Compaction）：**

```
Head（保存最近 2h 数据）
    │
    │ 当 Head 中数据超过 chunkRange（默认2h）
    │ 或者 Head 中 chunk 数量超过阈值
    ▼
┌─────────────┐
│ 创建新 Block │  ← 将 Head 中超出时间窗口的数据写为不可变 Block
│ (2h 时间范围)│
└─────────────┘
```

**Block 合并压缩（Level Compaction）：**

多个小 Block 可以合并为更大的 Block，减少文件数量并提升查询效率：

```
Level 0:  [2h] [2h] [2h] [2h] [2h] [2h] [2h] [2h] [2h]
                    ↓ 合并
Level 1:  [────6h────] [────6h────] [────6h────]
                    ↓ 合并
Level 2:  [──────────18h──────────] [────6h────]
```

合并过程中会：
- 合并重叠的时间序列
- 应用 tombstones（物理删除已标记删除的数据）
- 重建索引

**垂直压缩（Vertical Compaction）：**

处理时间范围重叠的 Block（通常由于乱序数据或回填产生）。将重叠 Block 中相同序列的样本合并去重。

#### 2.6 索引结构

每个 Block 包含一个 `index` 文件，用于支持基于标签的高效查询。索引采用倒排索引（Inverted Index）结构：

```
Index 文件结构：
┌────────────────────────────────────────┐
│ Symbol Table                            │  ← 所有字符串的字典（label names, values）
├────────────────────────────────────────┤
│ Series                                  │  ← 所有序列的 labels + chunk 引用
├────────────────────────────────────────┤
│ Label Index (已废弃)                    │
├────────────────────────────────────────┤
│ Postings                                │  ← 倒排列表：label pair → series IDs
├────────────────────────────────────────┤
│ Postings Offset Table                   │  ← 倒排列表的偏移索引
├────────────────────────────────────────┤
│ TOC (Table of Contents)                 │  ← 各部分的偏移量
└────────────────────────────────────────┘
```

**查询示例：** 查询 `http_requests_total{job="api", method="GET"}`

1. 在 Postings 中找到 `job="api"` 对应的 series ID 列表：[1, 3, 5, 8, 12]
2. 在 Postings 中找到 `method="GET"` 对应的 series ID 列表：[2, 3, 5, 7, 12]
3. 对两个有序列表做交集：[3, 5, 12]
4. 根据 series ID 从 Series 部分获取完整 labels 和 chunk 引用
5. 根据 chunk 引用从 `chunks/` 目录读取实际数据

---

### 三、数据采集链路（Scrape Loop）

#### 3.1 整体框架

Scrape 子系统负责从监控目标拉取指标数据。其层级结构为：

```
scrape.Manager
  │
  ├── scrapePool ("prometheus" job)
  │     ├── scrapeLoop (target: localhost:9090)
  │     └── scrapeLoop (target: localhost:9091)
  │
  ├── scrapePool ("node_exporter" job)
  │     ├── scrapeLoop (target: node1:9100)
  │     └── scrapeLoop (target: node2:9100)
  │
  └── scrapePool ("mysql_exporter" job)
        └── scrapeLoop (target: mysql-exporter:9104)
```

- **scrape.Manager**：顶层管理器，接收 Service Discovery 的 target 更新
- **scrapePool**：对应配置中的一个 `job_name`，管理该 job 下所有 target 的 scrapeLoop
- **scrapeLoop**：对应一个具体的 target endpoint，负责周期性拉取

#### 3.2 ScrapeLoop 详细执行流程

每个 scrapeLoop 是一个独立的 goroutine，按照配置的 `scrape_interval` 周期执行以下步骤：

```go
// 简化的 scrapeLoop 执行流程 (scrape/scrape.go)
func (sl *scrapeLoop) run(errc chan<- error) {
    ticker := time.NewTicker(sl.interval)  // 按 scrape_interval 定时触发
    
    for {
        select {
        case <-ticker.C:
            sl.scrapeAndReport(sl.ctx)
        case <-sl.ctx.Done():
            return
        }
    }
}

func (sl *scrapeLoop) scrapeAndReport(ctx context.Context) {
    // 1. 创建带超时的 context
    scrapeCtx, cancel := context.WithTimeout(ctx, sl.timeout)
    defer cancel()
    
    // 2. 发送 HTTP GET 请求到目标的 /metrics 端点
    resp, err := sl.scraper.scrape(scrapeCtx)
    
    // 3. 解析响应内容（Prometheus text format 或 protobuf）
    // 4. 遍历每一个 metric sample
    for parser.Next() {
        metric, timestamp, value := parser.At()
        
        // 5. 应用 metric_relabel_configs（指标级别的重标记规则）
        labels = sl.applyRelabeling(metric)
        if labels == nil {
            continue  // 被 relabel 规则丢弃
        }
        
        // 6. 调用 TSDB Appender 写入数据
        ref, err := appender.Append(ref, labels, timestamp, value)
    }
    
    // 7. 追加内置指标（up、scrape_duration_seconds 等）
    sl.addReportSamples(appender, timestamp, scrapeDuration, err)
    
    // 8. 提交事务
    appender.Commit()
}
```

#### 3.3 HTTP 抓取细节

```go
// scraper 接口实现
type targetScraper struct {
    client  *http.Client
    req     *http.Request
    timeout time.Duration
}

func (s *targetScraper) scrape(ctx context.Context) (*http.Response, error) {
    // 配置请求头
    // Accept: application/openmetrics-text;version=1.0.0,
    //         application/openmetrics-text;version=0.0.1;q=0.75,
    //         text/plain;version=0.0.4;q=0.5
    // Accept-Encoding: gzip (如果启用压缩)
    
    return s.client.Do(s.req.WithContext(ctx))
}
```

**支持的响应格式：**
- `text/plain; version=0.0.4`：经典 Prometheus text 格式
- `application/openmetrics-text`：OpenMetrics 格式（支持 Exemplar）
- `application/vnd.google.protobuf`：Protocol Buffers 格式（Native Histogram）

#### 3.4 Write Path（写入路径）完整链路

从 scrapeLoop 到数据落盘的完整写入路径：

```
scrapeLoop.scrapeAndReport()
    │
    ▼
DB.Appender(ctx) → 返回 headAppender
    │
    ▼
headAppender.Append(ref, labels, t, v)
    │
    ├── 1. 如果是新序列（ref == 0）：
    │       → head.getOrCreate(labels)
    │       → 分配新的 HeadSeriesRef
    │       → 注册到 stripeSeries 和 MemPostings
    │
    ├── 2. 验证时间戳有序性
    │       → 如果乱序且启用了 OOO (Out-of-Order)：走 WBL 路径
    │
    └── 3. 将样本暂存到 headAppender 的 buffer 中
    
    │
    ▼
headAppender.Commit()
    │
    ├── 1. 将 Series 记录写入 WAL（仅新序列）
    ├── 2. 将 Samples 记录写入 WAL
    ├── 3. 将样本追加到对应 memSeries 的活跃 chunk 中
    │       → memSeries.append(t, v)
    │       → 使用 XOR 编码压缩存储
    └── 4. 更新 Head 的 minTime/maxTime
```

**源码关键位置：**
- `tsdb/head_append.go:407-498`：Append 方法实现
- `tsdb/head_append.go:661-680`：Commit 方法（写 WAL + 更新内存）
- `tsdb/head.go`：Head 管理（getOrCreate、GC 等）

#### 3.5 内置指标

每次 scrape 完成后，Prometheus 会自动追加几个关于 scrape 本身的指标：

| 指标名 | 类型 | 描述 |
|--------|------|------|
| `up` | Gauge | 1=scrape成功, 0=失败 |
| `scrape_duration_seconds` | Gauge | 本次 scrape 耗时 |
| `scrape_samples_scraped` | Gauge | 本次 scrape 获取的样本数 |
| `scrape_samples_post_metric_relabeling` | Gauge | relabel 后保留的样本数 |
| `scrape_series_added` | Gauge | 本次 scrape 新增的序列数 |

---

### 四、PromQL 查询引擎

#### 4.1 查询引擎架构

PromQL（Prometheus Query Language）是 Prometheus 的查询语言，引擎实现在 `promql/` 包中：

```
┌────────────────────────────────────────────────────┐
│                 PromQL Engine                        │
│                                                    │
│  ┌──────────┐   ┌──────────────┐   ┌───────────┐  │
│  │ Parser   │──→│ AST          │──→│ Evaluator │  │
│  │ (解析器) │   │ (抽象语法树) │   │ (评估器)  │  │
│  └──────────┘   └──────────────┘   └─────┬─────┘  │
│                                           │        │
│                                           ▼        │
│                                    ┌────────────┐  │
│                                    │ TSDB       │  │
│                                    │ Querier    │  │
│                                    └────────────┘  │
└────────────────────────────────────────────────────┘
```

#### 4.2 查询执行详细流程

以查询 `rate(http_requests_total{job="api"}[5m])` 为例：

```
Step 1: 解析（Parse）
─────────────────────
将 PromQL 字符串解析为 AST：

Call{
    Func: "rate",
    Args: [
        MatrixSelector{
            Name: "http_requests_total",
            LabelMatchers: [{Name:"job", Value:"api", Type:Equal}],
            Range: 5m
        }
    ]
}

Step 2: 创建查询（NewInstantQuery / NewRangeQuery）
──────────────────────────────────────────────────────
- 确定查询时间（instant query）或时间范围 + 步长（range query）
- 创建查询上下文，绑定超时和取消机制
- 设置最大采样数限制（防止 OOM）

Step 3: 执行评估（Eval）
─────────────────────────
递归遍历 AST，自底向上评估：

1. 评估 MatrixSelector：
   - 确定查询的时间区间：[queryTime-5m, queryTime]
   - 调用 Querier.Select(matchers...) 从 TSDB 获取匹配的序列
   - 对于 Head 中的数据：遍历 stripeSeries
   - 对于 Block 中的数据：使用倒排索引定位
   - 返回 Matrix（每个序列在时间范围内的所有样本点）

2. 评估 Call("rate")：
   - 对 Matrix 中的每个序列调用 rate 函数
   - rate 计算：(lastValue - firstValue) / (lastTimestamp - firstTimestamp)
   - 处理 Counter reset（如果值减小，说明 counter 重置了）
   - 返回 Vector（每个序列一个瞬时值）

Step 4: 返回结果
─────────────────
- Instant Query → 返回 Vector（向量）
- Range Query → 返回 Matrix（矩阵，每个步长一个 Vector）
```

#### 4.3 查询类型对比

| 特性 | Instant Query | Range Query |
|------|---------------|-------------|
| API | `/api/v1/query` | `/api/v1/query_range` |
| 参数 | `query`, `time` | `query`, `start`, `end`, `step` |
| 评估次数 | 1次（在 time 时刻） | 多次（从 start 到 end，每 step 一次） |
| 返回类型 | Vector/Scalar/String | Matrix |
| 典型用途 | 当前值、告警判断 | 图表渲染、趋势分析 |

#### 4.4 查询优化机制

- **预过滤（Pre-filtering）**：在读取数据前通过 Postings（倒排索引）快速定位匹配的序列，避免全表扫描
- **并发读取**：对于跨 Block 的查询，可以并行读取多个 Block
- **样本限制**：通过 `--query.max-samples` 限制单次查询最大样本数，防止 OOM
- **超时控制**：通过 `--query.timeout` 设置查询超时时间
- **缓存**：查询结果不做缓存（由上层如 Thanos Query Frontend 处理）

#### 4.5 关键源码路径

| 文件 | 职责 |
|------|------|
| `promql/engine.go` | 引擎核心，协调解析和执行 |
| `promql/parser/parser.go` | PromQL 解析器（yacc 生成） |
| `promql/parser/ast.go` | AST 节点定义 |
| `promql/functions.go` | 所有内置函数的实现 |
| `promql/value.go` | 值类型定义（Vector, Matrix, Scalar） |

---

### 五、服务发现机制

#### 5.1 三层流水线架构

服务发现（Service Discovery，简称 SD）负责动态感知监控目标的变化。其内部采用三层流水线设计：

```
┌──────────────────┐     ┌───────────────────┐     ┌────────────────┐
│  External Source │     │  Discovery Layer   │     │  Consumption   │
│  (外部数据源)    │     │  (发现层)          │     │  Layer         │
│                  │     │                    │     │  (消费层)      │
│  K8s API Server  │────→│  Discoverer 实现   │     │                │
│  Consul          │     │      │             │     │                │
│  DNS             │     │      ▼             │     │                │
│  File            │     │  Provider          │     │                │
│  HTTP            │     │      │             │     │                │
│  EC2/GCE/Azure   │     │      ▼             │     │                │
│                  │     │  discovery.Manager  │────→│ scrape.Manager │
│                  │     │  (聚合 + 节流)     │     │                │
└──────────────────┘     └───────────────────┘     └────────────────┘
```

#### 5.2 核心接口

```go
// 所有发现机制必须实现的接口
type Discoverer interface {
    // Run 启动发现过程，当发现新的 target groups 时通过 channel 发送
    // 必须首先发送完整的初始 target groups，然后按变化发送增量更新
    // 当 ctx 取消时必须退出
    Run(ctx context.Context, up chan<- []*targetgroup.Group)
}

// TargetGroup 描述一组具有共同标签的 targets
type Group struct {
    Targets []model.LabelSet  // 目标列表
    Labels  model.LabelSet    // 共享标签
    Source  string            // 来源标识
}
```

#### 5.3 Discovery Pipeline 详细流程

```
1. Discoverer.Run() 监视外部源
   │
   │ 产生 []*targetgroup.Group
   ▼
2. Provider 接收并转发到 Manager 的 updates channel
   │
   ▼
3. Manager.updater() goroutine 接收更新
   │ → 合并到全局 targets map
   │ → 通知 sender
   ▼
4. Manager.sender() goroutine（节流阀）
   │ → 等待 updatert 间隔（默认 5 秒）后发送
   │ → 避免高频变化淹没下游
   ▼
5. SyncCh() channel 输出最终的 target groups
   │
   ▼
6. scrape.Manager 消费 target groups
   │ → 对比当前 targets 和新 targets
   │ → 创建/销毁 scrapeLoop
```

#### 5.4 Relabeling（重标记）

在 target 发现之后、实际 scrape 之前，Prometheus 会应用 `relabel_configs` 对目标标签进行转换。这是一个非常强大的机制：

```yaml
relabel_configs:
  # 只保留特定命名空间的 Pod
  - source_labels: [__meta_kubernetes_namespace]
    regex: "production|staging"
    action: keep
    
  # 将 Pod 名称设置为 instance 标签
  - source_labels: [__meta_kubernetes_pod_name]
    target_label: instance
    
  # 丢弃内部指标
  - source_labels: [__name__]
    regex: "go_.*"
    action: drop
```

**Relabeling 动作类型：**

| Action | 描述 |
|--------|------|
| replace | 用正则替换标签值（默认） |
| keep | 只保留匹配的 target |
| drop | 丢弃匹配的 target |
| hashmod | 对标签值取哈希模，用于分片 |
| labelmap | 用正则匹配标签名并重命名 |
| labeldrop | 删除匹配的标签 |
| labelkeep | 只保留匹配的标签 |

#### 5.5 内置发现机制

| 类别 | 具体实现 | 数据源 |
|------|---------|--------|
| 容器编排 | kubernetes_sd | K8s API Server（Node/Pod/Service/Endpoint/Ingress） |
| 容器编排 | docker_sd | Docker Engine API |
| 容器编排 | nomad_sd | HashiCorp Nomad |
| 云平台 | ec2_sd | AWS EC2 |
| 云平台 | azure_sd | Azure Resource Manager |
| 云平台 | gce_sd | Google Compute Engine |
| 注册中心 | consul_sd | HashiCorp Consul |
| 注册中心 | eureka_sd | Netflix Eureka |
| 通用 | file_sd | 本地 JSON/YAML 文件 |
| 通用 | http_sd | HTTP 端点 |
| 通用 | dns_sd | DNS SRV/A/AAAA 记录 |
| 静态 | static_configs | 配置文件中直接指定 |

---

### 六、Remote Read/Write 远程存储

#### 6.1 设计理念

Prometheus 本地 TSDB 是单节点设计，存在两个核心限制：
1. **容量有限**：单机磁盘容量有上限
2. **单点风险**：数据只存一份，机器故障会丢数据

Remote Read/Write 机制通过定义标准化的接口协议，让 Prometheus 可以对接第三方分布式存储系统。

#### 6.2 Remote Write 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     Prometheus Server                             │
│                                                                  │
│  TSDB Head                                                       │
│    │                                                             │
│    │ 新数据写入                                                   │
│    ▼                                                             │
│  ┌───────────────────────────────────────────────────────────┐   │
│  │  remote.QueueManager (队列管理器)                          │   │
│  │                                                            │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐               │   │
│  │  │ Shard 0  │  │ Shard 1  │  │ Shard N  │  ← 动态分片   │   │
│  │  │ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │               │   │
│  │  │ │Buffer│ │  │ │Buffer│ │  │ │Buffer│ │               │   │
│  │  │ └──┬───┘ │  │ └──┬───┘ │  │ └──┬───┘ │               │   │
│  │  │    │     │  │    │     │  │    │     │               │   │
│  │  │    ▼     │  │    ▼     │  │    ▼     │               │   │
│  │  │ Batch    │  │ Batch    │  │ Batch    │  ← 批量发送   │   │
│  │  │ Send     │  │ Send     │  │ Send     │               │   │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘               │   │
│  └───────┼──────────────┼──────────────┼─────────────────────┘   │
│          │              │              │                          │
└──────────┼──────────────┼──────────────┼──────────────────────────┘
           │              │              │
           ▼              ▼              ▼
     ┌─────────────────────────────────────────┐
     │         Remote Write Endpoint            │
     │  (Thanos Receive / Cortex / Mimir /     │
     │   VictoriaMetrics / InfluxDB ...)       │
     └─────────────────────────────────────────┘
```

**核心特性：**

- **协议格式**：HTTP POST + Protocol Buffers 编码 + Snappy 压缩
- **动态分片**：根据积压队列长度自动调整 shard 数量（min_shards ~ max_shards）
- **批量发送**：积攒到 batch_send_deadline 或 max_samples_per_send 时批量发送
- **重试机制**：支持指数退避重试
- **WAL-based**：通过 WAL Watcher 监视 WAL 变化，确保不丢数据

#### 6.3 Remote Read

Remote Read 用于查询远程存储中的历史数据：

```
Grafana/PromQL 查询
    │
    ▼
Prometheus Query Engine
    │
    ├── 查询本地 TSDB（最近数据）
    │
    └── 查询 Remote Read Endpoint（历史数据）
         │
         │ HTTP POST (protobuf + snappy)
         ▼
    Remote Storage (Thanos Store / VictoriaMetrics ...)
```

#### 6.4 主流远程存储方案对比

| 方案 | 架构特点 | 适用场景 |
|------|---------|---------|
| Thanos | Sidecar + Store Gateway + Compactor，对象存储 | 多集群联邦、长期存储 |
| Cortex/Mimir | 微服务架构，写入/查询/压缩分离 | 大规模多租户 SaaS |
| VictoriaMetrics | 单二进制或集群模式，自研压缩 | 高性能、低资源消耗 |
| M3DB | 分布式时序数据库 | 超大规模指标存储 |

---

### 七、Alertmanager 告警架构

#### 7.1 告警流程全景

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Prometheus Server                               │
│                                                                      │
│  rules.Manager                                                       │
│    │                                                                 │
│    ├── Recording Rules（预计算规则）                                   │
│    │   → 定期计算 PromQL 表达式，结果写回 TSDB                       │
│    │   → 例如：record: job:http_requests:rate5m                      │
│    │         expr: sum(rate(http_requests_total[5m])) by (job)       │
│    │                                                                 │
│    └── Alerting Rules（告警规则）                                     │
│        → 定期评估告警条件                                             │
│        → 例如：alert: HighErrorRate                                  │
│              expr: rate(http_errors_total[5m]) > 0.05                │
│              for: 5m  ← Pending 持续 5 分钟才变为 Firing             │
│                                                                      │
│        评估结果：                                                     │
│        ┌──────┐   ┌─────────┐   ┌─────────┐                        │
│        │Inactive│─→│ Pending │─→│ Firing  │                        │
│        └──────┘   └─────────┘   └────┬────┘                        │
│                                       │                              │
│                            notifier.Manager                          │
│                                       │                              │
└───────────────────────────────────────┼──────────────────────────────┘
                                        │ HTTP POST /api/v2/alerts
                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Alertmanager                                   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Dispatch Pipeline                                            │   │
│  │                                                               │   │
│  │  1. 接收告警                                                  │   │
│  │     ▼                                                         │   │
│  │  2. 去重（Deduplication）                                     │   │
│  │     → 相同 fingerprint 的告警合并                             │   │
│  │     ▼                                                         │   │
│  │  3. 分组（Grouping）                                          │   │
│  │     → 按 group_by 标签聚合，如 [alertname, cluster]          │   │
│  │     ▼                                                         │   │
│  │  4. 抑制（Inhibition）                                        │   │
│  │     → 高优先级告警抑制低优先级告警                            │   │
│  │     ▼                                                         │   │
│  │  5. 静默（Silencing）                                         │   │
│  │     → 匹配静默规则的告警被跳过                                │   │
│  │     ▼                                                         │   │
│  │  6. 路由（Routing）                                           │   │
│  │     → 按路由树匹配告警标签，分发到对应 receiver              │   │
│  │     ▼                                                         │   │
│  │  7. 通知（Notification）                                      │   │
│  │     → 发送到配置的渠道                                        │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  通知渠道：                                                          │
│  Email │ Slack │ PagerDuty │ Webhook │ 钉钉 │ 企业微信 │ ...       │
└─────────────────────────────────────────────────────────────────────┘
```

#### 7.2 告警规则评估机制

```go
// rules/manager.go 中的核心逻辑
type Manager struct {
    groups map[string]*Group  // 规则分组
    // ...
}

type Group struct {
    name     string
    rules    []Rule      // 规则列表（AlertingRule 或 RecordingRule）
    interval time.Duration  // 评估间隔
    // ...
}

// 每个 Group 独立运行一个 goroutine
func (g *Group) run(ctx context.Context) {
    iter := 0
    ticker := time.NewTicker(g.interval)
    
    for {
        select {
        case <-ticker.C:
            g.Eval(ctx, time.Now())  // 评估所有规则
            iter++
        case <-ctx.Done():
            return
        }
    }
}

func (g *Group) Eval(ctx context.Context, ts time.Time) {
    for _, rule := range g.rules {
        // 使用 PromQL 引擎执行规则表达式
        result, err := rule.Eval(ctx, ts, g.opts.QueryFunc, ...)
        
        switch r := rule.(type) {
        case *AlertingRule:
            // 更新告警状态（Inactive/Pending/Firing）
            // 如果状态变为 Firing，通知 notifier.Manager
        case *RecordingRule:
            // 将计算结果写回 TSDB
        }
    }
}
```

#### 7.3 高可用部署

Alertmanager 支持集群模式部署，多实例之间通过 **Gossip 协议**（基于 hashicorp/memberlist）同步以下状态：

- 通知日志（Notification Log）：哪些告警已经发送了通知
- 静默规则（Silences）：当前生效的静默规则

这确保了在多实例部署下，同一个告警只会产生一次通知（去重），而不会每个实例都发一遍。

---

### 八、Prometheus 源码目录结构

```
prometheus/prometheus (GitHub)
│
├── cmd/prometheus/main.go       # 程序入口：组件初始化、生命周期管理、配置热加载
│
├── config/                       # 配置文件解析（prometheus.yml → Go struct）
│   └── config.go                # 主配置结构体定义
│
├── discovery/                    # 服务发现子系统
│   ├── manager.go               # SD Manager（聚合所有 discoverer 的输出）
│   ├── kubernetes/              # Kubernetes SD 实现
│   ├── consul/                  # Consul SD
│   ├── dns/                     # DNS SD
│   ├── file/                    # File SD
│   ├── http/                    # HTTP SD
│   ├── azure/                   # Azure SD
│   ├── aws/                     # AWS EC2/Lightsail SD
│   └── ...                      # 更多 SD 实现
│
├── scrape/                       # 指标抓取子系统
│   ├── manager.go               # Scrape Manager
│   ├── scrape.go                # scrapeLoop 核心（HTTP 抓取 + 样本处理）
│   └── target.go                # Target 结构定义
│
├── tsdb/                         # 时序数据库（核心存储引擎）
│   ├── db.go                    # DB 顶层结构和协调逻辑
│   ├── head.go                  # Head（内存层）管理
│   ├── head_append.go           # 写入路径（Append + Commit）
│   ├── head_read.go             # Head 读取路径
│   ├── head_wal.go              # WAL 回放逻辑
│   ├── compact.go               # Compaction（压缩合并）
│   ├── block.go                 # Block 结构（不可变磁盘块）
│   ├── querier.go               # 查询器实现
│   ├── index/                   # 倒排索引实现
│   │   ├── index.go             # 索引读写
│   │   └── postings.go          # Postings（倒排列表）
│   ├── chunks/                  # Chunk 编码/解码
│   │   ├── head_chunks.go       # Head 中的 chunk 管理
│   │   └── chunks.go            # 磁盘 chunk 读写
│   ├── wlog/                    # WAL 实现
│   │   ├── wlog.go              # WAL 核心读写
│   │   ├── checkpoint.go        # Checkpoint 创建和回放
│   │   └── watcher.go           # WAL Watcher（供 Remote Write 使用）
│   ├── record/                  # WAL 记录类型定义和编解码
│   └── docs/format/             # 磁盘格式文档
│       ├── wal.md               # WAL 格式规范
│       ├── chunks.md            # Chunk 格式规范
│       └── index.md             # Index 格式规范
│
├── promql/                       # PromQL 查询引擎
│   ├── engine.go                # 引擎核心（查询调度、超时、限制）
│   ├── functions.go             # 所有内置函数实现（rate, sum, avg...）
│   ├── value.go                 # 值类型（Vector, Matrix, Scalar）
│   └── parser/                  # PromQL 解析器
│       ├── parser.go            # 语法解析（yacc 生成）
│       ├── lex.go               # 词法分析
│       └── ast.go               # AST 节点定义
│
├── rules/                        # 规则引擎
│   ├── manager.go               # Rules Manager（调度规则评估）
│   ├── alerting.go              # AlertingRule 实现
│   └── recording.go             # RecordingRule 实现
│
├── notifier/                     # 告警通知
│   └── notifier.go              # Notifier Manager（发送告警到 Alertmanager）
│
├── storage/                      # 存储抽象层
│   ├── interface.go             # 存储接口定义（Queryable, Appendable...）
│   └── remote/                  # 远程存储
│       ├── write.go             # Remote Write 客户端
│       ├── read.go              # Remote Read 客户端
│       └── queue_manager.go     # 远程写入队列管理
│
├── web/                          # Web 层
│   ├── web.go                   # Web Handler（路由注册）
│   ├── api/v1/api.go            # HTTP API 实现
│   └── ui/                      # 前端 UI 静态文件
│
├── model/                        # 数据模型（Label, Sample, Metric...）
│
└── documentation/                # 架构文档
    └── internal_architecture.md  # 内部架构说明
```

---

## 第二部分：Grafana 深度剖析

### 九、Grafana 整体架构设计

#### 9.1 定位与设计理念

Grafana 是一个**可观测性与数据可视化平台**，核心设计理念是：

1. **插件化**：一切皆插件——数据源、面板、应用都是插件，核心只提供框架和基础设施
2. **数据源无关**：不存储监控数据，通过统一接口对接任意数据源
3. **前后端分离**：后端（Go）负责 API、认证、插件管理；前端（React）负责 UI 渲染
4. **统一可观测性**：整合 Metrics（Prometheus）、Logs（Loki）、Traces（Tempo）三大支柱

#### 9.2 架构层次

```
┌─────────────────────────────────────────────────────────────────────┐
│                          用户浏览器                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │              Grafana Frontend (React SPA)                      │  │
│  │  AppChrome │ Dashboard │ Explore │ Alerting │ Admin           │  │
│  └───────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
                                           │ HTTP/WebSocket
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Grafana Backend (Go)                              │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  API Layer                                                    │    │
│  │  Legacy REST API │ K8s-style Aggregated API Server           │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Service Layer                                                │    │
│  │  Dashboard Service │ Datasource Service │ Auth/IAM           │    │
│  │  NGAlert (Alerting) │ Plugin Manager │ Rendering             │    │
│  │  Live (WebSocket) │ Search │ Annotations                     │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Plugin Runtime                                               │    │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐               │    │
│  │  │Plugin Proc│  │Plugin Proc│  │Plugin Proc│  (gRPC 子进程)  │    │
│  │  │(Prometheus)│  │(Loki)    │  │(Custom)   │               │    │
│  │  └───────────┘  └───────────┘  └───────────┘               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Storage Layer                                                │    │
│  │  SQLite/MySQL/PostgreSQL (元数据) │ Unified Storage (新架构)  │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 十、后端架构（Go）

#### 10.1 启动流程与依赖注入

Grafana 后端使用 **Google Wire** 框架进行编译时依赖注入。所有服务的装配逻辑定义在 `pkg/server/wire.go` 中。

启动流程：

```
cmd/grafana-server/main.go
    │
    ▼
server.Initialize()   ← Wire 生成的依赖注入代码
    │
    ├── 创建 Setting（配置系统）
    ├── 创建 SqlStore（数据库连接）
    ├── 创建 PluginManager（插件管理）
    ├── 创建 HTTPServer（API 服务）
    ├── 创建 NGAlert（告警引擎）
    ├── 创建 RenderingService（图片渲染）
    ├── ... 创建所有其他服务
    │
    ▼
server.Run()
    │
    ├── 按优先级调用所有 Service.Init()
    │   → SqlStore.Init()    ← 最高优先级，初始化数据库
    │   → HTTPServer.Init()  ← 注册路由
    │   → ...
    │
    └── 启动所有 BackgroundService.Run()
        → HTTPServer.Run()    ← 监听端口，处理请求
        → NGAlert.Run()       ← 告警调度循环
        → Live.Run()          ← WebSocket 服务
        → ...
```

#### 10.2 服务体系

Grafana 定义了两种服务接口：

```go
// 基础服务——只需初始化
type Service interface {
    Init() error
}

// 后台服务——需要持续运行
type BackgroundService interface {
    Service
    Run(ctx context.Context) error
}
```

核心服务说明：

| 服务 | 职责 | 优先级 |
|------|------|--------|
| SqlStore | 数据库连接、表迁移、注册 SQL Handler | 最高 |
| HTTPServer | HTTP/HTTPS 监听、路由注册、中间件 | 最高 |
| PluginManager | 插件发现、加载、生命周期 | 高 |
| NGAlert | Unified Alerting 调度和评估 | 中 |
| RenderingService | Dashboard/Panel 截图渲染 | 中 |
| Live | Grafana Live（WebSocket 实时推送） | 中 |
| UsageStatsService | 使用统计采集和上报 | 低 |

#### 10.3 事件总线（Bus）模式

Grafana 使用基于反射的同步消息总线来降低组件耦合：

```go
// 发送查询请求
query := &models.GetDashboardQuery{Slug: "my-dashboard"}
err := bus.Dispatch(ctx, query)
// query.Result 中包含查询结果

// 注册 Handler（通常在 SqlStore.Init() 中）
bus.AddHandler("sql", func(ctx context.Context, query *models.GetDashboardQuery) error {
    // 执行 SQL 查询
    query.Result = dashboard
    return nil
})
```

这种模式让业务逻辑层不需要直接依赖数据库层——只需要定义 Command/Query 消息结构，具体的数据访问由注册的 Handler 完成。

#### 10.4 API 体系演进

**Legacy API**（当前主体）：

```go
// pkg/api/api.go 中的路由注册
func (hs *HTTPServer) registerRoutes() {
    // Dashboard API
    r.Get("/api/dashboards/uid/:uid", hs.GetDashboard)
    r.Post("/api/dashboards/db", hs.PostDashboard)
    r.Delete("/api/dashboards/uid/:uid", hs.DeleteDashboard)
    
    // DataSource API
    r.Get("/api/datasources", hs.GetDataSources)
    r.Post("/api/datasources", hs.AddDataSource)
    
    // Query API（面板查询入口）
    r.Post("/api/ds/query", hs.QueryMetricsV2)
    
    // 更多路由...
}
```

**K8s-style API**（新架构，正在迁移中）：

Grafana 嵌入了 `k8s.io/apiserver`，将核心资源建模为 Kubernetes 风格的 API：

```
/apis/dashboard.grafana.app/v0alpha1/namespaces/{org}/dashboards/{uid}
/apis/alerting.grafana.app/v0alpha1/namespaces/{org}/alertrules/{uid}
/apis/plugins.grafana.app/v0alpha1/pluginmeta
```

#### 10.5 数据库存储

Grafana 使用关系型数据库存储所有元数据（不存储监控数据本身）：

| 表名 | 存储内容 |
|------|---------|
| dashboard | Dashboard 定义（JSON 格式） |
| data_source | 数据源配置（URL、认证信息等） |
| user | 用户信息 |
| org | 组织（多租户隔离） |
| org_user | 用户-组织关系及角色 |
| alert_rule | 告警规则定义 |
| annotation | 注解（事件标记） |
| api_key | API Key |
| plugin_setting | 插件配置 |

ORM 框架使用 **xorm**，数据库迁移通过版本号递增的迁移脚本自动执行。

---

### 十一、前端架构（React）

#### 11.1 技术栈

| 技术 | 用途 |
|------|------|
| React 18+ | UI 框架 |
| TypeScript | 类型安全 |
| Redux | 全局状态管理 |
| Emotion | CSS-in-JS 样式方案 |
| Monaco Editor | 代码/查询编辑器 |
| D3.js / uPlot | 图表渲染底层 |
| React Router | 客户端路由 |
| Webpack | 构建打包 |

#### 11.2 Monorepo 包架构

```
packages/
├── @grafana/data          # 核心数据类型和工具
│   ├── DataFrame          # 标准化数据格式（类似二维表）
│   ├── DataSourceApi      # 数据源插件接口
│   ├── DataQuery          # 查询请求基础类型
│   ├── FieldConfig        # 字段配置（颜色、单位、阈值）
│   └── Transformations    # 数据转换框架
│
├── @grafana/ui            # UI 组件库（对外公开）
│   ├── Button, Input, Select   # 基础组件
│   ├── Graph, Table, Stat      # 可视化组件
│   ├── QueryField, CodeEditor  # 编辑器组件
│   └── Theme system            # 主题系统（Light/Dark）
│
├── @grafana/runtime       # 运行时服务
│   ├── BackendSrv         # HTTP 请求服务（封装 fetch）
│   ├── LocationService    # 路由服务
│   ├── getTemplateSrv()   # 模板变量服务
│   └── config             # 全局配置获取
│
├── @grafana/schema        # Schema 定义
│   └── Dashboard schema   # Dashboard JSON Schema
│
└── @grafana/scenes        # Scene 框架（新一代 Dashboard 状态管理）
    ├── SceneObject        # 场景对象基类
    ├── SceneQueryRunner   # 查询执行器
    └── VizPanel           # 可视化面板
```

#### 11.3 DataFrame——统一数据格式

DataFrame 是 Grafana 的核心数据抽象，所有数据源返回的数据都必须转换为此格式：

```typescript
interface DataFrame {
    name?: string;           // 数据帧名称
    fields: Field[];         // 字段列表（每个字段是一列）
    length: number;          // 行数
    meta?: DataFrameMeta;    // 元数据
}

interface Field {
    name: string;            // 字段名
    type: FieldType;         // 类型（time, number, string, boolean）
    values: any[];           // 值数组
    config: FieldConfig;     // 配置（单位、颜色、阈值）
    labels?: Labels;         // 标签
}
```

#### 11.4 Scene Framework

Grafana 正在将 Dashboard 状态管理迁移到自研的 **Scene Framework**：

```
DashboardScene (根节点)
├── $timeRange: SceneTimeRange
├── $variables: SceneVariableSet
├── body: SceneGridLayout
│   ├── DashboardGridItem
│   │   └── VizPanel
│   │       └── $data: SceneQueryRunner
│   │             └── queries: [PromQuery, ...]
│   └── ...
└── controls: [TimePicker, RefreshPicker]
```

---

### 十二、数据源插件机制

#### 12.1 数据源代理

前端不直接访问外部数据源，通过后端代理转发：

```
浏览器 → POST /api/ds/query → Grafana Backend → Prometheus HTTP API
                                    ↑
                          查找数据源配置
                          注入认证信息
                          转发请求
```

#### 12.2 后端插件通信（gRPC）

有后端组件的数据源插件作为独立子进程运行，通过 gRPC 通信：

```go
// grafana-plugin-sdk-go 定义的核心接口
type QueryDataHandler interface {
    QueryData(ctx context.Context, req *backend.QueryDataRequest) (*backend.QueryDataResponse, error)
}

type CheckHealthHandler interface {
    CheckHealth(ctx context.Context, req *backend.CheckHealthRequest) (*backend.CheckHealthResult, error)
}

type CallResourceHandler interface {
    CallResource(ctx context.Context, req *backend.CallResourceRequest, sender backend.CallResourceResponseSender) error
}
```

---

### 十三、Dashboard 渲染流水线

完整流程：加载 Dashboard JSON → 构建 Scene 树 → 解析变量 → 执行查询 → 数据转换 → 面板渲染。

详见第十四章「查询数据流完整链路」。

---

### 十四、查询数据流完整链路

以查询 `rate(http_requests_total{job="api"}[5m])` 为例的完整链路：

```
Phase 1: 前端触发
───────────────────
用户操作（时间范围变化/变量切换/刷新）
    → SceneQueryRunner 检测到依赖变化
    → 构造 QueryDataRequest
    → BackendSrv.fetch("POST /api/ds/query", body)

Phase 2: 后端路由
───────────────────
HTTPServer.QueryMetricsV2() 接收请求
    → 认证和权限检查
    → 从 DataSourceCache 获取数据源配置
    → 根据数据源类型找到对应插件

Phase 3: 插件执行
───────────────────
Prometheus 插件进程收到 gRPC QueryData 请求
    → 解析查询参数（expr, start, end, step）
    → 构造 Prometheus HTTP API 请求
    → GET http://prometheus:9090/api/v1/query_range?...

Phase 4: Prometheus 执行
───────────────────────────
PromQL Engine 解析并执行查询
    → 通过倒排索引定位序列
    → 从 Head/Blocks 读取数据
    → 计算 rate 函数
    → 返回 JSON 响应

Phase 5: 数据转换
───────────────────
插件将 Prometheus JSON 转换为 DataFrame
    → 时间戳转毫秒
    → 应用 legendFormat
    → 通过 gRPC 返回

Phase 6: 前端渲染
───────────────────
SceneQueryRunner 收到 DataFrame
    → 应用 Transformations（可选）
    → 传递给面板插件 React 组件
    → 读取 FieldConfig（单位、颜色、阈值）
    → 使用 uPlot 渲染折线图
    → 用户看到实时图表
```

---

### 十五、告警系统架构（Unified Alerting）

Grafana 8.0+ 的 Unified Alerting（NGAlert）将告警从 Dashboard Panel 中独立出来：

**核心组件：**
- **Scheduler**：按评估间隔调度规则评估
- **Evaluator**：执行查询和条件判断
- **State Manager**：管理告警状态转换（Normal → Pending → Alerting → Resolved）
- **内嵌 Alertmanager**：去重、分组、路由、通知

**状态流转：**
```
Normal ──条件满足──→ Pending ──持续 for 时间──→ Alerting
  ↑                                                │
  └──────────── 条件不再满足 ←──── Resolved ───────┘
```

---

### 十六、插件系统设计

#### 16.1 三种插件类型

- **Data Source Plugin**：连接外部数据源，实现 QueryData/CheckHealth
- **Panel Plugin**：自定义可视化组件，接收 DataFrame 渲染图表
- **App Plugin**：复合型插件，可包含多个面板/数据源/自定义页面

#### 16.2 插件生命周期

```
启动时 PluginManager.Init()
    → 扫描插件目录
    → 解析 plugin.json
    → 注册到 PluginStore
    → 对有后端的插件：启动子进程 + 建立 gRPC 连接
    → 运行时：前端按需加载 module.js
    → 插件崩溃时自动重启
```

---

### 十七、Grafana 源码目录结构

```
grafana/grafana
├── apps/                    # K8s 风格独立 Go 模块
├── packages/                # 前端 npm 包（@grafana/data, ui, runtime, scenes）
├── pkg/                     # Go 后端核心
│   ├── api/                # HTTP API Handler
│   ├── cmd/                # CLI 入口
│   ├── server/             # Wire 依赖注入
│   ├── services/           # 核心服务（ngalert, datasources, plugins...）
│   ├── tsdb/               # 内置数据源后端（prometheus, loki...）
│   └── setting/            # 配置系统
├── public/                  # 前端代码
│   ├── app/core/           # 核心模块（AppChrome、导航）
│   ├── app/features/       # 功能模块（dashboard, explore, alerting）
│   └── app/plugins/        # 内置插件（datasource/, panel/）
└── conf/                    # 配置文件模板
```

---

## 第三部分：Prometheus + Grafana 集成原理

### 十八、集成内部实现

#### 18.1 Prometheus 数据源插件

Prometheus 作为 Grafana 最核心的内置数据源，前后端代码位于：

- 前端：`public/app/plugins/datasource/prometheus/`
- 后端：`pkg/tsdb/prometheus/`

#### 18.2 PromQL 查询编辑器

提供两种模式：
- **Code Mode**：基于 Monaco Editor，集成语法高亮和自动补全
- **Builder Mode**：可视化下拉菜单构建查询

#### 18.3 Step 自动计算

```
step = max(
    floor(timeRange / maxDataPoints),
    scrapeInterval
)
```

**内置变量：**
- `$__interval`：自动计算的步长
- `$__rate_interval`：推荐的 rate 区间 = `max(4 * scrapeInterval, step + scrapeInterval)`
- `$__range`：查询时间范围

#### 18.4 数据格式转换

```
Prometheus JSON (matrix) → Grafana DataFrame

转换规则：
- metric labels → Field.labels
- timestamps (秒) → Time field (毫秒)
- values (string) → Number field (float64)
- legendFormat 模板 → Field.config.displayName
```

---

## 第四部分：面试高频问题与总结

### 核心问题梳理

**Q1: Prometheus 为什么选择 Pull 模型而不是 Push？**

Pull 模型让监控目标与监控系统解耦，被监控服务只需暴露 HTTP 端点，不需要集成 SDK 或知道监控服务器地址。同时 Pull 天然提供健康检查能力（无法拉取 = 目标可能故障），且避免了 Push 模型下的推送风暴问题。对于短生命周期任务则通过 Pushgateway 补充。

**Q2: TSDB 的两层存储模型解决了什么问题？**

Head（内存层）保证了写入的高性能——新数据先写 WAL 再追加到内存中的 chunk，无需磁盘随机写。Block（磁盘层）保证了历史数据的高效压缩存储和查询——不可变的 Block 可以高效压缩，倒排索引支持快速的标签查询。两层之间通过 Compaction 机制衔接。

**Q3: WAL 的作用是什么？为什么需要 Checkpoint？**

WAL 保证了 Head 中内存数据的持久性——崩溃后可以通过回放 WAL 恢复。Checkpoint 是 WAL 在某个时刻的快照，存储仍然需要的记录，启动时只需回放 Checkpoint + 后续 WAL 段，避免回放全部历史 WAL 导致启动过慢。

**Q4: Prometheus 的压缩算法为什么高效？**

利用了时序数据的两个统计特征：时间戳间隔几乎固定（Delta-of-Delta 接近 0，只需 1 bit）；相邻值非常接近（XOR 结果前导零多，只需记录有效位）。平均每个样本仅需 ~1.37 字节。

**Q5: Grafana 为什么要做数据源代理？**

解决三个问题：安全性（敏感凭据不暴露给浏览器）、跨域（浏览器同源策略限制）、权限控制（后端可以做细粒度的访问控制和审计）。

**Q6: Grafana 的插件为什么要作为独立进程运行？**

隔离性：插件崩溃不影响主进程；安全性：插件运行在受限沙箱中；独立性：插件可以使用任何 Go 库而不影响 Grafana 版本；生命周期管理：可以独立更新、重启插件。

**Q7: Prometheus 如何做到水平扩展？**

Prometheus 单节点设计不原生支持水平扩展，但社区方案通过以下方式实现：Thanos（Sidecar + 对象存储 + 全局查询视图）、Cortex/Mimir（微服务架构，写入/查询/压缩分离）、VictoriaMetrics（集群模式）。在美团内部，MPS 通过抓取服务 + Mafka 消息队列 + VictoriaMetrics 存储引擎实现了分布式 Prometheus。

**Q8: PromQL 查询引擎的执行流程是怎样的？**

字符串 → Parser 解析为 AST → Engine 创建查询上下文 → Evaluator 递归遍历 AST 自底向上评估 → 叶子节点通过 Querier.Select() 从 TSDB 获取数据 → 中间节点执行聚合/函数计算 → 返回 Vector/Matrix 结果。

---

### 参考资料

**Prometheus 官方文档与源码：**
- [Prometheus Internal Architecture](https://github.com/prometheus/prometheus/blob/main/documentation/internal_architecture.md)
- [TSDB Format Documentation](https://github.com/prometheus/prometheus/tree/main/tsdb/docs/format)
- [Remote Write Specification](https://prometheus.io/docs/specs/remote_write_spec_2_0/)

**Prometheus 深度分析：**
- [DeepWiki - Prometheus 源码文档](https://deepwiki.com/prometheus/prometheus)
- [Prometheus Storage Engine 分析](https://liujiacai.net/blog/2021/04/11/prometheus-storage-engine/)
- [Prometheus 源码分析 - Scrape 模块](https://segmentfault.com/a/1190000041238216)
- [Prometheus Architecture Internals](https://www.youngju.dev/blog/prometheus/prometheus_architecture_internals.en)

**Grafana 官方文档与源码：**
- [Grafana Architecture Overview](https://deepwiki.com/grafana/grafana/3-architecture-overview)
- [Grafana Plugin SDK for Go](https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins/)
- [An Inside Look at React in Grafana](https://grafana.com/blog/an-inside-look-at-how-react-powers-grafanas-frontend/)

**Grafana 源码分析：**
- [Grafana 源码分析 - 黄挤挤（博客园）](https://www.cnblogs.com/huanggze/p/12542502.html)
- [Grafana 后台源码分析（腾讯云）](https://cloud.tencent.com/developer/article/2171604)
- [DeepWiki - Grafana Plugin System](https://deepwiki.com/grafana/grafana/10-plugin-system)
- [DeepWiki - Grafana Unified Alerting](https://deepwiki.com/grafana/grafana/7-unified-alerting-system)

**美团内部实践：**
- [MPS：美团自研分布式 Prometheus 监控系统](https://km.sankuai.com/collabpage/1666037833)
- [PrometheusService（MPS）接入指南](https://km.sankuai.com/collabpage/1377593193)