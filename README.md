# DistributedPaperLearnNotes

> 一个从分布式系统经典论文出发，延伸至后端架构设计、AI Agent 工程实践、中间件源码剖析的综合性技术知识库。

本仓库是作者在系统学习分布式系统过程中逐步积累的笔记、代码和实践记录，涵盖从理论基础（MIT 6.824 课程、经典论文精读）到工程落地（Raft 算法 Java 实现、SOFA-JRaft 源码分析、Netty/Dubbo/Sentinel 源码解析），再到前沿方向（AI Agent 架构、RAG、LangChain/LangGraph）的全链路知识体系。

---

## 目录

- [知识库总览](#知识库总览)
- [核心模块导航](#核心模块导航)
  - [一、分布式系统理论与课程笔记](#一分布式系统理论与课程笔记)
  - [二、经典论文精读与解析](#二经典论文精读与解析)
  - [三、Raft 算法深度研究](#三raft-算法深度研究)
  - [四、后端架构设计文档](#四后端架构设计文档)
  - [五、业务系统架构设计](#五业务系统架构设计)
  - [六、AI Agent 工程实践](#六ai-agent-工程实践)
  - [七、Java 生态源码深度解析](#七java-生态源码深度解析)
  - [八、中间件与基础设施笔记](#八中间件与基础设施笔记)
  - [九、计算机基础课程笔记](#九计算机基础课程笔记)
  - [十、测试工程与质量保障](#十测试工程与质量保障)
  - [十一、杂项笔记与技术八股](#十一杂项笔记与技术八股)
- [学习路线建议](#学习路线建议)
- [文件格式说明](#文件格式说明)

---

## 知识库总览

| 模块 | 核心内容 | 文件量级 |
|------|---------|---------|
| 分布式系统理论 | MIT 6.824 课程、一致性模型、共识算法、CAP/BASE、分布式时钟 | 60+ 篇 |
| 经典论文精读 | CAP、Paxos、Raft、Spanner、Chubby、ZooKeeper、LSM-Tree 等 | 15+ 篇论文 + 讲解 |
| Raft 算法研究 | Java 实现、SOFA-JRaft 源码、Vertical Paxos、Multi-Raft | 30+ 篇 + 完整代码 |
| 后端架构设计 | RPC、消息队列、缓存、分布式锁、限流熔断、ServiceMesh 等 | 50 篇 |
| 业务系统架构 | 秒杀、购物车、优惠券、库存、订单、支付等 30 个系统设计 | 30 篇 |
| AI Agent 工程 | 上下文管理、记忆系统、工具权限、多 Agent 架构、RAG、Skill 设计 | 15+ 篇 |
| Java 源码解析 | Netty 17 篇、Dubbo 8 篇、Sentinel 6 篇、MyBatis、Spring 等 | 35+ 篇 |
| 中间件笔记 | Redis、RabbitMQ、MySQL、Elasticsearch、MongoDB、K8s 等 | 40+ 篇 |
| 计算机基础 | 计算机网络、计算机组成原理、CSAPP Labs、操作系统 | 30+ 篇 |

---

## 核心模块导航

### 一、分布式系统理论与课程笔记

**路径：`DistributedSystem/`**

这是整个知识库的理论基石，包含两大子模块：

**1. DistributedSystem-Notes/** — 结构化的分布式系统知识体系，按照主题组织：

- `01~分布式基础/` — 不可靠的分布式系统（网络、时钟、进程）、节点与集群、CAP 定理、日志模型（WAL、分割日志）。附有 *Distributed Systems for Fun and Profit* 中文翻译和 *Patterns of Distributed Systems* 模式集（24 个分布式设计模式详解）。
- `02~一致性与共识/` — 一致性模型全谱（线性一致性、顺序一致性、因果一致性、最终一致性）、共识算法（Paxos、Raft、ZAB）、分布式时钟与序列号、拜占庭问题。
- `10~分布式存储/` 和 `20~分布式计算/` — 存储与计算专题的延伸阅读链接。
- `88~其他分布式概念/` — 分布式 ID、分布式事务、分布式锁、分布式数据库、分布式操作系统的概念索引。
- `GFS/` — Google File System 深度解析，包括并发读写冲突分析、HDFS 源码读写流程、脑裂与主节点宕机恢复方案。
- `RPCAndThreads/` — MIT 6.824 课程第一讲：RPC 与线程模型。
- `课程前提/` — Go 语言学习笔记（MIT 6.824 实验语言）。

**2. MIT6.824_DistributedSystem/** — MIT 6.824 分布式系统课程的完整实验代码与文档：

- `src/mapreduce/` — Lab 1: MapReduce 完整实现（含测试通过截图）。
- `src/raft/` — Lab 2: Raft 共识算法 Go 实现（含 Part 2A/2B/2C 实验报告与调试日志）。
- `lab/lab3_KVServer/` — Lab 3: 基于 Raft 的分布式 KV 服务器架构文档。

### 二、经典论文精读与解析

**路径：`papers/`**

收录了分布式系统领域的经典论文 PDF 及其配套讲解文档。`index.json` 记录了 40+ 篇论文的元数据（标题、DOI、下载状态），`index.csv` 提供表格化索引。

已收录的论文 PDF 包括：

- **CAP 理论** — Brewer 猜想与 CAP 定理原始论文、CAP Twelve Years Later
- **共识算法** — Paxos Made Simple、Vertical Paxos、TiDB 基于 Raft 的高性能写数据库
- **分布式存储** — LSM-Tree、RocksDB、Facebook Haystack 照片存储、Google Spanner
- **分布式协调** — Google Chubby 锁服务、ZooKeeper
- **分布式时钟** — Virtual Time and Global States of Distributed Systems
- **机器学习系统** — Scaling Distributed Machine Learning with the Parameter Server
- **其他** — 共识算法中的分区同步现象、两阶段提交简要说明、一个可扩展的互联网应用点对点查询服务

配套的论文讲解文档分布在仓库根目录和 `RaftByJava_副本/` 中，采用"teacher-style"讲解风格，将论文核心思想拆解为易懂的中文解读：

- `lsmtree-raft-teacher-style讲解.md` — LSM-Tree 论文精讲
- `RocksDB-raft-teacher-style讲解.md` — RocksDB 论文精讲
- `TiDB-基于raft的高性能写数据库-raft-teacher-style讲解.md` — TiDB 论文精讲
- `vertical-paxos-raft-teacher-style讲解.md` — Vertical Paxos 论文精讲
- `分布式时钟领域大作-raft-teacher-style讲解.md` — 分布式时钟论文精讲

### 三、Raft 算法深度研究

**路径：`RaftByJava_副本/`**

这是整个知识库中最具深度的实践模块，从论文理解到工程落地形成完整闭环：

**原创 Raft Java 实现** (`src/main/java/com/raft/`)：完整的 Raft 共识算法 Java 实现，包含核心模块（选举管理、日志管理、复制管理、快照管理、持久化、状态机）、RPC 通信层（基于 Netty 的客户端/服务端、消息编解码）、以及完整的消息类型定义（RequestVote、AppendEntries、InstallSnapshot 等）。

**深度研究文档**：

- `我眼里的Raft.md` — 从第一性原理理解 Raft 算法设计
- `我眼里的Multi-Raft.md` — Multi-Raft 架构分析（Region 分裂与迁移）
- `工程落地的Raft源码讲解-结合我眼里的Raft.md` — 结合自研代码的工程落地讲解
- `Raft工程落地思维.md` — 从理论到工程的思维转换
- `Raft算法-写代码前研究提纲.md` — 实现前的系统性研究框架
- `Raft节点启动流程详解.md` — 节点启动全流程剖析
- `分布式集群算法选型思维.md` — Raft vs Paxos vs ZAB 选型决策
- `Redis与Raft的取舍.md` — Redis Cluster 与 Raft 的权衡
- `Vertical Paxos与Raft的关系.md` + `Vertical Paxos论文逐节精讲.md` — Vertical Paxos 论文逐节解读及其与 Raft 的关系
- `Spec-Coding完整流程指南.md` — 规范化编码实践

**SOFA-JRaft 源码分析** (`sofa-jraft/`)：蚂蚁金服开源的生产级 Raft 实现完整源码，包含核心模块（NodeImpl、Replicator、BallotBox、FSMCaller）、存储层（LogManager、SnapshotStorage）、RPC 层、RheaKV 分布式 KV 引擎，以及扩展模块（gRPC RPC 实现、BDB 日志存储）。

**Netty 源码全流程解析** (`output/`)：17 篇系列文章，从 NioEventLoop 线程模型到 ByteBuf 内存管理，从 ChannelPipeline 责任链到零拷贝实现，覆盖 Netty 全部核心机制。

**Dubbo 源码全流程解析** (`docs-advanced-config/`)：8 篇 Dubbo 源码深度解析，加上 6 篇 Sentinel 流控框架源码分析。

### 四、后端架构设计文档

**路径：`后端架构设计文档/`**

50 篇系统性后端架构设计文档，每篇聚焦一个后端核心组件或架构主题，涵盖从设计原理到工程实践的完整链路：

**分布式基础设施**（01-12）：RPC 框架与服务治理、消息队列、缓存体系、分布式锁、分布式 ID 生成、配置中心、限流降级熔断、分布式事务、分布式任务调度、数据库与分库分表、数据同步与 CDC、文件存储与对象存储。

**网络与流量**（13-16）：搜索引擎、API 网关、七层 HTTP 网关、流量路由与灰度发布。

**可观测性与稳定性**（17-22）：分布式链路追踪、可观测性体系、日志系统、容量规划与全链路压测、故障演练与混沌工程、故障分析。

**云原生与基础设施**（23-26）：容器集群平台、镜像服务、Serverless、ServiceMesh。

**编程与工程实践**（27-32）：并发编程、JVM 与 GC 优化、网络编程、Java 编码规范、微服务架构与 DDD、API 设计与网关安全。

**业务系统架构**（33-42）：认证授权体系、风控系统、支付与交易系统、订单系统、推荐系统、推送系统、LBS 与地理位置服务、IM 即时通讯系统、实时计算与流处理、数据仓库与 OLAP。

**研发效能与组织**（43-50）：CICD 与发布、代码质量与 CodeReview、AB 测试与实验平台、工作流引擎与规则引擎、静态网站托管、SET 化治理、大模型助力研发提效、研发组织转型。

### 五、业务系统架构设计

**路径：`业务系统架构设计文档/`**

30 个常见互联网业务系统的架构设计方案，每个文档包含系统概述、核心架构设计、关键技术创新点、数据模型、接口设计和难点分析：

电商核心（秒杀、购物车、优惠券、红包、库存、商品中心、会员、积分、营销促销、售后退款、物流、评论），内容与社交（Feed 流、短链接、排行榜、点赞、弹幕、内容审核、直播、短视频、社交图谱），平台基础设施（消息通知中心、多租户 SaaS、权限系统、工单系统、审批流系统、计费系统、账务系统、在线文档、监控系统、登录认证、KMS 密钥管理）。

### 六、AI Agent 工程实践

**路径：`harnessEngineer/` + `agent八股文详解/` + `pythonai框架的使用和细节/`**

从 Agent 底层机制到工程实现的完整知识体系：

**Agent 工程机制** (`harnessEngineer/`)：

- 上下文管理机制 + 实战实现
- 记忆功能机制 + 实战实现（含 Python 代码 `agent_context_demo.py`）
- 工具和权限机制 + 实战实现
- 紧急停止机制
- 多 Agent 架构 + 实战实现
- RAG 检索增强生成深度解析 + 补充材料
- Agent 评测体系深度解析
- Skill 设计 D8 原则详解

**AI 面试题体系** (`agent八股文详解/大模型面试题/`)：Agent 面试题（上/下）、RAG 面试题（上/下）、LLM 工具调用面试题、大模型工程面试题（上/下）。

**图解系列** (`agent八股文详解/`)：图解 Agent 全集、ClaudeCode 实战技巧与源码解析。

**Python AI 框架** (`pythonai框架的使用和细节/`)：LangChain 框架详解、LangGraph 框架详解。

### 七、Java 生态源码深度解析

**路径：`RaftByJava_副本/output/` + `RaftByJava_副本/docs-advanced-config/` + 根目录散篇**

**Netty 源码系列**（17 篇）：从 NioEventLoop 线程模型到 Channel 体系与生命周期，从 ChannelPipeline 责任链到 ByteBuf 内存管理，从 Bootstrap 启动流程到编解码器框架，从写缓冲区与 Flush 机制到零拷贝与 FileRegion，从内存泄漏检测到 TCP 连接管理，从 HTTP 协议支持到 epoll/kqueue 原生传输，最终总结 Netty 整体架构与分层设计哲学，并从 Netty 视角理解 Dubbo 与 RocketMQ 网络层设计。

**Dubbo 源码系列**（8 篇）：SPI 扩展点与插件替换、线程池与消息派发、启动优化与连接管理、集群容错与负载均衡、RpcContext 与隐式传参、服务治理与流量管控、Filter 链与自定义扩展、配置中心与元数据与链路追踪与泛化调用，以及 Exchange 与 Transport 分层设计、URL 全流程变化形态、服务暴露与引用的全流程源码解析。

**Sentinel 源码系列**（6 篇）：核心入口责任链执行、流量控制限流规则、热点参数限流令牌桶与 LRU 缓存、熔断降级 CircuitBreaker 状态机、系统自适应保护 BBR 算法、规则动态配置 DataSource 与 PropertyListener 的全流程源码解析。

**其他源码与深度解析**（根目录）：

- `Java字节码增强技术深度解析_ByteBuddy_Javassist_JavaAgent.md`
- `Netty全面详解-配置与高级用法.md`
- `SkyWalking_深度解析.md` + `SkyWalking-OAP-源码架构全景.md`

### 八、中间件与基础设施笔记

**路径：`笔记讲解/` + `windows电脑上的相关笔记/常用中间件超详细剖析/`**

**笔记讲解/** — 深度技术博客级别的中间件解析，覆盖：

- 存储类：RocksDB 深度解析、Lucene 深度解析与可复用思维提炼、Zebra 数据库访问中间件、Squirrel 分布式缓存、关系型与非关系型数据库分布式架构实践
- 消息类：企业级分布式消息队列系统深度解析、RabbitMQ 深度解析、xxl-job 深度解析、Crane 分布式任务调度
- 基础设施类：K8s 全方位深度讲解（5 个 Part）、企业级 SET 化架构、企业级网关架构、企业级内网架构、企业级分布式架构与高并发设计
- 网络类：交换机深度解析、路由器深度解析、企业网络终端隔离与同子网互访原理、远程控制软件底层链路设计
- 其他：Git 底层原理、Maven 全面说明书、HotRing 详解、Thrift 与 Pigeon 协议、Lion 配置中心、macOS vs Windows 内存管理差异、Rust 学习路线、分布式事务知识体系、数据库能力专题、延时任务方案对比、状态机自动生成测试用例

**常用中间件超详细剖析/** — 以图文并茂的方式剖析 Redis 各数据类型（String/List/Set/Zset）、MySQL 索引与锁、Dubbo 调用流程、Sentinel 核心概念、Spring Cloud Gateway、Nacos、Seata、数据库分库分表等核心中间件。

### 九、计算机基础课程笔记

**路径：`windows电脑上的相关笔记/`**

- `计算机网络2/` — 物理层到应用层的完整网络协议笔记，含大量架构图（ARP、DHCP、NAT、以太网 MAC 帧、IP 分组、CIDR、路由聚合等）
- `计算机组成原理/` — 计算机组成原理课程笔记
- `CSAPP/` — CSAPP（深入理解计算机系统）9 个 Lab 的完整实现（Data Lab、Bomb Lab、Attack Lab、Arch Lab、Cache Lab、Perf Lab、Shell Lab、Malloc Lab、Proxy Lab）
- `JUC/` — Java 并发编程实战代码
- `准备面试八股/` — JVM、Netty、Redis、RabbitMQ、监控系统等面试核心知识点

### 十、测试工程与质量保障

**路径：`百个应用链路测试方案和细节企业版/`**

- 企业级多环境隔离架构：一套代码如何自动连接正确的环境
- 全链路灰度测试架构解析
- 测试开发监听器和上报器学习
- 状态机自动生成测试用例详解

### 十一、杂项笔记与技术八股

**路径：根目录散篇 + `windows电脑上的相关笔记/`**

- `MapReduce.txt`、`raft算法.txt`、`skywalking.txt`、`springcloud.txt` — 早期学习笔记
- 根目录 PNG 图片 — Raft 算法各概念的可视化图解（角色定义与切换、领导者/跟随者/候选人、心跳机制、日志同步场景、多数派原则、任期与日志索引、状态机和预写日志、一致性哈希算法系列、两阶段提交、读写一致性方案等）
- `windows电脑上的相关笔记/` 下的 `git.txt`、`JVM和Linux.txt`、`nginx.txt`、`shell.txt`、`MCP服务器.txt`、`KMP.txt` 等技术八股
- `windows电脑上的相关笔记/ai-guide/` — AI 编程指南（Vibe Coding 教程、DeepSeek 使用指南等）
- `windows电脑上的相关笔记/VideoCode/` — A2A 协议深度解析、MCP 终极指南、RAG 系统构建、Agent 概念原理等视频配套代码
- `.tmp-source-study/reactor-core/` — Reactor Core 源码学习

---

## 学习路线建议

如果你是分布式系统的初学者，建议按以下顺序阅读：

**第一阶段：建立直觉。** 从 `DistributedSystem/DistributedSystem-Notes/01~分布式基础/` 开始，理解分布式系统为什么"不可靠"（网络不可靠、时钟不可靠、进程不可靠），然后学习 CAP 定理和 BASE 理论，建立对分布式系统核心权衡的直觉。

**第二阶段：论文精读。** 阅读 `papers/` 中的经典论文，配合根目录下的 teacher-style 讲解文档。推荐顺序：CAP → Paxos Made Simple → Raft → LSM-Tree → GFS → Chubby → ZooKeeper → Spanner。

**第三阶段：算法实现。** 参考 `DistributedSystem/MIT6.824_DistributedSystem/` 中的 MIT 6.824 实验代码，尝试自己实现 MapReduce 和 Raft。然后阅读 `RaftByJava_副本/` 中的 Java 版 Raft 实现和 SOFA-JRaft 源码，理解工业级 Raft 的工程细节。

**第四阶段：架构视野。** 阅读 `后端架构设计文档/` 中的 50 篇架构设计文档，建立后端系统全貌认知。然后根据兴趣选择 `业务系统架构设计文档/` 中的具体业务系统进行深入学习。

**第五阶段：前沿探索。** 进入 `harnessEngineer/` 学习 AI Agent 的工程化实现，结合 `pythonai框架的使用和细节/` 中的 LangChain/LangGraph 笔记进行实践。

---

## 文件格式说明

本知识库包含多种文件格式，各有用途：

- `.md` — 主要的笔记和文档格式，推荐使用支持 Markdown 渲染的编辑器阅读
- `.txt` — 早期笔记和快速记录的内容
- `.pdf` — 论文原文
- `.png/.jpg/.webp` — 架构图和概念图解
- `.go` — MIT 6.824 实验代码（Go 语言）
- `.java` — Raft Java 实现、各种 Java 项目源码
- `.py` — Agent 工程实战代码
- `.html` — 交互式文档和架构对比图
- `.json/.csv` — 论文索引和配置文件

> **提示：** 根目录下的 PNG 图片是 Raft 算法和分布式核心概念的可视化图解，建议配合相关文档一起阅读。`DistributedSystem/DistributedSystem-Notes/` 可通过 `index.html` 以文档站点形式浏览。
