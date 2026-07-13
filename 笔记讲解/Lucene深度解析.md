# Apache Lucene 深度解析：从倒排索引到源码级实现

> 写给想彻底搞懂搜索引擎底层的工程师的 Lucene 完全指南
>
> 本文融合了 Apache 官方文档、Elastic 官方博客、Lucene 源码分析以及美团内部搜索实践，力求把 Lucene 的每一个核心机制都讲透。

---

## 目录

1. [Lucene 是什么：数据平台、定位与生态](#1-lucene-是什么数据平台定位与生态)
2. [整体架构：索引链路与搜索链路](#2-整体架构索引链路与搜索链路)
3. [核心概念：Document、Field、Term、Segment](#3-核心概念documentfieldtermsegment)
4. [倒排索引：Lucene 的心脏](#4-倒排索引lucene-的心脏)
5. [FST：内存中的词典索引](#5-fst内存中的词典索引)
6. [索引文件格式全解析](#6-索引文件格式全解析)
   - 6.1 [文件体系三层结构](#61-文件体系三层结构)
   - 6.2 [segments_N：全局提交点](#62-segments_n全局提交点)
   - 6.3 [.si：段元数据](#63-si段元数据)
   - 6.4 [.tip / .tim：词典与词典索引](#64-tip--tim词典与词典索引)
   - 6.5 [.doc / .pos / .pay：倒排链](#65-doc--pos--pay倒排链)
   - 6.6 [.fdt / .fdx / .fdm：正排行存](#66-fdt--fdx--fdm正排行存)
   - 6.7 [.dvd / .dvm：DocValues 列存](#67-dvd--dvm-docvalues-列存)
   - 6.8 [Codec 机制](#68-codec-机制)
7. [Analyzer 分词器体系](#7-analyzer-分词器体系)
8. [IndexWriter 写入流程（源码级）](#8-indexwriter-写入流程源码级)
9. [Segment 段合并机制](#9-segment-段合并机制)
10. [IndexSearcher 查询流程](#10-indexsearcher-查询流程)
11. [评分模型：TF-IDF 与 BM25](#11-评分模型tf-idf-与-bm25)
12. [DocValues：列式存储与正排](#12-docvalues列式存储与正排)
13. [近实时搜索（NRT）：Refresh、Commit、Translog](#13-近实时搜索nrtrefreshcommittranslog)
14. [压缩编码：Delta、FOR、PForDelta、Roaring](#14-压缩编码deltaforpfordeltaroaring)
15. [跳表（Skip List）加速倒排链查找](#15-跳表skip-list加速倒排链查找)
16. [并发模型：读写分离与可见性](#16-并发模型读写分离与可见性)
17. [Lucene 与 Elasticsearch 的关系](#17-lucene-与-elasticsearch-的关系)
18. [工业级实践：外卖平台 RLE 倒排索引优化](#18-工业级实践外卖平台-rle-倒排索引优化)
19. [实战代码：从建索引到搜索](#19-实战代码从建索引到搜索)
20. [性能调优要点总结](#20-性能调优要点总结)

---

## 1. Lucene 是什么：数据平台、定位与生态

### 1.1 一句话定义

Apache Lucene 是一个用 Java 编写的**高性能、可扩展的信息检索（Information Retrieval, IR）库**。注意，它是一个**库（library）**，而不是一个开箱即用的搜索服务器或应用。它提供了构建搜索引擎所需的全部底层能力：分词、建立倒排索引、相关性评分、各种查询类型、高亮、拼写检查等，但它本身不提供 HTTP 接口、不提供分布式能力、不提供管理界面——这些都要由使用它的人（或上层产品如 Elasticsearch、Solr）来封装。

### 1.2 数据平台与历史脉络

Lucene 的故事始于 1999 年。Doug Cutting（后来 Hadoop 的创始人之一）创建了 Lucene 的最初版本，"Lucene" 这个名字取自他妻子的中间名。2001 年，Lucene 加入 Apache 软件基金会，成为 Apache 旗下第一个开源搜索项目。

下面这条时间线能帮你理解 Lucene 的能力是如何一步步演进的：

- **1999 年**：Doug Cutting 发布 Lucene 最初版本。
- **2001 年**：Lucene 进入 Apache 基金会。
- **2004 年**：CNET 的 Yonik Seeley 基于 Lucene 开发了 **Solr**，2006 年捐给 Apache。同年，Shay Banon 为妻子写食谱搜索，基于 Lucene 封装了 Compass。
- **2010 年**：Shay Banon 重写 Compass，推出 **Elasticsearch**，主打分布式与 RESTful。
- **Lucene 4.0（2012）**：里程碑版本，引入**可插拔的 Codec 架构**，使得索引文件格式、打分算法都可以替换，不再被锁死在 TF-IDF 上；同时引入了基于有限状态机（FST）的词典。
- **Lucene 5.0**：引入 Roaring Bitmaps 替代传统 Bitmap 做 Filter 缓存；DocValues 成为列存的主力。
- **Lucene 8.x**：默认相似度算法从 TF-IDF 切换为 **BM25**；引入 BKD 树处理数值/地理范围查询。
- **Lucene 9.0**：默认编解码器为 Lucene90Codec；引入 **HNSW 向量搜索**，正式支持 kNN。
- **Lucene 10.0**：引入**段内并发查询**（intra-segment search concurrency），解决单个大段的查询并行度瓶颈。

### 1.3 核心定位

Lucene 的定位可以用三句话概括：

它是一个**纯粹的检索内核**，专注于"把文档变成可被高效检索的索引"和"根据查询从索引中找出最相关的文档"这两件事。它**不关心数据从哪来、查询结果给谁用、集群如何协调**。它追求**极致的单机性能与空间效率**，为此设计了大量精巧的数据结构（FST、跳表、各种压缩编码）和工程优化（段不可变、近实时刷新）。

### 1.4 生态关系一览

| 项目 | 角色 | 与 Lucene 的关系 |
|------|------|-----------------|
| **Lucene** | 底层检索库 | 本体 |
| **Solr** | 企业级搜索服务器 | 在 Lucene 上封装 XML 配置、HTTP API、SolrCloud 分布式 |
| **Elasticsearch** | 分布式搜索/分析平台 | 在 Lucene 上封装 RESTful API、分片、副本、集群管理 |

记住一个关键事实：**Elasticsearch 的每个分片（Shard）本质上就是一个独立的 Lucene 索引**。你在 ES 里看到的 refresh、flush、merge、segment、translog，绝大部分概念都直接来自 Lucene（translog 是 ES 自己加的）。搞懂了 Lucene，ES 的存储层就一通百通了。

---

## 2. 整体架构：索引链路与搜索链路

Lucene 的工作分为两条主线：**写入（Indexing）**和**搜索（Searching）**。

### 2.1 索引链路（写入）

写入链路把原始文档变成磁盘上的倒排索引文件：

```
原始文档
   │
   ▼
Analyzer 分词（CharFilter → Tokenizer → TokenFilter）
   │
   ▼
IndexWriter.addDocument()
   │
   ▼
DocumentsWriter → DocumentsWriterPerThread(DWPT)  每个线程独立缓冲
   │
   ▼
内存缓冲（倒排、正排、DocValues 等在内存中累积）
   │
   ▼ flush（达到内存/文档阈值）
生成一个不可变的 Segment（一批索引文件）
   │
   ▼ commit
写入 segments_N 提交点，fsync 落盘
   │
   ▼ merge（后台）
小段合并成大段，物理删除被标记的文档
```

### 2.2 搜索链路（查询）

搜索链路把用户查询变成命中的文档列表并打分排序：

```
查询字符串
   │
   ▼
QueryParser / 直接构造 Query 对象
   │
   ▼
IndexSearcher.search(Query, n)
   │
   ▼
Query.rewrite() 查询重写（展开 Prefix/Wildcard 等）
   │
   ▼
createWeight() 生成 Weight（与 Searcher 绑定的可复用状态）
   │
   ▼
对每个 Segment：Weight.scorer() 生成 Scorer
   │
   ▼
Scorer 遍历倒排链（借助跳表加速）+ Similarity 打分
   │
   ▼
Collector 收集 TopN（优先队列）
   │
   ▼
TopDocs 结果
```

### 2.3 一个关键的设计哲学：段不可变（Immutable Segment）

理解 Lucene 的所有机制，都绕不开一个核心前提：**Segment 一旦生成就永不修改**。

这个看似"反直觉"的设计带来了一连串好处：

写入永远是追加，不存在就地更新，因此写入吞吐高、对 SSD 友好。查询时段是只读的，多个查询线程可以无锁并发访问同一个段，性能极好。索引文件可以被操作系统页缓存（OS Cache）高效缓存，因为内容不变。

代价是：删除不能真删，只能在 `.liv` 文件里打个标记；更新等于"删除旧的 + 写入新的"；垃圾（被删文档）要靠后台的段合并（merge）来真正清理。这就是为什么 Lucene 必须有 merge 机制，也是为什么 ES 删大量数据后磁盘不会立刻释放。

---

## 3. 核心概念：Document、Field、Term、Segment

在深入数据结构之前，先把几个最基础的概念厘清，否则后面会处处卡壳。

### 3.1 Document（文档）

Document 是 Lucene 索引和检索的**基本单位**，类比关系数据库里的"一行记录"。一个 Document 由若干个 Field 组成。注意，Lucene 的 Document 是无 schema 约束的——同一个索引里不同 Document 可以有不同的字段集合（这点和 ES 的 mapping 不同，mapping 是 ES 在上层加的约束）。

### 3.2 Field（字段）

Field 是 Document 的组成部分，类比"一个列的值"。一个 Field 由三部分构成：**名称（name）、类型（FieldType）、值（value）**。

Field 的行为完全由它的 FieldType 决定。FieldType 控制这个字段是否被索引、是否被存储、是否分词、记录哪些倒排信息、是否启用 DocValues 等。下面是 FieldType 的核心属性：

| 属性 | 含义 |
|------|------|
| `stored` | 是否存储原始值（决定能否在搜索结果中取回该字段，对应 ES 的 `store`） |
| `tokenized` | 是否分词 |
| `indexOptions` | 索引粒度：`DOCS`（只记文档）/ `DOCS_AND_FREQS`（记词频）/ `DOCS_AND_FREQS_AND_POSITIONS`（记位置）/ `..._AND_OFFSETS`（记字符偏移） |
| `storeTermVectors` | 是否存储词向量（高亮、More Like This 用） |
| `docValuesType` | DocValues 类型：`NONE/NUMERIC/BINARY/SORTED/SORTED_SET/SORTED_NUMERIC` |
| `omitNorms` | 是否省略归一化因子（省略后无法做长度归一化打分，省内存） |

### 3.3 常见 Field 类型速查

Lucene 提供了一批开箱即用的 Field 子类，对应不同的 FieldType 组合：

| Field 类 | 分词 | 索引 | 存储 | 典型用途 |
|----------|------|------|------|---------|
| **TextField** | ✓ | ✓ | 可选 | 全文检索（标题、正文） |
| **StringField** | ✗ | ✓（整体作为一个 Term） | 可选 | 精确匹配（ID、枚举、状态） |
| **StoredField** | ✗ | ✗ | ✓ | 仅存储、不检索（原始 JSON） |
| **IntPoint/LongPoint/FloatPoint/DoublePoint** | ✗ | ✓（BKD 树） | ✗ | 数值/范围查询 |
| **NumericDocValuesField** | ✗ | ✓（DocValues） | ✗ | 数值排序、聚合 |
| **SortedDocValuesField** | ✗ | ✓（DocValues） | ✗ | 字符串排序、聚合 |
| **SortedSetDocValuesField** | ✗ | ✓（DocValues） | ✗ | 多值字段聚合（标签） |
| **SortedNumericDocValuesField** | ✗ | ✓（DocValues） | ✗ | 多值数值排序聚合 |

**TextField vs StringField 是新手最容易踩的坑**：TextField 会分词，"北京欢迎你"会被切成多个 Term，搜"北京"能命中；StringField 不分词，整个"北京欢迎你"是一个 Term，只有搜完整的"北京欢迎你"才能命中。前者用于全文搜索，后者用于精确过滤。

### 3.4 Term（词项）

Term 是 Lucene 检索的**最小单位**，是一个 `<字段名, 字段值>` 的二元组。比如字段 `title` 的值 "lucene" 分词后产生的 Term 就是 `title:lucene`。倒排索引就是建立在 Term 上的——"哪些文档包含某个 Term"。

### 3.5 Segment（段）

Segment 是 Lucene 索引的**物理存储单位**，一个段就是一个**自包含的、不可变的、完整的倒排索引**（包含若干文档的全部索引信息）。一个 Lucene 索引（一个目录）由多个 Segment 组成。

每个 Segment 由一组文件构成（.tim、.tip、.doc、.fdt 等）。新写入的文档先在内存累积，flush 时生成一个新段。段越攒越多，就需要 merge 把小段合成大段。

---

## 4. 倒排索引：Lucene 的心脏

### 4.1 正排 vs 倒排

**正排索引（Forward Index）**记录的是"文档 → 内容"：给定文档 ID，能取出它包含哪些词。这适合"展示某篇文档"，但不适合"搜索"。

**倒排索引（Inverted Index）**记录的是"词 → 文档"：给定一个词，能立刻列出包含它的所有文档。这正是搜索引擎需要的——用户输入关键词，系统要快速找到包含这些词的文档。

举个例子，有三篇文档：

```
Doc1: "the quick brown fox"
Doc2: "the lazy dog"
Doc3: "quick dog"
```

倒排索引大致长这样：

```
Term      → Posting List (DocID 列表 + 词频 + 位置)
"the"     → [Doc1, Doc2]
"quick"   → [Doc1, Doc3]
"brown"   → [Doc1]
"fox"     → [Doc1]
"lazy"    → [Doc2]
"dog"     → [Doc2, Doc3]
```

搜 "quick dog"，分别取 "quick" 的倒排链 [Doc1, Doc3] 和 "dog" 的倒排链 [Doc2, Doc3]，求交集得到 Doc3。

### 4.2 Lucene 倒排索引的三层结构

Lucene 的倒排索引不是一个简单的 HashMap，而是为了**在海量词库下兼顾检索速度与内存占用**而精心设计的三层结构：

| 层 | 名称 | 存储位置 | 文件 | 作用 |
|----|------|---------|------|------|
| 1 | **Term Index（词项索引）** | 内存 | `.tip` | 用 FST 压缩存储 Term 的前缀，快速定位到词典的某个 Block |
| 2 | **Term Dictionary（词项字典）** | 磁盘 | `.tim` | 按 Block 存储 Term 后缀、统计信息、倒排链指针 |
| 3 | **Posting List（倒排列表）** | 磁盘 | `.doc/.pos/.pay` | 存储每个 Term 对应的有序 DocID 列表、词频、位置等 |

为什么要这么分层？因为一个大索引可能有上亿个不同的 Term，全部放内存会爆掉，全部放磁盘又会让每次查询都做大量磁盘 IO。Lucene 的方案是：**把 Term 的"索引"（前缀信息）用 FST 高度压缩后放内存，把 Term 的"全量字典"放磁盘**。FST 通常只有原始 Term 总大小的几十分之一，可以轻松驻留内存。

### 4.3 倒排链查找的完整三步

当你搜索一个 Term（比如 `title:lucene`）时，Lucene 内部做这三步：

第一步，用内存里的 **FST（.tip）** 快速定位：根据 Term 的前缀，FST 输出该 Term 所在的 Term Dictionary Block 在 `.tim` 文件中的偏移量（文件指针 fp）。

第二步，读取 `.tim` 中对应的 Block，在 Block 内对若干个 Term 做**二分查找**，找到目标 Term，拿到它的元数据：包括 `docFreq`（多少文档含此词）、`totalTermFreq`（总词频）以及倒排链在 `.doc` 文件中的起始指针 `DocStartFP`、跳表偏移 `SkipOffset` 等。

第三步，根据指针去 **.doc 文件**读取 Posting List（DocID 列表 + 词频），如果是短语查询还要去 **.pos** 读位置信息。读取时借助跳表（Skip List）跳过不需要的部分。

---

## 5. FST：内存中的词典索引

FST（Finite State Transducer，有限状态转换器）是 Lucene 4.0 引入的、用于实现 Term Index 的核心数据结构，理解它是理解 Lucene 内存效率的关键。

### 5.1 FST 是什么

FST 可以理解为一个**带输出的、最小化的有向无环图（Minimal Acyclic FST）**。它和 FSA（有限状态自动机）的区别在于：FSA 只能判断一个输入"是否被接受"（返回 true/false），而 FST 在接受输入的同时还能**产生一个输出值**。

在 Lucene 里，FST 的输入是 Term（按字典序排列的词），输出是这个 Term 在 `.tim` 文件中的位置指针。也就是说，FST 实现了 `Term → 文件偏移量` 的映射，功能上类似 `Map<String, Long>`，但内存占用极小。

形式化定义，一个 FST 是一个六元组 `(Q, Σ, Ω, q₀, F, ρ)`：Q 是有限状态集，Σ 是输入标签集，Ω 是输出标签集，q₀ 是初始状态，F 是终止状态集，ρ 是最终输出函数。

### 5.2 FST 为什么省内存：前缀与后缀共享

FST 省内存的秘诀是**共享公共前缀和公共后缀**。

假设要存 "cat"、"deep"、"do"、"dog"、"dogs" 这几个词。普通的 Map 会把每个字符串完整存储，而 FST 会：

- "do"、"dog"、"dogs" 共享前缀 "do"，所以 d→o 这两条边只存一次；
- 如果有 "cats"、"dogs" 都以 "s" 结尾，结尾的 s 状态也可以共享。

```
插入顺序（必须按字典序）：cat, deep, do, dog, dogs

         c     a     t
   (0)─────►(1)────►(2)────►((3))    "cat"
    │
    │ d    e     e     p
    └────►(4)──►(5)──►(6)──►((7))     "deep"
          │
          │ o
          └──►((8))                   "do"
              │ g
              └──►((9))               "dog"
                  │ s
                  └──►((10))          "dogs"
```

通过共享，FST 的体积通常只有所有 Term 拼起来的几十分之一，查询时间复杂度是 O(len(term))，和 HashMap 几乎一样快。这就是 Lucene 能把"词典索引"放进内存的根本原因。

### 5.3 FST 的构造过程：冰封（Freeze）机制

FST 要求**输入必须按字典序有序**，这是它能边构建边压缩的前提。Lucene 用 `FSTCompiler`（旧版叫 `Builder`）来构建，核心是一个 `UnCompiledNode<T>[] frontier` 数组，维护当前正在构建的"前沿路径"。

构建过程的关键是**冰封（freeze）**：

1. 每来一个新词，先和前一个词计算**最长公共前缀**。
2. 公共前缀之后的那些节点，因为后续的词字典序更大，不可能再修改它们了，于是从尾部开始把它们"冰封"。
3. 冰封就是调用 `compileNode`，把可变的 `UnCompiledNode` 转成不可变的、二进制形式的 `CompiledNode`。
4. 冰封时通过 `NodeHash` 检查是否存在结构相同的节点（后缀共享），若有则复用，进一步省内存。

每个节点构建中用一个 `current[]` 数组描述边，每条边是四元组 `(index, output, label, flag)`：`label` 是输入字符，`output` 是该边携带的输出，`flag` 标记是否终止节点等。

### 5.4 FST 在 .tip 文件中的角色与 BlockTree

Lucene 的词典实现叫 **BlockTree Term Dictionary**，FST 是它的索引层。具体来说：

每个字段（Field）在 `.tip` 文件里有一个独立的 FST。FST 的输出（output）编码了指向 `.tim` 文件中某个 Block 的位置：`fp`（文件偏移）+ `hasTerm` 标志 + `isFloor` 标志，编码为 VInt（可变长整数）。

BlockTree 把 Term 按公共前缀分组成 **Block**，相关的两个阈值参数是：

- `DEFAULT_MIN_BLOCK_SIZE = 25`：当某个前缀下的子项超过 25 个时，写成一个 Block。
- `DEFAULT_MAX_BLOCK_SIZE = 48`：当超过 48 个时，拆成多个 Block（即 floor block / 分层 block）。

于是 Block 分两类：**Non-floor Block**（子节点数 ≤ max，一个块搞定）和 **Floor Block**（子节点数 > max，需要拆成多个子块）。FST 只索引到 Block 级别，Block 内部再做二分查找，从而在"内存索引大小"和"磁盘查找次数"之间取得平衡。

### 5.5 FST vs HashMap vs LongObjectHashMap

值得一提的是，FST 虽然省内存，但查询时需要"走状态机"，对极热的词其实不如哈希表快。美团在外卖搜索的实践中对比过三种结构（详见第 18 节）：FST 内存最省但查询要遍历状态；HashMap 查询 O(1) 但内存占用大；LongObjectHashMap 是优化版哈希表。在不同场景下需要权衡。

---

## 6. 索引文件格式全解析

这一节是 Lucene 最硬核的部分——彻底搞懂磁盘上那一堆后缀诡异的文件分别是什么。理解了文件格式，你就理解了 Lucene 的全部数据结构。

### 6.1 文件体系三层结构

Lucene 的文件组织分为三个层次，从全局到局部：

**第一层：全局索引元数据（`segments_N`）**——描述当前索引目录里有哪些有效的段，哪个是最新提交。

**第二层：段元数据（`.si`）**——每个段自己的元数据（版本、文档数、用的 Codec、文件组成方式等）。

**第三层：段相关数据文件**——最复杂最庞大的一层，包括倒排、正排、列存、词向量、归一化、存活位图、点索引等各类文件。

完整的文件扩展名对照表（基于 Lucene 9.0 / Lucene90Codec）：

| 扩展名 | 名称 | 用途 |
|--------|------|------|
| `segments_N` | Segments File | 提交点，记录索引包含哪些段 |
| `.si` | Segment Info | 段元数据（文档数、Codec、版本） |
| `.fnm` | Fields | 字段名、编号、索引选项等元数据 |
| `.fdt` | Field Data | 正排行存：字段原始内容（即 ES 的 _source） |
| `.fdx` | Field Index | 正排行存：指向 .fdt 中文档位置的指针 |
| `.fdm` | Field Metadata | 正排行存：元数据 |
| `.tim` | Term Dictionary | 倒排：词项字典（后缀、统计、倒排指针） |
| `.tip` | Term Index | 倒排：词典索引（FST） |
| `.tmd` | Term Dict Metadata | 词典元数据 |
| `.doc` | Frequencies | 倒排：DocID 列表 + 词频 + 跳表 |
| `.pos` | Positions | 倒排：词位置（短语/邻近查询用） |
| `.pay` | Payloads | 倒排：payload + 字符偏移量 |
| `.dvd` | DocValues Data | 列存：DocValues 数据（排序/聚合） |
| `.dvm` | DocValues Metadata | 列存：DocValues 元数据 |
| `.nvd` | Norms Data | 归一化因子数据（打分用） |
| `.nvm` | Norms Metadata | 归一化元数据 |
| `.liv` | Live Documents | 存活文档位图（标记删除） |
| `.dim` | Points Data | BKD 树数据（数值/地理范围查询） |
| `.dii` | Points Index | BKD 树索引 |
| `.tvx/.tvd` | Term Vectors | 词向量索引/数据（高亮、MLT） |
| `.vec/.vex/.vem` | Vectors | kNN 向量数据/索引(HNSW)/元数据 |
| `.cfs/.cfe` | Compound File | 复合文件（把多个小文件打包，减少句柄） |

### 6.2 segments_N：全局提交点

`segments_N` 是整个索引的"目录总账"，每次 commit 都会生成一个新的、N 更大的 `segments_N` 文件。一个索引目录里可能同时存在多个 `segments_N`，**N 值最大的那个才是当前有效的提交点**。

它内部记录的关键信息：

- `LuceneVersion` / `IndexCreatedVersionMajor`：写入版本、索引创建时的主版本。
- `Version`：本次提交的版本号（单调递增）。
- `NameCounter`：用于生成下一个段名的计数器。
- `SegCount`：本提交包含多少个段。
- 每个段的 **SegmentCommitInfo**（见下表）。
- `CommitUserData`：commit 时可附带的自定义键值对（ES 用它存 translog 信息、可用于检查点回退）。

每个段的 `SegmentCommitInfo` 字段非常关键：

| 字段 | 含义 |
|------|------|
| `SegName` | 段名前缀（如 `_1`） |
| `SegID` | 段的唯一标识 |
| `SegCodec` | 该段使用的 Codec 名称（如 `Lucene87`） |
| `DelGen` | `.liv` 文件的迭代编号（每次删除递增） |
| `DeletionCount` | 被删除的文档数 |
| `FieldInfosGen` | `.fnm` 文件迭代编号 |
| `DocValuesGen` | `.dvd/.dvm` 文件迭代编号 |
| `SoftDelCount` | 软删除文档数 |
| `UpdatesFiles` | 记录发生变化的索引文件 |

**IndexDeletionPolicy（提交点保留策略）**决定旧的 `segments_N` 何时删除：默认是 `KeepOnlyLastCommitDeletionPolicy`（只保留最新提交）；如果用 `NoDeletionPolicy`（或 `SnapshotDeletionPolicy`）可以保留多个历史提交点，配合 `CommitUserData` 实现"检查点回退"——这是做快照/备份的基础。

### 6.3 .si：段元数据

`.si`（Segment Info）记录单个段自己的元数据：文档总数、使用的 Codec、段版本号、是否使用复合文件（compound file）、`IndexSort`（段内排序方式）、以及诊断信息（写入时的 OS、JVM、Lucene 版本、创建时间戳）。它是读取一个段的"入口说明书"。

### 6.4 .tip / .tim：词典与词典索引

这两个文件实现了第 4-5 节讲的倒排索引的前两层。

**`.tip`（Term Index，词典索引）**，存 FST：

- `FSTIndex`：每个 Field 的 FST 结构本体。
- `IndexStartFP`：每个 Field 的 FST 在 `.tip` 中的起始位置。
- `DirOffset`：FSTIndex 目录在 `.tip` 文件中的偏移量。

**`.tim`（Term Dictionary，词典）**，存 Term 的完整信息，按 Block 组织：

- `OuterNode` / `InnerNode`：两种节点（块）类型。
- `Suffix`：Term 的后缀部分（前缀已被 FST 索引，这里只存后缀，省空间）。
- `TermStats`：Term 的统计信息——`docFreq`（包含此词的文档数）、`totalTermFreq`（此词出现总次数）。
- `TermMetaData`：指向倒排链的元数据，包括 `SingletonDocID`（如果该词只在一个文档出现的优化）、`DocStartFP`（倒排链在 `.doc` 的起始指针）、`PosStartFP`（在 `.pos` 的指针）、`PayStartFP`（在 `.pay` 的指针）、`SkipOffset`（跳表偏移）等。
- `FieldSummary`：按 Field 组织的汇总信息。

> ⭐ 这部分的逐字节格式是学城内部文档的独有深度内容，网上一般只笼统说"tip 存 FST、tim 存词典"，很少给出 `TermMetaData` 中 `SingletonDocID/SkipOffset/DocStartFP` 这样的字段级布局。

### 6.5 .doc / .pos / .pay：倒排链

这三个文件实现倒排索引的第三层——Posting List。

**`.doc`（Frequencies）**：存储每个 Term 的 **DocID 列表**和**词频（TF）**。这是倒排链的主体。DocID 用差分编码（Delta）+ FOR/PForDelta 压缩（见第 14 节），并内嵌**跳表**（见第 15 节）加速。

**`.pos`（Positions）**：存储每个 Term 在文档中出现的**位置序号**。短语查询（PhraseQuery）、邻近查询（slop）需要它来判断词的相对顺序和距离。只有 `indexOptions` 包含 POSITIONS 时才生成。

**`.pay`（Payloads）**：存储 **payload**（附加在某个位置上的任意字节，可用于自定义打分）和**字符偏移量（offset）**（高亮时定位原文位置）。只有需要时才生成。

不同的 `indexOptions` 决定生成哪些文件：`DOCS` 只生成 .doc（不记词频，节省空间，适合只判断"有没有"的字段）；`DOCS_AND_FREQS` 记词频；`DOCS_AND_FREQS_AND_POSITIONS` 加 .pos；再加 OFFSETS 则加 .pay 的 offset。

### 6.6 .fdt / .fdx / .fdm：正排行存

这三个文件实现**正排（行式）存储**，即"按文档存原始字段值"，对应 `stored=true` 的字段、ES 里的 `_source`。

**`.fdt`（Field Data，数据文件）**的组织方式很讲究：

- 以 **Chunk** 为单位组织，默认 **128 个文档为一个 Chunk**（也可能被 chunkSize 字节数触发）。
- 每个 Chunk 包含：`DocBase`（本 Chunk 第一个文档的 ID，后续用差值）、`ChunkDocs`（= `numBufferedDocs | slicedBit | dirtyBit`，编码本 chunk 文档数和标志位）、`DocFieldCounts`（每个文档的字段数）、`DocLengths`（每个文档序列化后的长度）、`CompressedDocs`（压缩后的字段内容）。
- `CompressedDocs` 用 **LZ4**（或 DEFLATE，取决于压缩模式 BEST_SPEED / BEST_COMPRESSION）压缩。
- 字段内容里，`FieldNumAndType = (fieldNumber << TYPE_BITS) | fieldType`，把字段编号和类型组合成一个 long 存储，节省空间。
- `DocFieldCounts` 有三种编码情况：所有文档字段数都相同时只存一个值；只有一个文档时直接存；各不相同时逐个存。

**`.fdx`（Field Index，索引文件）**：用于根据 DocID 快速定位到 `.fdt` 中的位置。

- 每 **1024 个 Chunk 为一个 Block**（`BlockShift = 10`，即 2^10）。
- `NumDocsBlock`：每个 Chunk 的文档数，用 `DirectMonotonicWriter`（单调递增编码）压缩。
- `StartPointBlock`：每个 Chunk 在 `.fdt` 中的起始指针。

**`.fdm`（Field Metadata，元数据文件）**：存 `NumDocs`、`BlockShift`、`TotalChunks`，以及解压 `.fdx` 所需的参数（`NumDocsMeta`、`StartPointsMeta`、`SPEndPointer`、`maxPointer`）。

> ⭐ Lucene 9.6 的正排构建入口是 `IndexingChain#processDocument`：先 `startStoredFields(docID)`，对每个字段调 `processField`（`writeField` 内部区分定长/变长——定长直接写入 `bufferedDocs`，变长先写长度再写值），最后 `finishStoredFields()`。`finishDocument` 做四件事：记录 `numStoredFields`、记录 `endOffsets`、递增 `numBufferedDocs`、判断是否 `triggerFlush`。flush 条件是 `bufferedDocs.size() >= chunkSize` 或 `numBufferedDocs >= maxDocsPerChunk`。

### 6.7 .dvd / .dvm：DocValues 列存

`.dvd`（数据）和 `.dvm`（元数据）实现**列式存储（DocValues）**，即"按字段存所有文档的值"，用于排序、聚合、分面。详见第 12 节。简言之，`.dvd` 存实际的列数据（用 GCD、DirectWriter、DirectMonotonicWriter 等编码压缩），`.dvm` 存索引和统计信息。

### 6.8 Codec 机制

从 Lucene 4.0 起，所有索引文件的读写格式都由一个可插拔的 **Codec**（编解码器）决定。`org.apache.lucene.codecs.Codec` 是抽象类，不同 Lucene 版本有不同默认实现（`Lucene87Codec`、`Lucene90Codec`、`Lucene99Codec` 等）。

Codec 把索引拆成多个"格式组件"，每个组件负责一类文件：`PostingsFormat`（管 .tim/.tip/.doc/.pos/.pay）、`StoredFieldsFormat`（管 .fdt/.fdx/.fdm）、`DocValuesFormat`（管 .dvd/.dvm）、`NormsFormat`、`PointsFormat`、`KnnVectorsFormat` 等。

每个文件都有 **codec header**（魔数 + codec 名 + 版本）和 **footer**（校验和 checksum），用于验证文件完整性、防止损坏。Codec 机制的意义在于：你可以替换某一类文件的存储格式而不动其他部分，比如换一个更高压缩比的 PostingsFormat，这给了 Lucene 极强的演进能力。

---

## 7. Analyzer 分词器体系

分词（Analysis）是把一段原始文本切分成一个个 Term 的过程，发生在两个时刻：**索引时**（把文档内容切成 Term 建倒排）和**查询时**（把查询字符串切成 Term 去匹配）。一个非常重要的原则是：**索引时和查询时通常要用同一个 Analyzer**，否则切出来的 Term 对不上，就搜不到。

### 7.1 三段式管道：CharFilter → Tokenizer → TokenFilter

Lucene 的 Analyzer 内部是一条三段式的处理管道，设计上类似 Java I/O 的装饰器模式：

```
原始文本 (Reader)
   │
   ▼
CharFilter（可选，0~多个）   字符级预处理：去 HTML 标签、字符映射替换
   │
   ▼
Tokenizer（必须，1 个）      基础分词：把字符流切成 Token 流
   │
   ▼
TokenFilter（0~多个）        Token 级加工：转小写、去停用词、词干提取、同义词
   │
   ▼
TokenStream（最终的 Token 流）
```

- **CharFilter**：在分词之前对原始字符流做预处理。比如 `HTMLStripCharFilter` 去掉 HTML 标签，`MappingCharFilter` 做字符替换。
- **Tokenizer**：核心分词器，决定如何切词。它接收一个 Reader，输出 Token 流。每个 Analyzer 必须有且仅有一个 Tokenizer。
- **TokenFilter**：对 Tokenizer 产出的 Token 流做进一步加工，可以串联多个。常见的有 `LowerCaseFilter`（转小写）、`StopFilter`（去停用词）、`PorterStemFilter`（英文词干提取）、`SynonymFilter`（同义词扩展）。

`Analyzer` 抽象类的核心方法是 `createComponents(String fieldName)`，它返回一个 `TokenStreamComponents`（封装 Tokenizer + TokenFilter 链）；`initReaderForNormalization` 可挂 CharFilter。

### 7.2 TokenStream 与 Attribute

分词的结果不是简单的字符串列表，而是一个 `TokenStream`，每个 Token 携带一组 **Attribute（属性）**：

- `CharTermAttribute`：词本身的文本。
- `OffsetAttribute`：词在原文中的起止字符偏移（高亮用）。
- `PositionIncrementAttribute`：位置增量（同义词、停用词会用到，比如停用词被删后位置仍占位）。
- `TypeAttribute`：词的类型（单词、数字等）。
- `PayloadAttribute`：附加的 payload。

遍历 TokenStream 的标准代码模式是 `incrementToken()` 循环。

### 7.3 常见 Analyzer 速查

| Analyzer | Tokenizer | TokenFilter 链 | 特点 |
|----------|-----------|---------------|------|
| **StandardAnalyzer** | StandardTokenizer | StandardFilter → LowerCaseFilter → StopFilter | 基于 Unicode UAX#29 文本分割，最常用 |
| **WhitespaceAnalyzer** | WhitespaceTokenizer | 无 | 仅按空白切分，不转小写、不去停用词 |
| **SimpleAnalyzer** | LowerCaseTokenizer | 无 | 按非字母字符切分并转小写 |
| **StopAnalyzer** | LowerCaseTokenizer | StopFilter | 在 Simple 基础上去停用词 |
| **KeywordAnalyzer** | KeywordTokenizer | 无 | 不分词，整段文本作为一个 Token（等价 StringField 的效果） |
| **IKAnalyzer** | IKTokenizer | IK 智能/最细粒度 | 中文分词，最常用的中文方案 |
| **SmartChineseAnalyzer** | HMMChineseTokenizer | 多个中文 Filter | Lucene 自带中文分词（基于 HMM） |

注意 **StandardAnalyzer 处理中文是逐字切分**（"中国人" → "中"/"国"/"人"），并不适合中文全文检索，中文场景应该用 IK 等专门的分词器。

### 7.4 中文分词的难点与 IK

英文天然用空格分词，中文没有词边界，是分词的难点。中文分词方法的演进大致是：**字典法**（查字典、最少切分）→ **统计语言模型**（动态规划 + 维特比算法找最优切分）→ **机器学习**（HMM/CRF/SVM，乃至深度学习的 BiLSTM+CRF）。

**IK 分词器**是工业界最常用的中文方案，提供两种模式：

- **ik_smart（智能/粗粒度）**：做最少切分，"中华人民共和国" → "中华人民共和国"。
- **ik_max_word（最细粒度）**：穷尽所有可能的词，"中华人民共和国" → "中华人民共和国/中华人民/中华/华人/人民共和国/人民/共和国/共和/国"。

IK 支持**词典热更新**：可以远程加载自定义词典和停用词典并定期刷新，无需重启服务就能识别新词（比如新出现的网络热词、品牌名）。一个常见的实战配置是：索引时用 ik_max_word（建索引尽量细，召回率高），查询时用 ik_smart（查询切得粗，精度高）。

---

## 8. IndexWriter 写入流程（源码级）

IndexWriter 是写入的总入口，**线程安全**，整个流程相当精巧。这一节深入到源码层面。

### 8.1 核心类关系

```
IndexWriter                       写入主入口（线程安全）
  └── DocumentsWriter             添加文档、协调删除与 flush
        ├── DocumentsWriterPerThreadPool (DWPTP)   维护 ThreadState 池
        │     └── ThreadState     持有一个 DWPT 引用，用完归还池
        ├── DocumentsWriterPerThread (DWPT)        每线程独立缓冲，实际处理文档
        ├── DocumentsWriterDeleteQueue             全局共享的删除队列（CAS 无锁）
        └── DocumentsWriterFlushControl            全局刷盘调度器
```

设计精髓：**每个写入线程独占一个 DWPT**，在自己的 DWPT 里建立内存索引，互不干扰，从而实现高并发写入。DWPT 之间不共享内存缓冲，这是 Lucene 写入能并行的关键。

### 8.2 IndexWriterConfig 关键配置

构造 IndexWriter 前要先配 `IndexWriterConfig`，几个最重要的参数：

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `setRAMBufferSizeMB` | 16 MB | 所有 DWPT 总内存超过此值就触发 flush。**调大可显著提升写入吞吐** |
| `setMaxBufferedDocs` | DISABLE（不限） | 单个 DWPT 文档数超过此值触发 flush |
| `setOpenMode` | CREATE_OR_APPEND | 创建新索引 / 追加 / 覆盖 |
| `setMergePolicy` | TieredMergePolicy | 段合并策略 |
| `setMergeScheduler` | ConcurrentMergeScheduler | 合并调度器（多线程后台合并） |
| `setUseCompoundFile` | true | 是否用复合文件（.cfs）减少句柄 |
| `setSimilarity` | BM25Similarity | 打分算法 |
| `setIndexSort` | 无 | 段内排序（写入时排序，利于 early termination） |

### 8.3 写入流程的两个阶段

理解写入，关键是看清它分**两个阶段**，因为不同的索引文件只能在不同阶段生成。

**第一阶段：addDocument（文档处理）**

调用链：`IndexWriter.addDocument()` → `updateDocument()` → `DocumentsWriter.updateDocuments()` → 拿到 DWPT → `DWPT.updateDocument()` → `consumer.processDocument()`。

这个阶段把文档分词、构建内存中的倒排/正排结构。这一阶段**可以直接边处理边写**的文件有：`.fdx/.fdt`（正排，因为字段值拿到就能写）、`.tvd/.tvx`（词向量）。

**第二阶段：flush（落盘成段）**

当触发 flush 时，把内存里攒的数据一次性写成一个完整的段。这一阶段才生成的文件有：`.tim/.tip/.doc/.pos/.pay`（倒排——因为要等所有 Term 收齐、排序后才能构建 FST）、`.dvd/.dvm`（DocValues——要等列数据收齐才能选最优压缩编码）、`.nvd/.nvm`（norms）、`.liv`（存活位图）、`.dim/.dii`（点）。

**为什么分两阶段？** 因为倒排索引的构建需要"全局信息"：FST 要求 Term 有序，必须等一批文档的所有 Term 都收集完才能排序构建；DocValues 的压缩编码（如 GCD、选最小位宽）也要看到全部数据分布才能定。所以这些只能在 flush 阶段做，而正排（每个文档独立）可以边来边写。

### 8.4 自动 Flush 的三个触发条件

Lucene 会在以下任一条件满足时**自动**触发 flush：

| 条件 | 配置项 | 默认值 | 粒度 |
|------|--------|--------|------|
| **文档数超限** | `MaxBufferedDocs` | DISABLE_AUTO_FLUSH | 单个 DWPT |
| **总内存超限** | `RAMBufferSizeMB` | 16 MB | 所有 DWPT 之和 |
| **单线程硬限制** | `RAMPerThreadHardLimitMB` | 1945 MB | 单个 DWPT（建立后不可改） |

当 `RAMBufferSizeMB` 触发时，Lucene 会挑**内存占用最大的那个 DWPT** 去 flush。`RAMPerThreadHardLimitMB` 是一道硬墙，防止单个线程的 DWPT 无限膨胀。

### 8.5 被动 Flush 与 fullFlushLock

除了自动 flush，还有**被动 flush**：用户显式调用 `commit()`、`flush()`，或者打开 NRT reader（`getReader()`），或 ES 的 `refresh`、索引关闭时。

被动 flush 会要求把**所有** DWPT 都 flush（full flush），此时需要拿 **`fullFlushLock`** 这把全局锁，避免多个线程同时调度 full flush 造成混乱。

### 8.6 内存管理与写入阻塞

DocumentsWriterFlushControl 维护两个量：`activeBytes`（正在被添加/更新占用的内存）和 `flushBytes`（已标记待 flush 的内存）。当 `(activeBytes + flushBytes) > 2 * ramBufferSizeMB` 且 `activeBytes < limit` 时，写入线程会被**阻塞**（`wait(1000)`，每秒检查一次），等待后台 flush 释放内存。

**调优启示**：如果写入吞吐上不去且观察到频繁阻塞，适当调大 `ramBufferSizeMB` 往往立竿见影——更大的缓冲意味着更少的 flush 次数、更少的小段、更少的阻塞。

### 8.7 Commit 流程

`commit()` 比 flush 更重，它要保证数据**持久化到磁盘**：

1. `prepareCommitInternal()`：检查是否有进行中的 commit；执行 full flush；发布已 flush 的段；应用所有挂起的删除和更新。
2. `commitInternal()`：把内存中所有数据刷到磁盘；写新的 `segments_N` 提交点文件；执行 `fsync` 确保落盘；触发合并策略检查。

flush 只是"把内存数据变成段文件（可能还在 OS Cache）"，commit 才是"fsync 落盘 + 写提交点"，因此 commit 之后即使断电数据也不会丢。这也是 ES 里 refresh（≈flush，可搜但未持久化）和 flush（≈commit，持久化）的区别来源。

### 8.8 事务性：Rollback 与序列号

IndexWriter 提供 `rollback()`，能把索引恢复到**上一次成功 commit 的状态**，丢弃此后所有未提交的更改——这给了 Lucene 一定的事务能力。每个写操作（add/update/delete）会返回一个单调递增的**序列号（sequence number）**，可用于判断操作顺序和实现更细粒度的并发控制。

---

## 9. Segment 段合并机制

### 9.1 为什么必须合并

回顾第 2.3 节：段不可变，每次 flush 产生新段，删除只是打标记。这会带来三个问题，都要靠合并解决：

**文件句柄爆炸**——段越来越多，每个段一堆文件，文件描述符会被耗尽。**查询变慢**——查询要遍历所有段的倒排链，段越多越慢。**垃圾堆积**——被标记删除的文档一直占着磁盘和倒排链，不合并就清不掉。

合并（Merge）就是把多个小段读出来，合成一个大段，过程中**真正剔除被删除的文档**，重新压缩，然后用新大段替换掉那些小段。

### 9.2 TieredMergePolicy：默认的分层合并策略

Lucene 4 之后默认用 `TieredMergePolicy`（之前是 `LogMergePolicy`）。它的核心思想是**按段大小分层，优先合并大小相近的段**，避免反复合并大段造成浪费。

核心参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxMergeAtOnce` | 10 | 一次合并最多选多少个段 |
| `segmentsPerTier` | 10 | 每一层允许多少个段（相当于"预算"） |
| `maxMergedSegmentMB` | 5120（5GB） | 合并后段的大小上限，超过的大段不再参与合并 |
| `floorSegmentMB` | 2 | 小于此值的段一律按 2MB 计（避免给小段分太细的层） |
| `deletePctAllowed` | 33 | 索引中允许的最大删除文档百分比，超过会触发合并清理 |
| `noCFSRatio` | 0.1 | 段大小占索引比例超此值则不用复合文件 |

工作流程大致是：先算出"预算"——根据索引总大小，索引里应该有多少个段是合理的。如果实际段数超预算，就按段大小（折算删除比例后）降序排，寻找"代价最小"的一组段来合并。合并代价 = skew（组内最大段/最小段，越接近 1 越好）+ 总合并大小（越小越好）+ 能回收的删除文档比例（越多越好）。它**偏好合并大小相近、总量小、能清掉很多删除文档的段**。

### 9.3 合并的触发时机

合并会在这些时候被考虑触发：每次 flush 出新段后、commit 时、打开 NRT reader 时、删除文档比例超过 `deletePctAllowed` 时，以及用户手动 `forceMerge`。合并由 `ConcurrentMergeScheduler` 在**后台多线程**异步执行，不阻塞写入。

### 9.4 删除文档的物理清理

删除时只在 `.liv` 文件用 BitSet 标记 docID，搜索时跳过被标记的文档。**只有当包含被删文档的段参与合并时，这些文档才会被物理移除**（不被写进新的合并段）。

这就解释了一个常见现象：**为什么删了大量数据，磁盘却不见少？** 因为那些被删文档所在的段如果一直没被选中合并（比如是很大的冷数据段、或合并策略跳过了它们），删除标记就一直在，物理数据就一直占着盘。

### 9.5 forceMerge vs 自动合并

| 维度 | 自动合并 | forceMerge |
|------|---------|-----------|
| 触发 | Lucene 周期性检查 | 用户手动调用 |
| 强度 | 部分合并，保留多层段 | 可强制合到指定段数（如 `maxNumSegments=1`） |
| 阻塞性 | 后台异步，不影响读写 | 同步阻塞，期间 IO/CPU 飙高，性能下降 |
| 适用 | 日常维护 | 索引转只读后的优化、彻底清理删除文档 |

**forceMerge 是把双刃剑**：合到 1 个段后查询最快、删除全清，但合并过程开销巨大（要重写整个索引，磁盘临时占用可能翻倍），而且合出的超大段（>5GB）之后不再参与自动合并，如果之后还有写入和删除，反而会积累删除标记无法清理。

### 9.6 不建议主动合并的场景

⭐ 根据美团内部实践总结，以下场景**不建议**手动 forceMerge：写入高峰期（会和写入抢 IO/CPU）、磁盘空间不足（合并需要额外临时空间，最坏翻倍）、SSD 寿命敏感环境（合并是大量写入）、索引仍在持续高频更新（合出大段后删除标记又会堆积）、查询性能要求极高的在线时段、近实时性要求很高的场景。最佳实践是：只对**已经停止写入、转为只读**的索引做 forceMerge。

---

## 10. IndexSearcher 查询流程

### 10.1 查询执行的完整链路

```
IndexSearcher.search(Query, n)
   │
   ▼ Query.rewrite()              查询重写：展开 Prefix/Wildcard/Fuzzy 等
   │
   ▼ createWeight(query, scoreMode, boost)   生成 Weight（可复用的查询状态）
   │
   ▼ 对每个 LeafReaderContext（每个段）:
   │     Weight.scorer(context)   生成 Scorer
   │        └── Scorer.iterator() 得到 DocIdSetIterator
   │
   ▼ Scorer 遍历匹配文档（跳表加速） + Similarity 打分
   │
   ▼ Collector.collect(doc)       收集结果（TopN 用优先队列）
   │
   ▼ TopDocs                      返回得分最高的 N 个文档
```

### 10.2 Query 重写（Rewrite）

很多查询不能直接执行，需要先**重写**成基础查询。比如 `PrefixQuery("算")` 要先在词典里找出所有以"算"开头的 Term（算法、算术、算子……），重写成一个 `BooleanQuery`，里面是一堆 `TermQuery` 的 SHOULD 组合。需要重写的查询包括 `PrefixQuery`、`WildcardQuery`、`FuzzyQuery`、`RegexpQuery`、`RangeQuery` 等（统称 `MultiTermQuery`）。

`MultiTermQuery` 有多种重写策略：`SCORING_BOOLEAN_REWRITE`（展开成参与打分的 BooleanQuery）、`CONSTANT_SCORE_BOOLEAN_REWRITE`、`CONSTANT_SCORE_FILTER_REWRITE`（不打分，当 filter 用，匹配多时更高效）。如果展开的 Term 太多（超过 `BooleanQuery.maxClauseCount`，默认 1024），会抛 `TooManyClauses` 异常——这就是为什么前缀/通配符查询命中词太多时会报错。

### 10.3 常见 Query 类型

| Query | 说明 |
|-------|------|
| `TermQuery` | 单个精确词项查询，最基础 |
| `BooleanQuery` | 布尔组合（MUST/SHOULD/MUST_NOT/FILTER） |
| `PhraseQuery` | 短语查询，要求词按顺序相邻（可设 slop 容忍间隔） |
| `PrefixQuery` | 前缀查询 |
| `WildcardQuery` | 通配符 `*`（多字符）和 `?`（单字符） |
| `FuzzyQuery` | 模糊查询，基于编辑距离（Levenshtein） |
| `RegexpQuery` | 正则查询 |
| `PointRangeQuery` | BKD 树数值/地理范围查询 |
| `MatchAllDocsQuery` | 匹配全部文档 |
| `ConstantScoreQuery` | 包装另一个查询，得分恒定（当 filter 用，省打分开销） |

### 10.4 Weight 与 Scorer

**Weight** 是 Query 与某个 IndexSearcher 绑定后的"可复用编译产物"。它保存了与 Searcher 相关的统计信息（如 IDF 计算需要的全局 docFreq），使得同一个 Query 可以被多次执行。关键方法：`scorer(LeafReaderContext)` 为每个段创建 Scorer，`explain()` 解释打分细节。

**Scorer** 负责真正干活——遍历匹配的文档并打分。它继承自 `DocIdSetIterator`，提供 `docID()`、`nextDoc()`（顺序推进）、`advance(target)`（跳跃到目标，跳表加速）和 `score()`（算当前文档分数）。

### 10.5 BooleanQuery 的执行与优化

BooleanQuery 的四种子句：`MUST`（必须匹配，参与打分，取交集）、`SHOULD`（应该匹配，参与打分，取并集，可配 `minimumShouldMatch`）、`MUST_NOT`（必须不匹配，排除）、`FILTER`（必须匹配但**不参与打分**，可被缓存）。

执行时 Lucene 做了大量优化：用**跳表**让多个倒排链做交集时快速对齐 docID；对 MUST 子句，选最短的倒排链做"驱动"（leading iterator），其他链用 `advance` 去对齐；**WAND / Block-Max WAND** 算法可以在求 TopN 时提前跳过不可能进入结果集的文档（动态计算每个块的最大可能得分，低于当前第 N 名就整块跳过），大幅减少打分次数。

### 10.6 Filter 的 bitset 与缓存机制

⭐ 对于不需要打分的过滤条件（如 `term`、`range` 当 filter 用），Lucene/ES 有两层加速：

**bitset 机制**：在倒排索引里查出匹配的 docID，构建成一个 bitset（位图）。多个 filter 做交集时，优先从**最稀疏**（命中最少）的 bitset 开始，快速缩小候选集。

**caching 机制**：跟踪最近的查询，在最近 256 个查询中出现次数超过一定阈值的过滤条件，会把它的 bitset 缓存起来复用。但**小段不缓存**（文档数 < 1000 或占索引 < 3%），因为小段重算很快，缓存收益不值。这就是为什么 ES 里 filter 上下文比 query 上下文快、且重复 filter 越用越快。

---

## 11. 评分模型：TF-IDF 与 BM25

相关性打分是搜索引擎的灵魂——同样命中的文档，谁排前面？Lucene 用 `Similarity` 抽象打分算法，历史上默认是 TF-IDF，从 Lucene 6 / ES 5 起默认改为 **BM25**。

### 11.1 两个基础直觉

所有文本相关性打分都基于两个朴素直觉：

**TF（Term Frequency，词频）**：一个词在某文档里出现越多，这篇文档和这个词越相关。搜"咖啡"，正文出现 10 次"咖啡"的文档大概比只出现 1 次的更相关。

**IDF（Inverse Document Frequency，逆文档频率）**：一个词在越少的文档里出现，它越有区分度、越重要。"的""是"这种到处都有的词几乎没区分度（IDF 低），"量子纠缠"这种罕见词区分度极高（IDF 高）。

### 11.2 经典 TF-IDF（Lucene 工程化版本）

Lucene 的 TF-IDF 不是教科书上简单的 `TF×IDF`，而是工程化改良过的 **Practical Scoring Function**：

```
score(q,d) = coord(q,d) · queryNorm(q) · Σ_t [ tf(t,d) · idf(t)² · boost(t) · norm(t,d) ]
```

其中：`tf(t,d) = √freq`（用平方根，避免词频线性暴涨）；`idf(t) = 1 + log(numDocs / (docFreq+1))`；`norm(t,d)` 是字段长度归一化（短字段命中权重更高）；`coord(q,d)` 是协调因子（查询词命中越多越好）；`queryNorm(q)` 让不同查询的分数可比。

### 11.3 BM25：当前默认算法

BM25（Best Matching 25）是对 TF-IDF 的重要改进，解决了 TF-IDF 的两个问题。完整公式：

```
                         freq · (k1 + 1)
score(D,Q) = Σ IDF(qi) · ─────────────────────────────────────
                         freq + k1 · (1 - b + b · |D|/avgdl)
```

其中各量：

- `freq`：词 qi 在文档 D 中的出现次数。
- `|D|`：文档（字段）长度（token 数）。
- `avgdl`：该字段在所有文档上的平均长度。
- **`k1`（默认 1.2）**：词频饱和参数。控制 TF 的增长上限——TF 贡献最终趋近 `k1+1`，不会无限增长。
- **`b`（默认 0.75）**：长度归一化强度。`b=0` 完全忽略文档长度；`b=1` 完全按长度归一化。

BM25 的 IDF 公式也更平滑：

```
IDF(qi) = ln(1 + (N - n + 0.5) / (n + 0.5))
```

N 是总文档数，n 是包含 qi 的文档数。

### 11.4 BM25 凭什么比 TF-IDF 好

**第一，TF 饱和。** TF-IDF 里词频是线性甚至（开方后）持续增长的，一篇恶意堆砌 100 次关键词的文档会得到畸高的分。BM25 里 TF 的贡献有上限（渐近 `k1+1`），出现 5 次和出现 50 次的差距没那么夸张，更符合"出现几次就够了，再多边际收益递减"的真实语感。

**第二，长度归一化更可控。** BM25 用参数 `b` 灵活控制长文档的"惩罚"力度。长文档天然词频高，不归一化的话长文档总占便宜；BM25 用 `|D|/avgdl` 把文档长度和平均长度比较来矫正。

### 11.5 一个完整打分算例

搜索 "live"，假设：`freq=3`、`k1=1.2`、`b=0.75`、文档长度 `dl=14`、平均长度 `avgdl=16.81`、总文档 `N=26`、含该词文档 `n=3`、boost=2.2。

TF 部分：`3 / (3 + 1.2×(1 - 0.75 + 0.75×14/16.81)) = 3 / 4.05 ≈ 0.7408`
IDF 部分：`ln(1 + (26-3+0.5)/(3+0.5)) = ln(7.71) ≈ 2.043`
总分：`2.2 × 0.7408 × 2.043 ≈ 3.33`

你可以用 `IndexSearcher.explain(query, docId)` 打印出这套计算的每一步，调试相关性时极其有用。

### 11.6 其他打分手段

除了纯文本相关性，实际业务常需要融入业务因素：`ConstantScoreQuery`（恒定得分，当 filter）、`FunctionScoreQuery`（用字段值修改分数，如按销量/热度加权——`FieldValueFactor`，按距离衰减——衰减函数，随机打分——`RandomScore`，脚本完全自定义——`ScriptScore`）、`DisMaxQuery`（多字段取最高分，`tie_breaker` 让其他字段也有一点影响）。

---

## 12. DocValues：列式存储与正排

### 12.1 为什么需要 DocValues

倒排索引解决的是"词 → 文档"，但很多操作需要的是反向的"文档 → 字段值"：**排序**（按价格排序，需要每个文档的价格）、**聚合**（统计各品类销量，需要每个文档的品类和销量）、**分面（faceting）**、**脚本打分**（用字段值算分）。

如果用倒排索引来做这些，效率极低。Lucene 4.0 之前用 **FieldCache**——查询时把字段值全部加载到 JVM 堆内存里。问题很大：第一次加载（预热）很慢，且海量字段会 **OOM**。

**DocValues** 是 Lucene 4.0 引入的解决方案：在**索引时**就把"文档→值"的映射以**列式（按字段）**结构写到磁盘上，查询时通过内存映射（mmap）按需读取，由操作系统页缓存管理，既快又不撑爆堆内存。

### 12.2 五种 DocValues 类型

| 类型 | 存什么 | 适用 |
|------|--------|------|
| `NumericDocValues` | 单值数值 | 数值排序/聚合 |
| `SortedDocValues` | 单值字符串（带排序序号 ord） | 字符串排序/聚合 |
| `SortedSetDocValues` | 多值字符串 | 多值字段聚合（如多标签） |
| `SortedNumericDocValues` | 多值数值 | 多值数值排序/聚合 |
| `BinaryDocValues` | 任意二进制 | 自定义二进制数据 |

### 12.3 列式存储的压缩技巧

DocValues 列存能用上多种针对性压缩，因为同一列的值类型一致、分布规律：

- **GCD 压缩**：如果一列数值都能被某个最大公约数整除（比如都是 1000 的倍数），先全部除以 GCD 再存，数值变小更省位。
- **DirectWriter（位压缩）**：用恰好够用的位数存值。比如某列值域只有 0~3，每个值只需 2 bit，而不是 64 bit 的 long。
- **DirectMonotonicWriter（单调编码）**：对单调递增的序列（如偏移量数组），存差值或斜率，极致压缩。
- **Sorted 类型用 ord 间接引用**：把所有不同的字符串值排序后只存一份（term dictionary），每个文档只存一个序号 `ord` 指向它。聚合/排序时直接比 ord（整数比较）即可，无需比字符串。

文件层面，`.dvd` 存实际列数据，`.dvm` 存元数据（每列的偏移、统计、用了哪种编码）。

### 12.4 DocValues vs Stored Fields

两者都是"正排"，但用途和组织完全不同：

| 维度 | Stored Fields（.fdt） | DocValues（.dvd） |
|------|----------------------|-------------------|
| 组织 | 行式（按文档聚集） | 列式（按字段聚集） |
| 访问模式 | 按 docID 随机取一整篇文档的字段 | 按字段扫描很多文档的同一列 |
| 用途 | 返回搜索结果原文（_source） | 排序、聚合、分面、脚本 |
| 压缩 | 块压缩（LZ4/DEFLATE） | 列式编码（GCD/位压缩/单调） |
| 内存 | 不常驻，读时解压 | mmap，OS 页缓存 |

简单记：**要展示给用户看的原文用 Stored Fields；要拿来排序/聚合/算分的用 DocValues。** ES 的 `keyword` 字段默认两者都开（`store` 看需要，`doc_values` 默认开）。

---

## 13. 近实时搜索（NRT）：Refresh、Commit、Translog

### 13.1 NRT 要解决的矛盾

搜索引擎面临一个根本矛盾：**写入要立刻可搜（实时性）** vs **写入要立刻落盘（持久性）**。两者都做到极致就太慢了。Lucene/ES 的方案是**近实时（Near Real-Time, NRT）**——数据写入后约 1 秒内可搜，但持久化（fsync）可以延后，用 WAL 日志（translog）兜底防丢。

### 13.2 三个关键动作

| 动作 | 谁负责 | 做什么 | 默认频率 |
|------|--------|--------|----------|
| **Refresh** | ES（基于 Lucene flush） | 把内存 buffer 变成新 Segment（进 OS Cache），使数据**可被搜索** | 1 秒 |
| **Commit / Flush** | Lucene commit | 把所有 Segment **fsync 落盘** + 写 segments_N 提交点 + 清空 translog | 30 分钟或 translog 满 |
| **Translog** | ES（Lucene 没有） | 每次写操作记 WAL，**保证未 commit 的数据不丢** | 每次写（默认 5 秒 fsync 一次） |

> 注意术语错位：**Lucene 的 flush ≈ ES 的 refresh**（生成段、可搜、未必落盘）；**Lucene 的 commit ≈ ES 的 flush**（fsync 落盘 + 提交点）。这是初学者最容易混的地方。

### 13.3 完整写入与可见性链路

```
写入文档
   │
   ├──► Index Buffer（内存）
   └──► Translog（WAL，默认每 5 秒 fsync）
   │
   ▼ Refresh（默认每 1 秒）
内存 buffer → 新 Segment（写入 OS Cache，注意还没 fsync 到磁盘！）
清空 buffer，新段可被搜索  ← 这就是"近实时"
   │
   ▼ Flush（默认 30 分钟 / translog 过大）
对所有 Segment 执行 fsync 落盘
写 commit point（segments_N）
清空 translog，启用新 translog
```

### 13.4 为什么是"近"实时

数据从 Index Buffer 到"可搜"要等一次 refresh，默认间隔 1 秒，所以写入后最长约 1 秒才能搜到——这就是"近"实时而非"完全"实时的由来。如果业务要更实时，可以缩短 `refresh_interval`，但太频繁会产生大量小段、增加 merge 压力，得不偿失；反之，批量导入时把 `refresh_interval` 调大（甚至设 -1 关闭）能大幅提升写入吞吐。

### 13.5 Translog 的持久化策略

translog 决定了"断电最多丢多少数据"：

- `async`（默认）：每 5 秒 fsync 一次 translog，崩溃最多丢 5 秒数据，性能好。
- `request`：每次写请求都 fsync translog 后才返回成功，几乎不丢数据，但每次写都有一次磁盘 IO，吞吐低。

用 `index.translog.durability` 控制，根据"能容忍丢多少数据"来权衡。

### 13.6 Lucene 层的 NRT 实现

在纯 Lucene 里，NRT 靠 `DirectoryReader.openIfChanged(reader, writer)` 或 `writer.getReader()` 实现：它触发一次 flush 生成新段，但**不 commit**（不 fsync），开销远小于 commit，从而让新写的文档快速可见。配套的 `SearcherManager` 负责安全地刷新和切换 IndexSearcher（`maybeRefresh()` 后台刷新，`acquire()/release()` 借还 Searcher），保证查询线程总能拿到一致的快照。

---

## 14. 压缩编码：Delta、FOR、PForDelta、Roaring

Lucene 能在巨大数据量下保持小体积、快查询，很大程度归功于一套精巧的整数压缩编码。倒排链里的 DocID 是有序整数序列，特别适合压缩。

### 14.1 Delta（差分）编码

倒排链里 DocID 是递增的，直接存大数浪费空间。Delta 编码只存**相邻差值**：

```
原始 DocID: [100, 102, 105, 108, 120]
Delta 后:   [100,   2,   3,   3,  12]
```

差值通常远小于原值，配合可变长编码（VInt）或位压缩，能省大量空间。这是所有后续压缩的基础。

### 14.2 FOR（Frame of Reference）

FOR 在 Delta 基础上**按块统一位宽**压缩。把差值序列每 128 / 256 个分成一个 Block，计算 Block 内**最大差值需要多少 bit**，然后块内所有值都用这个位宽存储：

```
DocID: [73, 300, 302, 332, 343, 372]
Delta: [73, 227,   2,  30,  11,  29]
最大差值 227 需要 8 bit → 块内每个值都用 8 bit 存
```

这样解码时知道位宽就能批量、对齐地快速解码（甚至 SIMD），比逐个变长解码快得多。Lucene 从 4.1 起在 PostingsFormat 里用 FOR/PForDelta。

### 14.3 PForDelta（Patched FOR）

FOR 有个弱点：如果块里大部分差值很小，但偶尔有一个超大"异常值"，那整块都得用大位宽，浪费严重。PForDelta（Patched FOR）解决这个问题：

选一个位宽，使**绝大多数（如 90% 以上）**的值能正常编码；少数放不下的"异常值（exception）"单独存到一个异常区域，并用占位标记。解码时先用小位宽快速解出主体，再回填异常值。这样既保持了块解码的高速，又不被个别大值拖累。

### 14.4 Roaring Bitmaps

Roaring Bitmap 用于**内存中的 Filter 缓存**（Lucene 5.0 起替代传统 Bitmap）。它的思路是把 32 位整数空间按**高 16 位分桶**（共 65536 个桶），每个桶内根据稀疏程度自适应选择容器：

- 桶内元素 **< 4096**：用**有序数组**存（稀疏时更省）。
- 桶内元素 **≥ 4096**：用**位图**存（稠密时更省）。

查找一个值：高 16 位二分定位桶，低 16 位在桶内查。这种自适应使 Roaring 在各种数据分布下都很省内存，且交集/并集运算很快——非常适合"查询条件 → 命中 docID 集合"的缓存。

---

## 15. 跳表（Skip List）加速倒排链查找

### 15.1 为什么倒排链需要跳表

BooleanQuery 的 MUST（交集）查询需要在多个倒排链上做"对齐"——比如链 A 当前在 docID=1000，要在链 B 里找 ≥1000 的第一个文档。如果链 B 很长，从头线性扫到 1000 太慢，而且还要逐块解压缩。**跳表（Skip List）**就是为这种 `advance(target)` 操作加速的。

### 15.2 Lucene 的多级跳表

Lucene 在 `.doc` 文件里为每个（足够长的）倒排链内嵌**多级跳表**：

- **Skip Interval**：与压缩块大小一致，每个 skip entry 指向一个 block 的起点。
- **多级结构**：Level 0 每 `skipInterval` 个文档一个指针；Level 1 每 `skipInterval × skipMultiplier` 个一个指针；以此类推，层级越高越稀疏，跨度越大。
- `advance(target)` 时，从**最高层**开始往前跳，跳过头了就下降一层精调，逐层逼近目标——这正是经典跳表的 O(log n) 查找。

### 15.3 跳表的双重收益

跳表带来两个层面的加速：**跳过遍历**（不用逐个文档走过去）和**跳过解压**（不用解压那些被跳过的压缩块——FOR 解码本身有成本）。代价是额外存一点跳表指针，典型的空间换时间。这也是为什么 Lucene 的交集查询、`advance` 密集的短语查询能这么快。

---

## 16. 并发模型：读写分离与可见性

### 16.1 一写多读

Lucene 的并发模型很清晰：**一个索引同一时刻只能有一个 IndexWriter（独占写），但可以有多个 IndexReader（并发读）**。

写独占通过文件锁 `write.lock` 实现（可通过 `Directory.setLockFactory()` 自定义锁实现）。如果第二个 IndexWriter 尝试打开同一索引，会抛 `LockObtainFailedException`。

读并发非常友好：因为段不可变，多个 IndexReader、多个查询线程可以无锁并发访问，同步代码极少。推荐做法是一个进程里**单例共享 IndexReader/IndexSearcher，多线程使用**。

### 16.2 读写分离与快照语义

IndexReader 可以在 IndexWriter 正在改索引时打开。关键语义：**每个 IndexReader 看到的是它被打开那一刻的索引快照**。Writer 后续的修改，Reader 看不到，直到 Reader 被重新打开（且 Writer 已让新段可见）。

### 16.3 Reader 的层次结构

```
IndexReader（抽象基类）
├── CompositeReader
│    └── DirectoryReader        组合多个段
│         └── StandardDirectoryReader
└── LeafReader                   读单个段（叶子）
     ├── SegmentReader           核心实现（一个段一个）
     ├── FilterLeafReader
     └── ...
```

`DirectoryReader` 是多个 `SegmentReader` 的组合。查询时 IndexSearcher 会遍历每个 `LeafReaderContext`（每个段），分别执行再汇总。IndexWriter 内部还维护一个 `ReaderPool`，用于段合并、应用删除、NRT 等场景下复用 Reader。

### 16.4 写入侧的并发控制

写入虽然是"单 Writer"，但 Writer 内部高度并发：每个写线程独占一个 DWPT 并行建索引（见第 8 章）；`DocumentsWriterDeleteQueue` 用 CAS 无锁队列处理删除；`DocumentsWriterFlushControl` 作为全局调度器决定何时、哪些 DWPT 去 flush，既防内存爆掉又防线程饿死。所以 IndexWriter 的公开接口都是线程安全的，你可以多线程同时 `addDocument`。

### 16.5 段内并发查询（Lucene 10.0）

历史上 Lucene 的查询并行度是"段级"的——不同段可以并行查，但单个段只能单线程查。当索引被 forceMerge 成一个超大段时，就失去了并行能力。Lucene 10.0 引入**段内并发查询（intra-segment search concurrency）**，允许把单个大段的 docID 空间切片，多线程并行处理，解决了大段的查询并行度瓶颈。

---

## 17. Lucene 与 Elasticsearch 的关系

把前面的知识串起来，就能彻底看懂 ES 存储层和 Lucene 的对应关系。

### 17.1 概念映射

| Elasticsearch | Lucene | 说明 |
|---------------|--------|------|
| Index（索引） | 多个 Lucene 索引（分布在各分片） | ES 索引是逻辑概念 |
| **Shard（分片）** | **一个 Lucene 索引** | 这是最核心的对应！每个主分片/副本分片就是一个完整的 Lucene 索引 |
| Segment | Segment | 完全一致 |
| Document | Document | ES 在上面加了 _id、_source、mapping |
| refresh | IndexWriter flush（getReader） | 生成段、可搜、未 fsync |
| flush | IndexWriter commit | fsync 落盘 + 提交点 |
| merge | merge | 完全一致 |
| translog | （Lucene 无） | ES 自己加的 WAL |

### 17.2 ES 在 Lucene 之上加了什么

ES 的核心价值，是在 Lucene 这个"单机检索内核"之上补齐了生产所需的一切：**分布式**（分片、副本、集群协调、故障转移）、**RESTful API 与 JSON**（不用写 Java）、**Mapping**（schema 管理）、**translog**（写入持久化保障）、**聚合框架**（基于 DocValues 的强大分析能力）、**生态**（Kibana、Beats、Logstash）。

一句话：**ES = 分布式协调 + REST 接口 + N 个 Lucene 索引**。你在 ES 里遇到的段太多查询慢、删除不释放磁盘、refresh 影响实时性、forceMerge 谨慎使用等问题，根因全在 Lucene 层，理解了本文就能从根上理解它们。

---

## 18. 工业级实践：外卖平台 RLE 倒排索引优化

⭐ 这一节是美团内部的真实搜索优化案例，是网上找不到的工业级深度实践。

### 18.1 背景与痛点

外卖平台搜索的倒排链查询遵循标准三步：用 FST 定位 term 在 `.tim` 中的位置 → 读取该 term 的倒排链元数据（`DocStartFP` 等）→ 根据 `.doc` 文件读取倒排链。在超大规模、超高 QPS 下，倒排链的读取与求交成为性能瓶颈，TP99 偏高。

### 18.2 词典结构选型对比

团队对比了三种"term → 倒排链指针"的查找结构：

| 结构 | 查询性能 | 内存占用 | 特点 |
|------|---------|---------|------|
| **FST** | 需遍历状态机 | 极小（前后缀共享） | Lucene 默认，省内存但查询非最快 |
| **HashMap** | O(1) | 大 | 查询最快但吃内存 |
| **LongObjectHashMap** | O(1)，优化版 | 较大 | 针对 long key 优化的哈希表 |

在内存允许、追求极致查询延迟的热点场景，用哈希结构替代 FST 可以换取查询速度。

### 18.3 RLE 编码倒排链

核心优化是用 **RLE（Run-Length Encoding，游程编码）**压缩倒排链。外卖场景下，很多倒排链的 docID 是**连续成段**的（比如同一个商家的大量商品 docID 相邻）。RLE 把连续区间压成"起点 + 长度"：

```
原始 docID: [1, 2, 3, 4, 5, 10, 11, 12]
RLE 编码:   [1-5, 10-12]   （即 (起点1,长度5), (起点10,长度3)）
```

对连续度高的链，RLE 既大幅省空间，又能让"判断某 docID 是否在链中""求交集"变成区间运算，极快。

### 18.4 SparseRoaringDocIdSet

进一步，团队实现了 **SparseRoaringDocIdSet**——结合 Roaring Bitmap 和 RLE 的稀疏文档集：对**稀疏**区域用 RLE 编码，对**稠密**区域用 Bitmap，自适应选择最优表示，兼顾空间与求交速度。

### 18.5 效果

这套优化（哈希词典 + RLE Container + SparseRoaringDocIdSet）带来了显著收益：**倒排相关环节 TP99 降低约 96%，搜索全链路性能提升约 84%**。这印证了一个道理：Lucene 的默认实现是通用最优，但在特定数据分布（如高连续度 docID）下，针对性的数据结构改造能带来数量级的提升。

---

## 19. 实战代码：从建索引到搜索

下面用一段完整的 Java 代码把前面的概念串起来（基于 Lucene 9.x）。

### 19.1 创建索引

```java
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;

import java.nio.file.Paths;

public class IndexDemo {
    public static void main(String[] args) throws Exception {
        // 1. 打开索引目录（FSDirectory 会自动选最优实现，通常是 MMapDirectory）
        Directory dir = FSDirectory.open(Paths.get("/tmp/lucene_index"));

        // 2. 配置 IndexWriter
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE); // 每次重建
        config.setRAMBufferSizeMB(64);                          // 调大写缓冲提升吞吐

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            // 3. 构造并写入文档
            addDoc(writer, "1", "Lucene 入门", "Lucene 是一个高性能的全文检索库");
            addDoc(writer, "2", "Elasticsearch 实战", "Elasticsearch 基于 Lucene 构建");
            addDoc(writer, "3", "搜索引擎原理", "倒排索引是搜索引擎的核心数据结构");

            // 4. commit：fsync 落盘 + 写提交点（不 commit 数据可能丢）
            writer.commit();
        }
    }

    static void addDoc(IndexWriter w, String id, String title, String content)
            throws Exception {
        Document doc = new Document();
        // StringField：不分词，精确匹配（适合 ID）
        doc.add(new StringField("id", id, Field.Store.YES));
        // TextField：分词，可全文检索（适合标题、正文）
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new TextField("content", content, Field.Store.YES));
        // SortedDocValuesField：用于排序/聚合（列存，不是给搜索用的）
        doc.add(new SortedDocValuesField("title_sort",
                new org.apache.lucene.util.BytesRef(title)));
        w.addDocument(doc);
    }
}
```

### 19.2 搜索索引

```java
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;

import java.nio.file.Paths;

public class SearchDemo {
    public static void main(String[] args) throws Exception {
        Directory dir = FSDirectory.open(Paths.get("/tmp/lucene_index"));

        // 1. 打开 Reader（一个时间点的索引快照），构造 Searcher
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // searcher.setSimilarity(new BM25Similarity()); // 默认就是 BM25

            // 2. 用 QueryParser 把查询字符串解析成 Query（要用同一个 Analyzer）
            QueryParser parser = new QueryParser("content", new StandardAnalyzer());
            Query query = parser.parse("倒排索引");

            // 3. 执行搜索，取 Top 10
            TopDocs topDocs = searcher.search(query, 10);
            System.out.println("命中数: " + topDocs.totalHits);

            // 4. 遍历结果，取回存储的字段
            StoredFields storedFields = searcher.storedFields();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                System.out.printf("score=%.4f  title=%s%n",
                        sd.score, doc.get("title"));
                // explain 打印打分细节（调试相关性神器）
                // System.out.println(searcher.explain(query, sd.doc));
            }
        }
    }
}
```

### 19.3 各类 Query 用法速览

```java
// 精确词项
Query q1 = new TermQuery(new Term("title", "lucene"));

// 布尔组合：title 必须含 lucene，content 应含 索引，排除 id=3
Query q2 = new BooleanQuery.Builder()
        .add(new TermQuery(new Term("title", "lucene")), BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term("content", "索引")), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term("id", "3")), BooleanClause.Occur.MUST_NOT)
        .build();

// 短语查询：要求 "全文" "检索" 相邻（slop=0），slop 越大越宽松
PhraseQuery q3 = new PhraseQuery.Builder()
        .add(new Term("content", "全文"))
        .add(new Term("content", "检索"))
        .setSlop(1)
        .build();

// 前缀查询（注意：命中词过多会被重写成超大 BooleanQuery）
Query q4 = new PrefixQuery(new Term("title", "luc"));

// 模糊查询（编辑距离，容忍拼写错误）
Query q5 = new FuzzyQuery(new Term("title", "lucane"), 2); // 最大编辑距离 2

// 数值范围查询（需要用 IntPoint 等建索引）
Query q6 = IntPoint.newRangeQuery("price", 10, 100);

// 当 filter 用、不打分（更快、可缓存）
Query q7 = new ConstantScoreQuery(new TermQuery(new Term("id", "1")));
```

### 19.4 排序与分页

```java
// 按 DocValues 字段排序（必须建了对应的 DocValues）
Sort sort = new Sort(new SortField("title_sort", SortField.Type.STRING));
TopDocs sorted = searcher.search(query, 10, sort);

// 深分页用 searchAfter（避免 from+size 的深分页性能陷阱）
ScoreDoc lastDocOfPrevPage = /* 上一页最后一个 */ null;
TopDocs nextPage = searcher.searchAfter(lastDocOfPrevPage, query, 10);
```

### 19.5 更新与删除

```java
try (IndexWriter writer = new IndexWriter(dir, config)) {
    // 删除：按 Term 删（本质是在 .liv 打标记，合并时才物理删）
    writer.deleteDocuments(new Term("id", "2"));

    // 更新 = 删除旧 + 写入新（id 相同的旧文档被标记删除）
    Document newDoc = new Document();
    newDoc.add(new StringField("id", "1", Field.Store.YES));
    newDoc.add(new TextField("title", "Lucene 进阶", Field.Store.YES));
    writer.updateDocument(new Term("id", "1"), newDoc);

    writer.commit();

    // 谨慎使用：强制合并成 1 个段（仅对只读索引）
    // writer.forceMerge(1);
}
```

---

## 20. 性能调优要点总结

把全文的调优经验汇总成一份可操作的清单。

### 20.1 写入调优

适当**调大 `setRAMBufferSizeMB`**（如 64~256MB），减少 flush 次数、减少小段、减少写入阻塞。批量导入时**关闭或调大 refresh 间隔**（ES 里设 `refresh_interval=-1` 或 30s），导入完再恢复。**用多线程并发 addDocument**，充分利用每线程独立 DWPT 的并行能力。不需要的字段别开 `stored`、别建倒排，按需选 `indexOptions`（只需判断有无的字段用 `DOCS`，省 .pos/.pay）。

### 20.2 查询调优

**能用 filter 就别用 query**——filter 不打分、可缓存、可用 bitset 加速（如范围、精确匹配类条件放 filter 上下文）。**避免命中词过多的前缀/通配符/正则查询**，否则重写出的 BooleanQuery 巨大甚至报 `TooManyClauses`；通配符尽量别以 `*` 开头（前导通配符要扫全词典）。**排序/聚合字段一定建 DocValues**，别靠 stored fields 现解析。深分页用 `searchAfter` 而非大 `from`。

### 20.3 段与合并调优

**只对停止写入的只读索引做 forceMerge**，在线高峰期、磁盘紧张时绝不 forceMerge。监控段数量和删除文档比例，删除/更新频繁的索引注意 `deletePctAllowed`，必要时让自动合并清理。理解"删数据磁盘不立刻降"是正常现象，物理清理依赖合并。

### 20.4 内存与可见性

充分利用 **OS 页缓存**——Lucene 的 `.tim/.tip/.doc/.dvd` 等大量依赖 mmap 和页缓存，给操作系统留足内存（ES 经典建议：堆内存不超过物理内存一半、且不超过 ~31GB，把另一半留给页缓存）。`title`/排序字段等用 DocValues 走 mmap，避免 FieldCache 式的堆内存膨胀。

### 20.5 调试相关性

用 `IndexSearcher.explain(query, docId)` 拆解 BM25 每一项贡献，定位"为什么这篇排前面/排后面"。理解 `k1`（词频饱和）、`b`（长度归一化）两个参数的含义，必要时按业务调整 Similarity。

---

## 结语

Lucene 的精妙之处，在于它围绕**"段不可变"**这一个核心约束，演化出了一整套自洽而高效的机制：用 FST 把词典索引压进内存，用三层倒排结构兼顾速度与空间，用两阶段写入和 DWPT 实现高并发，用 Delta/FOR/PForDelta/Roaring 把整数压到极致，用跳表加速倒排链求交，用 BM25 衡量相关性，用 DocValues 支撑排序聚合，用 refresh/commit/merge 在实时性与持久性之间优雅平衡。

理解了 Lucene，你不仅理解了一个库，更理解了现代搜索引擎（Elasticsearch、Solr）的存储与检索内核——因为它们的心脏，正是这台叫 Lucene 的精密引擎。

---

> 本文资料来源：Apache Lucene 官方文档、Elastic 官方博客（BM25、Codec、NRT）、Lucene 源码分析（DeepWiki、各技术博客）、以及美团内部学城的 Lucene 底层原理系列与外卖搜索 RLE 实践文档。