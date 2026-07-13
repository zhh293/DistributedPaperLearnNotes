# RocksDB 深度解析：从原理到实战

> 写给已经了解 InnoDB/B+树 的工程师的 RocksDB 完全指南

---

## 目录

1. [数据平台与背景：为什么需要 RocksDB？](#1-数据平台与背景为什么需要-rocksdb)
2. [发展历程：从 LevelDB 到 RocksDB](#2-发展历程从-leveldb-到-rocksdb)
3. [核心定位：RocksDB 是什么？](#3-核心定位rocksdb-是什么)
4. [与 InnoDB 的本质区别：B+树 vs LSM-Tree](#4-与-innodb-的本质区别b树-vs-lsm-tree)
5. [核心原理：LSM-Tree 深度剖析](#5-核心原理lsm-tree-深度剖析)
   - 5.1 [写入流程：WAL + MemTable](#51-写入流程wal--memtable)
   - 5.2 [Flush：MemTable 落盘为 SSTable](#52-flushmeemtable-落盘为-sstable)
   - 5.3 [SSTable 文件格式详解](#53-sstable-文件格式详解)
   - 5.4 [Compaction：合并与整理](#54-compaction合并与整理)
   - 5.5 [读取流程：多层查找](#55-读取流程多层查找)
   - 5.6 [Bloom Filter：读取加速神器](#56-bloom-filter读取加速神器)
   - 5.7 [Block Cache：内存缓存层](#57-block-cache内存缓存层)
6. [三大放大问题：写放大、读放大、空间放大](#6-三大放大问题写放大读放大空间放大)
7. [核心功能特性](#7-核心功能特性)
   - 7.1 [Column Family（列族）](#71-column-family列族)
   - 7.2 [事务支持](#72-事务支持)
   - 7.3 [快照与迭代器](#73-快照与迭代器)
   - 7.4 [TTL 与数据过期](#74-ttl-与数据过期)
   - 7.5 [压缩算法](#75-压缩算法)
   - 7.6 [Merge Operator](#76-merge-operator)
8. [用法指南：从低级到高级](#8-用法指南从低级到高级)
   - 8.1 [安装与环境搭建](#81-安装与环境搭建)
   - 8.2 [基础操作（C++）](#82-基础操作c)
   - 8.3 [基础操作（Python）](#83-基础操作python)
   - 8.4 [基础操作（Java）](#84-基础操作java)
   - 8.5 [Column Family 高级用法](#85-column-family-高级用法)
   - 8.6 [事务操作](#86-事务操作)
   - 8.7 [迭代器与范围查询](#87-迭代器与范围查询)
   - 8.8 [性能调优参数](#88-性能调优参数)
9. [Compaction 策略详解](#9-compaction-策略详解)
10. [谁在用 RocksDB？工业级应用案例](#10-谁在用-rocksdb工业级应用案例)
11. [在你的项目中应用 RocksDB](#11-在你的项目中应用-rocksdb)
    - 11.1 [项目现状分析](#111-项目现状分析)
    - 11.2 [Session 持久化存储](#112-session-持久化存储)
    - 11.3 [Memory 持久化存储](#113-memory-持久化存储)
    - 11.4 [Trace 事件存储](#114-trace-事件存储)
    - 11.5 [完整集成方案](#115-完整集成方案)
12. [RocksDB 的局限性与选型建议](#12-rocksdb-的局限性与选型建议)

---

## 1. 数据平台与背景：为什么需要 RocksDB？

### 1.1 Facebook 的存储困境（2012年）

2012年，Facebook 面临一个极其棘手的工程问题。他们的服务器上运行着大量的 Flash SSD（固态硬盘），而当时主流的存储引擎（包括 InnoDB）都是为机械硬盘（HDD）设计的，核心数据结构是 B+树。

B+树在机械硬盘时代是完美的选择，因为它的设计目标是**减少磁盘寻道次数**——机械硬盘的随机 I/O 极慢，而 B+树通过大页（Page）和树形结构把随机 I/O 降到最低。

但 SSD 的特性完全不同：

- SSD 的随机读性能远超 HDD，随机读不再是瓶颈
- SSD 的**写入寿命有限**，每个存储单元只能被擦写有限次数（P/E Cycles）
- SSD 的写入以"页"为单位，但擦除以"块"为单位，导致写入放大严重
- B+树的就地更新（in-place update）机制会产生大量随机写，加速 SSD 磨损

Facebook 的工程师们意识到：**需要一种专门为 SSD 优化的存储引擎，它应该把随机写转化为顺序写，从而减少 SSD 的写入放大，延长硬件寿命，同时提升写入吞吐量。**

### 1.2 Google LevelDB 的启发

2011年，Google 发布了 LevelDB，这是一个基于 LSM-Tree（Log-Structured Merge Tree）的嵌入式键值存储库。LSM-Tree 的核心思想正是**将随机写转化为顺序写**，与 Facebook 的需求高度契合。

但 LevelDB 有明显的局限性：

- 单线程 Compaction，无法充分利用多核 CPU
- 不支持多线程并发写入
- 没有 Column Family 概念，数据隔离性差
- 缺乏生产级别的监控和调优能力
- 不支持事务
- 对大型数据库（TB 级别）的性能表现不佳

### 1.3 RocksDB 的诞生

2012年，Facebook 工程师 Dhruba Borthakur（HBase 的联合创始人）带领团队，基于 LevelDB 1.5 进行 fork，开始开发 RocksDB。

核心目标是：
- 充分利用 Flash 存储和多核 CPU
- 支持高并发写入
- 提供生产级别的可靠性和可观测性
- 在 Facebook 内部大规模部署验证

2013年，RocksDB 在 Facebook 内部正式上线，用于支撑 Facebook 的消息系统、Feed 流等核心业务。同年，Facebook 在 Data@Scale 大会上公开介绍了 RocksDB，并于 2013年底正式开源。

---

## 2. 发展历程：从 LevelDB 到 RocksDB

### 2.1 关键版本里程碑

| 时间 | 版本/事件 | 重要特性 |
|------|-----------|----------|
| 2011 | Google 发布 LevelDB | LSM-Tree 嵌入式 KV 存储 |
| 2012 | Facebook fork LevelDB | 开始 RocksDB 研发 |
| 2013 | RocksDB 1.0 开源 | 多线程 Compaction、多线程写入 |
| 2014 | RocksDB 3.0 | **Column Family** 支持 |
| 2015 | RocksDB 4.0 | 事务支持（TransactionDB） |
| 2016 | RocksDB 5.0 | 悲观/乐观事务、Merge Operator 增强 |
| 2017 | RocksDB 5.8 | 分区索引过滤器（Partitioned Index Filters） |
| 2018 | RocksDB 6.0 | 原子 Flush、改进的 Rate Limiter |
| 2020 | RocksDB 6.14 | 远程 Compaction 支持 |
| 2021 | FAST'21 论文发表 | Facebook 发表 8 年演进总结论文 |
| 2022 | RocksDB 7.x | 用户自定义时间戳、Tiered Storage |
| 2023 | RocksDB 8.x | 进一步的 SSD 优化、WASM 支持探索 |
| 2024 | RocksDB 9.x | 持续迭代，性能与稳定性提升 |

### 2.2 Facebook 内部的演进重点

根据 Facebook 在 FAST'21 发表的论文《RocksDB: Evolution of Development Priorities in a KV Store Serving Large-scale Applications》，RocksDB 的演进经历了三个阶段：

**第一阶段（2013-2015）：性能为王**
重点是提升单机写入吞吐量，优化 Compaction 并发度，支持 Column Family。

**第二阶段（2015-2018）：资源效率**
重点是降低 CPU 和内存消耗，优化空间放大，支持分层存储（冷热分离）。

**第三阶段（2018至今）：可靠性与可观测性**
重点是数据正确性保证、错误处理、监控指标完善，以及在大规模分布式系统中的稳定运行。

---

## 3. 核心定位：RocksDB 是什么？

RocksDB 是一个**嵌入式、持久化、高性能的键值存储库（Embedded Key-Value Store Library）**。

几个关键词需要仔细理解：

**嵌入式（Embedded）**：RocksDB 不是一个独立运行的数据库服务，而是一个库（Library），直接链接到你的应用程序进程中。没有网络协议，没有独立进程，数据库就在你的进程内部。这与 MySQL、Redis 这类 C/S 架构完全不同。

**持久化（Persistent）**：数据写入后会持久化到磁盘，进程重启后数据不丢失。这与纯内存的 Redis（不开持久化时）不同。

**键值存储（Key-Value Store）**：数据模型是 `key → value`，key 和 value 都是任意字节序列（byte array）。没有 SQL，没有 Schema，没有 JOIN。

**高性能（High Performance）**：专门针对 SSD 和多核 CPU 优化，写入性能远超传统 B+树引擎。

### RocksDB 的定位图谱

```
                    ┌─────────────────────────────────────────┐
                    │           数据库服务（C/S架构）            │
                    │  MySQL / PostgreSQL / MongoDB / Redis    │
                    └─────────────────────────────────────────┘
                                        ↑
                              需要网络、独立进程
                                        
                    ┌─────────────────────────────────────────┐
                    │         嵌入式存储引擎（Library）          │
                    │  RocksDB / LevelDB / SQLite / LMDB      │
                    └─────────────────────────────────────────┘
                                        ↑
                              直接链接到应用进程
                              
                    ┌─────────────────────────────────────────┐
                    │      基于 RocksDB 构建的上层系统           │
                    │  TiKV / MyRocks / CockroachDB / Flink   │
                    └─────────────────────────────────────────┘
```

---

## 4. 与 InnoDB 的本质区别：B+树 vs LSM-Tree

既然你已经了解 InnoDB，我们直接从对比入手，这是理解 RocksDB 最快的路径。

### 4.1 核心数据结构对比

| 维度 | InnoDB（B+树） | RocksDB（LSM-Tree） |
|------|--------------|---------------------|
| 核心结构 | B+树（就地更新） | LSM-Tree（追加写入） |
| 写入方式 | 随机写（找到位置，原地修改） | 顺序写（追加到末尾） |
| 读取方式 | 直接定位，O(log N) | 多层查找，可能需要查多个文件 |
| 写性能 | 中等（随机 I/O） | 极高（顺序 I/O） |
| 读性能 | 极高（稳定 O(log N)） | 中等（需要 Bloom Filter 优化） |
| 空间利用率 | 约 60-70%（页分裂浪费） | 约 50-90%（取决于 Compaction） |
| 事务支持 | 完整 ACID | 支持，但需要额外配置 |
| 适合场景 | 读多写少，OLTP | 写多读少，时序数据，日志 |

### 4.2 写入路径对比

**InnoDB 写入一条数据的过程：**

```
1. 将变更记录写入 redo log buffer（内存，顺序写）
2. 修改 Buffer Pool 中对应的 B+树数据页（内存随机写，此时页变为"脏页"）
3. 事务提交时：
   a. 将 redo log buffer 中的记录标记为 prepare 状态
   b. 写 binlog（如果开启）
   c. 将 redo log buffer 刷入 redo log 文件（顺序写磁盘，持久化）
   d. 将 redo log 中对应记录标记为 commit 状态
   → 至此事务提交完成，数据已安全（即使进程崩溃也能从 redo log 恢复）
4. 后台异步线程（Page Cleaner）将 Buffer Pool 中的脏页刷回磁盘上的 .ibd 文件
   （随机写，写到 B+树对应的页位置）
```

> 注意：InnoDB 的 redo log 是**环形文件**，固定大小，写满后会覆盖旧记录（前提是对应脏页已经刷盘）。这与 RocksDB 的 WAL 是追加写、可以无限增长的文件有本质区别。

**RocksDB 写入一条数据的过程：**

```
1. 写 WAL（顺序写）
2. 写入内存中的 MemTable（内存操作）
3. MemTable 满了，顺序写入磁盘 SSTable 文件
4. 后台 Compaction 合并 SSTable（顺序读 + 顺序写）
```

RocksDB 的所有磁盘写入都是**顺序写**，这是它写入性能远超 InnoDB 的根本原因。

### 4.3 一个直观的比喻

把数据库想象成一本书：

- **InnoDB（B+树）**：像一本有目录的精装书，每次修改都要找到对应页码，直接在那一页上涂改。找起来很快，但修改很麻烦（可能需要重新排版）。

- **RocksDB（LSM-Tree）**：像一本日记本，每次修改都在最新的一页追加记录。写起来极快，但要找某条记录时，需要从最新的日记往前翻，直到找到为止。为了加速查找，会定期把日记整理归档（Compaction）。

---

## 5. 核心原理：LSM-Tree 深度剖析

LSM-Tree（Log-Structured Merge Tree）是 RocksDB 的灵魂。理解了 LSM-Tree，就理解了 RocksDB 的一切。

### 5.1 写入流程：WAL + MemTable

```
                    写入请求 (key=foo, value=bar)
                            │
                            ▼
              ┌─────────────────────────┐
              │   WAL (Write-Ahead Log)  │  ← 顺序追加写入磁盘
              │   /data/rocksdb/LOG      │    崩溃恢复用
              └─────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │       MemTable          │  ← 写入内存跳表
              │   (Skip List，有序)      │    支持快速插入和查找
              └─────────────────────────┘
                            │
                    MemTable 达到阈值
                    (默认 64MB)
                            │
                            ▼
              ┌─────────────────────────┐
              │   Immutable MemTable    │  ← 变为只读
              │   (等待 Flush 到磁盘)    │
              └─────────────────────────┘
```

**WAL（Write-Ahead Log）的作用：**

WAL 是崩溃恢复的保障。每次写入，数据**先顺序追加到 WAL 文件**，再写入内存中的 MemTable。如果进程崩溃，MemTable 中的数据丢失，但 WAL 还在磁盘上，重启时 RocksDB 会自动从 WAL 重放所有未 Flush 的操作，恢复数据。

**与 InnoDB redo log 的对比：**

两者都是 WAL 机制，核心目的相同（保证崩溃安全），但有几个关键差异：

| 对比维度 | RocksDB WAL | InnoDB redo log |
|----------|-------------|-----------------|
| 文件结构 | 追加写的普通文件，可无限增长 | **环形文件**，固定大小，写满后覆盖旧记录 |
| 写入时机 | 每次写操作直接追加到 WAL 文件 | 先写 redo log buffer（内存），事务提交时才刷到文件 |
| 提交流程 | 写 WAL 成功即视为持久化 | 两阶段提交：prepare → 写 binlog → commit 刷盘 |
| 清理时机 | 对应的 MemTable 成功 Flush 为 SSTable 后，WAL 才可以删除 | 对应脏页刷盘后，redo log 空间才可以被覆盖复用 |
| 与数据的关系 | WAL 记录的是 KV 操作本身，MemTable 是其内存镜像 | redo log 记录的是物理页变更，Buffer Pool 是其内存镜像 |

**MemTable 的数据结构：**

RocksDB 默认使用**跳表（Skip List）**作为 MemTable 的底层数据结构。跳表是一种有序的数据结构，支持 O(log N) 的插入、查找和删除，同时支持范围查询。

跳表的优势在于：
- 天然有序，方便后续 Flush 时直接生成有序的 SSTable
- 支持并发读写（通过 CAS 操作）
- 实现简单，性能稳定

### 5.2 Flush：MemTable 落盘为 SSTable

当 MemTable 达到大小阈值（默认 64MB），它会变成 Immutable MemTable（只读），同时创建一个新的 MemTable 接收写入。后台线程将 Immutable MemTable 的数据**顺序写入磁盘**，生成一个 SSTable（Sorted String Table）文件，这个过程叫做 **Flush**。

```
MemTable (64MB)
      │
      │ 达到阈值
      ▼
Immutable MemTable ──→ 后台 Flush 线程 ──→ L0 层 SSTable 文件
      │                                         (000001.sst)
      │
新 MemTable 继续接收写入
```

Flush 生成的 SSTable 文件放在 **Level 0（L0）**层。

### 5.3 SSTable 文件格式详解

SSTable（Sorted String Table）是 RocksDB 磁盘存储的核心文件格式。每个 SSTable 文件内部的 key 是**有序排列**的。

```
┌──────────────────────────────────────────────────────┐
│                    SSTable 文件结构                    │
├──────────────────────────────────────────────────────┤
│  Data Block 1  │  Data Block 2  │  ...  │  Data Block N  │
│  (key-value对)  │  (key-value对)  │       │  (key-value对)  │
├──────────────────────────────────────────────────────┤
│                    Meta Block                         │
│  (Bloom Filter 数据，用于快速判断 key 是否存在)          │
├──────────────────────────────────────────────────────┤
│                  Meta Index Block                     │
│  (指向各个 Meta Block 的索引)                          │
├──────────────────────────────────────────────────────┤
│                    Index Block                        │
│  (每个 Data Block 的最大 key 和偏移量，用于二分查找)     │
├──────────────────────────────────────────────────────┤
│                      Footer                           │
│  (Magic Number + Index Block 和 Meta Index 的偏移量)   │
└──────────────────────────────────────────────────────┘
```

**Data Block：** 存储实际的 key-value 数据，默认大小 4KB，支持 Snappy/LZ4/Zstd 等压缩。

**Index Block：** 存储每个 Data Block 的最大 key 和文件偏移量。查找时先二分搜索 Index Block，定位到目标 Data Block，再在 Data Block 内查找。

**Bloom Filter（Meta Block）：** 布隆过滤器，用于快速判断某个 key 是否**可能**存在于这个 SSTable 中。如果 Bloom Filter 说"不存在"，则一定不存在，可以跳过这个文件，大幅减少不必要的磁盘读取。

### 5.4 Compaction：合并与整理

Compaction 是 LSM-Tree 最核心也最复杂的机制。它解决了两个问题：

1. **读放大**：随着 SSTable 文件越来越多，读取一个 key 可能需要查找很多文件
2. **空间放大**：同一个 key 可能在多个 SSTable 中有多个版本，旧版本占用空间

**分层结构（Leveled Compaction）：**

```
内存层：
  MemTable (可写)
  Immutable MemTable (只读，等待 Flush)

磁盘层：
  L0: 4个 SSTable 文件（文件间 key 范围可能重叠）
      ↓ Compaction
  L1: 10个 SSTable 文件，总大小约 256MB（文件间 key 范围不重叠）
      ↓ Compaction
  L2: 100个 SSTable 文件，总大小约 2.56GB
      ↓ Compaction
  L3: 1000个 SSTable 文件，总大小约 25.6GB
      ↓ Compaction
  L4: ...（每层大小是上一层的 10 倍）
```

**L0 的特殊性：** L0 层的 SSTable 文件之间 key 范围可能重叠（因为每次 Flush 都是全量写入），所以读取时需要查找所有 L0 文件。当 L0 文件数量超过阈值（默认 4 个），会触发 Compaction，将 L0 文件合并到 L1。

**L1 及以下层：** 每层内的 SSTable 文件 key 范围不重叠，且按 key 有序排列。Compaction 时，从上层选取一个 SSTable，与下层中 key 范围有重叠的 SSTable 进行归并排序，生成新的 SSTable 放入下层。

**Compaction 的本质是归并排序：**

```
L1 文件: [a-f]  [g-m]  [n-z]
              ↓
L2 文件: [a-c] [d-f] [g-i] [j-m] [n-p] [q-z]

选取 L1 的 [g-m] 与 L2 中重叠的 [g-i][j-m] 进行归并排序
→ 生成新的 L2 文件，删除旧文件
```

### 5.5 读取流程：多层查找

读取一个 key 时，RocksDB 按照从新到旧的顺序查找：

```
查找 key="foo"
      │
      ▼
1. 查 MemTable（最新数据）
      │ 未找到
      ▼
2. 查 Immutable MemTable（等待 Flush 的数据）
      │ 未找到
      ▼
3. 查 L0 层所有 SSTable（需要查所有文件，因为 key 范围可能重叠）
      │ 未找到
      ▼
4. 查 L1 层（二分查找定位到一个文件）
      │ 未找到
      ▼
5. 查 L2 层 → L3 层 → ... → Ln 层
      │
      ▼
返回找到的最新版本，或返回 Not Found
```

**每次查找 SSTable 文件的步骤：**

```
1. 检查 Bloom Filter → 如果确定不存在，跳过此文件
2. 读取 Index Block → 二分查找定位 Data Block
3. 读取 Data Block → 在 Block 内查找 key
```

### 5.6 Bloom Filter：读取加速神器

Bloom Filter（布隆过滤器）是 RocksDB 读取性能的关键优化。

**原理：** Bloom Filter 是一个位数组，通过多个哈希函数将 key 映射到位数组的多个位置，将这些位置设为 1。查询时，如果所有对应位置都是 1，则 key **可能存在**；如果有任何一个位置是 0，则 key **一定不存在**。

```
插入 key="foo":
  hash1("foo") = 3  → bit[3] = 1
  hash2("foo") = 7  → bit[7] = 1
  hash3("foo") = 12 → bit[12] = 1

查询 key="bar":
  hash1("bar") = 3  → bit[3] = 1 ✓
  hash2("bar") = 5  → bit[5] = 0 ✗ → 一定不存在！跳过此文件
```

**效果：** 对于不存在的 key（点查 miss），Bloom Filter 可以过滤掉 99% 以上的 SSTable 文件读取，极大提升读取性能。

RocksDB 默认每个 key 使用 10 bits 的 Bloom Filter，误判率约为 1%。

### 5.7 Block Cache：内存缓存层

Block Cache 是 RocksDB 的内存缓存，缓存从磁盘读取的 Data Block，避免重复读盘。

```
读取请求
    │
    ▼
Block Cache 命中？ ──→ 是 ──→ 直接返回（纯内存操作）
    │
    否
    ▼
从磁盘读取 Data Block ──→ 放入 Block Cache ──→ 返回结果
```

Block Cache 默认使用 LRU 策略，大小可配置（生产环境通常设置为可用内存的 1/3）。

---

## 6. 三大放大问题：写放大、读放大、空间放大

这是 LSM-Tree 设计中最核心的权衡（Trade-off），理解这三个概念是调优 RocksDB 的基础。

### 6.1 写放大（Write Amplification）

**定义：** 实际写入磁盘的数据量 / 用户写入的数据量

**产生原因：** Compaction 过程中，同一份数据会被反复读取和写入。一条数据从 L0 一路 Compaction 到 Ln，可能被写入磁盘 10-30 次。

**典型值：** Leveled Compaction 下，写放大约为 10-30x。

**影响：** 加速 SSD 磨损，消耗 I/O 带宽。

### 6.2 读放大（Read Amplification）

**定义：** 读取一个 key 实际需要读取的磁盘 I/O 次数

**产生原因：** 需要从 MemTable 到各层 SSTable 逐层查找，最坏情况下需要查找所有层。

**典型值：** 有 Bloom Filter 时，点查通常只需要 1-2 次磁盘 I/O；范围查询可能需要查找多个文件。

**优化手段：** Bloom Filter、Block Cache、增大 L0 文件数量。

### 6.3 空间放大（Space Amplification）

**定义：** 实际占用磁盘空间 / 数据的逻辑大小

**产生原因：** 同一个 key 的多个版本（旧版本）在 Compaction 完成前都占用磁盘空间；删除操作只是写入一个"墓碑标记"（Tombstone），旧数据在 Compaction 后才真正删除。

**典型值：** Leveled Compaction 下，空间放大约为 1.1-1.5x。

### 6.4 三者的权衡关系

```
                    写放大
                      ↑
                      │
    Tiered Compaction ●          ● Leveled Compaction
    (写放大小，读/空间放大大)      (写放大大，读/空间放大小)
                      │
                      └──────────────────────→ 读放大 + 空间放大
```

没有完美的策略，只有适合你业务场景的策略。

---

## 7. 核心功能特性

### 7.1 Column Family（列族）

Column Family 是 RocksDB 3.0 引入的重要特性，允许在同一个数据库实例中创建多个逻辑命名空间。

**核心特点：**
- 每个 Column Family 有独立的 MemTable 和 SSTable
- 每个 Column Family 可以有独立的配置（压缩算法、Compaction 策略等）
- 不同 Column Family 之间可以进行**原子写入**（跨 CF 的 WriteBatch）
- 所有 Column Family **共享同一个 WAL**（保证跨 CF 原子性）

**类比：** 如果把 RocksDB 比作 MySQL，Column Family 就像不同的表（Table），但它们共享同一个数据库文件。

**使用场景：**
- 将不同类型的数据分开存储（如：用户数据、元数据、索引数据）
- 对不同数据设置不同的 TTL 或压缩策略
- 隔离热数据和冷数据

### 7.2 事务支持

RocksDB 支持两种事务模式：

**悲观事务（TransactionDB）：**
- 写入时立即加锁
- 适合冲突频繁的场景
- 支持 BEGIN / COMMIT / ROLLBACK

**乐观事务（OptimisticTransactionDB）：**
- 写入时不加锁，提交时检查冲突
- 适合冲突较少的场景
- 冲突时事务失败，需要重试

```cpp
// 悲观事务示例
TransactionDB* txn_db;
TransactionDB::Open(options, txn_db_options, path, &txn_db);

Transaction* txn = txn_db->BeginTransaction(write_options);
txn->Put("key1", "value1");
txn->Put("key2", "value2");
txn->Commit();  // 原子提交
delete txn;
```

### 7.3 快照与迭代器

**快照（Snapshot）：** 提供某一时刻数据库状态的一致性视图，读取快照时不受后续写入影响。

```cpp
const Snapshot* snapshot = db->GetSnapshot();
ReadOptions read_options;
read_options.snapshot = snapshot;
// 使用 read_options 读取，看到的是快照时刻的数据
db->ReleaseSnapshot(snapshot);
```

**迭代器（Iterator）：** 支持范围扫描，按 key 顺序遍历数据。

```cpp
Iterator* it = db->NewIterator(ReadOptions());
for (it->SeekToFirst(); it->Valid(); it->Next()) {
    cout << it->key().ToString() << ": " << it->value().ToString() << endl;
}
delete it;
```

### 7.4 TTL 与数据过期

RocksDB 支持为数据设置 TTL（Time-To-Live），过期数据在 Compaction 时自动清理。

```cpp
// 打开带 TTL 的数据库，TTL = 3600 秒
DBWithTTL* db;
DBWithTTL::Open(options, path, &db, 3600);
db->Put(WriteOptions(), "key", "value");  // 1小时后自动过期
```

### 7.5 压缩算法

RocksDB 支持多种压缩算法，可以按层配置：

| 算法 | 压缩率 | 速度 | 适用场景 |
|------|--------|------|----------|
| None | 1x | 最快 | 数据已压缩，或追求极致速度 |
| Snappy | ~2x | 很快 | 默认选择，平衡压缩率和速度 |
| LZ4 | ~2.5x | 快 | 比 Snappy 更好的压缩率 |
| Zstd | ~3-4x | 中等 | 追求高压缩率，CPU 充足 |
| Zlib | ~3x | 慢 | 兼容性场景 |

**典型配置：** L0/L1 用 LZ4（热数据，频繁读写），L2 及以下用 Zstd（冷数据，追求压缩率）。

### 7.6 Merge Operator

Merge Operator 是 RocksDB 的独特特性，允许定义"读-改-写"的原子操作，避免先读后写的竞争条件。

**典型场景：** 计数器累加

```cpp
// 不用 Merge Operator 的方式（有竞争条件）：
string value;
db->Get(key, &value);
int count = atoi(value.c_str()) + 1;
db->Put(key, to_string(count));

// 使用 Merge Operator（原子操作）：
db->Merge(WriteOptions(), "counter", "1");  // 原子加 1
```

---

## 8. 用法指南：从低级到高级

### 8.1 安装与环境搭建

**C++ 安装（macOS）：**
```bash
brew install rocksdb
```

**C++ 安装（Ubuntu）：**
```bash
sudo apt-get install librocksdb-dev
```

**Python 安装：**
```bash
# 推荐使用 rocksdict（更活跃维护）
pip install rocksdict

# 或者使用 python-rocksdb（较老）
pip install python-rocksdb
```

**Java 安装（Maven）：**
```xml
<dependency>
    <groupId>org.rocksdb</groupId>
    <artifactId>rocksdbjni</artifactId>
    <version>9.0.0</version>
</dependency>
```

### 8.2 基础操作（C++）

```cpp
#include "rocksdb/db.h"
#include "rocksdb/options.h"
#include <iostream>

using namespace rocksdb;

int main() {
    // ===== 打开数据库 =====
    Options options;
    options.create_if_missing = true;           // 不存在则创建
    options.compression = kLZ4Compression;      // 使用 LZ4 压缩
    options.write_buffer_size = 64 * 1024 * 1024; // MemTable 大小 64MB
    
    DB* db;
    Status s = DB::Open(options, "/tmp/testdb", &db);
    assert(s.ok());
    
    // ===== 写入 =====
    s = db->Put(WriteOptions(), "name", "RocksDB");
    assert(s.ok());
    
    // ===== 读取 =====
    std::string value;
    s = db->Get(ReadOptions(), "name", &value);
    if (s.ok()) {
        std::cout << "name = " << value << std::endl;
    } else if (s.IsNotFound()) {
        std::cout << "key not found" << std::endl;
    }
    
    // ===== 删除 =====
    s = db->Delete(WriteOptions(), "name");
    assert(s.ok());
    
    // ===== 批量写入（WriteBatch，原子操作）=====
    WriteBatch batch;
    batch.Put("user:1", "Alice");
    batch.Put("user:2", "Bob");
    batch.Delete("user:0");
    s = db->Write(WriteOptions(), &batch);
    assert(s.ok());
    
    // ===== 关闭数据库 =====
    delete db;
    return 0;
}
```

**编译：**
```bash
g++ -o demo demo.cpp -lrocksdb -lpthread -ldl -std=c++17
```

### 8.3 基础操作（Python）

```python
from rocksdict import Rdict, Options, WriteOptions, ReadOptions

# ===== 打开数据库 =====
opt = Options()
opt.create_if_missing(True)
opt.set_compression_type("lz4")

db = Rdict("/tmp/testdb", opt)

# ===== 基础读写 =====
db["name"] = "RocksDB"
db["version"] = "9.0"

print(db["name"])          # RocksDB
print("name" in db)        # True
print("missing" in db)     # False

# ===== 删除 =====
del db["version"]

# ===== 批量写入 =====
db.write_batch({
    "user:1": "Alice",
    "user:2": "Bob",
    "user:3": "Charlie",
})

# ===== 范围查询（迭代器）=====
for key, value in db.items():
    print(f"{key}: {value}")

# ===== 前缀查询 =====
for key, value in db.items(from_key="user:", to_key="user:~"):
    print(f"{key}: {value}")

# ===== 关闭 =====
db.close()
```

**存储复杂对象（使用 JSON 序列化）：**

```python
import json
from rocksdict import Rdict

db = Rdict("/tmp/testdb")

# 存储字典
user = {"id": 1, "name": "Alice", "age": 30}
db["user:1"] = json.dumps(user).encode()

# 读取字典
raw = db["user:1"]
user = json.loads(raw.decode())
print(user["name"])  # Alice

db.close()
```

### 8.4 基础操作（Java）

```java
import org.rocksdb.*;

public class RocksDBExample {
    static {
        RocksDB.loadLibrary();
    }
    
    public static void main(String[] args) throws RocksDBException {
        // ===== 打开数据库 =====
        Options options = new Options()
            .setCreateIfMissing(true)
            .setCompressionType(CompressionType.LZ4_COMPRESSION)
            .setWriteBufferSize(64 * 1024 * 1024L);
        
        try (RocksDB db = RocksDB.open(options, "/tmp/testdb")) {
            
            // ===== 写入 =====
            db.put("name".getBytes(), "RocksDB".getBytes());
            
            // ===== 读取 =====
            byte[] value = db.get("name".getBytes());
            System.out.println("name = " + new String(value));
            
            // ===== 批量写入 =====
            try (WriteBatch batch = new WriteBatch()) {
                batch.put("user:1".getBytes(), "Alice".getBytes());
                batch.put("user:2".getBytes(), "Bob".getBytes());
                batch.delete("user:0".getBytes());
                db.write(new WriteOptions(), batch);
            }
            
            // ===== 迭代器 =====
            try (RocksIterator iter = db.newIterator()) {
                iter.seekToFirst();
                while (iter.isValid()) {
                    System.out.println(new String(iter.key()) + 
                                       " = " + new String(iter.value()));
                    iter.next();
                }
            }
        }
    }
}
```

### 8.5 Column Family 高级用法

```python
from rocksdict import Rdict, Options, ColumnFamilyDescriptor

# ===== 创建带多个 Column Family 的数据库 =====
opt = Options()
opt.create_if_missing(True)
opt.create_missing_column_families(True)

# 定义列族
cf_names = ["default", "sessions", "memory", "traces"]
cf_opts = [Options() for _ in cf_names]

db = Rdict("/tmp/testdb", opt, column_families=dict(zip(cf_names, cf_opts)))

# ===== 访问不同列族 =====
sessions_cf = db.get_column_family("sessions")
memory_cf = db.get_column_family("memory")

# 写入不同列族
sessions_cf["session:abc123"] = '{"user_id": 1, "created_at": "2024-01-01"}'
memory_cf["mem:001"] = '{"content": "用户喜欢Python", "type": "preference"}'

# 读取
print(sessions_cf["session:abc123"])

# ===== 跨列族原子写入 =====
# 使用 WriteBatch 保证原子性
from rocksdict import WriteBatch
batch = WriteBatch()
batch.put("session:new", '{"status": "active"}', sessions_cf)
batch.put("mem:new", '{"content": "新记忆"}', memory_cf)
db.write(batch)

db.close()
```

### 8.6 事务操作

```python
from rocksdict import TransactionDb, Options, TransactionOptions

# 打开事务数据库
opt = Options()
opt.create_if_missing(True)
txn_db = TransactionDb("/tmp/txn_testdb", opt)

# ===== 悲观事务 =====
txn = txn_db.begin_transaction()
try:
    txn.put("balance:alice", "1000")
    txn.put("balance:bob", "500")
    
    # 读取（在事务内，看到的是事务开始时的快照）
    alice_balance = txn.get("balance:alice")
    
    txn.commit()
    print("事务提交成功")
except Exception as e:
    txn.rollback()
    print(f"事务回滚: {e}")
finally:
    del txn

txn_db.close()
```

### 8.7 迭代器与范围查询

```python
from rocksdict import Rdict, ReadOptions

db = Rdict("/tmp/testdb")

# 写入测试数据
for i in range(100):
    db[f"user:{i:04d}"] = f"User {i}"

# ===== 全量遍历 =====
for key, value in db.items():
    print(f"{key}: {value}")

# ===== 前缀扫描（模拟 SQL 的 LIKE 'user:%'）=====
# 从 user:0010 开始，到 user:0020 结束
for key, value in db.items(from_key="user:0010", to_key="user:0020"):
    print(f"{key}: {value}")

# ===== 反向遍历 =====
for key, value in db.items(backwards=True):
    print(f"{key}: {value}")
    break  # 只取最后一条

# ===== 使用快照保证一致性读 =====
snapshot = db.snapshot()
read_opt = ReadOptions()
read_opt.set_snapshot(snapshot)

for key, value in db.items(read_opt=read_opt):
    print(f"{key}: {value}")

db.close()
```

### 8.8 性能调优参数

```python
from rocksdict import Rdict, Options, BlockBasedOptions, Cache, BloomFilterPolicy

# ===== 生产级别配置 =====
opt = Options()

# 写入优化
opt.create_if_missing(True)
opt.set_write_buffer_size(128 * 1024 * 1024)      # MemTable 128MB
opt.set_max_write_buffer_number(4)                  # 最多 4 个 MemTable
opt.set_min_write_buffer_number_to_merge(2)         # 至少 2 个才合并

# Compaction 优化
opt.set_level_zero_file_num_compaction_trigger(4)   # L0 文件数触发 Compaction
opt.set_level_zero_slowdown_writes_trigger(20)      # L0 文件数触发写入减速
opt.set_level_zero_stop_writes_trigger(36)          # L0 文件数触发停止写入
opt.set_max_bytes_for_level_base(512 * 1024 * 1024) # L1 最大 512MB
opt.set_target_file_size_base(64 * 1024 * 1024)     # SSTable 文件目标大小 64MB

# 并发优化
opt.increase_parallelism(8)                         # 8 个后台线程
opt.set_max_background_jobs(8)                      # 最多 8 个后台任务

# 压缩配置（L0/L1 用 LZ4，L2+ 用 Zstd）
opt.set_compression_per_level([
    "no",    # L0
    "no",    # L1
    "lz4",   # L2
    "lz4",   # L3
    "zstd",  # L4
    "zstd",  # L5
    "zstd",  # L6
])

# Block Cache 配置（1GB 缓存）
block_opts = BlockBasedOptions()
cache = Cache(1 * 1024 * 1024 * 1024)              # 1GB Block Cache
block_opts.set_block_cache(cache)
block_opts.set_bloom_filter(BloomFilterPolicy(10))  # 10 bits per key
block_opts.set_block_size(16 * 1024)                # 16KB Block 大小
opt.set_block_based_table_factory(block_opts)

db = Rdict("/tmp/production_db", opt)
```

---

## 9. Compaction 策略详解

### 9.1 Leveled Compaction（默认）

**原理：** 每层有固定的大小限制，超过限制触发向下层的 Compaction。每层内的 SSTable key 范围不重叠。

**优点：** 读放大小（每层最多查一个文件），空间放大小（旧版本快速清理）。

**缺点：** 写放大大（数据被反复 Compaction）。

**适用场景：** 读写混合，对读性能有要求的场景。

### 9.2 Universal Compaction（Tiered Compaction）

**原理：** 所有 SSTable 文件按时间顺序排列，Compaction 时将相邻的文件合并。

**优点：** 写放大小（每次 Compaction 合并相邻文件，数据移动少）。

**缺点：** 读放大大，空间放大大（旧版本清理慢）。

**适用场景：** 写多读少，对写入性能要求极高的场景（如日志、时序数据）。

### 9.3 FIFO Compaction

**原理：** 只保留最新的数据，当总大小超过阈值时，删除最旧的 SSTable 文件。

**特点：** 没有真正的 Compaction，写放大最小，但数据量受限。

**适用场景：** 缓存场景，只需要保留最近一段时间的数据。

---

## 10. 谁在用 RocksDB？工业级应用案例

RocksDB 已经成为现代数据基础设施的基石，以下是最具代表性的使用案例：

### 10.1 TiKV / TiDB（PingCAP）

TiKV 是 TiDB 的分布式 KV 存储层，每个 TiKV 节点使用两个 RocksDB 实例：
- **raftdb**：存储 Raft 日志
- **kvdb**：存储用户数据，包含 4 个 Column Family（raft、lock、default、write）

TiDB 通过 RocksDB 实现了 MVCC（多版本并发控制），write CF 存储事务提交记录，default CF 存储实际数据值。

### 10.2 MyRocks（Facebook / MySQL）

MyRocks 是 Facebook 开发的 MySQL 存储引擎，用 RocksDB 替换 InnoDB。在 Facebook 的 UDB（用户数据库）中，MyRocks 相比 InnoDB：
- 存储空间减少 50%
- 写入 I/O 减少 10 倍
- 闪存寿命延长数倍

### 10.3 Apache Flink

Flink 使用 RocksDB 作为 RocksDB State Backend，用于存储流处理任务的状态数据（如窗口聚合的中间结果）。RocksDB 允许 Flink 的状态数据超过内存大小，溢出到磁盘。

### 10.4 CockroachDB

CockroachDB 在每个节点上使用 RocksDB 作为本地 KV 存储，在其上构建分布式事务和 SQL 层。

### 10.5 Apache Kafka（KRaft 模式）

Kafka 的 KRaft 模式（无 ZooKeeper）使用 RocksDB 存储元数据日志。

### 10.6 其他知名用户

- **Netflix**：用于存储流媒体元数据
- **LinkedIn**：用于 Espresso 分布式数据库
- **Uber**：用于 Schemaless 数据库
- **Nebula Graph**：图数据库的存储层
- **Ceph**：分布式存储系统

---

## 11. 在你的项目中应用 RocksDB

### 11.1 项目现状分析

通过分析你的项目代码，这是一个 **Python FastAPI + React 的 AI Agent 框架**，包含以下核心模块：

| 模块 | 当前实现 | 问题 |
|------|----------|------|
| `SessionStore` | 纯内存 `dict` | 进程重启后所有会话丢失 |
| `MemoryManager` | 纯内存 `list` | 进程重启后所有记忆丢失 |
| `Tracer` | 纯内存 `defaultdict` | 进程重启后所有 Trace 丢失 |

这三个模块都是 RocksDB 的完美应用场景：**需要持久化、写入频繁、按 key 查找、偶尔范围扫描**。

### 11.2 Session 持久化存储

**当前代码（`backend/core/session.py`）：**

```python
class SessionStore:
    def __init__(self) -> None:
        self._sessions: dict[str, dict] = {}      # 内存存储，重启丢失
        self._messages: dict[str, list[dict]] = defaultdict(list)
```

**RocksDB 改造方案：**

```python
# backend/core/session_rocksdb.py
import json
from rocksdict import Rdict, Options, ColumnFamilyDescriptor
from datetime import datetime
from uuid import uuid4


class RocksDBSessionStore:
    """
    使用 RocksDB 持久化 Session 数据
    
    Column Family 设计：
    - sessions CF: session_id → session_metadata (JSON)
    - messages CF: {session_id}:{timestamp}:{msg_id} → message (JSON)
    
    Key 设计：
    - sessions: "session:{session_id}"
    - messages: "msg:{session_id}:{timestamp_ms}:{msg_id}"
      （时间戳前缀保证同一 session 的消息按时间有序）
    """
    
    def __init__(self, db_path: str = "./data/sessions_db"):
        opt = Options()
        opt.create_if_missing(True)
        opt.create_missing_column_families(True)
        
        # 为 messages 设置更激进的压缩（消息数据量大）
        msg_opt = Options()
        msg_opt.set_compression_type("lz4")
        
        self._db = Rdict(
            db_path, 
            opt,
            column_families={
                "default": Options(),
                "sessions": Options(),
                "messages": msg_opt,
            }
        )
        self._sessions_cf = self._db.get_column_family("sessions")
        self._messages_cf = self._db.get_column_family("messages")
    
    def create(self) -> dict:
        session_id = str(uuid4())
        session = {
            "id": session_id,
            "title": "New Chat",
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat(),
        }
        # key: "session:{id}"，方便前缀扫描所有 session
        self._sessions_cf[f"session:{session_id}"] = json.dumps(session)
        return session
    
    def list(self) -> list[dict]:
        sessions = []
        # 前缀扫描所有 session
        for key, value in self._sessions_cf.items(
            from_key="session:", to_key="session:~"
        ):
            sessions.append(json.loads(value))
        # 按 updated_at 排序
        return sorted(sessions, key=lambda x: x["updated_at"], reverse=True)
    
    def add_message(self, session_id: str, role: str, content: str) -> dict:
        now = datetime.utcnow()
        msg_id = str(uuid4())
        message = {
            "id": msg_id,
            "role": role,
            "content": content,
            "created_at": now.isoformat(),
        }
        
        # key 包含时间戳，保证按时间有序
        timestamp_ms = int(now.timestamp() * 1000)
        msg_key = f"msg:{session_id}:{timestamp_ms:016d}:{msg_id}"
        self._messages_cf[msg_key] = json.dumps(message)
        
        # 更新 session 的 updated_at
        session_key = f"session:{session_id}"
        if session_key in self._sessions_cf:
            session = json.loads(self._sessions_cf[session_key])
            session["updated_at"] = now.isoformat()
            if role == "user" and session["title"] == "New Chat":
                session["title"] = content[:30] or "New Chat"
            self._sessions_cf[session_key] = json.dumps(session)
        
        return message
    
    def get_messages(self, session_id: str) -> list[dict]:
        messages = []
        # 前缀扫描该 session 的所有消息（已按时间戳有序）
        prefix = f"msg:{session_id}:"
        for key, value in self._messages_cf.items(
            from_key=prefix, to_key=prefix + "~"
        ):
            messages.append(json.loads(value))
        return messages
    
    def delete(self, session_id: str) -> None:
        # 删除 session 元数据
        session_key = f"session:{session_id}"
        if session_key in self._sessions_cf:
            del self._sessions_cf[session_key]
        
        # 删除该 session 的所有消息
        prefix = f"msg:{session_id}:"
        keys_to_delete = []
        for key, _ in self._messages_cf.items(
            from_key=prefix, to_key=prefix + "~"
        ):
            keys_to_delete.append(key)
        for key in keys_to_delete:
            del self._messages_cf[key]
    
    def close(self):
        self._db.close()
```

### 11.3 Memory 持久化存储

**当前代码（`backend/memory/manager.py`）：**

```python
class MemoryManager:
    def __init__(self) -> None:
        self._items: list[MemoryItem] = []  # 内存存储，重启丢失
```

**RocksDB 改造方案：**

```python
# backend/memory/manager_rocksdb.py
import json
from rocksdict import Rdict, Options
from datetime import datetime
from uuid import uuid4
from memory.types import MemoryItem, MemoryType


class RocksDBMemoryManager:
    """
    使用 RocksDB 持久化 Memory 数据
    
    Key 设计：
    - "mem:{created_at_ms}:{id}" → MemoryItem JSON
      （时间戳前缀保证按创建时间有序，方便获取最新 N 条）
    
    Column Family 设计：
    - default CF: 主存储
    - tags_index CF: "tag:{tag_name}:{mem_id}" → "" （标签倒排索引）
    """
    
    def __init__(self, db_path: str = "./data/memory_db"):
        opt = Options()
        opt.create_if_missing(True)
        opt.create_missing_column_families(True)
        # 记忆数据适合高压缩率
        opt.set_compression_type("zstd")
        
        self._db = Rdict(
            db_path,
            opt,
            column_families={
                "default": Options(),
                "tags_index": Options(),
            }
        )
        self._tags_cf = self._db.get_column_family("tags_index")
    
    def store(
        self, 
        content: str, 
        memory_type: MemoryType, 
        tags: list[str] | None = None
    ) -> MemoryItem:
        now = datetime.utcnow()
        item_id = str(uuid4())
        summary = content[:150]
        
        item = MemoryItem(
            id=item_id,
            type=memory_type,
            content=content,
            summary=summary,
            tags=tags or [],
        )
        
        # 主存储：时间戳前缀保证有序
        timestamp_ms = int(now.timestamp() * 1000)
        key = f"mem:{timestamp_ms:016d}:{item_id}"
        self._db[key] = json.dumps(item.model_dump(mode="json"))
        
        # 标签倒排索引
        for tag in (tags or []):
            self._tags_cf[f"tag:{tag}:{item_id}"] = key
        
        return item
    
    def get_recent(self, n: int = 10) -> list[MemoryItem]:
        """获取最近 N 条记忆（利用 key 有序性，从末尾反向遍历）"""
        items = []
        count = 0
        for key, value in self._db.items(
            from_key="mem:", to_key="mem:~", backwards=True
        ):
            if count >= n:
                break
            items.append(MemoryItem(**json.loads(value)))
            count += 1
        return list(reversed(items))  # 恢复正序
    
    def search_by_content(self, query: str, limit: int = 3) -> list[MemoryItem]:
        """简单的内容搜索（全扫描，生产环境建议结合向量数据库）"""
        matches = []
        for key, value in self._db.items(from_key="mem:", to_key="mem:~"):
            item_data = json.loads(value)
            if query.lower() in item_data["content"].lower():
                matches.append(MemoryItem(**item_data))
                if len(matches) >= limit:
                    break
        return matches
    
    def search_by_tag(self, tag: str) -> list[MemoryItem]:
        """按标签查询（利用倒排索引，O(k) 复杂度，k 为该标签的记忆数量）"""
        items = []
        prefix = f"tag:{tag}:"
        for _, mem_key in self._tags_cf.items(
            from_key=prefix, to_key=prefix + "~"
        ):
            if mem_key in self._db:
                item_data = json.loads(self._db[mem_key])
                items.append(MemoryItem(**item_data))
        return items
    
    def build_context_for_turn(self, session_id: str, task_description: str) -> dict:
        recent_items = self.get_recent(10)
        matches = self.search_by_content(task_description, limit=3)
        
        return {
            "index_summaries": [item.summary for item in recent_items],
            "loaded_documents": [item.model_dump(mode="json") for item in matches],
            "budget_used": sum(len(item.content) for item in matches),
            "budget_total": 12000,
        }
    
    def close(self):
        self._db.close()
```

### 11.4 Trace 事件存储

**当前代码（`backend/observability/tracer.py`）：**

```python
class Tracer:
    def __init__(self) -> None:
        self._events: dict[str, list[TraceEvent]] = defaultdict(list)  # 内存存储
```

**RocksDB 改造方案：**

```python
# backend/observability/tracer_rocksdb.py
import json
from rocksdict import Rdict, Options
from datetime import datetime
from observability.events import TraceEvent


class RocksDBTracer:
    """
    使用 RocksDB 持久化 Trace 事件
    
    Key 设计：
    - "trace:{session_id}:{timestamp_ms}:{event_id}" → TraceEvent JSON
    
    特点：
    - 按 session_id 前缀扫描，获取某个 session 的所有事件
    - 时间戳保证事件有序
    - 使用 FIFO Compaction 自动清理旧数据（保留最近 7 天）
    """
    
    def __init__(self, db_path: str = "./data/trace_db"):
        opt = Options()
        opt.create_if_missing(True)
        # Trace 数据量大，使用高压缩率
        opt.set_compression_type("zstd")
        # 可选：使用 FIFO Compaction 自动清理旧 Trace
        # opt.set_compaction_style("fifo")
        # opt.set_compaction_options_fifo({"max_table_files_size": 7 * 24 * 3600 * 1024 * 1024})
        
        self._db = Rdict(db_path, opt)
    
    def record(self, event: TraceEvent) -> None:
        now = datetime.utcnow()
        timestamp_ms = int(now.timestamp() * 1000)
        key = f"trace:{event.session_id}:{timestamp_ms:016d}:{event.id}"
        self._db[key] = json.dumps(event.model_dump(mode="json"))
    
    def list_events(self, session_id: str) -> list[TraceEvent]:
        events = []
        prefix = f"trace:{session_id}:"
        for key, value in self._db.items(
            from_key=prefix, to_key=prefix + "~"
        ):
            events.append(TraceEvent(**json.loads(value)))
        return events
    
    def get_recent_events(self, session_id: str, limit: int = 100) -> list[TraceEvent]:
        """获取最近 N 条事件"""
        events = []
        prefix = f"trace:{session_id}:"
        for key, value in self._db.items(
            from_key=prefix, to_key=prefix + "~",
            backwards=True
        ):
            events.append(TraceEvent(**json.loads(value)))
            if len(events) >= limit:
                break
        return list(reversed(events))
    
    def close(self):
        self._db.close()
```

### 11.5 完整集成方案

**在 `backend/main.py` 中统一初始化：**

```python
# backend/main.py（改造建议）
import os
from contextlib import asynccontextmanager
from fastapi import FastAPI

# 数据目录
DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
os.makedirs(DATA_DIR, exist_ok=True)

# 使用 RocksDB 版本替换内存版本
from core.session_rocksdb import RocksDBSessionStore
from memory.manager_rocksdb import RocksDBMemoryManager
from observability.tracer_rocksdb import RocksDBTracer

session_store = RocksDBSessionStore(os.path.join(DATA_DIR, "sessions_db"))
memory_manager = RocksDBMemoryManager(os.path.join(DATA_DIR, "memory_db"))
tracer = RocksDBTracer(os.path.join(DATA_DIR, "trace_db"))

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时无需特殊初始化（RocksDB 已在构造函数中打开）
    yield
    # 关闭时优雅关闭 RocksDB
    session_store.close()
    memory_manager.close()
    tracer.close()

app = FastAPI(lifespan=lifespan)
```

**改造后的收益：**

| 改造点 | 改造前 | 改造后 |
|--------|--------|--------|
| 数据持久化 | 进程重启丢失所有数据 | 数据永久保存 |
| 内存占用 | 所有数据在内存中 | 只有热数据在 Block Cache |
| 历史查询 | 无法查询历史 Session | 可查询任意历史 Session |
| 数据规模 | 受内存限制 | 受磁盘限制（TB 级别） |
| 写入性能 | O(1) 内存写 | 接近 O(1)（WAL + MemTable） |
| 读取性能 | O(1) 内存读 | 有 Block Cache 时接近 O(1) |

---

## 12. RocksDB 的局限性与选型建议

### 12.1 RocksDB 不适合的场景

**不适合复杂查询：** RocksDB 只支持 key-value 操作和范围扫描，没有 SQL、没有 JOIN、没有聚合函数。如果你需要复杂查询，应该用 PostgreSQL/MySQL。

**不适合多进程访问：** RocksDB 是嵌入式库，同一时刻只能有一个进程打开同一个数据库（有文件锁保护）。如果需要多进程共享，应该用 Redis 或独立数据库服务。

**不适合读多写少的场景：** 如果你的业务是 90% 读、10% 写，InnoDB 的 B+树可能比 LSM-Tree 更合适，因为 B+树的读取性能更稳定。

**Compaction 可能影响延迟：** Compaction 是后台操作，但在极端情况下（如 L0 文件堆积）会触发写入限速甚至停写，导致延迟抖动。

### 12.2 选型决策树

```
你的需求是什么？
│
├─ 需要 SQL / 复杂查询 / JOIN？
│   └─ 用 PostgreSQL / MySQL
│
├─ 需要多进程共享 / 网络访问？
│   └─ 用 Redis / MongoDB
│
├─ 写入量极大，读取较少？
│   └─ 用 RocksDB ✓
│
├─ 需要嵌入式存储，数据量超过内存？
│   └─ 用 RocksDB ✓
│
├─ 需要作为其他系统的存储引擎？
│   └─ 用 RocksDB ✓
│
└─ 数据量小，读写均衡，不需要持久化？
    └─ 用内存 dict / Redis
```

### 12.3 RocksDB vs 其他嵌入式存储对比

| 特性 | RocksDB | LevelDB | SQLite | LMDB |
|------|---------|---------|--------|------|
| 数据结构 | LSM-Tree | LSM-Tree | B-Tree | B-Tree |
| 写性能 | ★★★★★ | ★★★★ | ★★★ | ★★★ |
| 读性能 | ★★★★ | ★★★ | ★★★★ | ★★★★★ |
| 并发写 | ★★★★★ | ★★ | ★★ | ★★★ |
| 事务支持 | ★★★★ | ★★ | ★★★★★ | ★★★ |
| SQL 支持 | ✗ | ✗ | ✓ | ✗ |
| 生产成熟度 | ★★★★★ | ★★★ | ★★★★★ | ★★★★ |
| 社区活跃度 | ★★★★★ | ★★ | ★★★★★ | ★★★ |

---

## 总结

RocksDB 是现代存储系统的基石之一。它的核心价值在于：

**一句话总结：** RocksDB 通过 LSM-Tree 将所有写入转化为顺序写，用写放大换取极高的写入吞吐量，特别适合 SSD 存储和写密集型场景。

**与 InnoDB 的本质区别：** InnoDB 是就地更新（B+树），适合读多写少；RocksDB 是追加写入（LSM-Tree），适合写多读少。两者没有绝对的优劣，只有适合不同场景的选择。

**在你的项目中：** 你的 AI Agent 框架目前所有数据都在内存中，进程重启后全部丢失。用 RocksDB 替换内存存储，可以以极低的代码改动成本，获得数据持久化、无限扩展的存储能力，同时保持接近内存的读写性能。

---

*文档生成时间：2026-04-17 | 基于 RocksDB 9.x 版本*

---

## 附录：LSM-Tree 到底是个什么东西？（图文通俗版）

> 如果前面的原理章节看得有点晕，这里从零开始，用最直白的方式重新讲一遍。

---

### 第一步：先忘掉 LSM-Tree，想想"记账"这件事

假设你开了一家小店，每天要记录进货和销售流水。你有两种记账方式：

**方式 A：台账本（对应 B+树 / InnoDB）**

```
┌─────────────────────────────────────┐
│           商品库存台账               │
├──────────┬──────────────────────────┤
│  商品名   │  当前库存数量             │
├──────────┼──────────────────────────┤
│  苹果     │  50 个                   │
│  香蕉     │  30 个                   │
│  橙子     │  20 个                   │
└──────────┴──────────────────────────┘
```

每次进货或卖出，你都要**翻到对应那一行，直接改掉数字**。
查起来很方便，一眼就能看到当前库存。
但改起来麻烦——要先找到那一行，再擦掉旧数字，写上新数字。

---

**方式 B：流水账本（对应 LSM-Tree / RocksDB）**

```
┌─────────────────────────────────────┐
│           进出货流水账               │
├──────────┬──────────┬───────────────┤
│  时间     │  商品名   │  操作         │
├──────────┼──────────┼───────────────┤
│  09:00   │  苹果     │  入库 100 个  │  ← 最新写在最下面
│  10:30   │  香蕉     │  入库 50 个   │
│  11:00   │  苹果     │  卖出 30 个   │
│  14:00   │  橙子     │  入库 20 个   │
│  15:30   │  苹果     │  卖出 20 个   │  ← 最新记录
└──────────┴──────────┴───────────────┘
```

每次操作，你都**在账本末尾追加一行新记录**，永远不修改旧记录。
写起来极快——直接翻到最后一页，写上去就完事。
但查起来麻烦——要查苹果的当前库存，得从最新记录往前翻，把所有苹果的记录加加减减才能算出来。

---

**LSM-Tree 就是"流水账本"这种思路的工程化实现。**

它的核心哲学只有一句话：**永远不修改已有数据，只追加新记录。**

---

### 第二步：流水账本的问题——账本越来越厚怎么办？

流水账本有个明显的问题：时间久了，账本会越来越厚，查一个商品的库存要翻很多页。

LSM-Tree 的解决方案是：**定期把账本"整理归档"**。

```
原始流水账（杂乱，按时间顺序）：
┌─────────────────────────────────────┐
│  苹果 +100  香蕉 +50  苹果 -30      │
│  橙子 +20   苹果 -20  香蕉 -10      │
│  苹果 +50   橙子 -5   苹果 -15      │
└─────────────────────────────────────┘
           ↓ 整理归档（Compaction）
归档账本（整洁，按商品名排序，合并同类项）：
┌─────────────────────────────────────┐
│  橙子: 15   苹果: 85   香蕉: 40     │
└─────────────────────────────────────┘
```

整理后，每个商品只有一条最终记录，查起来就快了。这个"整理归档"的过程，在 RocksDB 里就叫 **Compaction**。

---

### 第三步：RocksDB 的完整运作图

现在把上面的比喻映射到 RocksDB 的真实结构：

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                        你的应用程序                              │
  │              db.Put("apple", "85")  写入请求                    │
  └──────────────────────────┬──────────────────────────────────────┘
                             │
                             ▼
  ╔══════════════════════════════════════════════════════════════════╗
  ║                        【第一层：内存层】                         ║
  ║                                                                  ║
  ║   ┌──────────────────────────────────────────────────────────┐  ║
  ║   │  WAL 文件（磁盘，顺序写）                                  │  ║
  ║   │  作用：防止崩溃丢数据，相当于"草稿纸"                       │  ║
  ║   │  000001.log: [apple=85] [banana=40] [orange=15] ...      │  ║
  ║   └──────────────────────────────────────────────────────────┘  ║
  ║                             ↓ 同时写入                           ║
  ║   ┌──────────────────────────────────────────────────────────┐  ║
  ║   │  MemTable（内存，跳表结构，有序）                           │  ║
  ║   │  作用：最新数据的内存缓冲，读写都先来这里                    │  ║
  ║   │                                                           │  ║
  ║   │   apple → 85    banana → 40    orange → 15               │  ║
  ║   │   (按 key 字母序排列)                                      │  ║
  ║   └──────────────────────────────────────────────────────────┘  ║
  ║                    MemTable 写满(64MB)后变为只读                  ║
  ╚══════════════════════════════════════════════════════════════════╝
                             │
                             │ Flush（后台线程，顺序写磁盘）
                             ▼
  ╔══════════════════════════════════════════════════════════════════╗
  ║                        【第二层：磁盘层】                         ║
  ║                                                                  ║
  ║  L0（刚从内存 Flush 下来，文件间 key 可能重叠）                   ║
  ║  ┌────────────┐  ┌────────────┐  ┌────────────┐                 ║
  ║  │ 000001.sst │  │ 000002.sst │  │ 000003.sst │  ...            ║
  ║  │ apple=85   │  │ apple=90   │  │ banana=40  │                 ║
  ║  │ banana=40  │  │ orange=15  │  │ orange=20  │                 ║
  ║  └────────────┘  └────────────┘  └────────────┘                 ║
  ║         │                                                        ║
  ║         │ L0 文件数超过阈值，触发 Compaction（归并排序）           ║
  ║         ▼                                                        ║
  ║  L1（文件间 key 不重叠，每个文件负责一段 key 范围）               ║
  ║  ┌────────────┐  ┌────────────┐  ┌────────────┐                 ║
  ║  │ a - f 段   │  │ g - m 段   │  │ n - z 段   │                 ║
  ║  │ apple=90   │  │ ...        │  │ orange=20  │                 ║
  ║  └────────────┘  └────────────┘  └────────────┘                 ║
  ║         │                                                        ║
  ║         │ L1 总大小超过阈值，继续向下 Compaction                  ║
  ║         ▼                                                        ║
  ║  L2（更大，文件更多，key 不重叠）                                 ║
  ║  L3 → L4 → L5 → L6（每层大小是上一层的 10 倍）                   ║
  ╚══════════════════════════════════════════════════════════════════╝
```

---

### 第四步：读取一个 key 的完整过程

查询 `db.Get("apple")` 时，RocksDB 按从新到旧的顺序查找：

```
查询 "apple" 的值
        │
        ▼
  ① 先查 MemTable ──────────────────────────── 找到了？直接返回 ✓
        │ 没找到
        ▼
  ② 查 Immutable MemTable（如果有）──────────── 找到了？直接返回 ✓
        │ 没找到
        ▼
  ③ 查 L0 的每个 SST 文件
     （L0 文件间 key 可能重叠，所以每个都要查）
     
     对每个 SST 文件：
     ┌─────────────────────────────────────────────────┐
     │  先问 Bloom Filter："apple 在这个文件里吗？"      │
     │       ↓                                         │
     │  "不在" → 跳过，不读磁盘（节省 99% 的 I/O）      │
     │  "可能在" → 读 Index Block → 定位 Data Block     │
     │           → 在 Block 内查找 apple               │
     └─────────────────────────────────────────────────┘
        │ 没找到
        ▼
  ④ 查 L1 → L2 → L3 → ... （每层只需查一个文件）
        │
        ▼
  返回找到的最新版本值，或 Not Found
```

**Bloom Filter 是读取性能的关键：**

```
没有 Bloom Filter 的情况：
  查 "apple" → 要把 L0 的 4 个文件全部读一遍 → 4 次磁盘 I/O

有 Bloom Filter 的情况（apple 只在第 2 个文件里）：
  查 "apple" → 问文件1的BF："不在" → 跳过
             → 问文件2的BF："可能在" → 读磁盘，找到！
             → 文件3、4 不用查了
  → 只需 1 次磁盘 I/O
```

---

### 第五步：Compaction 的本质——归并排序

Compaction 其实就是你学过的**归并排序（Merge Sort）**，只不过是在文件级别做的。

```
Compaction 前（L0 有 3 个文件，key 有重叠，有旧版本）：

  文件A（较旧）:  apple=50  banana=30  orange=10
  文件B（较新）:  apple=85  cherry=5
  文件C（最新）:  banana=40  orange=20

                    ↓ 归并排序，保留每个 key 的最新版本

Compaction 后（生成新文件，key 不重叠，旧版本被清理）：

  新文件:  apple=85  banana=40  cherry=5  orange=20
           （apple 的旧值 50 被丢弃，orange 的旧值 10 被丢弃）
```

这就是为什么 Compaction 会产生**写放大**——同一份数据被反复读出来、排序、再写回去。

---

### 第六步：一张图总结 LSM-Tree 的全貌

```
写入路径（极快，全部顺序写）：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  新数据写入
      │
      ├──→ WAL 文件（顺序追加，崩溃保护）
      │
      └──→ MemTable（内存跳表，有序）
                │
                │ 写满后（64MB）
                ▼
          Immutable MemTable
                │
                │ 后台 Flush
                ▼
             L0 SST ──→ Compaction ──→ L1 SST ──→ Compaction ──→ L2 ...


读取路径（从新到旧，Bloom Filter 加速）：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  读取请求
      │
      ▼
  MemTable → Immutable MemTable → L0(全查) → L1(查1个) → L2 → ...
      │              │                │
      └──────────────┴────────────────┘
              每层都先问 Bloom Filter，
              "不在"就跳过，不读磁盘


数据生命周期：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  新写入 → 内存(热) → L0(较热) → L1 → L2 → ... → Ln(冷，大量压缩)
  
  数据越老，越往下沉，压缩率越高，访问越少
```

---

### 第七步：LSM-Tree 和 B+树的本质取舍，一句话版

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   B+树（InnoDB）：                                              │
│   "我把数据整理得井井有条，查起来飞快，但每次改动都要重新整理"    │
│    → 读快，写慢（随机 I/O）                                      │
│                                                                 │
│   LSM-Tree（RocksDB）：                                         │
│   "我先把所有改动记下来，查的时候再去翻记录，定期统一整理"        │
│    → 写快（顺序 I/O），读稍慢（多层查找 + Bloom Filter 补救）    │
│                                                                 │
│   没有谁更好，只有谁更适合你的场景：                             │
│   写多读少 → LSM-Tree    读多写少 → B+树                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

*文档生成时间：2026-04-17 | 基于 RocksDB 9.x 版本*
