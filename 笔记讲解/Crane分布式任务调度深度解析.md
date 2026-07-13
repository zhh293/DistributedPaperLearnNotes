# 企业级分布式任务调度深度解析

> 本文基于企业内部定时任务 技术架构 Review、架构设计文档、PRFAQ、高可用架构 2.0 等核心资料撰写，面向零基础读者，从"为什么需要"到"怎么做的"逐层展开。

---

## 一、从一个简单的需求说起：为什么需要分布式任务调度？

### 1.1 你一定写过这样的代码

假设你是一个后端工程师，老板给你提了一个需求："每天凌晨 2 点清理 30 天前的过期日志"。你可能 5 分钟就搞定了：

```java
// 方案一：Spring @Scheduled
@Scheduled(cron = "0 0 2 * * ?")
public void cleanExpiredLogs() {
    logRepository.deleteByCreateTimeBefore(LocalDateTime.now().minusDays(30));
}
```

或者你更熟悉 Linux，直接写一条 crontab：

```bash
# 方案二：Linux crontab
0 2 * * * /usr/bin/python3 /opt/scripts/clean_logs.py
```

这两种方案在单机环境下确实能跑，而且跑得很好。但是，当你的系统从一台服务器变成了 10 台、100 台服务器的时候，问题就来了。

### 1.2 传统定时任务的三大痛点

**痛点一：重复执行**

你的服务部署在 10 台机器上，每台机器都有一份 `@Scheduled` 代码。凌晨 2 点一到，10 台机器同时开始清理日志。如果你清理的是数据库中的数据，那恭喜你——10 条一模一样的 DELETE 语句同时打到数据库上。轻则浪费资源，重则把数据库搞挂。

**痛点二：单点故障**

如果你聪明一点，用一个"只在一台机器跑"的方案，比如加个分布式锁。那万一这台机器凌晨 1:59 宕机了呢？任务就没人执行了。第二天早上你被老板叫过去问："昨天的日志为什么没清？"

**痛点三：无法管理**

现在你的系统里有 50 个定时任务，分散在 20 个服务里。你想回答一个简单的问题："昨天凌晨的月报生成任务跑成功了吗？"——你发现你得登录到具体那台机器上，翻日志，找半天。没有统一的管理平台，没有执行历史，没有告警通知。

用一张表来对比一下：

| 问题 | Spring @Scheduled | Linux crontab | 分布式任务调度（如 Crane） |
|------|-------------------|---------------|--------------------------|
| 多机部署重复执行 | 会重复 | 不涉及（单机） | 只在一台机器执行 |
| 单点故障 | 无容错 | 无容错 | 自动故障转移 |
| 任务生命周期管理 | 无 | 无 | 创建/暂停/删除/查看历史 |
| 执行监控与告警 | 无 | 无 | 内置监控、超时告警 |
| 任务编排（A 完了再跑 B） | 不支持 | 不支持 | DAG 图式编排 |
| 分片并行处理 | 不支持 | 不支持 | 全节点分片调度 |

这就是为什么你需要一个**分布式任务调度系统**。

---

## 二、Crane 是什么？它从哪里来？

### 2.1 一句话定义

**Crane 是美团统一的分布式任务调度中间件**，解决微服务架构下任务调度、任务管理、任务监控等一系列问题。

Crane 这个名字来源于英文"鹤"，寓意着优雅和稳定。它的目标很简单：**让研发工程师不再操心"什么时候执行"、"在哪台机器执行"、"执行失败了怎么办"这些问题，只需要专注于业务逻辑本身。**

### 2.2 Crane 能用在什么场景？

Crane 的使用场景非常广泛，举几个你一定见过的例子：

- **每秒钟**进行一次视频转码状态检查
- **每 5 分钟**刷新一次缓存数据
- **每小时**清理一遍临时日志文件
- **每天凌晨**进行历史数据迁移
- **每周五下午**生成周报并发送邮件
- **每月最后一天**执行工资发放流程
- **每年一次**的生日提醒

这些场景都有一个共同特点：**需要在特定的时间点自动执行某段逻辑**。Crane 都能帮你搞定。

### 2.3 从 Taurus + Mschedule 到 Crane：发展历程

Crane 并不是凭空造出来的。它是由两个更早期的系统融合而来：

- **Taurus**：原美团（点评侧）的调度中间件
- **Mschedule**：原美团（美团侧）的调度中间件

2015 年美团与大众点评合并后，两套调度系统并存，维护成本高、功能重叠。于是团队决定将二者合二为一，这就是 Crane 的由来。

下面是 Crane 的完整里程碑时间线：

| 时间 | 里程碑事件 |
|------|-----------|
| 2016/06/20 | MVP 版本上线，整合 Taurus 和 Mschedule |
| 2016/07/14 | 进程内任务支持类名+方法名调度方式，支持下发任务参数 |
| 2016/09/19 | **架构 1.0 升级**，调度引擎优化为延迟队列，调度器支持水平扩展 |
| 2016/11/18 | 支持分片执行方式 |
| 2017/01/03 | 新增 Docker 任务类型 |
| 2017/04/10 | 客户端注册节点由临时节点改为持久节点，增加 Monitor 模块 |
| 2017/08/04 | 新增 Agent 任务类型，支持脚本类型的任务调度 |
| 2018/03/19 | 拆分调度集群，支撑任务数快速增长 |
| 2018/04/20 | 支持 Set 及泳道调度 |
| 2018/07/20 | 完成管理平台重构，提升用户体验 |
| 2018/11/05 | **新增 DAG 任务类型**，支持任务编排 |
| 2019/02/27 | **推出延迟任务功能**（领先业界） |
| 2021 年 | 经历 3 起线上故障，开始稳定性治理 |
| 2023/06/30 | 美团中间件团队正式对外发布 Crane |
| 2024 年 | 高可用架构 2.0 落地，Slot 粒度灰度、秒级回滚 |

### 2.4 Crane 的规模有多大？

截至 2024 年底（基于 Crane 2025 工作计划数据），Crane 的业务规模如下：

| 指标 | 2023 年数据 | 2024 年数据 | 增长 |
|------|-----------|-----------|------|
| 接入服务数量 | 11,809 | 13,054 | +10.5% |
| 周期任务数量 | 163,964 | 191,727 | +16.9% |
| 周期任务日调度量 | 6,416 万次/日 | 7,858 万次/日 | +22.5% |
| 周期任务集群数 | 30 个 | 49 个 | +63.3% |

核心性能指标：

| 指标 | 数值 |
|------|------|
| 周期任务平均调度延迟 | < 20ms |
| 周期任务调度延迟 TP99 | < 100ms |
| 延迟任务提交平均耗时 | < 10ms |
| 延迟任务提交成功率 | > 99.995% |
| 调度器单机 TPS | 2,000 |
| 集群承载最大任务数 | 77,000 |

每天近 **8000 万次**的调度量，平均延迟不到 20 毫秒——这就是 Crane 的体量。

---

## 三、整体架构：Crane 的五脏六腑

### 3.1 架构全景图（ASCII）

```
                           ┌─────────────────────────────────────────┐
                           │              用户 / 研发工程师             │
                           └───────────────────┬─────────────────────┘
                                               │ 浏览器访问
                                               ▼
                           ┌─────────────────────────────────────────┐
                           │          Portal（管理平台 / Web UI）       │
                           │   提供统一的 UI 操作界面 + HTTP 服务接口     │
                           │        北上 2 地 4 机房部署，可水平扩展      │
                           └───────────┬─────────────┬───────────────┘
                                       │ HTTP        │ HTTP
                      ┌────────────────▼─────┐  ┌────▼───────────────┐
                      │    Scheduler 调度器    │  │   Scheduler 调度器  │
                      │  (按 BG 隔离部署)      │  │   (按 BG 隔离部署)  │
                      │  ┌─────────────────┐  │  │                    │
                      │  │  延迟队列引擎     │  │  │                    │
                      │  │  (Java           │  │  │                    │
                      │  │   DelayQueue)    │  │  │                    │
                      │  └─────────────────┘  │  │                    │
                      └──┬──────────┬─────────┘  └────────────────────┘
                         │          │                        ▲
           ┌─────────────▼──┐  ┌────▼──────────────┐        │
           │   客户端节点     │  │   MySQL 数据库      │        │ 租约发放
           │  (业务机器)      │  │  ·任务元数据库       │   ┌────┴──────────┐
           │  执行具体的      │  │  ·调度记录库         │   │   Manager      │
           │  业务逻辑        │  └────────────────────┘   │  管理调度器     │
           └────────────────┘                             │  Slot 分配     │
                      ▲                                   │  心跳检测       │
                      │ 注册信息                           │  通过 ZK 选主   │
                      │                                   └───┬────────────┘
                 ┌────▼──────────────────┐                    │
                 │   Zookeeper 注册中心    │◄───────────────────┘
                 │   单侧 5 节点 3 机房部署  │
                 └────┬──────────────────┘
                      │ 心跳探活
                 ┌────▼──────────────┐
                 │   Monitor 模块     │
                 │   客户端健康检测     │
                 └───────────────────┘
```

### 3.2 各模块职责详解

#### 3.2.1 Portal 模块——统一入口

Portal 是你和 Crane 打交道的唯一入口。它提供了两个核心能力：

1. **Web UI**：一个管理平台，你可以在上面创建任务、配置 Cron 表达式、查看执行历史、手动触发任务等。
2. **HTTP 接口**：提供 OpenAPI，让你可以通过代码调用 Crane（比如在某个业务流程结束后触发一个任务）。

Portal 本身是无状态的，可以任意水平扩展。它部署在北京和上海两地、共 4 个机房。当你在管理平台操作一个任务时，Portal 会做两件事：
- 根据该任务的 **appKey** 确定它属于哪个 **BG 集群**
- 根据任务名计算 hash，定位到具体哪台**调度器**负责这个任务

#### 3.2.2 Scheduler 模块——调度核心

Scheduler（调度器）是 Crane 最核心的模块，负责"什么时候触发什么任务"。它的核心工作包括：

- 按照用户配置的 Cron 表达式，**定时生成调度实例**
- 将调度指令**发送到客户端**（业务机器）执行
- 接收客户端执行结果的**回写**
- 将执行记录**持久化**到 MySQL

调度器按 BG（Business Group，事业群）进行隔离部署，每个 BG 内部单侧 3 机房部署，可以任意水平扩展。

#### 3.2.3 Manager 模块——调度器的管理者

如果说 Scheduler 是"干活的工人"，那 Manager 就是"工头"。Manager 的职责包括：

- **Slot 分配**：通过虚拟 Slots 层，将任务合理分配到各个调度器上
- **租约发放**：对调度器发放 Lease 租约，保证分布式环境下的一致性
- **心跳检测**：探测调度器是否存活，及时摘除宕机节点
- **通过 Zookeeper 选主**：Manager 自身也是多节点部署，通过 ZK 选出一个主节点来工作

Manager 单侧 3 机房部署。当主节点宕机时，会在 **6 秒**后完成切换，选举出新的主节点。

#### 3.2.4 Zookeeper 模块——注册中心

Zookeeper 在 Crane 架构中扮演注册中心的角色，单侧 5 节点 3 机房部署。它存储三类信息：

1. **客户端注册信息**：业务服务启动时，会把自己的 IP、appKey、泳道、Set 信息以及注册的任务列表上报到 ZK。
2. **调度器注册信息**：调度器启动时注册自身节点，供 Manager 管理。
3. **Monitor 注册信息**：各 Monitor 注册后，平均分配客户端进行心跳检测。

**重要的一点：Zookeeper 是弱依赖**。如果 ZK 集群宕机了，调度器会按之前缓存的客户端列表继续调度，不会导致服务不可用。

#### 3.2.5 Monitor 模块——健康哨兵

Monitor 负责对客户端（业务机器）进行心跳检测。它会定期检查每个客户端是否存活，如果某台业务机器宕机了，Monitor 会将其标记为不可用，这样调度器就不会再把任务发到这台机器上了。

#### 3.2.6 其他模块

在高可用架构 2.0 中，Crane 还引入了以下模块：

| 模块 | 职责 |
|------|------|
| Alarm | 告警模块，负责任务异常状态告警，推送给业务方 |
| Meta-Server | 元数据模块，代理客户端的注册/下线请求 |

### 3.3 BG 隔离部署

Crane 在部署上按照 **BG（Business Group，事业群）** 进行隔离。截至文档撰写时，共拆分为 **7 个 BG 集群**：

| 部署位置 | BG 集群 |
|----------|---------|
| 上海侧 | 默认集群、点综集群 |
| 北京侧 | 外卖集群、酒旅集群、金融集群、猫眼集群、餐饮集群 |

每个 BG 集群之间**完全隔离**，互不影响。通过 appKey 决定任务归属哪个调度集群。新增一个 BG 集群，只需要部署对应 BG 所需的各内部模块即可，对其他 BG 无影响。

---

## 四、核心调度模型：延迟队列驱动的周期性调度

### 4.1 调度模型总览

Crane 的调度引擎基于 Java 原生的 **DelayQueue（延迟队列）** 实现。整个调度流程可以用下面的循环来描述：

```
┌──────────────────────────────────────────────────────────┐
│                     调度循环流程                           │
│                                                          │
│  ① 任务加载：Scheduler 启动时，加载所有归属自己的任务       │
│       │                                                  │
│       ▼                                                  │
│  ② 生成调度实例：根据 Cron 表达式计算下一次触发时间，        │
│     生成"调度实例"放入延迟队列                              │
│       │                                                  │
│       ▼                                                  │
│  ③ 等待到期：延迟队列按时间排序，到期的实例自动弹出          │
│       │                                                  │
│       ▼                                                  │
│  ④ 消费实例：取出到期的调度实例，发送调度指令给客户端        │
│       │                                                  │
│       ▼                                                  │
│  ⑤ 生成下一次：立即根据 Cron 表达式计算下下次触发时间，      │
│     生成新的调度实例，放回延迟队列                           │
│       │                                                  │
│       └──────────── 回到步骤 ③ ──────────────────────────│
└──────────────────────────────────────────────────────────┘
```

### 4.2 用一个例子来理解

假设你配置了一个任务，Cron 表达式为 `0 0 2 * * ?`（每天凌晨 2 点执行）：

1. **Scheduler 启动**：加载这个任务，计算下一次触发时间为"明天凌晨 2:00:00"
2. **生成调度实例**：创建一个"延迟到明天凌晨 2:00:00 的实例"，放入 DelayQueue
3. **等待**：DelayQueue 在明天凌晨 2:00:00 之前一直阻塞
4. **到期弹出**：明天凌晨 2:00:00 到了，实例从队列中弹出
5. **执行调度**：Scheduler 通过 HTTP 将调度指令发送到客户端（业务机器），客户端执行 `cleanExpiredLogs()` 方法
6. **生成下一次**：立即计算下下次触发时间为"后天凌晨 2:00:00"，放入队列
7. **循环往复**

这个模型的优点是：
- **精确**：DelayQueue 本身基于堆结构，时间精度高
- **轻量**：不需要额外的轮询机制，到期自动唤醒
- **自驱动**：每次消费完自动生成下一次，形成闭环

### 4.3 调度实例的生命周期

一个调度实例从创建到结束，会经历以下状态：

```
  创建（CREATED）
       │
       ▼
  等待中（WAITING）──── 在延迟队列中等待到期
       │
       ▼
  调度中（DISPATCHING）── Scheduler 发送调度指令
       │
       ▼
  执行中（RUNNING）──── 客户端正在执行业务逻辑
       │
       ├── 成功 → 成功（SUCCESS）
       │
       ├── 失败 → 失败（FAILED）──── 可配置失败重试
       │
       └── 超时 → 超时（TIMEOUT）
```

---

## 五、基于租约的分布式调度器设计

这是 Crane 架构中最精妙的部分，也是面试中经常被问到的知识点。让我们一步步来理解。

### 5.1 问题的起源：为什么需要租约？

在分布式环境下，一个 BG 内部通常有多台调度器同时运行。一个核心问题是：**如何保证每个任务只被一台调度器调度，不会重复？**

#### 第一步解决：虚拟 Slots 层

Crane 引入了一个 **虚拟 Slots 层**来解决任务分配问题。你可以把 Slots 想象成一排"信箱"：

```
  Slot-0   Slot-1   Slot-2   Slot-3   Slot-4   Slot-5   ...   Slot-N
    │        │        │        │        │        │                │
    └────┬───┘        └───┬────┘        └───┬────┘               │
         │                │                 │                     │
   Scheduler-A      Scheduler-B       Scheduler-C               ...
```

**分配规则**：Manager 将所有 Slots 平均分配给各台调度器。

**任务路由规则**：对任务名取 hash，然后对 Slots 总数取模，得到的结果就是该任务归属的 Slot。拥有这个 Slot 的调度器就负责调度这个任务。

```
hash("clean-expired-logs") % N = 3  →  Slot-3  →  Scheduler-B 负责
hash("generate-weekly-report") % N = 0  →  Slot-0  →  Scheduler-A 负责
```

这样就实现了"一个任务只由一个调度器负责"。

#### 第二步解决：心跳检测与被动摘除

当一台调度器**正常下线**时（比如发版重启），它会主动从 Zookeeper 中删除自己的注册节点。Manager 通过监听 ZK，能实时感知到调度器下线，然后重新分配 Slots。

但如果调度器**宕机**了呢？它来不及主动注销。这时候就需要 Manager 进行**被动摘除**：Manager 会定期对所有调度器发起心跳检测，如果某台调度器连续多次没有响应，就认为它宕机了，Manager 会将它的 Slots 重新分配给其他调度器。

#### 第三步解决：租约机制（关键！）

心跳检测解决了"调度器宕机"的场景，但还有一个更棘手的问题——**网络分区（脑裂）**。

想象这个场景：
1. Scheduler-B 和 Manager 之间的网络断了
2. Manager 连续几次 ping 不通 Scheduler-B，认为它宕机了
3. Manager 把 Scheduler-B 的 Slots 重新分配给 Scheduler-A
4. 但实际上 Scheduler-B 还活着！它只是网络不通
5. 现在 Scheduler-A 和 Scheduler-B **同时认为自己负责** Slot-3 的任务
6. 任务被**重复调度**了！

```
  网络分区前：                        网络分区后（脑裂）：
  
  Manager ──── Scheduler-B            Manager ─ ✕ ─ Scheduler-B
     │                                    │            (以为自己还活着，
     │                                    │             继续调度 Slot-3)
     │                                    ▼
     │                              Scheduler-A
     │                              (被分配了 Slot-3，
     │                               也开始调度 Slot-3)
```

**租约机制就是为了解决这个问题**。

#### 租约的工作流程

```
  ┌─────────────────────────────────────────────────────┐
  │                    租约发放与续租流程                   │
  │                                                     │
  │  Manager（主节点）                                    │
  │     │                                               │
  │     │──── 发放租约（Lease）──→ Scheduler-A            │
  │     │     有效期 = T 秒        收到租约，开始调度       │
  │     │                                               │
  │     │──── 发放租约（Lease）──→ Scheduler-B            │
  │     │     有效期 = T 秒        收到租约，开始调度       │
  │     │                                               │
  │     │     ... 经过一段时间 ...                         │
  │     │                                               │
  │     │──── 续租请求 ──→ Scheduler-A                    │
  │     │     A 响应成功           租约续期，继续调度        │
  │     │                                               │
  │     │──── 续租请求 ──→ Scheduler-B                    │
  │     │     B 无响应（网络断了）   无法续租！              │
  │     │                                               │
  │     │     ... 租约到期 ...                             │
  │     │                                               │
  │     │     Manager 侧：B 的租约过期，认为 B 宕机         │
  │     │     Scheduler-B 侧：自己的租约也过期了，           │
  │     │                      主动停止调度！               │
  │     │                                               │
  │     │     → 不会出现两边同时调度的情况                   │
  └─────────────────────────────────────────────────────┘
```

**关键点在于：租约是双向约束的**。

- **Manager 侧**：如果调度器没有响应续租，当租约到期时，Manager 认为调度器宕机，将其 Slots 重新分配。
- **调度器侧**：如果自己的租约到期了（没有收到续租），调度器会**主动停止调度**。

这样，即使出现网络分区，也不会出现"两边同时调度"的情况。这就是租约机制实现**状态判断一致性**的精髓。

### 5.2 Manager 的选主机制

Manager 自身也是多节点部署的。既然 Manager 负责给调度器发放租约，那谁来决定哪个 Manager 是"主节点"呢？

答案是**Zookeeper 选主**。Manager 通过 ZK 的临时有序节点来竞争主节点：

```
  Manager-1  ──┐
  Manager-2  ──┼──→ Zookeeper 选主 ──→ Manager-1 成为主节点
  Manager-3  ──┘                       Manager-2, 3 成为备节点
```

主节点负责：
- 对所有调度器进行租约发放
- 响应调度器上下线，重新分配 Slots

当主节点宕机时，ZK 会在 **6 秒**后触发重新选主。

**ZK 不可用时的降级策略**：每个 Manager 会定时检测 ZK 上是否存在主节点。如果 ZK 集群整体不可用，无法选出主节点，此时**所有 Manager 都会对调度器进行心跳检测**，确保即使 ZK 挂了，调度器的故障仍然能被发现。

---

## 六、任务类型详解

Crane 支持两大类任务：**周期任务**和**延迟任务**。

### 6.1 周期任务

周期任务按照 Cron 表达式周期性执行，又细分为 4 种类型：

#### 6.1.1 进程内任务（使用最广泛）

进程内任务就是在你的 Java/Node.js 服务进程内执行的任务。你只需要引入 `xxljob-client` 依赖，在代码中加上注解即可：

```java
@CraneConfiguration
public class MyTasks {

    @Crane("com.sankuai.myapp.clean-expired-logs")
    public void cleanExpiredLogs() {
        // 你的业务逻辑
        logRepository.deleteByCreateTimeBefore(
            LocalDateTime.now().minusDays(30)
        );
    }
}
```

然后在 Crane 管理平台上创建一个同名任务，配置 Cron 表达式就行了。

**适用场景**：绝大多数定时任务，比如数据清理、报表生成、缓存刷新等。

#### 6.1.2 Docker 任务

Docker 任务会在任务运行时**动态分配一台 Docker 容器**，任务执行完后容器自动销毁。每个任务有独立的内存和 CPU 资源。

**适用场景**：
- IO 密集型或 CPU 密集型任务（如爬虫、数据处理）
- 多语言脚本任务（Shell、Python 等）
- 需要资源隔离的任务

#### 6.1.3 Agent 任务

Agent 任务通过部署在每台机器上的 `cr_agent` 来执行。你只需要把脚本上传到指定目录，Crane 就会在指定时间通过 Agent 调用你的脚本。

**适用场景**：
- Shell/Python 脚本类型的定时任务
- 替代原来独立进程中的 crontab 脚本

#### 6.1.4 DAG 任务（任务编排）

DAG（Directed Acyclic Graph，有向无环图）任务用于描述**任务之间的依赖关系**。比如：

```
  任务 A（数据抽取）
       │
       ├──→ 任务 B（数据清洗）
       │         │
       │         └──→ 任务 D（生成报表）
       │
       └──→ 任务 C（数据校验）
                  │
                  └──→ 任务 D（生成报表）
```

在这个例子中：
- 任务 A 执行完后，任务 B 和任务 C 可以**并行**执行
- 任务 D 必须等任务 B **和** 任务 C 都执行完才能开始

Crane 管理平台支持通过**图形化界面**拖拽配置 DAG 任务的依赖关系，非常直观。

**适用场景**：ETL 数据流水线、多步骤报表生成、有先后依赖的批处理任务。

### 6.2 延迟任务

延迟任务是一种"一次性"的任务——你告诉 Crane"在 N 秒/分钟后执行这个任务"，Crane 会在指定时间触发执行。

```java
// 提交一个延迟任务：30 分钟后检查订单是否支付
xxljobDelayClient.submit(
    "check-order-payment",  // 任务名
    orderId,                // 任务参数
    30 * 60                 // 延迟秒数
);
```

**适用场景**：
- 订单超时未支付自动取消（下单 30 分钟后检查）
- 预约提醒（会议开始前 15 分钟提醒）
- 延迟重试（请求失败后 5 分钟重试）

Crane 是**业界最早提供延迟任务功能的调度中间件之一**（2019 年 2 月上线），这是一个领先于开源竞品的特性。

**注意**：根据 Crane FAQ，目前延迟任务已不再接入新任务，延迟消息的需求建议使用 Mafka 延迟消息来替代。

---

## 七、三种调度方式

Crane 支持三种调度方式，适用于不同的业务场景。

### 7.1 单节点调度

**定义**：任务只在**一台**客户端机器上执行。

```
  Scheduler
      │
      │── 调度指令 ──→ IP-1  ✓ 执行
      │
      │                IP-2  ✗ 不执行
      │
      │                IP-3  ✗ 不执行
```

Crane 会从注册在该任务下的所有客户端机器中**选择一台**来执行。如果被选中的机器宕机了，会自动选择其他存活的机器。

**适用场景**：大多数定时任务（清理日志、生成报表、发送通知等），**也是使用频率最高的调度方式**。

### 7.2 全节点调度（广播）

**定义**：任务在**所有**客户端机器上同时执行。

```
  Scheduler
      │
      │── 调度指令 ──→ IP-1  ✓ 执行
      │── 调度指令 ──→ IP-2  ✓ 执行
      │── 调度指令 ──→ IP-3  ✓ 执行
```

**适用场景**：
- 刷新每台机器上的本地缓存
- 清理每台机器上的临时文件
- 广播配置更新

**注意**：当前系统仅支持客户端节点数在**千级别以下**的广播调度。如果你的服务有数千台机器，需要考虑其他方案。

### 7.3 全节点分片调度

**定义**：将一个大任务拆分为多个**分片（Shard）**，分配到不同的客户端机器上**并行执行**。

```
  配置：6 个分片，3 台机器

  Scheduler
      │
      │── 分片 0, 1 ──→ IP-1  处理 shard 0 和 shard 1
      │── 分片 2, 3 ──→ IP-2  处理 shard 2 和 shard 3
      │── 分片 4, 5 ──→ IP-3  处理 shard 4 和 shard 5
```

**实际例子**：你需要刷新数据库中 1000 万条订单的状态。单机处理要 2 小时。如果用 5 台机器分片，每台只处理 200 万条，理论上只需要 24 分钟。

代码中获取分片信息：

```java
@Crane("refresh-order-status")
public void refreshOrderStatus() {
    List<Integer> shardItems = ShardItemsContext.getShardItems();  // 获取本机分片值
    int shardCount = ShardItemsContext.getShardCount();            // 获取分片总数

    for (Integer shard : shardItems) {
        // 根据分片值处理对应的数据
        List<Order> orders = orderRepository.findByIdMod(shardCount, shard);
        for (Order order : orders) {
            order.refreshStatus();
        }
    }
}
```

**分片策略**：Crane 提供 3 种分片策略：

| 策略 | 说明 |
|------|------|
| 平均分片（默认） | 将分片值平均分配给各台机器 |
| 随机分片 | 每次调度随机分配，分片分布不固定 |
| 哈希奇偶分片 | 根据任务名哈希值决定 IP 排序方式后再平均分配 |

**最佳实践**：分片数推荐设置为机器节点数的 **2 倍**，这样即使扩容，也能保证分片数大于机器节点数。

---

## 八、高可用设计

对于一个日调度量近 8000 万次的系统来说，"3 分钟内不调度可能定级 S4 故障"——高可用是生命线。

### 8.1 多层次隔离

```
                        ┌─────────────────────────┐
  第一层：BG 隔离         │  外卖 BG  │  酒旅 BG  │ ...
                        │ 完全独立   │ 完全独立   │
                        └─────────────────────────┘
                                    │
                        ┌───────────▼──────────────┐
  第二层：3 机房部署       │  机房 A  │ 机房 B │ 机房 C │
                        │  每个模块都部署在 3 个机房  │
                        └──────────────────────────┘
                                    │
                        ┌───────────▼──────────────┐
  第三层：N+1 容量         │  任意一个机房挂掉，       │
                        │  剩余机房可承载全部流量     │
                        └──────────────────────────┘
```

### 8.2 ZK 弱依赖

Zookeeper 用于保存客户端的注册信息。但如果 ZK 集群宕机了：
- **调度器**会按之前**缓存**的客户端列表继续调度
- **Manager**所有节点都会对调度器进行心跳检测，确保调度器故障能被发现

也就是说，ZK 挂了，Crane 依然能正常调度。只是**新上线的客户端**在 ZK 恢复前无法注册进来。

### 8.3 DB 弱依赖

MySQL 用于保存任务元数据和执行历史。如果数据库宕机了：
- 调度器仍然可以**正常调度**（任务元数据已缓存在内存中）
- 但执行历史**会丢失**（因为无法写入数据库）

### 8.4 高可用架构 2.0 的改进

2024 年，Crane 团队推出了高可用架构 2.0，解决了几个关键问题：

| 问题 | 1.0 架构 | 2.0 架构 |
|------|---------|---------|
| 灰度粒度 | 机器维度（1/3 影响面） | **Slot 维度**（精细控制） |
| 回滚速度 | 滚动回滚（分钟级） | **秒级回滚** |
| 回滚彻底性 | Slot 拓扑变化，可能触发二次问题 | **状态一致的彻底回滚** |
| 运维能力 | Slot 随机分配，不可人工指定 | 可控制 Slot 迁入/迁出 |
| 调度器运行时依赖 | 依赖 ZK、DB、Manager | **运行时无任何强依赖** |

---

## 九、与业界产品对比

下面这张表将 Crane 与业界主流的任务调度产品进行全面对比：

| 对比维度 | Crane（美团） | SchedulerX（阿里云） | PowerJob（开源） | Elastic-Job（开源） | K8s Job |
|---------|-------------|-------------------|----------------|-------------------|---------|
| **定位** | 美团统一任务调度中间件 | 阿里云商业产品 | 开源社区项目 | 开源社区项目 | K8s 原生 |
| **触发方式** | Cron（秒级）、OpenAPI、ISO8601、自定义、延迟任务 | Cron（分钟级）、OpenAPI、Fixed Rate、Second Delay | Cron（分钟级）、固定频率、固定延迟、OpenAPI | Cron（秒级） | Cron（分钟级）、手动 |
| **任务类型** | Java、Node.js、Docker、Agent（Go/Python/Shell） | Java、Shell、Python、Go、Node.js | Java、独立进程、Shell | Java、Shell | Shell |
| **任务编排** | DAG 图式调度，图形化配置 | DAG 图式调度，图形化配置，**任务间可数据传递** | DAG 图式调度 | 无 | 无 |
| **分布式计算** | 单机、广播、静态分片 | 单机、广播、静态分片、**Map、MapReduce** | 单机、广播、**Map、MapReduce** | 单机、广播、静态分片 | 单机、广播 |
| **调度引擎** | 自研（延迟队列） | 基于 Quartz | 基于 Quartz | 基于 Quartz | K8s Controller |
| **外部依赖** | DB + Zookeeper | DB + Zookeeper | DB（依赖较少） | DB + Zookeeper | K8s |
| **可靠性** | 至少调度一次 | 至少调度一次 | 分钟级至少一次，秒级至多一次 | 至少调度一次 | - |
| **延迟任务** | **支持（业界领先）** | 不支持 | 不支持 | 不支持 | 不支持 |
| **管控平台** | Web UI | Web UI | Web UI | Web UI | kubectl |

### 9.1 Crane 的优势

- **延迟任务**：Crane 是最早提供延迟任务功能的调度中间件之一
- **自研调度引擎**：不依赖 Quartz，基于延迟队列自研，性能更好
- **经过大规模验证**：日调度量近 8000 万次，接入 13,000+ 服务

### 9.2 Crane 的不足

- **触发方式**：缺乏 Fixed Rate（固定频率）、Second Delay（秒级延迟）等触发方式
- **分布式编程模型**：缺乏 Map、MapReduce 等动态分片的计算模型
- **DAG 功能**：缺乏子任务间数据传递、拥塞控制等高级功能
- **秒级任务**：对待秒级和分钟级任务的实现相同，缺乏差异化 SLA

---

## 十、从零开始使用 Crane：完整接入流程

### 10.1 第一步：引入依赖

在你的 Java 项目的 `pom.xml` 中添加 Crane 客户端依赖：

```xml
<dependency>
    <groupId>com.sankuai.service.mobile</groupId>
    <artifactId>xxljob-client</artifactId>
    <version>${xxljob.version}</version>
</dependency>
```

### 10.2 第二步：编写任务代码

```java
import com.sankuai.service.mobile.xxljob.annotation.Crane;
import com.sankuai.service.mobile.xxljob.annotation.CraneConfiguration;

@CraneConfiguration
public class OrderTasks {

    /**
     * 每天凌晨 2 点清理 30 天前的过期订单
     * 任务名与管理平台配置的保持一致
     */
    @Crane("com.sankuai.order.clean-expired-orders")
    public void cleanExpiredOrders() {
        log.info("开始清理过期订单...");
        int count = orderRepository.deleteByCreateTimeBefore(
            LocalDateTime.now().minusDays(30)
        );
        log.info("清理完成，共删除 {} 条过期订单", count);
    }
}
```

### 10.3 第三步：在管理平台配置任务

1. 访问 Crane 管理平台：`https://xxljob.mws.sankuai.com`
2. 点击"接入 AppKey"，配置你的服务 appKey
3. 点击"新建任务"，填写以下信息：

| 配置项 | 填写内容 | 说明 |
|-------|---------|------|
| 任务名 | com.sankuai.order.clean-expired-orders | 必须与代码中注解的值完全一致 |
| Cron 表达式 | 0 0 2 * * ? | 每天凌晨 2 点 |
| 工作方式 | 单节点 | 只在一台机器上执行 |
| 超时时间 | 300 秒 | 超过 5 分钟未完成则标记超时 |
| 失败重试次数 | 1 | 失败后重试 1 次 |

### 10.4 第四步：部署并验证

部署你的服务后，在管理平台的"注册列表"中确认你的机器已经注册成功（端口默认 8410）。然后你可以点击"手动触发"来测试任务是否正常执行。

### 10.5 常用 Cron 表达式速查表

| 表达式 | 含义 | 典型场景 |
|--------|------|---------|
| `0 0 * * *` | 每天凌晨 0 点 | 日终对账 |
| `0 0 2 * * ?` | 每天凌晨 2 点 | 数据清理、日志归档 |
| `0 9 * * 1-5` | 每周一到周五上午 9 点 | 晨会提醒 |
| `*/5 * * * *` | 每 5 分钟 | 缓存刷新 |
| `0 0 10,14,16 * * ?` | 每天 10 点、14 点、16 点 | 定时数据同步 |
| `30 2 1 * *` | 每月 1 号凌晨 2:30 | 月报生成 |
| `0 0 18 ? * FRI` | 每周五下午 6 点 | 周报发送 |
| `0 0 0 L * ?` | 每月最后一天凌晨 0 点 | 月末结算、发工资 |

---

## 十一、Crane 的不足与未来规划

### 11.1 当前不足

根据 Crane 2025 工作计划和技术架构 Review 文档，Crane 目前存在以下不足：

**1. 触发方式不够丰富**

Crontab 表达式无法表达"每隔 7 分钟执行一次"这样的固定频率需求。业界的 SchedulerX、PowerJob 已经支持 Fixed Rate 和 Second Delay。

**2. 缺乏分布式编程模型**

目前只支持静态分片。像 MapReduce 这样的动态分片计算模型尚未支持。业务侧已有类似需求。

**3. 大规模广播调度受限**

当前仅支持千级别以下节点数的广播调度。部分业务服务的节点数高达数千，无法满足需求。

**4. 任务可观测性不足**

缺乏任务执行报表（失败率、超时率、平均耗时等），执行记录保存较少，没有平台统一查看客户端执行日志的能力。

**5. 不具备异地容灾能力**

金服等对数据安全要求极高的业务，需要异地容灾能力，Crane 尚未支持。

**6. 多时区支持不便**

境外业务希望同一个任务可以配置多时区，目前还不支持。

### 11.2 未来规划

根据 Crane PRFAQ 中的长期规划：

| 时间范围 | 规划目标 |
|---------|---------|
| 半年内 | 聚焦在稳定性提升 |
| 1 年内 | 完成架构 3.0 的设计与落地 |
| 2 年内 | 支持任务中心、分布式计算模型（MapReduce）等新特性 |
| 3 年内 | 打造业内领先的任务调度中间件 |

2025 年的具体工作包括：
- 产品上区分分片任务与广播任务
- 无分片节点不进行调度指令的下发
- 治理分片默认值问题
- 探索广播任务的合理支持方式
- 优化分片任务，拆分分片任务与广播任务
- 完善 DAG 任务功能，支持 DAG 父任务在多集群执行
- 支持分布式编程模型

---

## 十二、核心概念速查表

| 概念 | 解释 |
|------|------|
| **Crane** | 美团统一的分布式任务调度中间件，由 Taurus 和 Mschedule 融合而来 |
| **Scheduler** | 调度器，Crane 最核心的模块，负责按 Cron 表达式触发任务 |
| **Manager** | 调度器管理器，负责 Slot 分配、租约发放、心跳检测 |
| **Portal** | 管理平台，提供 Web UI 和 HTTP 接口 |
| **Monitor** | 健康检测模块，对客户端进行心跳探活 |
| **Slot** | 虚拟槽，任务映射到调度器的中间层。多个任务映射到一个 Slot，多个 Slot 分配给一台调度器 |
| **Lease（租约）** | Manager 发给 Scheduler 的"调度许可证"，到期后 Scheduler 必须停止调度 |
| **BG** | Business Group（事业群），Crane 按 BG 隔离部署 |
| **Cron 表达式** | 描述定时任务执行时间规则的字符串，如 `0 0 2 * * ?` 表示每天凌晨 2 点 |
| **appKey** | 标识一个服务的唯一键，决定任务归属哪个 BG 集群 |
| **DAG** | Directed Acyclic Graph，有向无环图，用于描述任务之间的依赖关系 |
| **分片** | 将一个大任务拆成多个小任务分配到不同机器并行执行 |
| **广播** | 将任务发送到所有客户端机器同时执行 |
| **Meta-Server** | 元数据模块，代理客户端的注册/下线请求 |
| **DelayQueue** | Java 原生延迟队列，Crane 调度引擎的底层数据结构 |

---

## 十三、总结

让我们用一张图来总结 Crane 的全貌：

```
  ┌────────────────────────────────────────────────────────────────┐
  │                     Crane 分布式任务调度系统                      │
  ├────────────────────────────────────────────────────────────────┤
  │                                                                │
  │  【解决什么问题】                                                │
  │   传统定时任务的重复执行、单点故障、无法管理三大痛点                  │
  │                                                                │
  │  【核心架构】                                                    │
  │   Portal → Scheduler → 客户端                                  │
  │   Manager 管理 Scheduler（Slot 分配 + 租约机制）                  │
  │   Zookeeper 作为注册中心（弱依赖）                                │
  │   MySQL 存储元数据和执行记录（弱依赖）                             │
  │                                                                │
  │  【调度模型】                                                    │
  │   基于 Java DelayQueue 的延迟队列驱动                             │
  │   Cron 表达式 → 调度实例 → 延迟队列 → 到期触发 → 循环                │
  │                                                                │
  │  【高可用保障】                                                  │
  │   BG 隔离 + 3 机房部署 + N+1 容量                                │
  │   ZK 弱依赖 + DB 弱依赖 + 租约防脑裂                             │
  │                                                                │
  │  【任务类型】                                                    │
  │   周期任务：进程内 / Docker / Agent / DAG                         │
  │   延迟任务：指定延迟秒数后执行                                     │
  │                                                                │
  │  【调度方式】                                                    │
  │   单节点 / 全节点（广播）/ 全节点分片                               │
  │                                                                │
  │  【规模数据】                                                    │
  │   13,000+ 服务 | 19 万+ 任务 | 7,858 万次/日调度量                │
  │   平均延迟 < 20ms | TP99 < 100ms                                │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
```

Crane 从 2016 年诞生至今，已经走过了近 10 年的发展历程。从最初 Taurus 和 Mschedule 的简单融合，到如今支撑美团全公司近 8000 万次/日的调度量，它见证了美团从单体架构到微服务架构的演进。

作为一个分布式系统，Crane 在设计上充分体现了分布式系统的经典理念：通过虚拟 Slots 层实现任务的均匀分配，通过租约机制解决分布式环境下的脑裂问题，通过弱依赖设计保证核心功能的高可用。这些设计思想不仅适用于任务调度领域，对你理解其他分布式系统也大有裨益。

---

> **参考资料**
> - Crane 技术架构 Review（docs.sankuai.com/article/12345
> - Crane 架构设计文档（docs.sankuai.com/article/12345
> - Crane 分布式任务调度系统介绍（docs.sankuai.com/article/12345
> - Crane-PRFAQ（docs.sankuai.com/article/12345
> - Crane 产品简介（docs.sankuai.com/article/12345
> - Crane 高可用架构 2.0（docs.sankuai.com/article/12345
> - Crane 2025 工作计划（docs.sankuai.com/article/12345
> - Crane 容灾等级定义（docs.sankuai.com/article/12345
> - Crane 任务分片详解（docs.sankuai.com/article/12345
> - Crane FAQ（docs.sankuai.com/article/12345
> - Crane 接入文档（docs.sankuai.com/article/12345


---

## 十四、DelayQueue 延迟队列的源码级实现

在前面的章节中，我们提到了 Crane 的调度引擎基于 Java 原生的 `DelayQueue` 实现。但"基于 DelayQueue"这五个字背后，隐藏着大量精妙的设计。本节将从 JDK 源码级别，逐层剖析 DelayQueue 的实现原理，并对比时间轮算法，帮助你深入理解 Crane 调度引擎的底层基石。

### 14.1 Java PriorityQueue：DelayQueue 的底层数据结构

`DelayQueue` 是 JUC（java.util.concurrent）包下的一个延迟队列实现。它的核心特性是：**只有到期（delay expired）的元素才能被取出**。要理解 DelayQueue，首先需要理解它的底层数据结构——`PriorityQueue`（优先队列）。

#### 14.1.1 PriorityQueue 的本质：小顶堆

PriorityQueue 的底层是一个**小顶堆（Min-Heap）**，使用数组来实现。堆是一种特殊的完全二叉树，小顶堆的特点是：**每个父节点的值都小于或等于其子节点的值**。

```
  小顶堆示例（数组实现）：

  逻辑结构：                    数组存储：
  
       1                          [0] 1
      / \                         [1] 3
     3   5                        [2] 5
    / \   \                       [3] 7
   7   4   8                      [4] 4
                                  [5] 8

  对于任意节点 i：
    左子节点索引 = 2i + 1
    右子节点索引 = 2i + 2
    父节点索引   = (i - 1) / 2
```

在 Crane 的场景中，PriorityQueue 中存储的是**调度实例**，比较依据是**下次触发时间**（越早触发越在堆顶）。

#### 14.1.2 入队操作：siftUp

当一个新的调度实例被放入队列时，它会被添加到数组末尾，然后通过**上浮（siftUp）**操作调整堆结构：

```java
// JDK PriorityQueue.siftUp 源码（简化版）
private void siftUp(int k, E x) {
    Comparable<? super E> key = (Comparable<? super E>) x;
    while (k > 0) {
        int parent = (k - 1) >>> 1;  // 父节点索引
        Object e = queue[parent];
        if (key.compareTo((E) e) >= 0)  // 如果当前节点 >= 父节点，停止上浮
            break;
        queue[k] = e;  // 父节点下沉
        k = parent;    // 继续向上比较
    }
    queue[k] = key;
}
```

用图来理解上浮过程（假设新元素"2"插入到堆底）：

```
  插入前：          插入"2"到末尾：       siftUp 过程：         最终结果：

       1                  1                    1                    1
      / \                / \                  / \                  / \
     3   5              3   5                3   5                2   5
    / \                / \                  / \                  / \
   7   4              7   4                7   2                7   3
                       \                    \
                        2                    4

  (1) 新元素在末尾    (2) 2 < 3(父节点)     (3) 交换后继续        (4) 2 > 1，停止
      位置不合理        上浮                  2 < 3，继续上浮       上浮完成
```

**时间复杂度**：O(log n)，n 为队列中元素数量。

#### 14.1.3 出队操作：siftDown

取出堆顶元素（最早到期的调度实例）后，需要将数组末尾的元素移到堆顶，然后通过**下沉（siftDown）**操作重建堆：

```java
// JDK PriorityQueue.siftDown 源码（简化版）
private void siftDown(int k, E x) {
    int half = size >>> 1;  // 只需要遍历到非叶子节点
    while (k < half) {
        int child = (k << 1) + 1;  // 左子节点索引
        Object c = queue[child];
        int right = child + 1;
        if (right < size && 
            ((Comparable<? super E>) c).compareTo((E) queue[right]) > 0)
            c = queue[child = right];  // 取较小的子节点
        if (((Comparable<? super E>) x).compareTo((E) c) <= 0)
            break;  // 如果当前节点 <= 最小子节点，停止下沉
        queue[k] = c;  // 子节点上浮
        k = child;     // 继续向下比较
    }
    queue[k] = x;
}
```

**时间复杂度**：同样为 O(log n)。

#### 14.1.4 在 Crane 中的应用

Crane 中每个调度实例包装为一个 `Delayed` 接口的实现类：

```java
// Crane 中调度实例的简化模型
public class ScheduleInstance implements Delayed {
    
    private String taskName;           // 任务名
    private long triggerTime;          // 下次触发时间（毫秒时间戳）
    private String cronExpression;     // Cron 表达式
    private Map<String, String> params; // 任务参数
    
    @Override
    public long getDelay(TimeUnit unit) {
        long diff = triggerTime - System.currentTimeMillis();
        return unit.convert(diff, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public int compareTo(Delayed o) {
        if (o instanceof ScheduleInstance) {
            return Long.compare(this.triggerTime, 
                ((ScheduleInstance) o).triggerTime);
        }
        return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), 
            o.getDelay(TimeUnit.MILLISECONDS));
    }
}
```

**关键点**：
- `getDelay()` 返回剩余延迟时间，DelayQueue 通过它判断元素是否到期
- `compareTo()` 定义排序规则，保证最早触发的实例在堆顶
- 当 `getDelay()` 返回值 <= 0 时，元素可以被 `take()` 或 `poll()` 取出

### 14.2 DelayQueue 的核心源码剖析

#### 14.2.1 take() 方法：阻塞式获取到期元素

```java
// JDK DelayQueue.take() 源码（简化版）
public E take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
        for (;;) {
            E first = q.peek();  // 查看堆顶元素（不出队）
            if (first == null)
                available.await();  // 队列为空，无限等待
            else {
                long delay = first.getDelay(NANOSECONDS);
                if (delay <= 0)
                    return q.poll();  // 已到期，直接出队
                // 未到期，等待 delay 纳秒
                first = null;  // 等待期间释放引用
                if (leader != null)
                    available.await();  // 已有线程在等待，无限等待
                else {
                    Thread thisThread = Thread.currentThread();
                    leader = thisThread;
                    try {
                        available.awaitNanos(delay);  // 等待到期
                    } finally {
                        if (leader == thisThread)
                            leader = null;
                    }
                }
            }
        }
    } finally {
        if (leader == null && q.peek() != null)
            available.signal();  // 唤醒下一个等待线程
        lock.unlock();
    }
}
```

**Leader-Follower 模式**：DelayQueue 使用了一种称为 Leader-Follower 的线程模式来优化性能。同一时刻只有一个线程（Leader）在等待堆顶元素到期，其他线程（Follower）无限等待。当 Leader 取到元素后，会唤醒一个 Follower 成为新的 Leader。这避免了所有线程都定时唤醒造成的不必要竞争。

```
  DelayQueue.take() 线程模型：

  线程1（Leader）     线程2（Follower）    线程3（Follower）
       │                   │                   │
       │ awaitNanos(5s)    │ await()           │ await()
       │                   │                   │
       │ ...5秒后...       │                   │
       │                   │                   │
       │ 取到元素           │                   │
       │ signal() ──────────> 成为Leader        │
       │                   │ awaitNanos(3s)    │
       │                   │                   │
       │ 取到元素           │                   │
       │                   │ signal() ──────────> 成为Leader
       │                   │                   │ awaitNanos(10s)
```

#### 14.2.2 offer() 方法：入队操作

```java
// JDK DelayQueue.offer() 源码（简化版）
public boolean offer(E e) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        q.offer(e);  // 调用 PriorityQueue.offer，执行 siftUp
        if (q.peek() == e) {  // 如果新元素成为堆顶
            leader = null;
            available.signal();  // 唤醒等待的 Leader 线程
        }
        return true;
    } finally {
        lock.unlock();
    }
}
```

**关键点**：如果新入队的元素比当前堆顶更早到期（成为新的堆顶），需要唤醒正在等待的 Leader 线程，让它重新计算等待时间。这在 Crane 中的场景是：当一个新的调度实例被放入队列，且它的触发时间比当前堆顶更早时，调度线程需要重新调整等待时间。

### 14.3 DelayQueue vs 时间轮：算法对比

DelayQueue 虽然性能不错，但在极高并发场景下，时间轮（HashedWheelTimer）是另一种常见选择。让我们深入对比这两种算法。

#### 14.3.1 时间轮算法原理

时间轮的原理类似钟表：一个固定大小的数组（轮），每个槽位代表一个时间刻度。指针按固定速度转动，转到某个槽位时，执行该槽位中的所有任务。

```
  简单时间轮（8 个槽位，每个槽位 1 秒）：

       0
     /   \
    7     1    ← 指针当前位置在 0
    |     |
    6     2
     \   /
       5
       |
       4 - 3

  槽位 0: [Task-A, Task-B]  ← 当前秒要执行的任务
  槽位 1: [Task-C]          ← 下一秒执行
  槽位 2: []
  槽位 3: [Task-D]          ← 3 秒后执行
  槽位 4: []
  槽位 5: [Task-E, Task-F]  ← 5 秒后执行
  ...
```

对于延迟时间超过一轮的情况，需要使用**分层时间轮**（Hierarchical Timing Wheel）：

```
  分层时间轮：

  ┌─────────────────────────────────────────────────┐
  │  秒级时间轮（60 格，每格 1 秒）                     │
  │  当秒针走完一圈，分钟轮 +1                          │
  ├─────────────────────────────────────────────────┤
  │  分级时间轮（60 格，每格 1 分钟）                    │
  │  当分针走完一圈，小时轮 +1                          │
  ├─────────────────────────────────────────────────┤
  │  时级时间轮（24 格，每格 1 小时）                    │
  │  当时针走完一圈，天轮 +1                            │
  ├─────────────────────────────────────────────────┤
  │  天级时间轮（N 格，每格 1 天）                      │
  └─────────────────────────────────────────────────┘

  示例：延迟 2 小时 30 分 15 秒的任务
  → 先放入小时轮的第 2 格
  → 2 小时后，降级到分钟轮的第 30 格
  → 30 分钟后，降级到秒轮的第 15 格
  → 15 秒后，执行
```

#### 14.3.2 性能对比

| 维度 | DelayQueue（堆） | 时间轮 |
|------|-----------------|--------|
| **入队时间复杂度** | O(log n) | O(1) |
| **出队时间复杂度** | O(log n) | O(1) |
| **内存开销** | 数组，紧凑存储 | 固定大小数组 + 链表 |
| **适合场景** | 任务数量适中（万级），到期时间分散 | 任务数量极大（百万级），到期时间密集 |
| **精度** | 纳秒级 | 取决于格子大小（通常毫秒级） |
| **实现复杂度** | JDK 原生，简单 | 需自行实现分层逻辑 |

#### 14.3.3 Crane 为什么选择 DelayQueue？

Crane 选择 DelayQueue 而非时间轮，有以下原因：

1. **任务规模适中**：单个调度器承载的任务数在万级别（最大 77,000），DelayQueue 的 O(log n) 完全够用
2. **到期时间分散**：周期任务的触发时间分布在不同时间点，不像延迟消息那样密集
3. **JDK 原生支持**：无需额外引入依赖，且经过充分测试
4. **精度高**：纳秒级精度，满足 TP99 < 100ms 的要求
5. **Leader-Follower 模式**：天然支持多线程消费，且线程竞争小

如果未来 Crane 需要支持百万级延迟任务，时间轮会是更好的选择。事实上，Crane 的延迟任务模块（虽然已停止新接入）在内部就使用了类似时间轮的设计。

### 14.4 Crane 调度引擎中的 DelayQueue 使用模式

```java
// Crane 调度引擎核心循环（简化模型）
public class ScheduleEngine {
    
    private final DelayQueue<ScheduleInstance> delayQueue = new DelayQueue<>();
    private final ExecutorService dispatchExecutor;
    
    /**
     * 调度引擎启动
     */
    public void start() {
        // 1. 加载所有归属当前调度器的任务
        List<TaskConfig> tasks = taskRepository.findTasksBySchedulerId(getSchedulerId());
        
        for (TaskConfig task : tasks) {
            // 2. 为每个任务计算下一次触发时间，生成调度实例
            long nextTriggerTime = CronExpressionUtil.getNextTriggerTime(
                task.getCronExpression(), System.currentTimeMillis());
            ScheduleInstance instance = new ScheduleInstance(
                task.getName(), nextTriggerTime, task.getCronExpression());
            delayQueue.offer(instance);
        }
        
        // 3. 启动消费线程
        dispatchExecutor.submit(this::dispatchLoop);
    }
    
    /**
     * 调度循环
     */
    private void dispatchLoop() {
        while (running) {
            try {
                // 阻塞等待，直到有到期的调度实例
                ScheduleInstance instance = delayQueue.take();
                
                // 4. 发送调度指令到客户端
                dispatchToClient(instance);
                
                // 5. 计算下一次触发时间，生成新实例放回队列
                long nextTime = CronExpressionUtil.getNextTriggerTime(
                    instance.getCronExpression(), instance.getTriggerTime());
                ScheduleInstance nextInstance = new ScheduleInstance(
                    instance.getTaskName(), nextTime, instance.getCronExpression());
                delayQueue.offer(nextInstance);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
```

这个核心循环体现了 Crane 调度引擎的精髓：**自驱动的闭环调度**。每次消费完一个调度实例后，立即生成下一次的实例放回队列，形成一个永不停歇的循环。

---

## 十五、调度器线程模型详解

### 15.1 线程模型全景

Crane 调度器内部维护了多组线程池，各司其职。理解这些线程模型，对于排查调度延迟、性能调优至关重要。

```
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Scheduler 线程模型总览                         │
  ├──────────────────────────────────────────────────────────────────┤
  │                                                                  │
  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────────────┐   │
  │  │ 调度线程池    │   │ 派发线程池    │   │  回写线程池           │   │
  │  │ schedule-   │   │ dispatch-   │   │  callback-          │   │
  │  │ thread-pool │   │ thread-pool │   │  thread-pool        │   │
  │  │             │   │             │   │                     │   │
  │  │ 从DelayQueue│   │ HTTP调用    │   │ 接收客户端执行结果     │   │
  │  │ 取出到期实例 │──>│ 发送给客户端 │──>│ 写入DB和内存          │   │
  │  │ 1个核心线程  │   │ N个工作线程 │   │ M个工作线程           │   │
  │  └─────────────┘   └─────────────┘   └─────────────────────┘   │
  │                                                                  │
  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────────────┐   │
  │  │ 租约管理线程  │   │ ZK监听线程   │   │  监控上报线程         │   │
  │  │ lease-      │   │ watcher-   │   │  metrics-           │   │
  │  │ thread      │   │ thread     │   │  thread             │   │
  │  │             │   │             │   │                     │   │
  │  │ 定时续租     │   │ 监听ZK事件  │   │ 定时上报指标         │   │
  │  │ 检查租约状态 │   │ 处理客户端   │   │ 到监控系统           │   │
  │  │ 1个守护线程  │   │ 上下线      │   │ 1个守护线程          │   │
  │  └─────────────┘   └─────────────┘   └─────────────────────┘   │
  │                                                                  │
  └──────────────────────────────────────────────────────────────────┘
```

### 15.2 调度线程池（Schedule Thread Pool）

调度线程池是整个引擎的心脏。它负责从 DelayQueue 中取出到期的调度实例，并将其交给派发线程池。

```java
// 调度线程池配置
public class ScheduleThreadPool {
    
    // 核心调度线程：只有 1 个，避免多线程并发消费 DelayQueue 造成竞争
    private final Thread scheduleThread;
    
    // 调度线程的核心逻辑
    class ScheduleWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    // 从 DelayQueue 阻塞获取到期的调度实例
                    ScheduleInstance instance = delayQueue.take();
                    
                    // 提交给派发线程池异步执行
                    dispatchThreadPool.submit(() -> {
                        try {
                            dispatchToClient(instance);
                        } catch (Exception e) {
                            log.error("调度派发失败, task={}", 
                                instance.getTaskName(), e);
                            handleDispatchFailure(instance, e);
                        }
                    });
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
```

**为什么调度线程只有 1 个？**

DelayQueue 的 `take()` 方法内部使用了 Leader-Follower 模式，同一时刻只有一个线程在等待堆顶元素到期。多线程消费不仅不会提升性能，反而会增加锁竞争。因此，调度线程设置为 1 个，取出实例后立即交给独立的派发线程池异步处理，实现**取与派的解耦**。

### 15.3 派发线程池（Dispatch Thread Pool）

派发线程池负责将调度指令通过 HTTP 发送给客户端机器。由于网络 IO 是主要瓶颈，这里使用的是基于 NIO 的异步 HTTP 客户端。

```java
// 派发线程池配置
public class DispatchThreadPool {
    
    // 派发线程池参数
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        32,                              // 核心线程数
        64,                              // 最大线程数
        60, TimeUnit.SECONDS,            // 空闲存活时间
        new LinkedBlockingQueue<>(2000), // 任务队列容量
        new ThreadFactoryBuilder()
            .setNameFormat("dispatch-worker-%d")
            .setDaemon(true)
            .build(),
        new CallerRunsPolicy()           // 拒绝策略：调用者执行
    );
    
    // 派发逻辑
    public void dispatch(ScheduleInstance instance) {
        // 选择目标客户端
        List<String> targetIps = selectTargetIps(instance);
        
        for (String ip : targetIps) {
            executor.submit(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    // 构建 HTTP 请求
                    DispatchRequest request = DispatchRequest.builder()
                        .taskName(instance.getTaskName())
                        .ip(ip)
                        .params(instance.getParams())
                        .timeout(instance.getTimeout())
                        .build();
                    
                    // 发送调度指令
                    DispatchResponse response = httpClient.execute(request);
                    
                    // 记录调度延迟
                    long dispatchLatency = System.currentTimeMillis() 
                        - instance.getTriggerTime();
                    metrics.recordDispatchLatency(dispatchLatency);
                    
                    if (!response.isSuccess()) {
                        log.warn("调度指令发送失败, task={}, ip={}, code={}", 
                            instance.getTaskName(), ip, response.getCode());
                    }
                } catch (Exception e) {
                    log.error("调度派发异常, task={}, ip={}", 
                        instance.getTaskName(), ip, e);
                }
            });
        }
    }
}
```

### 15.4 拒绝策略与背压机制

当调度高峰期到来，派发线程池的队列可能被填满。Crane 设计了一套完善的背压机制：

```
  背压触发流程：

  DelayQueue 到期 ──> 派发线程池 ──> 队列已满？
                                        │
                              ┌─────────┴─────────┐
                              │                   │
                            否                   是
                              │                   │
                       正常提交任务           触发拒绝策略
                                                    │
                                          ┌─────────┴─────────┐
                                          │                   │
                                    CallerRunsPolicy      记录告警
                                    (调度线程自己执行)      上报背压指标
                                          │
                                    降低派发速度
                                    (天然限流效果)
```

**四种内置拒绝策略对比**：

| 拒绝策略 | 行为 | Crane 适用性 |
|---------|------|-------------|
| AbortPolicy | 抛出 RejectedExecutionException | 不适用，会导致调度线程崩溃 |
| CallerRunsPolicy | 由调用线程（调度线程）执行 | **Crane 默认策略**，天然限流 |
| DiscardPolicy | 静默丢弃任务 | 不适用，任务丢失 |
| DiscardOldestPolicy | 丢弃队列最老的任务 | 不适用，打乱调度顺序 |

**CallerRunsPolicy 的妙用**：当队列满时，调度线程自己来执行派发任务。这意味着调度线程在执行 HTTP 调用期间无法从 DelayQueue 取下一个实例，形成天然的**背压效果**——当下游消费不过来时，上游自动减速。

```java
// Crane 自定义的增强版拒绝策略
public class CraneRejectionHandler implements RejectedExecutionHandler {
    
    private final MetricsCollector metrics;
    private final AlarmService alarmService;
    
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        // 1. 上报背压指标
        metrics.incrementCounter("dispatch.rejected");
        
        // 2. 如果连续触发拒绝，发送告警
        if (metrics.getCounterRate("dispatch.rejected", 60) > 100) {
            alarmService.send(AlarmLevel.WARN, 
                "调度器派发队列积压，可能影响调度延迟");
        }
        
        // 3. 由调度线程自己执行（CallerRunsPolicy 效果）
        r.run();
    }
}
```

### 15.5 线程池参数调优建议

```
  派发线程池参数调优决策树：

  任务数量 < 5,000
    └── 核心线程 16, 队列 1000
        └── 正常场景

  任务数量 5,000 ~ 30,000
    └── 核心线程 32, 队列 2000
        └── 高峰期可考虑 48 核心

  任务数量 > 30,000
    └── 核心线程 64, 队列 4000
        └── 需配合背压监控
        └── 考虑拆分调度器集群
```

| 参数 | 推荐值（小规模） | 推荐值（中规模） | 推荐值（大规模） |
|------|----------------|----------------|----------------|
| 核心线程数 | 16 | 32 | 64 |
| 最大线程数 | 32 | 64 | 128 |
| 队列容量 | 1,000 | 2,000 | 4,000 |
| 空闲存活时间 | 60s | 60s | 120s |
| 拒绝策略 | CallerRunsPolicy | CallerRunsPolicy | 自定义增强版 |

---

## 十六、Slot 虚拟槽分配算法深入

### 16.1 一致性哈希：Slot 分配的理论基础

Crane 的 Slot 分配机制，本质上是一致性哈希（Consistent Hashing）思想的一种变体实现。要深入理解 Slot 机制，我们需要先理解一致性哈希。

#### 16.1.1 传统哈希分配的问题

假设有 5 台调度器，使用简单的取模分配：

```
  传统取模分配：
  hash(taskName) % 5 = slot

  调度器列表：[S1, S2, S3, S4, S5]

  task-A: hash("task-A") % 5 = 2 → S3
  task-B: hash("task-B") % 5 = 0 → S1
  task-C: hash("task-C") % 5 = 4 → S5
```

问题在于：当调度器数量变化时（扩容/缩容），**几乎所有任务都需要重新映射**：

```
  S5 宕机后，调度器变为 4 台：
  hash(taskName) % 4 = slot

  task-A: hash("task-A") % 4 = 0 → S1  (原来在 S3，变了！)
  task-B: hash("task-B") % 4 = 0 → S1  (原来在 S1，没变)
  task-C: hash("task-C") % 4 = 0 → S1  (原来在 S5，变了！)
```

在 Crane 中，任务重新映射意味着调度实例需要从一台调度器迁移到另一台，迁移过程中可能丢失正在等待的调度实例。因此，我们需要一种**调度器变化时影响最小**的分配算法。

#### 16.1.2 一致性哈希算法

一致性哈希的核心思想：将整个哈希值空间组织成一个虚拟的圆环（哈希环），将节点和 key 都映射到这个环上。

```
  一致性哈希环（0 ~ 2^32 - 1）：

           0
         /   \
    2^32      A（哈希值: 0x0F...）
       |       |
       |   B（哈希值: 0x3A...）
       |       |
    D（0xC0...） 
       |       |
       |   C（哈希值: 0x80...）
         \   /
          2^31

  路由规则：key 顺时针找到的第一个节点就是其归属节点
  
  task-A → 哈希值 0x20... → 顺时针找到 B
  task-B → 哈希值 0x50... → 顺时针找到 C
  task-C → 哈希值 0x90... → 顺时针找到 D
  task-D → 哈希值 0xD0... → 顺时针找到 A
```

**一致性哈希的优势**：当节点增减时，只影响相邻区间的 key 映射，不会全局重分配。

```
  B 宕机后：

           0
         /   \
    2^32      A
       |       |
       |      [B 消失]
       |       |
    D          |
       |       |
       |   C
         \   /
          2^31

  task-A → 顺时针找 → 现在找到 C（原来是 B）
  task-B → 顺时针找 → 找到 C（原来是 C，没变）
  task-C → 顺时针找 → 找到 D（没变）
  task-D → 顺时针找 → 找到 A（没变）
  
  只有 task-A 受到影响！
```

#### 16.1.3 虚拟节点解决数据倾斜

一致性哈希有一个问题：当节点数量较少时，节点在环上的分布可能不均匀，导致数据倾斜。

```
  无虚拟节点（3 个节点，分布不均匀）：

  哈希环上 A、B、C 分布稀疏
  → A 负责的区间可能占了 60%
  → B 负责的区间只有 15%
  → 数据严重倾斜！


  有虚拟节点（每个物理节点对应 150 个虚拟节点）：

  哈希环上密密麻麻分布了 450 个虚拟节点
  → A 的 150 个虚拟节点均匀分布
  → B 的 150 个虚拟节点均匀分布
  → C 的 150 个虚拟节点均匀分布
  → 每个物理节点大约负责 33% 的区间
```

### 16.2 Crane 的 Slot 机制：一致性哈希的工程化实现

Crane 没有直接使用一致性哈希环，而是使用了一种更简单、更可控的方式——**固定数量的虚拟槽（Slot）**。

#### 16.2.1 Slot 机制的核心设计

```
  Crane Slot 分配机制：

  ┌─────────────────────────────────────────────────────┐
  │  第一步：定义固定数量的 Slot（例如 1024 个）            │
  │  Slot-0, Slot-1, Slot-2, ..., Slot-1023              │
  ├─────────────────────────────────────────────────────┤
  │  第二步：任务映射到 Slot                               │
  │  slot = hash(taskName) % 1024                        │
  │  task-A → hash("task-A") % 1024 = 357 → Slot-357    │
  │  task-B → hash("task-B") % 1024 = 892 → Slot-892    │
  ├─────────────────────────────────────────────────────┤
  │  第三步：Slot 分配给调度器                             │
  │  Manager 将 1024 个 Slot 平均分配给各调度器            │
  │  3 台调度器：                                          │
  │  S1 → Slot 0~340     (341 个)                        │
  │  S2 → Slot 341~681   (341 个)                        │
  │  S3 → Slot 682~1023  (342 个)                        │
  ├─────────────────────────────────────────────────────┤
  │  第四步：调度器扩容/缩容时，只需调整 Slot 分配           │
  │  新增 S4：                                             │
  │  S1 → Slot 0~255     (256 个)                        │
  │  S2 → Slot 256~511   (256 个)                        │
  │  S3 → Slot 512~767   (256 个)                        │
  │  S4 → Slot 768~1023  (256 个)                        │
  │  只有被迁出的 Slot 对应的任务需要迁移！                  │
  └─────────────────────────────────────────────────────┘
```

#### 16.2.2 与一致性哈希的对比

| 维度 | 一致性哈希 | Crane Slot 机制 |
|------|----------|----------------|
| **分配粒度** | 连续区间 | 离散槽位 |
| **节点变化影响范围** | 相邻区间 | 可精确控制 |
| **负载均衡** | 依赖虚拟节点数量 | 天然均匀（取模分配） |
| **可运维性** | 难以人工干预 | 可指定 Slot 迁移 |
| **实现复杂度** | 较高 | 较低 |

Crane 选择 Slot 机制的关键原因之一是**可运维性**。在一致性哈希中，节点变化导致的影响是隐式的、难以预测的。而在 Slot 机制中，管理员可以精确地知道"Slot 357 正在从 S1 迁移到 S4"，并且可以控制迁移的速度和时机。

#### 16.2.3 Slot 迁移过程

当调度器扩容或缩容时，Slot 需要在调度器之间迁移。Crane 的 Slot 迁移过程如下：

```
  Slot 迁移流程（S4 新加入）：

  阶段1：Slot 分配调整
  ┌───────────────────────────────────────────┐
  │ Manager 重新计算 Slot 分配方案               │
  │ S1: Slot 0~255                            │
  │ S2: Slot 256~511                          │
  │ S3: Slot 512~767                          │
  │ S4: Slot 768~1023  ← 新分配给 S4            │
  └───────────────────────────────────────────┘

  阶段2：通知旧调度器释放 Slot
  ┌───────────────────────────────────────────┐
  │ Manager → S3: "释放 Slot 768~1023"          │
  │ S3 收到通知后：                               │
  │   1. 停止对 Slot 768~1023 内任务的调度        │
  │   2. 将这些任务的调度实例序列化               │
  │   3. 通过 DB 或直接传输给 S4                 │
  └───────────────────────────────────────────┘

  阶段3：通知新调度器接管 Slot
  ┌───────────────────────────────────────────┐
  │ Manager → S4: "接管 Slot 768~1023"          │
  │ S4 收到通知后：                               │
  │   1. 加载 Slot 768~1023 内的任务列表          │
  │   2. 为每个任务生成调度实例                   │
  │   3. 将调度实例放入本地 DelayQueue           │
  │   4. 开始正常调度                             │
  └───────────────────────────────────────────┘

  阶段4：验证与清理
  ┌───────────────────────────────────────────┐
  │ Manager 确认 S4 已成功接管                    │
  │ 通知 S3 清理已迁移任务的本地缓存               │
  │ 迁移完成                                     │
  └───────────────────────────────────────────┘
```

#### 16.2.4 Slot 数量如何确定？

Slot 数量的选择需要权衡：

| Slot 数量 | 优点 | 缺点 |
|----------|------|------|
| 太少（如 16） | 迁移速度快 | 负载不均匀 |
| 适中（如 1024） | 均衡且可控 | 迁移有一定开销 |
| 太多（如 65536） | 极度均匀 | 管理开销大 |

Crane 默认使用 **1024 个 Slot**，这对于最多支持数十台调度器的场景已经足够。假设有 10 台调度器，每台分配约 100 个 Slot，每个 Slot 平均承载约 190 个任务（以 19 万任务计），粒度适中。

### 16.3 负载均衡策略

除了基本的平均分配外，Crane 还考虑了以下负载均衡因素：

```
  负载均衡考量维度：

  ┌──────────────────────────────────────────────────────────────┐
  │                     Manager 负载均衡决策                       │
  ├──────────────────────────────────────────────────────────────┤
  │                                                              │
  │  1. Slot 数量均衡                                             │
  │     每台调度器分到的 Slot 数量尽量相等                          │
  │                                                              │
  │  2. 任务数量均衡                                               │
  │     不同 Slot 上的任务数量不同，需要考虑实际任务数               │
  │     Slot-A: 500 个任务                                        │
  │     Slot-B: 5 个任务                                          │
  │     → 不能只看 Slot 数量                                       │
  │                                                              │
  │  3. 调度频率均衡                                               │
  │     有些任务每秒调度一次，有些每天一次                           │
  │     高频任务集中在同一台调度器会导致负载不均                     │
  │                                                              │
  │  4. 调度器性能差异                                             │
  │     不同机器配置的调度器承载能力不同                             │
  │     → 按权重分配 Slot                                          │
  │                                                              │
  └──────────────────────────────────────────────────────────────┘
```

---

## 十七、租约协议的详细设计

### 17.1 租约协议核心参数

租约（Lease）是 Crane 防脑裂的核心机制。理解租约协议，需要先搞清楚几个关键参数：

| 参数 | 含义 | Crane 典型值 |
|------|------|-------------|
| Lease Period（租约期） | 租约的有效时长 | 15 秒 |
| Renew Window（续约窗口） | 在租约到期前多久发起续约 | 10 秒（即到期前 5 秒） |
| Grace Period（宽限期） | 租约到期后的容忍时间 | 0 秒（立即停止） |
| Heartbeat Interval（心跳间隔） | Manager 发起心跳的频率 | 5 秒 |
| Max Heartbeat Failures（最大心跳失败次数） | 连续失败几次判定宕机 | 3 次 |

### 17.2 租约时间线详解

```
  租约生命周期时间线：

  时间轴 ──────────────────────────────────────────────────────────→

  T=0s          T=5s          T=10s         T=15s         T=20s
   │             │             │             │             │
   │             │             │             │             │
   ▼             ▼             ▼             ▼             ▼
   
  [发放租约]   [心跳1]      [心跳2]      [租约到期]    [Slot重分配]
   有效期15s    续租成功      续租成功     ↓             ↓
                              ↓           Scheduler     Manager
                              续约窗口     停止调度       摘除Scheduler
                              T=10s        ↓             迁移Slot
                              发起续约     等待恢复       
   
  ┌─────────────────────────────────────────────────┐
  │  正常情况：心跳每 5 秒一次，租约每 15 秒续期一次    │
  │  续约窗口在 T=10s（租约到期前 5 秒）               │
  │  续约成功 → 租约延长至 T=25s                      │
  └─────────────────────────────────────────────────┘


  异常情况：网络分区

  T=0s          T=5s          T=10s         T=15s
   │             │             │             │
   ▼             ▼             ▼             ▼
   
  [发放租约]   [心跳1:失败!]  [心跳2:失败!]  [租约到期]
   有效期15s    Manager记录    Manager记录    ↓
                失败1次        失败2次        双方同时感知：
                                ↓             Manager: 重分配Slot
                              [心跳3:失败!]   Scheduler: 停止调度
                                ↓
                              失败3次 → 判定宕机
                              （但此时租约也刚好到期）
```

### 17.3 时钟偏移处理

在分布式系统中，不同机器的时钟可能存在偏差。如果 Manager 的时钟比 Scheduler 快 5 秒，Manager 认为租约已到期，但 Scheduler 还认为还有 5 秒——这就可能导致调度空窗或重复调度。

Crane 处理时钟偏移的策略：

```
  时钟偏移处理策略：

  ┌─────────────────────────────────────────────────────────────┐
  │  策略1：NTP 时钟同步                                         │
  │  所有机器通过 NTP 服务同步时钟，保证时钟偏差 < 50ms           │
  │  → 基础保障，但不作为唯一手段                                  │
  ├─────────────────────────────────────────────────────────────┤
  │  策略2：租约时间预留安全余量                                   │
  │  Manager 判定租约到期时间 = 实际到期时间 + 安全余量(2s)        │
  │  Scheduler 停止调度时间 = 实际到期时间 - 安全余量(2s)          │
  │  → Scheduler 先停，Manager 后摘，形成"安全间隙"               │
  ├─────────────────────────────────────────────────────────────┤
  │  策略3：双向确认机制                                           │
  │  Manager 在摘除调度器前，先通过 ZK 和直接心跳双重确认          │
  │  只有两者都失败才执行摘除                                      │
  └─────────────────────────────────────────────────────────────┘
```

```java
// 租约管理器简化实现
public class LeaseManager {
    
    private static final long LEASE_PERIOD_MS = 15_000;      // 租约期 15s
    private static final long RENEW_WINDOW_MS = 10_000;      // 续约窗口 10s
    private static final long SAFETY_MARGIN_MS = 2_000;      // 安全余量 2s
    private static final long HEARTBEAT_INTERVAL_MS = 5_000; // 心跳间隔 5s
    
    // Manager 侧：判定调度器租约是否过期
    public boolean isLeaseExpired(SchedulerInfo scheduler) {
        long now = System.currentTimeMillis();
        // Manager 多等安全余量时间，避免因时钟偏移提前摘除
        long expiryTime = scheduler.getLeaseStartTime() 
            + LEASE_PERIOD_MS + SAFETY_MARGIN_MS;
        return now > expiryTime;
    }
    
    // Scheduler 侧：判定自身租约是否过期
    public boolean isMyLeaseExpired() {
        long now = System.currentTimeMillis();
        // Scheduler 少算安全余量时间，提前停止调度
        long expiryTime = myLeaseStartTime 
            + LEASE_PERIOD_MS - SAFETY_MARGIN_MS;
        return now > expiryTime;
    }
    
    // Scheduler 侧：续约线程
    class RenewWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    long elapsed = now - myLeaseStartTime;
                    
                    if (elapsed >= RENEW_WINDOW_MS) {
                        // 进入续约窗口，发起续约
                        boolean renewed = managerClient.renewLease(getSchedulerId());
                        if (renewed) {
                            myLeaseStartTime = now;  // 续约成功，更新起始时间
                            log.debug("租约续期成功");
                        } else {
                            log.warn("租约续期失败");
                        }
                    }
                    
                    if (isMyLeaseExpired()) {
                        // 租约过期，停止调度！
                        log.error("租约已过期，停止所有调度");
                        stopScheduling();
                        // 等待 Manager 重新分配，或人工介入
                        waitForRecovery();
                    }
                    
                    Thread.sleep(1000);  // 每秒检查一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
```

### 17.4 租约与 Slot 的联动

租约和 Slot 是紧密耦合的。理解它们的联动关系，是理解 Crane 高可用设计的关键：

```
  租约与 Slot 联动状态机：

  ┌─────────────┐
  │  INITIALIZING │ ── 调度器启动中
  └──────┬──────┘
         │ 向 Manager 申请租约
         ▼
  ┌─────────────┐
  │   LEASING    │ ── 等待 Manager 发放租约
  └──────┬──────┘
         │ 收到租约 + Slot 分配
         ▼
  ┌─────────────┐     续约成功
  │   ACTIVE     │ ◄────────── 续约线程
  │ (正常调度)    │
  └──────┬──────┘
         │ 续约失败 / 租约过期
         ▼
  ┌─────────────┐
  │  SUSPENDED   │ ── 停止调度，保留 Slot 状态
  │ (停止调度)    │
  └──────┬──────┘
         │ 收到新租约
         ▼
  ┌─────────────┐
  │   ACTIVE     │ ── 恢复调度
  └─────────────┘
         │ 调度器关闭
         ▼
  ┌─────────────┐
  │  TERMINATED  │ ── 释放所有 Slot，通知 Manager
  └─────────────┘
```

### 17.5 租约机制的工程细节

#### 17.5.1 租约的存储

租约信息存储在两个地方：

1. **Manager 内存**：`Map<SchedulerId, LeaseInfo>`，用于快速判断租约状态
2. **Zookeeper 临时节点**：`/xxljob/scheduler/{schedulerId}/lease`，用于跨 Manager 节点同步

```
  ZK 中的租约信息结构：

  /xxljob
    └── scheduler
        ├── scheduler-1
        │   ├── info        (持久节点: IP, port, BG)
        │   └── lease       (临时节点: leaseId, expireTime)
        ├── scheduler-2
        │   ├── info
        │   └── lease
        └── manager
            └── master      (临时节点: 当前主 Manager 信息)
```

当 ZK 临时节点消失时（Session 过期），Manager 可以立即感知到调度器下线。

#### 17.5.2 网络分区恢复后的处理

当网络分区恢复后，被摘除的调度器重新连上 Manager，此时需要做以下处理：

```
  网络分区恢复处理流程：

  1. Scheduler 网络恢复
     │
     ▼
  2. Scheduler 向 Manager 发送重新注册请求
     │
     ▼
  3. Manager 检查当前 Slot 分配情况
     ├── 如果 Slot 已被分配给其他调度器
     │   └── 给该 Scheduler 分配新的 Slot（如果需要）
     │   └── 通知该 Scheduler 加载新 Slot 的任务
     │
     └── 如果 Slot 仍空闲
         └── 重新发放租约，恢复原有 Slot 分配
     │
     ▼
  4. Scheduler 清理旧的调度状态
     └── 移除所有本地缓存的任务和调度实例
     │
     ▼
  5. Scheduler 加载新分配的 Slot 对应的任务
     └── 生成调度实例，放入 DelayQueue
     │
     ▼
  6. 恢复正常调度
```

这个流程确保了网络分区恢复后不会出现"幽灵调度"——即被摘除的调度器恢复后不会继续按旧的 Slot 分配进行调度。

---

## 十八、DAG 任务编排引擎实现

### 18.1 DAG 基础概念回顾

DAG（Directed Acyclic Graph，有向无环图）是描述任务依赖关系的经典数据结构。在 Crane 中，DAG 任务允许用户定义多个子任务之间的执行顺序和依赖关系。

```
  DAG 示例：电商日报生成流水线

  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ 数据抽取  │ ──→ │ 数据清洗  │ ──→ │ 数据聚合  │
  │ (Task-A) │     │ (Task-B) │     │ (Task-D) │
  └──────────┘     └──────────┘     └──────────┘
       │                │                  │
       │                ▼                  ▼
       │          ┌──────────┐       ┌──────────┐
       └────────→ │ 数据校验  │ ──→  │ 日报生成  │
                  │ (Task-C) │       │ (Task-E) │
                  └──────────┘       └──────────┘

  依赖关系：
  Task-A → 无依赖（起始任务）
  Task-B → 依赖 Task-A
  Task-C → 依赖 Task-A
  Task-D → 依赖 Task-B
  Task-E → 依赖 Task-D, Task-C
```

### 18.2 拓扑排序：DAG 执行顺序的核心算法

要让 DAG 中的任务按正确顺序执行，需要使用**拓扑排序（Topological Sort）**。拓扑排序能够将 DAG 中的节点排成一个线性序列，保证对于每条边 (u, v)，u 在序列中排在 v 前面。

#### 18.2.1 Kahn 算法（BFS 实现）

```java
/**
 * Kahn 算法实现拓扑排序
 * 核心思想：每次取出入度为 0 的节点，移除其出边，重复直到所有节点被处理
 */
public class TopologicalSort {
    
    public List<String> sort(DAG graph) {
        List<String> result = new ArrayList<>();
        
        // 1. 计算每个节点的入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : graph.getAllNodes()) {
            inDegree.put(node, 0);
        }
        for (String node : graph.getAllNodes()) {
            for (String successor : graph.getSuccessors(node)) {
                inDegree.merge(successor, 1, Integer::sum);
            }
        }
        
        // 2. 将入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        // 3. BFS 遍历
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);
            
            // 移除该节点的所有出边
            for (String successor : graph.getSuccessors(node)) {
                int newDegree = inDegree.merge(successor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(successor);
                }
            }
        }
        
        // 4. 检查是否有环
        if (result.size() != graph.getAllNodes().size()) {
            throw new IllegalStateException("DAG 中存在环，无法进行拓扑排序");
        }
        
        return result;
    }
}
```

用图来理解 Kahn 算法的执行过程：

```
  初始状态（入度标注在节点旁）：

  A(0) ──→ B(1) ──→ D(1)
    │                 │
    └──→ C(1) ──→ E(2) ← (D 和 C 都指向 E)

  Step 1: A 入度为 0，取出 A
    队列: [A] → 取出 A
    减小 B、C 的入度: B(0), C(0)
    队列: [B, C]
    结果: [A]

  Step 2: B 入度为 0，取出 B
    队列: [C] → 取出 B
    减小 D 的入度: D(0)
    队列: [C, D]
    结果: [A, B]

  Step 3: C 入度为 0，取出 C
    队列: [D] → 取出 C
    减小 E 的入度: E(1)
    队列: [D]
    结果: [A, B, C]

  Step 4: D 入度为 0，取出 D
    队列: [] → 取出 D
    减小 E 的入度: E(0)
    队列: [E]
    结果: [A, B, C, D]

  Step 5: E 入度为 0，取出 E
    队列: [] → 取出 E
    结果: [A, B, C, D, E]

  最终执行顺序: A → B → C → D → E
  注意: B 和 C 可以并行执行！
```

#### 18.2.2 并行度控制

在实际执行中，Crane 不会严格按拓扑序列逐个执行，而是**最大化并行度**——所有入度为 0 的任务同时执行：

```java
/**
 * DAG 执行引擎（简化版）
 */
public class DAGExecutionEngine {
    
    private final ExecutorService taskExecutor;
    
    public void execute(DAG graph, DAGExecutionContext context) {
        // 1. 计算初始入度
        Map<String, Integer> inDegree = computeInDegree(graph);
        
        // 2. 使用 CountDownLatch 跟踪完成情况
        Map<String, CountDownLatch> latches = new HashMap<>();
        for (String node : graph.getAllNodes()) {
            latches.put(node, new CountDownLatch(
                inDegree.getOrDefault(node, 0)));
        }
        
        // 3. 使用 Phaser 控制并行度
        Phaser phaser = new Phaser(1); // 1 for main thread
        
        // 4. 提交所有任务
        for (String node : graph.getAllNodes()) {
            taskExecutor.submit(() -> {
                try {
                    // 等待所有前置任务完成
                    for (String predecessor : graph.getPredecessors(node)) {
                        latches.get(predecessor).await();
                    }
                    
                    // 检查前置任务是否有失败的
                    if (context.hasFailedPredecessor(node)) {
                        context.markSkipped(node, "前置任务失败");
                        latches.get(node).countDown();
                        return;
                    }
                    
                    // 执行任务
                    TaskResult result = executeTask(node, context);
                    
                    if (result.isSuccess()) {
                        context.markSuccess(node, result);
                    } else {
                        context.markFailed(node, result);
                        // 根据配置决定是否继续执行后续任务
                    }
                    
                } catch (Exception e) {
                    context.markFailed(node, new TaskResult(e));
                } finally {
                    latches.get(node).countDown();
                }
            });
        }
        
        // 5. 等待所有任务完成
        for (String node : graph.getAllNodes()) {
            latches.get(node).await();
        }
    }
}
```

### 18.3 DAG 任务状态机

DAG 中每个子任务有独立的状态，同时 DAG 整体也有一个状态：

```
  DAG 整体状态机：

  ┌──────────┐
  │  PENDING  │ ── 等待触发时间到达
  └─────┬────┘
        │ 触发
        ▼
  ┌──────────┐
  │ RUNNING   │ ── 正在执行中
  └─────┬────┘
        │
   ┌────┴────┬──────────┬──────────┐
   │         │          │          │
   ▼         ▼          ▼          ▼
  SUCCESS  PARTIAL   FAILED    TIMEOUT
  (全部成功) (部分成功)  (全部失败)  (超时)


  子任务状态机：

  ┌──────────┐
  │  WAITING   │ ── 等待前置任务完成
  └─────┬────┘
        │ 前置任务全部成功
        ▼
  ┌──────────┐
  │  READY    │ ── 就绪，等待分配执行资源
  └─────┬────┘
        │ 资源就绪
        ▼
  ┌──────────┐     成功
  │ RUNNING   │ ──────────→ SUCCESS
  └─────┬────┘
        │ 失败
        ├──→ FAILED ──→ (可重试) → READY
        │
        │ 前置任务失败
        └──→ SKIPPED（跳过执行）
```

### 18.4 依赖解析与环检测

在用户配置 DAG 时，Crane 需要在保存配置前进行环检测，防止用户配置出有环的依赖图：

```java
/**
 * 环检测：基于 DFS 的三色标记法
 * 白色：未访问
 * 灰色：正在访问（在当前 DFS 路径上）
 * 黑色：已完成访问
 */
public class CycleDetector {
    
    public boolean hasCycle(DAG graph) {
        Map<String, Color> colors = new HashMap<>();
        for (String node : graph.getAllNodes()) {
            colors.put(node, Color.WHITE);
        }
        
        for (String node : graph.getAllNodes()) {
            if (colors.get(node) == Color.WHITE) {
                if (dfs(node, graph, colors)) {
                    return true;  // 发现环
                }
            }
        }
        return false;
    }
    
    private boolean dfs(String node, DAG graph, Map<String, Color> colors) {
        colors.put(node, Color.GRAY);  // 标记为正在访问
        
        for (String successor : graph.getSuccessors(node)) {
            if (colors.get(successor) == Color.GRAY) {
                // 遇到灰色节点，说明有环！
                return true;
            }
            if (colors.get(successor) == Color.WHITE) {
                if (dfs(successor, graph, colors)) {
                    return true;
                }
            }
        }
        
        colors.put(node, Color.BLACK);  // 标记为已完成
        return false;
    }
    
    enum Color { WHITE, GRAY, BLACK }
}
```

```
  环检测示例：

  正常 DAG（无环）：              有环的配置（错误）：

  A → B → D                      A → B → C
  A → C → D                      ↑         │
                                  └─────────┘
  DFS 遍历：                      DFS 遍历：
  A(灰) → B(灰) → D(灰→黑)      A(灰) → B(灰) → C(灰) 
  B(黑) → C(灰→黑)              → 发现 C 的后继 A 是灰色
  A(黑)                          → 检测到环！
  无环，通过验证                  有环，拒绝保存
```

### 18.5 DAG 任务的调度实例生命周期

DAG 任务的调度与普通周期任务有所不同。普通任务一次调度只产生一个调度实例，而 DAG 任务一次调度会产生一个**DAG 实例**，其中包含多个子任务实例：

```
  DAG 任务调度流程：

  1. Cron 触发
     │
     ▼
  2. 创建 DAG 实例（dagInstanceId = "dag_20250115_001"）
     │
     ▼
  3. 拓扑排序，确定执行层级
     Level 0: [Task-A]
     Level 1: [Task-B, Task-C]  ← 可并行
     Level 2: [Task-D]
     Level 3: [Task-E]
     │
     ▼
  4. 按层级提交子任务到调度队列
     │
     ├── 提交 Task-A → 客户端执行
     │
     ├── Task-A 完成 → 提交 Task-B, Task-C（并行）
     │
     ├── Task-B 完成 → 等待 Task-C
     ├── Task-C 完成 → 提交 Task-D（B 和 C 都完成）
     │
     └── Task-D 完成 → 提交 Task-E
     │
     ▼
  5. 所有子任务完成 → DAG 实例完成
     记录整体执行时长、各子任务执行结果
```

---

## 十九、延迟任务的内部存储

### 19.1 延迟任务的设计挑战

延迟任务与周期任务有本质区别：
- 周期任务是**预知的**——Cron 表达式确定了未来所有触发时间
- 延迟任务是**动态的**——业务方在任意时刻提交，延迟任意时间后执行

这意味着延迟任务需要一套独立的存储和调度机制。

### 19.2 分层时间轮：延迟任务的核心数据结构

Crane 的延迟任务模块内部使用了**分层时间轮**来管理海量延迟任务：

```
  分层时间轮结构：

  ┌─────────────────────────────────────────────────────────┐
  │  第一层：秒级时间轮                                       │
  │  60 个槽位，每个槽位代表 1 秒                             │
  │  存储：延迟时间 < 60 秒的任务                             │
  │  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐    │
  │  │0 │1 │2 │3 │4 │5 │6 │7 │8 │9 │..│..│..│..│..│59│   │
  │  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘    │
  │  指针每秒移动一格                                         │
  ├─────────────────────────────────────────────────────────┤
  │  第二层：分钟级时间轮                                     │
  │  60 个槽位，每个槽位代表 1 分钟                           │
  │  存储：延迟时间 60s ~ 3600s 的任务                        │
  │  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐    │
  │  │0 │1 │2 │3 │4 │5 │6 │7 │8 │9 │..│..│..│..│..│59│   │
  │  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘    │
  │  指针每分钟移动一格                                       │
  ├─────────────────────────────────────────────────────────┤
  │  第三层：小时级时间轮                                     │
  │  24 个槽位，每个槽位代表 1 小时                           │
  │  存储：延迟时间 3600s ~ 86400s 的任务                     │
  │  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐    │
  │  │0 │1 │2 │3 │4 │5 │6 │7 │8 │9 │..│..│..│..│..│23│   │
  │  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘    │
  │  指针每小时移动一格                                       │
  ├─────────────────────────────────────────────────────────┤
  │  第四层：天级时间轮                                       │
  │  N 个槽位，每个槽位代表 1 天                              │
  │  存储：延迟时间 > 86400s 的任务                           │
  └─────────────────────────────────────────────────────────┘
```

#### 19.2.1 任务的添加过程

```java
/**
 * 分层时间轮的任务添加逻辑（简化版）
 */
public class HierarchicalWheelTimer {
    
    private static final int SECOND_WHEEL_SIZE = 60;
    private static final int MINUTE_WHEEL_SIZE = 60;
    private static final int HOUR_WHEEL_SIZE = 24;
    
    // 各层时间轮
    private final List<Set<DelayTask>>[] secondWheel = new List[60];
    private final List<Set<DelayTask>>[] minuteWheel = new List[60];
    private final List<Set<DelayTask>>[] hourWheel = new List[24];
    
    // 当前指针位置
    private int secondPointer = 0;
    private int minutePointer = 0;
    private int hourPointer = 0;
    
    /**
     * 添加延迟任务
     * @param task 延迟任务
     * @param delaySeconds 延迟秒数
     */
    public void add(DelayTask task, long delaySeconds) {
        if (delaySeconds < 60) {
            // 放入秒级时间轮
            int slot = (int) ((secondPointer + delaySeconds) % 60);
            secondWheel[slot].add(task);
        } else if (delaySeconds < 3600) {
            // 放入分钟级时间轮
            long delayMinutes = delaySeconds / 60;
            int slot = (int) ((minutePointer + delayMinutes) % 60);
            task.setRemainingDelay(delaySeconds % 60);  // 记录余数
            minuteWheel[slot].add(task);
        } else if (delaySeconds < 86400) {
            // 放入小时级时间轮
            long delayHours = delaySeconds / 3600;
            int slot = (int) ((hourPointer + delayHours) % 24);
            task.setRemainingDelay(delaySeconds % 3600);
            hourWheel[slot].add(task);
        } else {
            // 放入天级时间轮或 DB 持久化
            persistToDB(task, delaySeconds);
        }
    }
}
```

#### 19.2.2 任务的降级与执行

当高层时间轮的指针转到某个槽位时，该槽位中的任务需要**降级**到下一层时间轮：

```
  任务降级流程示例：

  任务延迟 2 小时 30 分 15 秒

  Step 1: 添加到小时轮第 2 格
  ┌─────────────┐
  │ Hour Wheel  │  [2]: {task, remaining=30min15s}
  └─────────────┘

  Step 2: 2 小时后，小时轮指针转到第 2 格
  → 将 task 降级到分钟轮
  ┌─────────────┐
  │ Minute Wheel│  [30]: {task, remaining=15s}
  └─────────────┘

  Step 3: 30 分钟后，分钟轮指针转到第 30 格
  → 将 task 降级到秒轮
  ┌─────────────┐
  │ Second Wheel│  [15]: {task, remaining=0}
  └─────────────┘

  Step 4: 15 秒后，秒轮指针转到第 15 格
  → 执行 task！
```

### 19.3 磁盘持久化与恢复

延迟任务存储在内存中的时间轮里，如果调度器宕机，内存中的任务会丢失。Crane 通过磁盘持久化来解决这一问题：

```
  延迟任务持久化策略：

  ┌───────────────────────────────────────────────────────────┐
  │  策略1：WAL（Write-Ahead Log）                             │
  │  每次添加延迟任务时，先写入 WAL 日志文件                     │
  │  调度器重启时，回放 WAL 重建时间轮                          │
  │  优点：不丢任务                                           │
  │  缺点：写入开销大                                         │
  ├───────────────────────────────────────────────────────────┤
  │  策略2：定时快照（Snapshot）                                │
  │  每隔 N 秒将时间轮状态序列化到磁盘                          │
  │  调度器重启时，加载最近的快照                               │
  │  优点：写入开销小                                         │
  │  缺点：可能丢失最近一个快照周期内的任务                      │
  ├───────────────────────────────────────────────────────────┤
  │  策略3：DB 兜底（Crane 实际采用）                           │
  │  所有延迟任务同时写入 MySQL                                │
  │  内存时间轮是 DB 的缓存                                   │
  │  调度器重启时，从 DB 加载未执行的任务                       │
  │  优点：可靠，不丢任务                                     │
  │  缺点：依赖 DB                                           │
  └───────────────────────────────────────────────────────────┘
```

```java
/**
 * 延迟任务的持久化与恢复（简化版）
 */
public class DelayTaskPersistence {
    
    // 添加延迟任务时的持久化
    public void submit(DelayTask task, long delaySeconds) {
        // 1. 写入 DB
        DelayTaskDO taskDO = new DelayTaskDO();
        taskDO.setTaskName(task.getName());
        taskDO.setParams(task.getParams());
        taskDO.setExpireTime(System.currentTimeMillis() + delaySeconds * 1000);
        taskDO.setStatus("PENDING");
        taskRepository.insert(taskDO);
        
        // 2. 放入内存时间轮
        wheelTimer.add(task, delaySeconds);
    }
    
    // 调度器重启时的恢复逻辑
    public void recover() {
        // 从 DB 加载所有未执行的延迟任务
        List<DelayTaskDO> pendingTasks = taskRepository
            .findByStatusAndExpireTimeAfter("PENDING", System.currentTimeMillis());
        
        for (DelayTaskDO taskDO : pendingTasks) {
            long remainingDelay = (taskDO.getExpireTime() 
                - System.currentTimeMillis()) / 1000;
            
            if (remainingDelay <= 0) {
                // 已过期的任务，立即执行
                executeImmediately(taskDO);
            } else {
                // 重新放入时间轮
                DelayTask task = convertToTask(taskDO);
                wheelTimer.add(task, remainingDelay);
            }
        }
        
        log.info("延迟任务恢复完成，共恢复 {} 个任务", pendingTasks.size());
    }
}
```

### 19.4 延迟任务与周期任务的对比

| 维度 | 周期任务 | 延迟任务 |
|------|---------|---------|
| 触发方式 | Cron 表达式 | 业务方提交时指定延迟时间 |
| 执行次数 | 无限次（周期性） | 1 次（一次性） |
| 底层队列 | DelayQueue | 分层时间轮 |
| 持久化 | 任务元数据存 DB，调度实例在内存 | 任务同时存 DB 和内存 |
| 任务量 | 万级 | 百万级 |
| 性能要求 | TP99 < 100ms | 提交耗时 < 10ms |
| 适用场景 | 定时清理、报表生成 | 订单超时、延迟重试 |

---

## 二十、任务分片算法详解

### 20.1 分片的核心目标

分片（Sharding）的目标是将一个大任务拆分为多个小任务，分配到不同机器上并行执行，从而缩短总执行时间。

```
  不分片：1 台机器处理 1000 万条数据，耗时 2 小时

  ┌──────────────────────────────────────┐
  │  Machine-1                            │
  │  处理第 1 ~ 10,000,000 条             │
  │  耗时：2 小时                          │
  └──────────────────────────────────────┘

  分片：5 台机器各处理 200 万条，耗时约 24 分钟

  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
  │ Machine-1   │ │ Machine-2   │ │ Machine-3   │ │ Machine-4   │ │ Machine-5   │
  │ shard 0     │ │ shard 1     │ │ shard 2     │ │ shard 3     │ │ shard 4     │
  │ 1~200万     │ │ 201~400万   │ │ 401~600万   │ │ 601~800万   │ │ 801~1000万  │
  │ 24分钟      │ │ 24分钟      │ │ 24分钟      │ │ 24分钟      │ │ 24分钟      │
  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
```

### 20.2 四种分片算法

#### 20.2.1 取模分片

最简单的分片算法：对数据 ID 取模，决定数据归属哪个分片。

```java
/**
 * 取模分片
 * 优点：实现简单，分片均匀
 * 缺点：分片数变化时，数据需要全量重新分配
 */
public class ModSharding {
    
    public int getShard(long dataId, int shardCount) {
        return (int) (dataId % shardCount);
    }
    
    // 查询时使用
    public List<Order> queryByShard(int shard, int shardCount) {
        // SELECT * FROM orders WHERE id % {shardCount} = {shard}
        return orderRepository.findByIdMod(shardCount, shard);
    }
}
```

```
  取模分片示例（4 个分片）：

  数据 ID: 1  → 1 % 4 = 1 → Shard-1
  数据 ID: 2  → 2 % 4 = 2 → Shard-2
  数据 ID: 3  → 3 % 4 = 3 → Shard-3
  数据 ID: 4  → 4 % 4 = 0 → Shard-0
  数据 ID: 5  → 5 % 4 = 1 → Shard-1
  数据 ID: 6  → 6 % 4 = 2 → Shard-2
  ...

  分布：
  Shard-0: 4, 8, 12, 16, ...
  Shard-1: 1, 5, 9, 13, ...
  Shard-2: 2, 6, 10, 14, ...
  Shard-3: 3, 7, 11, 15, ...
```

#### 20.2.2 范围分片

按数据 ID 的范围进行分片，每个分片负责一段连续的 ID 区间。

```java
/**
 * 范围分片
 * 优点：范围查询友好，扩容时只影响最后一个分片
 * 缺点：可能出现数据热点
 */
public class RangeSharding {
    
    public int getShard(long dataId, long totalRange, int shardCount) {
        long rangePerShard = totalRange / shardCount;
        return (int) (dataId / rangePerShard);
    }
    
    // 查询时使用
    public List<Order> queryByShard(int shard, long rangePerShard) {
        long minId = shard * rangePerShard;
        long maxId = (shard + 1) * rangePerShard - 1;
        // SELECT * FROM orders WHERE id BETWEEN {minId} AND {maxId}
        return orderRepository.findByIdRange(minId, maxId);
    }
}
```

```
  范围分片示例（4 个分片，总范围 1000 万）：

  Shard-0: ID 1 ~ 2,500,000
  Shard-1: ID 2,500,001 ~ 5,000,000
  Shard-2: ID 5,000,001 ~ 7,500,000
  Shard-3: ID 7,500,001 ~ 10,000,000
```

#### 20.2.3 哈希分片

对数据 ID 进行哈希后再取模，比直接取模分布更均匀。

```java
/**
 * 哈希分片
 * 优点：分布更均匀，减少数据倾斜
 * 缺点：范围查询不友好
 */
public class HashSharding {
    
    public int getShard(String dataKey, int shardCount) {
        // 使用 MurmurHash（比 String.hashCode 分布更均匀）
        int hash = MurmurHash3.hash32(dataKey.getBytes());
        // 确保非负
        return Math.abs(hash) % shardCount;
    }
    
    // 查询时使用
    public List<Order> queryByShard(int shard, int shardCount) {
        // 需要全表扫描后过滤，或预先计算哈希值并存储
        return orderRepository.findByHashMod(shardCount, shard);
    }
}
```

#### 20.2.4 一致性哈希分片

结合一致性哈希算法进行分片，扩缩容时影响最小。

```java
/**
 * 一致性哈希分片
 * 优点：扩缩容时迁移数据量最小
 * 缺点：实现复杂，可能数据倾斜
 */
public class ConsistentHashSharding {
    
    private final TreeMap<Integer, Integer> ring = new TreeMap<>();
    private static final int VIRTUAL_NODES = 150;
    
    public ConsistentHashSharding(List<Integer> shards) {
        for (int shard : shards) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                String vn = shard + ":" + i;
                int hash = MurmurHash3.hash32(vn.getBytes());
                ring.put(hash, shard);
            }
        }
    }
    
    public int getShard(String dataKey) {
        int hash = MurmurHash3.hash32(dataKey.getBytes());
        // 顺时针找到第一个虚拟节点
        Map.Entry<Integer, Integer> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            // 环绕到环的起点
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }
}
```

### 20.3 四种分片算法对比

| 维度 | 取模分片 | 范围分片 | 哈希分片 | 一致性哈希分片 |
|------|---------|---------|---------|-------------|
| **均匀性** | 好 | 一般 | 很好 | 较好 |
| **范围查询** | 不友好 | 友好 | 不友好 | 不友好 |
| **扩容影响** | 全量迁移 | 只影响末尾 | 全量迁移 | 最小迁移 |
| **实现复杂度** | 低 | 低 | 中 | 高 |
| **Crane 适用** | 是 | 是 | 是 | 否 |

### 20.4 Crane 的分片分配策略

Crane 将分片分配给客户端机器的策略有三种：

#### 20.4.1 平均分片（默认）

```
  平均分片策略（6 个分片，3 台机器）：

  机器列表（按 IP 排序）：[10.0.0.1, 10.0.0.2, 10.0.0.3]

  分配结果：
  10.0.0.1 → shard 0, shard 1
  10.0.0.2 → shard 2, shard 3
  10.0.0.3 → shard 4, shard 5

  公式：每台机器分配 shardCount / machineCount 个分片
  如果不整除，前面的机器多分一个
```

```java
public class AverageShardAllocator implements ShardAllocator {
    
    @Override
    public Map<String, List<Integer>> allocate(
            List<String> sortedIps, int shardCount) {
        Map<String, List<Integer>> result = new HashMap<>();
        int machineCount = sortedIps.size();
        int baseShardsPerMachine = shardCount / machineCount;
        int remainder = shardCount % machineCount;
        
        int shardIndex = 0;
        for (int i = 0; i < machineCount; i++) {
            List<Integer> shards = new ArrayList<>();
            // 前面 remainder 台机器各多分一个
            int shardNum = baseShardsPerMachine + (i < remainder ? 1 : 0);
            for (int j = 0; j < shardNum; j++) {
                shards.add(shardIndex++);
            }
            result.put(sortedIps.get(i), shards);
        }
        return result;
    }
}
```

#### 20.4.2 随机分片

```
  随机分片策略（6 个分片，3 台机器）：

  每次调度时，随机打乱分片到机器的映射：
  
  第一次调度：
  10.0.0.1 → shard 0, shard 3
  10.0.0.2 → shard 1, shard 5
  10.0.0.3 → shard 2, shard 4

  第二次调度（可能不同）：
  10.0.0.1 → shard 2, shard 5
  10.0.0.2 → shard 0, shard 1
  10.0.0.3 → shard 3, shard 4
```

#### 20.4.3 哈希奇偶分片

```
  哈希奇偶分片策略：

  步骤1：计算任务名的哈希值
  hash = hash("refresh-order-status")

  步骤2：根据哈希值的奇偶性决定 IP 排序方式
  if hash % 2 == 0:
      sortedIps = 正序排序 [10.0.0.1, 10.0.0.2, 10.0.0.3]
  else:
      sortedIps = 逆序排序 [10.0.0.3, 10.0.0.2, 10.0.0.1]

  步骤3：按排序后的 IP 列表进行平均分片

  目的：避免所有任务都把重分片分给同一台机器
```

### 20.5 分片数的最佳实践

```
  分片数选择决策树：

  ┌─ 已知机器数量 M？
  │   ├─ 是 → 分片数 = M × 2（推荐）
  │   │       理由：扩容时有余量，缩容时每台仍能分到分片
  │   │
  │   └─ 否 → 分片数 = 预估最大机器数 × 2
  │
  └─ 数据量很大？
      ├─ 是 → 分片数 = 数据量 / 单机处理能力
      │       例如：1000万数据，单机处理50万 → 20个分片
      │
      └─ 否 → 分片数 = 机器数 × 2

  注意事项：
  1. 分片数一旦确定，尽量不要修改（修改会导致数据重分布）
  2. 分片数应大于机器数，否则扩容后有些机器没有分片
  3. 分片数不宜过多，否则单分片数据量太小，调度开销占比高
```

---

## 二十一、客户端执行框架

### 21.1 客户端框架总体架构

当调度器将调度指令发送到客户端（业务机器）后，客户端需要执行具体的业务逻辑。Crane 客户端框架的设计需要处理反射调用、线程隔离、超时控制、结果上报等一系列问题。

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Crane Client 执行框架                       │
  ├──────────────────────────────────────────────────────────────┤
  │                                                              │
  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────────┐   │
  │  │ HTTP 接收器   │   │ 任务注册表    │   │  执行线程池       │   │
  │  │ HTTP-       │   │ Task-       │   │  Execution-     │   │
  │  │ Receiver    │   │ Registry    │   │  ThreadPool     │   │
  │  │             │   │             │   │                 │   │
  │  │ 接收调度指令 │──>│ 查找任务方法 │──>│ 执行业务逻辑     │   │
  │  └─────────────┘   └─────────────┘   └────────┬────────┘   │
  │                                                │             │
  │                                       ┌────────▼────────┐    │
  │                                       │  结果上报器       │    │
  │                                       │  Result-        │    │
  │                                       │  Reporter       │    │
  │                                       │                 │    │
  │                                       │  异步上报执行结果 │    │
  │                                       └─────────────────┘    │
  │                                                              │
  │  ┌─────────────┐   ┌─────────────┐   ┌─────────────────┐   │
  │  │ 注册管理器   │   │ 超时控制器   │   │  分片上下文       │   │
  │  │ Register-  │   │ Timeout-   │   │  ShardContext    │   │
  │  │ Manager    │   │ Controller │   │                 │   │
  │  │             │   │             │   │                 │   │
  │  │ 注册到 ZK   │   │ 超时中断     │   │ 提供分片信息     │   │
  │  └─────────────┘   └─────────────┘   └─────────────────┘    │
  │                                                              │
  └──────────────────────────────────────────────────────────────┘
```

### 21.2 反射调用机制

Crane 客户端通过 Java 反射机制调用业务代码中标注了 `@Crane` 注解的方法：

```java
/**
 * 任务注册表：管理任务名到方法的映射
 */
public class TaskRegistry {
    
    // 任务名 → 方法元数据
    private final Map<String, TaskMethodRef> registry = new ConcurrentHashMap<>();
    
    /**
     * 扫描所有 @Crane 注解方法并注册
     */
    public void scanAndRegister(Object bean) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            Crane annotation = method.getAnnotation(Crane.class);
            if (annotation != null) {
                String taskName = annotation.value();
                method.setAccessible(true);
                
                TaskMethodRef ref = new TaskMethodRef();
                ref.setBean(bean);
                ref.setMethod(method);
                ref.setTaskName(taskName);
                ref.setParamTypes(method.getParameterTypes());
                
                registry.put(taskName, ref);
                log.info("注册 Crane 任务: {} → {}.{}()", 
                    taskName, clazz.getSimpleName(), method.getName());
            }
        }
    }
    
    /**
     * 通过反射执行任务
     */
    public TaskResult invoke(String taskName, String params) {
        TaskMethodRef ref = registry.get(taskName);
        if (ref == null) {
            return TaskResult.fail("任务未注册: " + taskName);
        }
        
        try {
            // 解析参数
            Object[] args = parseArgs(ref.getParamTypes(), params);
            
            // 反射调用
            Object result = ref.getMethod().invoke(ref.getBean(), args);
            
            return TaskResult.success(result);
        } catch (InvocationTargetException e) {
            // 业务代码抛出的异常
            return TaskResult.fail(e.getTargetException());
        } catch (Exception e) {
            return TaskResult.fail(e);
        }
    }
}
```

### 21.3 线程隔离设计

不同任务的执行不应该相互影响。Crane 客户端通过线程隔离来保证这一点：

```java
/**
 * 执行线程池：每个任务使用独立的线程池
 */
public class IsolatedExecutionPool {
    
    // 每个任务名对应一个独立的线程池
    private final Map<String, ExecutorService> taskPools = new ConcurrentHashMap<>();
    
    // 默认线程池配置
    private ThreadPoolConfig getDefaultConfig(String taskName) {
        return ThreadPoolConfig.builder()
            .coreSize(1)          // 默认 1 个线程（单节点任务）
            .maxSize(1)
            .queueCapacity(1)     // 队列容量 1（不排队）
            .threadNamePrefix("xxljob-" + taskName + "-")
            .build();
    }
    
    public ExecutorService getOrCreate(String taskName) {
        return taskPools.computeIfAbsent(taskName, name -> {
            ThreadPoolConfig config = getConfig(name);
            return new ThreadPoolExecutor(
                config.getCoreSize(),
                config.getMaxSize(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()),
                new ThreadFactoryBuilder()
                    .setNameFormat(config.getThreadNamePrefix() + "%d")
                    .setDaemon(true)
                    .build(),
                new AbortPolicy()  // 队列满时抛异常，避免任务堆积
            );
        });
    }
    
    // 分片任务使用更大的线程池
    private ThreadPoolConfig getConfig(String taskName) {
        if (isShardTask(taskName)) {
            return ThreadPoolConfig.builder()
                .coreSize(4)
                .maxSize(8)
                .queueCapacity(4)
                .threadNamePrefix("xxljob-shard-" + taskName + "-")
                .build();
        }
        return getDefaultConfig(taskName);
    }
}
```

**线程隔离的好处**：

```
  不隔离（共享线程池）：

  ┌─────────────────────────────────────────────┐
  │  共享线程池（10 个线程）                       │
  │  [task-A-1] [task-A-2] [task-B-1] [task-C-1] │
  │  [task-A-3] [task-A-4] [task-A-5] [task-A-6] │
  │  [task-A-7] [task-A-8]                        │
  │                                               │
  │  问题：task-A 占满了线程池，task-B 和 task-C   │
  │  无法执行！                                   │
  └─────────────────────────────────────────────┘

  隔离（独立线程池）：

  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ task-A 线程池 │ │ task-B 线程池 │ │ task-C 线程池 │
  │ (2 线程)     │ │ (2 线程)     │ │ (2 线程)     │
  │ [A-1] [A-2] │ │ [B-1] [B-2] │ │ [C-1] [C-2] │
  └──────────────┘ └──────────────┘ └──────────────┘
  
  各任务互不影响！
```

### 21.4 超时控制机制

Crane 客户端使用 `Future.get()` 配合超时时间来实现任务执行的超时控制：

```java
/**
 * 超时控制的任务执行
 */
public class TimeoutAwareExecutor {
    
    public TaskResult executeWithTimeout(String taskName, 
            String params, long timeoutMs) {
        
        ExecutorService pool = isolatedPool.getOrCreate(taskName);
        
        Future<TaskResult> future = pool.submit(() -> {
            return taskRegistry.invoke(taskName, params);
        });
        
        try {
            // 设置超时
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时处理
            future.cancel(true);  // 尝试中断执行线程
            log.warn("任务执行超时, task={}, timeout={}ms", taskName, timeoutMs);
            
            // 注意：cancel(true) 只是设置中断标志
            // 如果业务代码不响应中断，线程仍会继续执行
            // 需要业务代码配合检查 Thread.interrupted()
            
            return TaskResult.timeout(taskName, timeoutMs);
            
        } catch (ExecutionException e) {
            return TaskResult.fail(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TaskResult.fail("执行被中断");
        }
    }
}
```

### 21.5 结果上报机制

任务执行完成后，客户端需要将执行结果异步上报给调度器：

```java
/**
 * 结果上报器（异步）
 */
public class ResultReporter {
    
    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<ReportTask> reportQueue;
    private final HttpClient httpClient;
    
    // 异步上报
    public void reportAsync(String taskName, String instanceId, 
            TaskResult result) {
        ReportTask report = new ReportTask(taskName, instanceId, result);
        
        // 放入上报队列
        if (!reportQueue.offer(report)) {
            // 队列满，降级为同步上报
            log.warn("上报队列已满，降级为同步上报");
            reportSync(report);
        }
    }
    
    // 上报线程
    class ReportWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    ReportTask report = reportQueue.poll(5, TimeUnit.SECONDS);
                    if (report != null) {
                        reportSync(report);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    // 同步上报（带重试）
    private void reportSync(ReportTask report) {
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            try {
                ReportResponse response = httpClient.post(
                    schedulerUrl + "/api/callback",
                    report.toJson()
                );
                if (response.isSuccess()) {
                    return;  // 上报成功
                }
            } catch (Exception e) {
                log.warn("结果上报失败, retry={}/{}", i + 1, maxRetry, e);
            }
            
            try {
                Thread.sleep(1000L * (i + 1));  // 指数退避
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        // 重试全部失败，写入本地文件兜底
        log.error("结果上报彻底失败，写入本地文件兜底: {}", report);
        persistToLocalFile(report);
    }
}
```

### 21.6 客户端注册流程

```java
/**
 * 客户端启动时注册到 ZK
 */
public class ClientRegistrar {
    
    public void register() {
        // 1. 扫描所有 @Crane 注解，收集任务列表
        List<String> taskNames = scanCraneAnnotations();
        
        // 2. 构建注册信息
        ClientInfo info = new ClientInfo();
        info.setAppKey(appKey);
        info.setIp(IpUtils.getLocalIp());
        info.setPort(xxljobClientPort);  // 默认 8410
        info.setLane(laneContext.getLane());
        info.setSet(setContext.getSet());
        info.setTaskNames(taskNames);
        
        // 3. 注册到 ZK（持久节点）
        String znodePath = "/xxljob/client/" + appKey + "/" + info.getIp();
        zookeeperClient.createPersistent(znodePath, info.toJson());
        
        log.info("Crane 客户端注册成功: appKey={}, ip={}, tasks={}", 
            appKey, info.getIp(), taskNames);
        
        // 4. 启动心跳线程（保活）
        startHeartbeat();
    }
    
    // 心跳：定期更新 ZK 节点数据
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String znodePath = "/xxljob/client/" + appKey + "/" + ip;
                // 更新节点数据（包含最新任务列表和心跳时间）
                ClientInfo info = buildClientInfo();
                info.setHeartbeatTime(System.currentTimeMillis());
                zookeeperClient.setData(znodePath, info.toJson());
            } catch (Exception e) {
                log.warn("心跳更新失败", e);
            }
        }, 30, 30, TimeUnit.SECONDS);  // 每 30 秒一次
    }
}
```

```
  客户端注册到 ZK 的节点结构：

  /xxljob
    └── client
        └── com.sankuai.order (appKey)
            ├── 10.0.0.1 (IP, 持久节点)
            │   └── 数据: {appKey, ip, port, lane, set, tasks, heartbeat}
            ├── 10.0.0.2
            │   └── 数据: {appKey, ip, port, lane, set, tasks, heartbeat}
            └── 10.0.0.3
                └── 数据: {appKey, ip, port, lane, set, tasks, heartbeat}
```

---

## 二十二、监控与告警体系

### 22.1 监控指标体系

Crane 建立了多层监控指标体系，覆盖调度全链路：

```
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Crane 监控指标体系                            │
  ├──────────────────────────────────────────────────────────────────┤
  │                                                                  │
  │  第一层：调度器健康指标                                            │
  │  ├── 调度器 CPU 使用率                                             │
  │  ├── 调度器内存使用率                                              │
  │  ├── 调度器 GC 频率和耗时                                          │
  │  ├── 调度器线程池状态（活跃线程、队列积压）                         │
  │  └── 调度器网络 IO                                                │
  │                                                                  │
  │  第二层：调度性能指标                                              │
  │  ├── 调度延迟（trigger_time → dispatch_time）                     │
  │  ├── 派发延迟（dispatch_time → client_receive_time）              │
  │  ├── 执行延迟（client_receive_time → execution_complete_time）    │
  │  ├── 端到端延迟（trigger_time → execution_complete_time）         │
  │  └── DelayQueue 等待时间                                           │
  │                                                                  │
  │  第三层：任务执行指标                                              │
  │  ├── 执行成功率                                                    │
  │  ├── 执行失败率                                                    │
  │  ├── 执行超时率                                                    │
  │  ├── 平均执行耗时                                                  │
  │  ├── TP50/TP90/TP99/TP999 执行耗时                                │
  │  └── 失败重试次数                                                  │
  │                                                                  │
  │  第四层：系统容量指标                                              │
  │  ├── 任务总数                                                     │
  │  ├── 每日调度总量                                                  │
  │  ├── 每秒调度 TPS                                                  │
  │  ├── 客户端注册总数                                                │
  │  └── DAG 任务数                                                   │
  │                                                                  │
  └──────────────────────────────────────────────────────────────────┘
```

### 22.2 调度延迟监控

调度延迟是 Crane 最核心的 SLA 指标。从"应该触发"到"实际派发"之间的时间差即为调度延迟：

```java
/**
 * 调度延迟监控埋点
 */
public class DispatchLatencyMonitor {
    
    private final MetricsCollector metrics;
    
    public void onDispatch(ScheduleInstance instance) {
        long now = System.currentTimeMillis();
        long dispatchLatency = now - instance.getTriggerTime();
        
        // 记录到指标系统
        metrics.recordTimer("xxljob.dispatch.latency", dispatchLatency, 
            TimeUnit.MILLISECONDS);
        
        // 分桶统计
        if (dispatchLatency < 20) {
            metrics.incrementCounter("xxljob.dispatch.latency.0_20ms");
        } else if (dispatchLatency < 50) {
            metrics.incrementCounter("xxljob.dispatch.latency.20_50ms");
        } else if (dispatchLatency < 100) {
            metrics.incrementCounter("xxljob.dispatch.latency.50_100ms");
        } else if (dispatchLatency < 500) {
            metrics.incrementCounter("xxljob.dispatch.latency.100_500ms");
        } else if (dispatchLatency < 1000) {
            metrics.incrementCounter("xxljob.dispatch.latency.500_1000ms");
        } else {
            metrics.incrementCounter("xxljob.dispatch.latency.1000ms_plus");
        }
        
        // 超过阈值记录日志
        if (dispatchLatency > 100) {
            log.warn("调度延迟过高: task={}, latency={}ms", 
                instance.getTaskName(), dispatchLatency);
        }
    }
}
```

```
  调度延迟分桶统计看板：

  ┌────────────────────────────────────────────────────┐
  │  调度延迟分布（最近 5 分钟）                          │
  ├────────────────────────────────────────────────────┤
  │  0-20ms:    ████████████████████████  78.3%  │     │
  │  20-50ms:   ██████████  15.2%                  │   │
  │  50-100ms:  ██  4.1%                            │   │
  │  100-500ms: █  1.8%                            │   │
  │  500ms+:    ▌  0.6%                            │   │
  ├────────────────────────────────────────────────────┤
  │  平均延迟: 18ms   TP99: 87ms   TP999: 312ms        │
  └────────────────────────────────────────────────────┘
```

### 22.3 执行成功率监控

```java
/**
 * 任务执行成功率监控
 */
public class SuccessRateMonitor {
    
    // 滑动窗口统计（1分钟窗口，10秒粒度）
    private final SlidingWindow successWindow = new SlidingWindow(60, 10);
    private final SlidingWindow totalWindow = new SlidingWindow(60, 10);
    
    public void onTaskComplete(String taskName, TaskResult result) {
        totalWindow.increment();
        if (result.isSuccess()) {
            successWindow.increment();
        }
        
        // 计算成功率
        double successRate = (double) successWindow.getSum() 
            / totalWindow.getSum();
        
        // 告警判断
        if (successRate < 0.95 && totalWindow.getSum() > 100) {
            alarmService.send(AlarmLevel.WARN, 
                String.format("任务执行成功率低于阈值: task=%s, rate=%.2f%%", 
                    taskName, successRate * 100));
        }
        
        if (successRate < 0.80 && totalWindow.getSum() > 50) {
            alarmService.send(AlarmLevel.CRITICAL,
                String.format("任务执行成功率严重低于阈值: task=%s, rate=%.2f%%", 
                    taskName, successRate * 100));
        }
    }
}
```

### 22.4 告警分级体系

Crane 定义了四级告警体系：

| 告警级别 | 触发条件 | 通知方式 | 响应时效 |
|---------|---------|---------|---------|
| P0（紧急） | 调度器整体不可用、全部任务停止调度 | 电话 + 大象消息 + 邮件 | 5 分钟内 |
| P1（严重） | 调度延迟 TP99 > 1s、成功率 < 80% | 大象消息 + 邮件 | 15 分钟内 |
| P2（警告） | 调度延迟 TP99 > 500ms、成功率 < 95% | 大象消息 | 30 分钟内 |
| P3（提醒） | 个别任务失败、客户端下线 | 邮件 | 工作时间内处理 |

```java
/**
 * 告警服务实现
 */
public class AlarmServiceImpl implements AlarmService {
    
    @Override
    public void send(AlarmLevel level, String message) {
        AlarmMessage alarm = AlarmMessage.builder()
            .level(level)
            .message(message)
            .timestamp(System.currentTimeMillis())
            .source("xxljob-scheduler-" + getSchedulerId())
            .build();
        
        switch (level) {
            case P0:
                // 紧急：电话 + 大象 + 邮件
                phoneCallService.call(onCallPhone, alarm);
               大象MessageService.send(onCallGroup, alarm.format());
                emailService.send(onCallEmails, "Crane紧急告警", alarm.format());
                break;
            case P1:
                // 严重：大象 + 邮件
                大象MessageService.send(onCallGroup, alarm.format());
                emailService.send(onCallEmails, "Crane严重告警", alarm.format());
                break;
            case P2:
                // 警告：大象
                大象MessageService.send(taskOwnerGroup, alarm.format());
                break;
            case P3:
                // 提醒：邮件
                emailService.send(taskOwnerEmails, "Crane提醒", alarm.format());
                break;
        }
        
        // 记录告警日志
        log.warn("告警发送: level={}, message={}", level, message);
    }
}
```

### 22.5 失败重试机制

```java
/**
 * 失败重试策略
 */
public class RetryPolicy {
    
    private int maxRetries;        // 最大重试次数
    private long retryInterval;    // 重试间隔（毫秒）
    private double backoffFactor;  // 退避因子
    
    public RetryResult executeWithRetry(Callable<TaskResult> task, 
            String taskName) {
        int attempt = 0;
        long currentInterval = retryInterval;
        
        while (attempt <= maxRetries) {
            try {
                TaskResult result = task.call();
                if (result.isSuccess()) {
                    if (attempt > 0) {
                        log.info("任务重试成功: task={}, attempt={}", 
                            taskName, attempt);
                    }
                    return RetryResult.success(result, attempt);
                }
                
                log.warn("任务执行失败: task={}, attempt={}, error={}", 
                    taskName, attempt, result.getError());
                
            } catch (Exception e) {
                log.warn("任务执行异常: task={}, attempt={}", 
                    taskName, attempt, e);
            }
            
            attempt++;
            if (attempt > maxRetries) {
                break;
            }
            
            // 指数退避等待
            try {
                Thread.sleep(currentInterval);
                currentInterval = (long) (currentInterval * backoffFactor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RetryResult.interrupted();
            }
        }
        
        return RetryResult.exhausted(maxRetries);
    }
}
```

```
  重试策略示例：

  配置：maxRetries=3, retryInterval=1000ms, backoffFactor=2.0

  时间线：
  T=0s:     第一次执行 → 失败
  T=1s:     等待 1000ms
  T=1s:     第二次执行 → 失败
  T=3s:     等待 2000ms（1000 × 2）
  T=3s:     第三次执行 → 失败
  T=7s:     等待 4000ms（2000 × 2）
  T=7s:     第四次执行 → 成功！
  
  总耗时：7 秒，重试 3 次
```

---

## 二十三、高可用架构 2.0 的详细设计

### 23.1 架构 2.0 的演进背景

2021 年，Crane 经历了 3 起线上故障，暴露了架构 1.0 的几个核心问题：

```
  架构 1.0 的痛点：

  痛点1：灰度粒度粗
  ┌─────────────────────────────────────────┐
  │  发版时，一次灰度 1/3 的调度器             │
  │  如果新版本有 bug，1/3 的任务受影响        │
  │  影响面太大，业务方投诉                    │
  └─────────────────────────────────────────┘

  痛点2：回滚慢
  ┌─────────────────────────────────────────┐
  │  发现问题后，需要滚动回滚所有调度器         │
  │  回滚过程中 Slot 拓扑会发生变化            │
  │  可能触发二次问题                          │
  │  整个回滚过程耗时数分钟                    │
  └─────────────────────────────────────────┘

  痛点3：运行时强依赖
  ┌─────────────────────────────────────────┐
  │  调度器运行时依赖 ZK、DB、Manager          │
  │  任何一个组件出问题，都可能影响调度          │
  │  调度器不够"自主"                          │
  └─────────────────────────────────────────┘
```

### 23.2 Slot 灰度：精细化发布

架构 2.0 的核心改进之一是将灰度粒度从**机器维度**降低到**Slot 维度**：

```
  架构 1.0：机器维度灰度

  调度器 S1（新版本） ← 灰度
  调度器 S2（旧版本）
  调度器 S3（旧版本）

  S1 上有 340 个 Slot → 340 个 Slot 上的任务都用新版本调度
  如果新版本有 bug → 1/3 的任务受影响


  架构 2.0：Slot 维度灰度

  调度器 S1: Slot 0~340 (新版本)
  调度器 S2: Slot 341~681 (旧版本)
  调度器 S3: Slot 682~1023 (旧版本)

  灰度策略：先将 S1 上的 10 个 Slot 迁移到新版本
  ┌──────────────────────────────────────┐
  │ S1: Slot 0~9 (新版本) + Slot 10~340 (旧版本) │
  │ S2: Slot 341~681 (旧版本)              │
  │ S3: Slot 682~1023 (旧版本)             │
  └──────────────────────────────────────┘
  
  只有 10 个 Slot 上的任务用新版本 → 影响面 < 1%
  观察一段时间无问题后，逐步扩大灰度范围
```

```java
/**
 * Slot 灰度发布控制器（简化版）
 */
public class SlotGrayReleaseController {
    
    /**
     * 灰度发布流程
     */
    public void grayRelease(String newVersion, GrayStrategy strategy) {
        // 阶段1：小范围灰度（1%）
        Set<Integer> graySlots = selectSlots(strategy.getInitialPercent());
        migrateSlotsToNewVersion(graySlots, newVersion);
        
        // 观察 10 分钟
        if (!observe(graySlots, Duration.ofMinutes(10))) {
            rollback(graySlots);
            return;
        }
        
        // 阶段2：中范围灰度（10%）
        graySlots = selectSlots(strategy.getSecondPercent());
        migrateSlotsToNewVersion(graySlots, newVersion);
        
        if (!observe(graySlots, Duration.ofMinutes(10))) {
            rollback(graySlots);
            return;
        }
        
        // 阶段3：全量发布
        migrateAllSlotsToNewVersion(newVersion);
    }
    
    /**
     * 观察灰度 Slot 的运行状态
     */
    private boolean observe(Set<Integer> slots, Duration duration) {
        long endTime = System.currentTimeMillis() + duration.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            for (int slot : slots) {
                // 检查调度延迟
                if (metrics.getDispatchLatency(slot) > 100) {
                    log.warn("灰度 Slot 调度延迟异常: slot={}", slot);
                    return false;
                }
                // 检查成功率
                if (metrics.getSuccessRate(slot) < 0.95) {
                    log.warn("灰度 Slot 成功率异常: slot={}", slot);
                    return false;
                }
            }
            sleep(30, TimeUnit.SECONDS);
        }
        return true;
    }
}
```

### 23.3 秒级回滚

架构 2.0 的另一个核心改进是**秒级回滚**：

```
  架构 1.0 回滚流程（分钟级）：

  发现问题 → 通知运维 → 滚动回滚 S1 → 等待 S1 恢复 → 滚动回滚 S2 → ...
  总耗时：5~10 分钟
  问题：回滚过程中 Slot 拓扑变化，可能触发二次问题


  架构 2.0 回滚流程（秒级）：

  发现问题 → 自动触发回滚 → Slot 快速迁移回旧版本
  总耗时：< 10 秒
  原理：Slot 状态快照 + 快速恢复机制
```

```java
/**
 * 秒级回滚实现
 */
public class FastRollback {
    
    // Slot 状态快照（每次灰度前保存）
    private Map<Integer, SlotSnapshot> snapshots = new HashMap<>();
    
    /**
     * 灰度前保存快照
     */
    public void saveSnapshotBeforeGray(Set<Integer> slots) {
        for (int slot : slots) {
            SlotSnapshot snapshot = new SlotSnapshot();
            snapshot.setSlotId(slot);
            snapshot.setVersion(currentVersion);
            snapshot.setTasks(taskRepository.findBySlot(slot));
            snapshot.setDelayQueueState(serializeDelayQueue(slot));
            snapshot.setTimestamp(System.currentTimeMillis());
            snapshots.put(slot, snapshot);
        }
    }
    
    /**
     * 快速回滚
     */
    public void rollback(Set<Integer> slots) {
        long startTime = System.currentTimeMillis();
        
        for (int slot : slots) {
            SlotSnapshot snapshot = snapshots.get(slot);
            if (snapshot == null) continue;
            
            // 1. 停止当前 Slot 的调度
            scheduler.suspendSlot(slot);
            
            // 2. 恢复到快照版本
            scheduler.restoreSlot(slot, snapshot);
            
            // 3. 恢复调度
            scheduler.resumeSlot(slot);
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Slot 回滚完成, 耗时={}ms, slots={}", elapsed, slots);
        
        // 上报回滚耗时
        metrics.recordTimer("xxljob.rollback.time", elapsed);
    }
}
```

### 23.4 无状态运行时

架构 2.0 的第三个核心改进是让调度器运行时**无强依赖**：

```
  架构 1.0 运行时依赖：

  ┌──────────┐
  │ Scheduler │ ──强依赖──→ ZK（获取客户端列表）
  │           │ ──强依赖──→ DB（读写任务元数据）
  │           │ ──强依赖──→ Manager（获取租约）
  └──────────┘
  任何一个不可用 → 调度器无法正常工作

  架构 2.0 运行时依赖：

  ┌──────────┐
  │ Scheduler │ ──弱依赖──→ ZK（启动时加载，运行时用缓存）
  │           │ ──弱依赖──→ DB（启动时加载，运行时用缓存）
  │           │ ──弱依赖──→ Manager（租约可用就行，不需要实时通信）
  └──────────┘
  运行时完全自主，不依赖任何外部服务
```

```
  无状态运行时的数据流：

  ┌──────────────────────────────────────────────────────────┐
  │  启动阶段（需要外部依赖）                                   │
  │                                                          │
  │  Scheduler 启动                                          │
  │    ├── 从 DB 加载任务元数据 → 缓存到内存                    │
  │    ├── 从 ZK 加载客户端列表 → 缓存到内存                    │
  │    ├── 从 Manager 获取租约和 Slot 分配                     │
  │    └── 生成调度实例 → 放入 DelayQueue                      │
  │                                                          │
  │  运行阶段（不依赖外部服务）                                  │
  │                                                          │
  │  DelayQueue 到期 → 查内存客户端列表 → HTTP 派发             │
  │  执行结果回写 → 写内存 + 异步写 DB（失败不影响调度）         │
  │  新任务上线 → Manager 通知 → 更新内存缓存                   │
  │                                                          │
  │  即使 ZK、DB、Manager 全部宕机：                            │
  │  → 调度器仍能正常调度（用内存缓存的数据）                    │
  │  → 只是无法感知新任务和客户端上下线                           │
  │                                                          │
  └──────────────────────────────────────────────────────────┘
```

### 23.5 架构 2.0 的运维能力增强

```
  运维能力对比：

  ┌─────────────────┬──────────────────┬──────────────────┐
  │  运维操作        │  架构 1.0         │  架构 2.0         │
  ├─────────────────┼──────────────────┼──────────────────┤
  │  灰度发布        │  机器维度         │  Slot 维度        │
  │  回滚            │  分钟级           │  秒级             │
  │  Slot 迁移       │  随机分配         │  可人工指定       │
  │  故障隔离        │  整机摘除         │  Slot 级摘除      │
  │  负载均衡        │  随机             │  按权重可控       │
  │  运行时依赖      │  ZK+DB+Manager   │  无强依赖         │
  └─────────────────┴──────────────────┴──────────────────┘
```

---

## 二十四、Zookeeper 在 Crane 中的使用细节

### 24.1 ZNode 结构设计

Crane 在 Zookeeper 中维护了完整的集群元数据。ZNode 的层级结构如下：

```
  /xxljob                                       ← 根节点
  │
  ├── scheduler                                ← 调度器注册根节点
  │   ├── {bg}_scheduler_1                     ← 调度器 1（持久节点）
  │   │   └── data: {
  │   │         "ip": "10.0.0.1",
  │   │         "port": 8080,
  │   │         "bg": "waimai",
  │   │         "version": "2.1.0",
  │   │         "startTime": 1700000000000
  │   │       }
  │   ├── {bg}_scheduler_2                     ← 调度器 2
  │   └── {bg}_scheduler_3                     ← 调度器 3
  │
  ├── manager                                  ← Manager 注册根节点
  │   ├── master                               ← 当前主 Manager（临时节点）
  │   │   └── data: {
  │   │         "ip": "10.0.0.10",
  │   │         "port": 9090,
  │   │         "term": 5
  │   │       }
  │   ├── manager_1                            ← Manager 1（持久节点）
  │   ├── manager_2                            ← Manager 2
  │   └── manager_3                            ← Manager 3
  │
  ├── client                                   ← 客户端注册根节点
  │   └── {appKey}                             ← 按 appKey 分组
  │       ├── 10.0.0.100                       ← 客户端机器 1（持久节点）
  │       │   └── data: {
  │       │         "appKey": "com.sankuai.order",
  │       │         "ip": "10.0.0.100",
  │       │         "port": 8410,
  │       │         "lane": "prod",
  │       │         "set": "default",
  │       │         "tasks": ["task-a", "task-b"],
  │       │         "heartbeat": 1700000000000
  │       │       }
  │       ├── 10.0.0.101                       ← 客户端机器 2
  │       └── 10.0.0.102                       ← 客户端机器 3
  │
  ├── slot                                     ← Slot 分配根节点
  │   └── {bg}                                 ← 按 BG 分组
  │       └── assignment                       ← Slot 分配方案
  │           └── data: {
  │                 "slots": {
  │                   "0": "scheduler_1",
  │                   "1": "scheduler_1",
  │                   ...
  │                   "1023": "scheduler_3"
  │                 },
  │                 "version": 42,
  │                 "updateTime": 1700000000000
  │               }
  │
  └── monitor                                  ← Monitor 注册根节点
      ├── monitor_1                            ← Monitor 1
      ├── monitor_2                            ← Monitor 2
      └── assignment                           ← 客户端分配方案
          └── data: {
                "monitor_1": ["appKey1", "appKey2"],
                "monitor_2": ["appKey3", "appKey4"]
              }
```

### 24.2 Watcher 机制

Crane 大量使用 ZK 的 Watcher 机制来实现事件驱动的架构：

```java
/**
 * ZK Watcher 使用示例
 */
public class CraneWatcher implements Watcher {
    
    @Override
    public void process(WatchedEvent event) {
        String path = event.getPath();
        Event.EventType type = event.getType();
        
        // 调度器上下线事件
        if (path.startsWith("/xxljob/scheduler/")) {
            handleSchedulerChangeEvent(path, type);
        }
        // 客户端上下线事件
        else if (path.startsWith("/xxljob/client/")) {
            handleClientChangeEvent(path, type);
        }
        // Manager 主节点变更
        else if (path.equals("/xxljob/manager/master")) {
            handleManagerMasterChangeEvent(type);
        }
        // Slot 分配变更
        else if (path.equals("/xxljob/slot/" + bg + "/assignment")) {
            handleSlotAssignmentChangeEvent(type);
        }
    }
    
    /**
     * 处理客户端上下线
     */
    private void handleClientChangeEvent(String path, Event.EventType type) {
        String appKey = extractAppKey(path);
        String ip = extractIp(path);
        
        switch (type) {
            case NodeCreated:
                // 新客户端上线
                log.info("客户端上线: appKey={}, ip={}", appKey, ip);
                clientRegistry.addClient(appKey, ip);
                break;
                
            case NodeDeleted:
                // 客户端下线
                log.info("客户端下线: appKey={}, ip={}", appKey, ip);
                clientRegistry.removeClient(appKey, ip);
                break;
                
            case NodeDataChanged:
                // 客户端信息更新（如任务列表变化）
                log.info("客户端信息更新: appKey={}, ip={}", appKey, ip);
                ClientInfo info = readClientInfo(path);
                clientRegistry.updateClient(appKey, ip, info);
                // 重新注册 Watcher（ZK 的 Watcher 是一次性的）
                zookeeper.exists(path, true);
                break;
        }
    }
}
```

```
  Watcher 事件流转：

  客户端启动
      │
      ▼
  创建 ZK 持久节点 /xxljob/client/{appKey}/{ip}
      │
      ▼
  Manager 的 Watcher 收到 NodeCreated 事件
      │
      ▼
  Manager 更新内存中的客户端注册表
      │
      ▼
  Manager 通知对应的调度器：有新客户端上线
      │
      ▼
  调度器更新本地客户端列表缓存
      │
      ▼
  下次调度时，新客户端可以被选中执行任务


  客户端宕机
      │
      ▼
  ZK Session 过期（默认 30s）
      │
      ▼
  ZK 自动删除持久节点？ 不！持久节点不会被自动删除
      │
      ▼
  Monitor 心跳检测发现客户端无响应
      │
      ▼
  Monitor 通知 Manager 删除该客户端节点
      │
      ▼
  Manager 的 Watcher 收到 NodeDeleted 事件
      │
      ▼
  更新注册表，通知调度器摘除该客户端
```

**重要细节**：Crane 在 2017 年 4 月将客户端注册节点从临时节点改为持久节点。原因是临时节点依赖 ZK Session，当 ZK 网络抖动时 Session 过期会导致客户端被误判下线。改为持久节点后，客户端下线由 Monitor 的心跳检测来判定，更加可靠。

### 24.3 Session 管理

```java
/**
 * ZK Session 管理器
 */
public class ZKSessionManager {
    
    private ZooKeeper zooKeeper;
    private final String zkConnectString;
    private final int sessionTimeout;  // 默认 30000ms
    
    // Session 过期处理
    private final SessionExpiredHandler expiredHandler;
    
    public void connect() {
        zooKeeper = new ZooKeeper(zkConnectString, sessionTimeout, event -> {
            switch (event.getState()) {
                case SyncConnected:
                    log.info("ZK 连接成功");
                    onConnected();
                    break;
                    
                case Disconnected:
                    log.warn("ZK 连接断开，进入降级模式");
                    onDisconnected();
                    break;
                    
                case Expired:
                    log.error("ZK Session 过期，需要重新创建 ZooKeeper 实例");
                    expiredHandler.onExpired();
                    // 重新创建 ZK 连接
                    reconnect();
                    break;
                    
                case ConnectedReadOnly:
                    log.warn("ZK 进入只读模式");
                    break;
            }
        });
    }
    
    // ZK 断开时的降级策略
    private void onDisconnected() {
        // 调度器：使用缓存的客户端列表继续调度
        scheduler.enterDegradedMode();
        
        // Manager：所有节点都开始心跳检测
        manager.startAllHeartbeat();
    }
    
    // ZK 恢复时的恢复逻辑
    private void onConnected() {
        // 重新注册自己
        registerSelf();
        
        // 重新加载数据
        reloadFromZK();
        
        // 退出降级模式
        scheduler.exitDegradedMode();
    }
}
```

### 24.4 ZK 弱依赖的工程实现

```java
/**
 * ZK 弱依赖的核心：本地缓存
 */
public class ZKBackedCache<T> {
    
    private final Map<String, T> cache = new ConcurrentHashMap<>();
    private final String zkPath;
    private final ZooKeeper zk;
    private final Class<T> type;
    
    // 启动时全量加载
    public void loadAll() {
        List<String> children = zk.getChildren(zkPath, true);
        for (String child : children) {
            T data = readFromZK(zkPath + "/" + child);
            cache.put(child, data);
        }
        log.info("从 ZK 加载完成: path={}, count={}", zkPath, cache.size());
    }
    
    // 获取数据：优先从缓存读
    public T get(String key) {
        return cache.get(key);  // 不走 ZK，纯内存读取
    }
    
    // ZK 变更时更新缓存
    public void onNodeChanged(String key, T data) {
        if (data != null) {
            cache.put(key, data);
        } else {
            cache.remove(key);
        }
    }
    
    // ZK 不可用时，缓存仍然可用
    // 只是无法感知变更
}
```

---

## 二十五、数据库设计

### 25.1 整体数据库架构

Crane 使用 MySQL 作为持久化存储，存储任务元数据、调度记录、执行日志等。数据库按 BG 集群进行物理隔离。

```
  数据库整体架构：

  ┌──────────────────────────────────────────────────────┐
  │                   MySQL 集群                           │
  │                                                      │
  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
  │  │  xxljob_waimai│  │ xxljob_jiulv │  │ xxljob_finance│  │
  │  │  (外卖BG库)  │  │ (酒旅BG库)  │  │ (金融BG库)   │  │
  │  ├─────────────┤  ├─────────────┤  ├─────────────┤  │
  │  │ task_config  │  │ task_config  │  │ task_config  │  │
  │  │ schedule_log │  │ schedule_log │  │ schedule_log │  │
  │  │ exec_log     │  │ exec_log     │  │ exec_log     │  │
  │  │ delay_task   │  │ delay_task   │  │ delay_task   │  │
  │  └─────────────┘  └─────────────┘  └─────────────┘  │
  │                                                      │
  │  每个库独立部署，互不影响                               │
  │  主从架构：1主2从，读写分离                            │
  └──────────────────────────────────────────────────────┘
```

### 25.2 核心表设计

#### 25.2.1 任务配置表（task_config）

```sql
CREATE TABLE `task_config` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_name`       VARCHAR(256)     NOT NULL COMMENT '任务名（唯一标识）',
    `app_key`         VARCHAR(128)     NOT NULL COMMENT '所属服务 appKey',
    `bg`              VARCHAR(32)      NOT NULL COMMENT '所属 BG',
    `cron_expression` VARCHAR(128)     DEFAULT NULL COMMENT 'Cron 表达式',
    `task_type`       TINYINT          NOT NULL DEFAULT 1 COMMENT '任务类型: 1=进程内, 2=Docker, 3=Agent, 4=DAG, 5=延迟',
    `schedule_type`   TINYINT          NOT NULL DEFAULT 1 COMMENT '调度方式: 1=单节点, 2=广播, 3=分片',
    `shard_count`     INT              DEFAULT 0 COMMENT '分片数（分片任务有效）',
    `shard_strategy`  TINYINT          DEFAULT 1 COMMENT '分片策略: 1=平均, 2=随机, 3=哈希奇偶',
    `timeout`         INT              DEFAULT 300 COMMENT '超时时间（秒）',
    `retry_count`     INT              DEFAULT 0 COMMENT '失败重试次数',
    `retry_interval`  INT              DEFAULT 1000 COMMENT '重试间隔（毫秒）',
    `status`          TINYINT          NOT NULL DEFAULT 0 COMMENT '状态: 0=暂停, 1=启用, 2=已删除',
    `params`          TEXT             DEFAULT NULL COMMENT '任务参数（JSON 格式）',
    `description`     VARCHAR(512)     DEFAULT NULL COMMENT '任务描述',
    `owner`           VARCHAR(64)      DEFAULT NULL COMMENT '负责人 MIS',
    `create_time`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_name` (`task_name`),
    KEY `idx_app_key` (`app_key`),
    KEY `idx_bg_status` (`bg`, `status`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务配置表';
```

#### 25.2.2 调度记录表（schedule_log）

```sql
CREATE TABLE `schedule_log` (
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_name`        VARCHAR(256)     NOT NULL COMMENT '任务名',
    `trigger_time`     DATETIME         NOT NULL COMMENT '计划触发时间',
    `dispatch_time`    DATETIME         DEFAULT NULL COMMENT '实际派发时间',
    `scheduler_ip`     VARCHAR(32)      DEFAULT NULL COMMENT '调度器 IP',
    `client_ip`        VARCHAR(32)      DEFAULT NULL COMMENT '执行客户端 IP',
    `status`           TINYINT          NOT NULL DEFAULT 0 COMMENT '状态: 0=调度中, 1=执行中, 2=成功, 3=失败, 4=超时, 5=跳过',
    `dispatch_latency` INT              DEFAULT 0 COMMENT '调度延迟（毫秒）',
    `exec_duration`    INT              DEFAULT 0 COMMENT '执行耗时（毫秒）',
    `retry_times`      INT              DEFAULT 0 COMMENT '已重试次数',
    `error_msg`        TEXT             DEFAULT NULL COMMENT '错误信息',
    `create_time`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_name_trigger` (`task_name`, `trigger_time`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_scheduler_ip` (`scheduler_ip`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调度记录表';
```

#### 25.2.3 执行日志表（exec_log）

```sql
CREATE TABLE `exec_log` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `schedule_log_id` BIGINT UNSIGNED  NOT NULL COMMENT '调度记录 ID',
    `task_name`       VARCHAR(256)     NOT NULL COMMENT '任务名',
    `shard_item`      INT              DEFAULT -1 COMMENT '分片值（-1 表示非分片任务）',
    `client_ip`       VARCHAR(32)      NOT NULL COMMENT '执行客户端 IP',
    `start_time`      DATETIME         NOT NULL COMMENT '执行开始时间',
    `end_time`        DATETIME         DEFAULT NULL COMMENT '执行结束时间',
    `duration`        INT              DEFAULT 0 COMMENT '执行耗时（毫秒）',
    `status`          TINYINT          NOT NULL DEFAULT 0 COMMENT '状态: 0=执行中, 1=成功, 2=失败, 3=超时',
    `result`          TEXT             DEFAULT NULL COMMENT '执行结果',
    `error_stack`     MEDIUMTEXT       DEFAULT NULL COMMENT '错误堆栈',
    `create_time`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_schedule_log_id` (`schedule_log_id`),
    KEY `idx_task_name_start` (`task_name`, `start_time`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志表';
```

#### 25.2.4 延迟任务表（delay_task）

```sql
CREATE TABLE `delay_task` (
    `id`           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `task_name`    VARCHAR(256)     NOT NULL COMMENT '任务名',
    `app_key`      VARCHAR(128)     NOT NULL COMMENT 'appKey',
    `params`       TEXT             DEFAULT NULL COMMENT '任务参数',
    `expire_time`  DATETIME         NOT NULL COMMENT '到期执行时间',
    `submit_time`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `status`       TINYINT          NOT NULL DEFAULT 0 COMMENT '状态: 0=待执行, 1=已执行, 2=已取消, 3=执行失败',
    `retry_count`  INT              DEFAULT 0 COMMENT '重试次数',
    `create_time`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_expire` (`status`, `expire_time`),
    KEY `idx_app_key_status` (`app_key`, `status`),
    KEY `idx_task_name` (`task_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='延迟任务表';
```

### 25.3 索引优化策略

```sql
-- 1. 调度记录表：按任务名查询最近调度记录（最频繁的查询）
-- 已有索引: idx_task_name_trigger (task_name, trigger_time)
-- 优化: 查询最近 7 天的调度记录
SELECT * FROM schedule_log 
WHERE task_name = 'com.sankuai.order.clean-expired-orders' 
  AND trigger_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)
ORDER BY trigger_time DESC
LIMIT 100;
-- 走索引 idx_task_name_trigger，效率高

-- 2. 调度记录表：查询失败的调度记录（告警统计）
-- 已有索引: idx_status_create (status, create_time)
SELECT task_name, COUNT(*) as fail_count 
FROM schedule_log 
WHERE status = 3  -- 失败
  AND create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY task_name
HAVING fail_count > 5;
-- 走索引 idx_status_create

-- 3. 执行日志表：查询单次调度的详细执行日志
-- 已有索引: idx_schedule_log_id (schedule_log_id)
SELECT * FROM exec_log 
WHERE schedule_log_id = 1234567890
ORDER BY shard_item;
-- 走索引 idx_schedule_log_id

-- 4. 延迟任务表：查询待执行的延迟任务（恢复用）
-- 已有索引: idx_status_expire (status, expire_time)
SELECT * FROM delay_task 
WHERE status = 0  -- 待执行
  AND expire_time <= NOW()
ORDER BY expire_time
LIMIT 1000;
-- 走索引 idx_status_expire
```

### 25.4 数据归档策略

由于调度记录和执行日志的数据量巨大（每天近 8000 万条调度记录），Crane 采用分表 + 归档策略：

```
  数据归档策略：

  ┌───────────────────────────────────────────────────────────┐
  │  schedule_log 表                                          │
  │                                                           │
  │  热数据：最近 7 天    → 存储在 schedule_log 表中            │
  │  温数据：7~30 天      → 归档到 schedule_log_archive 表      │
  │  冷数据：30 天以上    → 归档到 HDFS / Hive                  │
  │                                                           │
  │  定时任务：每天凌晨 3 点执行归档                            │
  │    1. 将 7 天前的数据从 schedule_log 迁移到 archive 表      │
  │    2. 将 30 天前的数据从 archive 表导出到 Hive              │
  │    3. 删除 archive 表中 30 天前的数据                       │
  └───────────────────────────────────────────────────────────┘

  分表策略（按月分表）：

  schedule_log_202501  ← 2025 年 1 月的数据
  schedule_log_202502  ← 2025 年 2 月的数据
  schedule_log_202503  ← 2025 年 3 月的数据
  ...
```

```sql
-- 归档定时任务 SQL（简化版）
-- 步骤1：迁移 7 天前的数据到归档表
INSERT INTO schedule_log_archive 
SELECT * FROM schedule_log 
WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);

-- 步骤2：删除原表中已归档的数据
DELETE FROM schedule_log 
WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY)
LIMIT 100000;  -- 分批删除，避免锁表

-- 步骤3：删除归档表中 30 天前的数据（已导出到 Hive）
DELETE FROM schedule_log_archive 
WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY)
LIMIT 100000;
```

### 25.5 数据库连接池配置

```java
/**
 * 数据库连接池配置（基于 HikariCP）
 */
@Configuration
public class DataSourceConfig {
    
    @Bean
    @ConfigurationProperties(prefix = "xxljob.datasource")
    public DataSource xxljobDataSource() {
        HikariConfig config = new HikariConfig();
        
        // 基本配置
        config.setJdbcUrl("jdbc:mysql://xxljob-db:3306/crone_waimai");
        config.setUsername("xxljob_app");
        config.setPassword("******");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        
        // 连接池大小
        config.setMaximumPoolSize(20);       // 最大连接数
        config.setMinimumIdle(5);            // 最小空闲连接
        config.setConnectionTimeout(3000);   // 获取连接超时（ms）
        config.setIdleTimeout(600000);       // 空闲连接超时（ms）
        config.setMaxLifetime(1800000);      // 连接最大生命周期（ms）
        
        // 性能优化
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        
        return new HikariDataSource(config);
    }
}
```

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| maximumPoolSize | 20 | 根据并发量调整，公式：(核心数 × 2 + 有效磁盘数) |
| minimumIdle | 5 | 与 maximumPoolSize 保持 1:4 比例 |
| connectionTimeout | 3000ms | 快速失败，避免线程阻塞 |
| idleTimeout | 600000ms | 10 分钟，避免频繁创建销毁 |
| maxLifetime | 1800000ms | 30 分钟，小于 MySQL wait_timeout |
| prepStmtCacheSize | 250 | 预编译语句缓存大小 |

---

## 二十六、典型故障案例分析

### 26.1 案例一：调度延迟飙升

**故障描述**：某天凌晨 2:00，大量用户反馈定时任务未按时执行，调度延迟从正常的 < 20ms 飙升到 5~10 秒。

**排查过程**：

```
  排查时间线：

  T+0min:  告警系统触发 P1 告警，调度延迟 TP99 > 5s
  T+5min:  值班人员登录调度器，查看监控面板
           → 发现 CPU 使用率飙升到 95%
           → GC 频率异常：Full GC 每 10 秒一次，每次 2~3 秒
  T+10min: dump 堆内存分析
           → DelayQueue 中有 50,000+ 个调度实例
           → 正常应该只有 ~2,000 个
  T+15min: 查看任务配置变更记录
           → 发现某用户配置了一个 Cron: */1 * * * * ?
           → 且该任务配置为广播调度，注册客户端 200 台
           → 每秒生成 200 个调度实例（1 个 Cron × 200 台客户端）
  T+20min: 紧急暂停该任务
  T+25min: 调度延迟恢复正常
```

**根因分析**：

```
  故障链条：

  用户配置错误（每秒广播 200 台）
      │
      ▼
  每秒生成 200 个调度实例
      │
      ▼
  DelayQueue 膨胀（50000+ 实例）
      │
      ▼
  堆内存占用过高
      │
      ▼
  频繁 Full GC（STW 2~3 秒）
      │
      ▼
  调度线程被 STW 阻塞
      │
      ▼
  调度延迟飙升到 5~10 秒
```

**改进措施**：

1. 增加**任务频率限制**：Cron 表达式最小间隔限制为 5 秒
2. 增加**广播数量限制**：广播调度的客户端数量上限从 1000 调整为 500
3. 增加**DelayQueue 大小监控**：超过 5000 个实例时告警
4. 增加**GC 耗时监控**：Full GC 超过 1 秒时告警

### 26.2 案例二：任务丢失

**故障描述**：调度器扩容后，部分任务"消失"了——既不调度，也不报错。

**排查过程**：

```
  排查时间线：

  T+0:    调度器 S4 新上线，接管 Slot 768~1023
  T+30min: 业务方反馈 3 个任务未执行
  
  排查：
  1. 查看任务配置 → 正常，status=1（启用）
  2. 查看调度记录 → 无记录，说明从未被调度
  3. 计算任务 Slot → hash("task-name") % 1024 = 850 → Slot-850
  4. Slot-850 应该由 S4 负责
  5. 检查 S4 的任务加载日志 → Slot-850 的任务未加载
  
  根因：
  Slot 迁移过程中，S3（旧调度器）已经停止调度 Slot 850 的任务
  但 S4（新调度器）在加载任务时，由于 DB 查询条件 bug，漏加载了部分任务
```

**根因分析**：

```sql
-- S4 加载任务的 SQL（有 bug）
SELECT * FROM task_config 
WHERE bg = 'waimai' 
  AND status = 1
  AND id % 1024 >= 768   -- BUG: 应该用 task_name 的 hash，不是 id
  AND id % 1024 <= 1023;
```

**改进措施**：

1. Slot 迁移后增加**任务加载验证**：对比迁出方和迁入方的任务列表
2. 增加**任务调度心跳**：每个任务每次调度都记录心跳，超时未调度的任务自动告警
3. 修复 SQL 查询，使用正确的 hash 函数

### 26.3 案例三：任务重复执行

**故障描述**：同一个任务在同一时间被调度了两次，导致数据库出现重复数据。

**排查过程**：

```
  排查：

  1. 查看调度记录 → 同一 trigger_time 有两条调度记录
     记录1: scheduler_ip=10.0.0.1 (S1)
     记录2: scheduler_ip=10.0.0.2 (S2)
  2. 两个调度器都认为自己负责该任务
  3. 检查 Slot 分配 → hash("task-name") % 1024 = 340
  4. Slot-340 在迁移过程中，S1 和 S2 短暂同时认为自己拥有 Slot-340
  
  根因：
  Slot 迁移的时序问题：
  T=0: Manager 通知 S2 接管 Slot-340
  T=1: S2 开始加载 Slot-340 的任务
  T=2: Manager 通知 S1 释放 Slot-340
  T=3: S1 停止调度（但此时 S2 已经开始调度）
  
  问题出在 T=1 到 T=3 之间：
  - S2 已经开始调度 Slot-340（因为已加载任务）
  - S1 还未停止调度 Slot-340（因为通知有延迟）
  - 出现"双调度"窗口
```

**改进措施**：

1. 调整 Slot 迁移时序：**先通知旧调度器释放，确认释放后再通知新调度器接管**
2. 增加调度去重：调度器在派发前，先检查 DB 中是否已有相同 trigger_time 的调度记录
3. 引入分布式锁：在 Slot 迁移期间，对该 Slot 的调度加锁

```
  改进后的 Slot 迁移时序：

  T=0: Manager 通知 S1 释放 Slot-340
  T=1: S1 停止调度 Slot-340，确认已释放
  T=2: Manager 收到 S1 的释放确认
  T=3: Manager 通知 S2 接管 Slot-340
  T=4: S2 加载任务，开始调度

  不再有"双调度"窗口！
```

### 26.4 案例四：脑裂恢复后调度异常

**故障描述**：网络分区恢复后，调度器 S2 恢复正常，但部分任务的调度实例"卡住"了——不再生成新的调度实例。

**排查过程**：

```
  排查：

  1. 网络分区期间：
     - S2 被摘除，Slot-340~681 迁移给 S1
     - S2 的租约过期，停止调度
  2. 网络恢复后：
     - S2 重新连接 Manager
     - Manager 给 S2 分配新的 Slot（不是原来的 340~681）
     - S2 开始加载新 Slot 的任务
  3. 问题：
     - S2 原来内存中还有旧 Slot-340~681 的调度实例
     - 这些实例已经过期（trigger_time 已过）
     - 但 S2 没有清理这些旧实例
     - 旧的调度实例占据了 DelayQueue 空间
     - 且因为已过期，take() 方法立即返回
     - 导致调度线程不断取出过期实例并尝试调度
     - 新实例无法被及时处理
```

**根因分析**：

```
  脑裂恢复后的状态：

  S2 内存中：
  ┌──────────────────────────────────────────┐
  │ DelayQueue:                               │
  │   [旧实例1: trigger=10:00:00 (已过期)]    │
  │   [旧实例2: trigger=10:05:00 (已过期)]    │
  │   [旧实例3: trigger=10:10:00 (已过期)]    │
  │   [新实例1: trigger=11:00:00]             │
  │   [新实例2: trigger=11:30:00]             │
  └──────────────────────────────────────────┘
  
  take() 总是先取到过期的旧实例 → 调度失败 → 重新放回 → 再次取出...
  形成死循环！新实例无法被取出。
```

**改进措施**：

1. 调度器恢复时，**清空所有旧的调度实例**，从 DB 重新加载
2. 增加过期实例检查：取出的实例如果 trigger_time 超过当前时间 N 分钟，直接丢弃并告警
3. 增加调度实例的版本号，旧版本的实例自动失效

### 26.5 故障案例总结

| 故障类型 | 根因 | 影响 | 改进措施 |
|---------|------|------|---------|
| 调度延迟飙升 | 用户配置高频广播任务 | 全调度器 GC 停顿 | 任务频率限制 + 队列大小监控 |
| 任务丢失 | Slot 迁移时任务加载 SQL bug | 部分任务停止调度 | 迁移验证 + 调度心跳告警 |
| 重复执行 | Slot 迁移时序问题 | 数据重复 | 调整迁移时序 + 调度去重 |
| 脑裂后卡死 | 旧调度实例未清理 | 新任务无法调度 | 恢复时清空重建 + 过期检查 |

---

## 二十七、性能调优指南

### 27.1 调度器参数调优

```yaml
# xxljob-scheduler.yaml（调度器核心配置）

# 调度引擎配置
xxljob:
  scheduler:
    # DelayQueue 相关
    delay-queue:
      max-size: 10000              # 队列最大容量，超过则告警
      expire-threshold-ms: 300000  # 过期阈值，超过5分钟的实例丢弃
    
    # 派发线程池
    dispatch-pool:
      core-size: 32                # 核心线程数
      max-size: 64                 # 最大线程数
      queue-capacity: 2000         # 队列容量
      keep-alive-seconds: 60       # 空闲线程存活时间
      rejected-policy: CALLER_RUNS # 拒绝策略
    
    # 回写线程池
    callback-pool:
      core-size: 16
      max-size: 32
      queue-capacity: 1000
    
    # HTTP 客户端
    http-client:
      max-connections: 200         # 最大连接数
      connect-timeout-ms: 1000     # 连接超时
      read-timeout-ms: 5000        # 读取超时
      max-per-route: 50            # 单路由最大连接
    
    # 租约配置
    lease:
      period-ms: 15000             # 租约期
      renew-window-ms: 10000       # 续约窗口
      safety-margin-ms: 2000       # 安全余量
    
    # 监控配置
    metrics:
      report-interval-ms: 10000    # 指标上报间隔
      latency-buckets: [0, 20, 50, 100, 500, 1000, 5000]  # 延迟分桶
```

### 27.2 JVM 参数调优

```bash
# 生产环境 JVM 参数推荐

# 堆内存配置（根据机器配置调整）
-Xms4g                          # 初始堆大小（与最大堆一致，避免动态扩容）
-Xmx4g                          # 最大堆大小

# 新生代配置
-Xmn2g                          # 新生代大小（堆的 1/2）
-XX:SurvivorRatio=6             # Eden:Survivor = 6:1:1

# GC 策略（JDK 8 推荐 G1GC）
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100        # 目标停顿时间 100ms
-XX:G1HeapRegionSize=16m        # G1 区域大小
-XX:InitiatingHeapOccupancyPercent=45  # 触发并发标记的堆占用率

# 元空间
-XX:MetaspaceSize=256m
-XX:MaxMetaspaceSize=512m

# 直接内存
-XX:MaxDirectMemorySize=512m

# GC 日志
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xloggc:/var/log/xxljob/gc.log
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=10
-XX:GCLogFileSize=100M

# OOM 处理
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/xxljob/heapdump

# 其他
-XX:+AlwaysPreTouch             # 启动时预触摸内存页
-Djava.awt.headless=true
-Dfile.encoding=UTF-8
```

```
  JVM 内存模型（Crane 调度器）：

  ┌──────────────────────────────────────────────────┐
  │                   JVM 堆内存 (4GB)                  │
  ├──────────────────────────────────────────────────┤
  │                                                  │
  │  ┌────────────────────────────────────────────┐  │
  │  │              新生代 (2GB)                    │  │
  │  │  ┌──────────┬──────────┬──────────┐        │  │
  │  │  │  Eden    │ Survivor0│ Survivor1│        │  │
  │  │  │ (1.5GB)  │ (256MB)  │ (256MB)  │        │  │
  │  │  └──────────┴──────────┴──────────┘        │  │
  │  │  调度实例在此分配和回收                        │  │
  │  └────────────────────────────────────────────┘  │
  │                                                  │
  │  ┌────────────────────────────────────────────┐  │
  │  │              老年代 (2GB)                    │  │
  │  │  任务元数据缓存                               │  │
  │  │  客户端列表缓存                               │  │
  │  │  大对象（如 params JSON）                     │  │
  │  └────────────────────────────────────────────┘  │
  │                                                  │
  ├──────────────────────────────────────────────────┤
  │  元空间 (256MB~512MB)                              │
  │  类元数据、方法信息                                 │
  ├──────────────────────────────────────────────────┤
  │  直接内存 (512MB)                                  │
  │  NIO Buffer、Netty 堆外内存                        │
  └──────────────────────────────────────────────────┘
```

### 27.3 GC 优化实战

```
  GC 调优前后对比：

  调优前（默认 CMS）：
  ┌──────────────────────────────────────────────┐
  │  Young GC: 每 3 秒一次，耗时 50ms              │
  │  Full GC: 每 30 秒一次，耗时 2~3 秒             │
  │  调度延迟 TP99: 350ms（受 Full GC 影响）        │
  │  堆使用率: 85%                                 │
  └──────────────────────────────────────────────┘

  调优后（G1GC + 参数优化）：
  ┌──────────────────────────────────────────────┐
  │  Young GC: 每 5 秒一次，耗时 20ms              │
  │  Mixed GC: 每 60 秒一次，耗时 80ms             │
  │  Full GC: 几乎不发生                            │
  │  调度延迟 TP99: 85ms                            │
  │  堆使用率: 60%                                 │
  └──────────────────────────────────────────────┘
```

**GC 调优关键点**：

1. **选择 G1GC**：G1GC 的可预测停顿时间特性适合调度器的低延迟需求
2. **设置合理的 MaxGCPauseMillis**：100ms 是一个合理目标，太小会增加 GC 频率
3. **增大新生代**：调度实例是短生命周期对象，新生代大则减少晋升到老年代的概率
4. **降低 IHOP**：从默认 45% 降到 35%，提前触发并发标记，避免 Full GC

### 27.4 连接池调优

```java
// HTTP 连接池（调度器 → 客户端）配置
public class HttpClientPoolConfig {
    
    // 连接池配置
    PoolingHttpClientConnectionManager connManager = 
        new PoolingHttpClientConnectionManager();
    connManager.setMaxTotal(200);           // 总连接数
    connManager.setDefaultMaxPerRoute(50);  // 每个路由（IP:Port）最大连接
    
    // Socket 配置
    SocketConfig socketConfig = SocketConfig.custom()
        .setSoTimeout(5000)               // 读取超时 5s
        .setTcpNoDelay(true)              // 禁用 Nagle 算法
        .setSoKeepAlive(true)             // TCP 保活
        .build();
    connManager.setDefaultSocketConfig(socketConfig);
    
    // 连接配置
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(1000)          // 连接建立超时 1s
        .setSocketTimeout(5000)           // 数据读取超时 5s
        .setConnectionRequestTimeout(500) // 从连接池获取连接超时 500ms
        .build();
    
    // 连接保活策略
    ConnectionKeepAliveStrategy keepAliveStrategy = 
        (response, context) -> TimeUnit.SECONDS.toMillis(30);
    
    // 重试策略（不重试 POST 请求）
    HttpRequestRetryHandler retryHandler = 
        (exception, executionCount, context) -> {
            if (executionCount > 3) return false;
            if (exception instanceof NoHttpResponseException) return true;
            if (exception instanceof SSLHandshakeException) return false;
            return false;
        };
}
```

### 27.5 性能调优检查清单

| 调优项 | 检查内容 | 推荐值 |
|--------|---------|--------|
| 堆大小 | 是否足够容纳峰值任务量 | 4GB~8GB |
| GC 策略 | 是否使用 G1GC | G1GC |
| 线程池 | 派发线程池是否合理 | 32~64 |
| HTTP 连接池 | 连接数是否够用 | 200 |
| DB 连接池 | 连接数是否够用 | 20 |
| 网络超时 | 连接/读取超时是否合理 | 1s/5s |
| 队列大小 | DelayQueue 和派发队列 | 10000/2000 |
| OS 参数 | tcp_tw_reuse, somaxconn | 1, 65535 |

---

## 二十八、面试常见问题与解答

### Q1：Crane 为什么选择 DelayQueue 而不是 Quartz？

**答**：Crane 选择自研基于 DelayQueue 的调度引擎，而非使用 Quartz，主要有以下原因：

1. **性能更好**：Quartz 基于线程轮询 + 数据库锁，在万级任务场景下性能瓶颈明显。DelayQueue 基于堆结构，入队和出队都是 O(log n)，性能更优。
2. **去数据库依赖**：Quartz 依赖数据库锁来保证集群调度的唯一性，Crane 通过租约机制实现了同样的目标，且运行时不强依赖数据库。
3. **定制化需求**：Crane 需要支持延迟任务、分片调度等定制功能，自研引擎更容易扩展。
4. **可控性**：自研引擎的代码完全可控，出问题时排查和修复更快速。

### Q2：租约机制如何防止脑裂？请详细描述。

**答**：租约机制通过**双向约束**来防止脑裂：

1. **Manager 侧**：Manager 定期对调度器发起心跳续约。如果调度器无响应，当租约到期时，Manager 认为调度器宕机，将其 Slot 重新分配给其他调度器。
2. **调度器侧**：调度器持有租约才能调度。如果自己的租约过期（没有收到 Manager 的续约响应），调度器会**主动停止调度**。

关键在于：即使网络分区导致 Manager 和调度器无法通信，双方都会在租约到期时做出正确的反应——Manager 会摘除该调度器并重新分配 Slot，而调度器会主动停止调度。因此不会出现"两边同时调度同一个任务"的情况。

此外，Crane 还通过**安全余量**处理时钟偏移：调度器提前 2 秒停止调度，Manager 延后 2 秒摘除调度器，形成 4 秒的安全间隙。

### Q3：Slot 机制和一致性哈希有什么区别？

**答**：Slot 机制是一致性哈希的工程化变体，主要区别：

1. **分配方式**：一致性哈希将节点映射到连续的哈希环上，key 顺时针找到第一个节点。Slot 机制将固定数量的虚拟槽平均分配给各节点，key 对槽取模。
2. **粒度控制**：一致性哈希中节点变化影响的是连续区间，难以精确控制。Slot 机制可以精确到单个槽的迁移，运维更可控。
3. **负载均衡**：一致性哈希依赖虚拟节点数量来平衡负载，Slot 机制天然均匀（取模分配）。
4. **可运维性**：Slot 机制支持人工指定某个 Slot 的归属，支持灰度发布。一致性哈希难以做到这一点。

### Q4：Crane 的 ZK 弱依赖具体是怎么实现的？

**答**：ZK 弱依赖的核心是**本地缓存**：

1. 调度器启动时，从 ZK 全量加载客户端注册信息，缓存到本地内存。
2. 运行时调度时，直接从内存缓存中读取客户端列表，不访问 ZK。
3. ZK 上的节点变更通过 Watcher 机制异步更新到本地缓存。
4. 如果 ZK 宕机，调度器使用缓存中的客户端列表继续调度。
5. 新上线的客户端在 ZK 恢复前无法被调度器感知（这是可接受的降级）。

关键设计：2017 年 Crane 将客户端注册节点从临时节点改为持久节点，避免 ZK Session 过期导致客户端被误判下线。

### Q5：DelayQueue 的 take() 方法为什么只有一个线程在消费？

**答**：DelayQueue 的 take() 方法使用了 Leader-Follower 模式。同一时刻只有一个线程（Leader）在等待堆顶元素到期，其他线程无限等待。

原因在于：DelayQueue 底层是 PriorityQueue（小顶堆），堆顶元素总是最早到期的。多线程同时 take() 不会有性能提升，反而会因为锁竞争降低性能。因此，用一个线程消费、取出后交给独立的线程池处理，是更高效的设计。

### Q6：Crane 如何保证任务不被重复调度？

**答**：Crane 通过多层机制保证不被重复调度：

1. **Slot 机制**：每个任务通过 hash 映射到唯一 Slot，每个 Slot 只由一个调度器负责。
2. **租约机制**：只有持有有效租约的调度器才能调度，租约过期后立即停止。
3. **Slot 迁移时序控制**：先通知旧调度器释放，确认释放后再通知新调度器接管。
4. **调度去重**：调度器在派发前检查 DB 中是否已有相同 trigger_time 的记录（兜底机制）。

### Q7：分片任务和广播任务有什么区别？

**答**：

- **分片任务**：将一个大任务拆分成 N 个分片，分配到 M 台机器并行执行，每台机器处理不同的分片。适用于数据量大、需要并行处理的场景。
- **广播任务**：同一任务在所有注册的客户端机器上同时执行，每台机器执行相同的逻辑。适用于刷新本地缓存、清理本地文件等场景。

关键区别：分片任务中每台机器处理的数据不同（互补），广播任务中每台机器处理的数据相同（冗余）。

### Q8：DAG 任务如何实现并行度控制？

**答**：DAG 任务通过拓扑排序确定执行层级，同一层级的任务可以并行执行：

1. 对 DAG 进行拓扑排序，计算每个节点的入度。
2. 入度为 0 的节点可以立即提交执行。
3. 每个节点执行完后，减少其后继节点的入度。
4. 后继节点入度变为 0 时，提交执行。
5. 使用 CountDownLatch 等待所有前置任务完成。

此外，Crane 还支持配置全局并发度限制，防止同时执行过多子任务导致资源耗尽。

### Q9：Crane 的调度延迟 TP99 为什么能做到 < 100ms？

**答**：关键因素包括：

1. **DelayQueue 的 O(log n) 复杂度**：即使 77,000 个任务，log(77000) ≈ 16，操作极快。
2. **Leader-Follower 模式**：减少线程竞争。
3. **异步派发**：调度线程只负责取出实例，HTTP 派发交给独立线程池。
4. **G1GC 调优**：将 GC 停顿控制在 100ms 以内。
5. **弱依赖设计**：运行时不等待 DB 和 ZK 的响应。
6. **NIO HTTP 客户端**：非阻塞 IO 提高派发效率。

### Q10：如果 Crane 调度器全部宕机，会发生什么？

**答**：调度器全部宕机意味着所有定时任务停止调度。但影响范围可控：

1. **已有任务不执行**：到期的任务不会被触发。
2. **客户端不受影响**：业务服务正常运行，只是定时任务不执行。
3. **数据不丢失**：任务配置存在 DB 中，调度器恢复后可以重新加载。
4. **恢复时间**：调度器重启后，从 DB 加载任务、从 ZK 加载客户端列表，约 1~2 分钟恢复正常调度。

### Q11：Crane 如何处理任务执行超时？

**答**：Crane 在客户端侧通过 `Future.get(timeout)` 实现超时控制：

1. 调度器在派发指令时携带超时时间参数。
2. 客户端收到指令后，将任务提交到独立线程池执行。
3. 使用 `Future.get(timeoutMs)` 等待执行结果。
4. 如果超时，调用 `future.cancel(true)` 尝试中断执行线程。
5. 将超时结果上报给调度器，调度器记录超时状态。
6. 根据配置决定是否重试。

注意：`cancel(true)` 只是设置中断标志，如果业务代码不响应中断，线程仍会继续执行。因此业务代码应定期检查 `Thread.interrupted()`。

### Q12：Crane 的延迟任务为什么用分层时间轮而不是 DelayQueue？

**答**：延迟任务和周期任务的需求不同：

1. **任务量不同**：周期任务在万级，延迟任务在百万级。DelayQueue 的 O(log n) 在百万级时性能下降，而时间轮的 O(1) 优势明显。
2. **到期分布不同**：周期任务的触发时间分散在不同时间点，延迟任务可能大量集中在同一秒到期。时间轮的槽位设计天然支持批量到期处理。
3. **内存效率**：百万级任务在 DelayQueue 中需要连续数组存储，内存压力大。时间轮的链表结构更灵活。

### Q13：Crane 为什么按 BG 隔离部署？

**答**：BG 隔离部署的核心目的是**故障隔离**：

1. 一个 BG 的调度器故障不会影响其他 BG。
2. 一个 BG 的任务量突增不会挤占其他 BG 的调度资源。
3. 不同 BG 可以独立升级、独立扩容。
4. 不同 BG 的安全合规要求不同（如金融 BG 要求更高），隔离部署便于满足合规。

### Q14：Manager 的选主为什么用 ZK 而不是 Raft？

**答**：Crane 选择 ZK 选主而非自研 Raft 的原因：

1. **已有基础设施**：Crane 已经使用 ZK 作为注册中心，复用 ZK 的选主能力成本最低。
2. **成熟可靠**：ZK 的 ZAB 协议经过大规模生产验证，自研 Raft 有额外风险。
3. **运维统一**：运维团队已有 ZK 的运维经验。
4. **选主频率低**：Manager 主节点切换是低频事件（仅在主节点宕机时），ZK 的性能完全够用。

### Q15：Crane 如何处理客户端（业务机器）动态上下线？

**答**：

1. **上线**：客户端启动时，在 ZK 创建持久节点，包含 IP、端口、任务列表等信息。调度器的 Watcher 收到 NodeCreated 事件，更新本地缓存。
2. **正常下线**：客户端正常关闭时，删除 ZK 节点。调度器的 Watcher 收到 NodeDeleted 事件，从本地缓存中移除。
3. **异常下线**：客户端宕机无法主动删除 ZK 节点。Monitor 模块定期心跳检测，发现无响应后，由 Manager 删除 ZK 节点。
4. **调度自适应**：调度器在选择目标客户端时，只从存活的客户端列表中选择。

### Q16：Crane 的监控指标有哪些？如何定位调度延迟问题？

**答**：核心监控指标包括调度延迟、执行成功率、GC 耗时、线程池状态、DelayQueue 大小等。

定位调度延迟问题的步骤：
1. 查看 GC 监控，是否有长时间 STW。
2. 查看 DelayQueue 大小，是否积压。
3. 查看派发线程池，是否队列满触发拒绝策略。
4. 查看是否有高频任务或广播任务导致实例暴增。
5. 查看网络 IO，是否有大量超时重连。

### Q17：Crane 的数据归档策略是什么？

**答**：Crane 对调度记录和执行日志采用三级归档：

1. **热数据**（7天内）：存储在 MySQL 的 schedule_log 表中，支持快速查询。
2. **温数据**（7~30天）：归档到 schedule_log_archive 表，降低热表大小。
3. **冷数据**（30天以上）：导出到 Hive，从 MySQL 删除。

每天凌晨 3 点由定时任务执行归档操作，分批迁移和删除，避免锁表。

### Q18：Crane 如何支持灰度发布？

**答**：架构 2.0 支持基于 Slot 的灰度发布：

1. 选择少量 Slot（如 10 个，占比 < 1%），迁移到新版本调度器。
2. 观察 10 分钟，检查调度延迟和成功率。
3. 无异常则逐步扩大灰度范围（10% → 50% → 100%）。
4. 有异常则秒级回滚（Slot 快照恢复）。

相比架构 1.0 的机器维度灰度（1/3 影响面），Slot 维度灰度可以将影响面控制在 < 1%。

### Q19：Crane 的 DAG 任务中，如果某个子任务失败，后续任务怎么处理？

**答**：Crane DAG 任务支持配置失败处理策略：

1. **继续执行**：某个子任务失败后，不影响后续无依赖关系的子任务执行。但依赖该失败任务的所有后继任务会被标记为 SKIPPED。
2. **终止执行**：某个子任务失败后，整个 DAG 实例标记为 FAILED，所有未执行的子任务标记为 SKIPPED。
3. **重试后继续**：失败的子任务按配置重试，重试成功后继续执行后继任务。

### Q20：Crane 未来计划支持 MapReduce 模式，这与分片任务有什么区别？

**答**：

- **分片任务（静态分片）**：调度前确定分片数和每台机器处理的数据范围。如果某台机器处理慢或失败，整批数据无法完成。缺乏动态扩展能力。
- **MapReduce（动态分片）**：调度器将大任务拆分成多个小 Task，动态分配给空闲的客户端机器执行。某台机器处理完后可以继续领取新 Task。如果某台机器失败，其 Task 可以重新分配。支持 Map 阶段（数据分片处理）和 Reduce 阶段（结果聚合）。

核心区别：分片任务是静态的、一次性的分配；MapReduce 是动态的、流式的分配，具有更好的弹性和容错性。

---

## 二十九、附录

### 29.1 完整配置参考

```yaml
# xxljob-scheduler-full-config.yaml
# Crane 调度器完整配置参考

xxljob:
  # 基础配置
  app:
    name: xxljob-scheduler
    bg: waimai                    # 所属 BG
    cluster: default              # 集群名称
    env: prod                     # 环境
    
  # 调度引擎
  scheduler:
    # DelayQueue 配置
    delay-queue:
      max-size: 10000
      expire-threshold-ms: 300000
    
    # 派发线程池
    dispatch-pool:
      core-size: 32
      max-size: 64
      queue-capacity: 2000
      keep-alive-seconds: 60
      rejected-policy: CALLER_RUNS
    
    # 回写线程池
    callback-pool:
      core-size: 16
      max-size: 32
      queue-capacity: 1000
    
    # HTTP 客户端
    http-client:
      max-connections: 200
      connect-timeout-ms: 1000
      read-timeout-ms: 5000
      max-per-route: 50
      retry-count: 3
    
    # 租约配置
    lease:
      period-ms: 15000
      renew-window-ms: 10000
      safety-margin-ms: 2000
      heartbeat-interval-ms: 5000
    
    # Slot 配置
    slot:
      total-count: 1024           # Slot 总数
      migration-batch-size: 10    # 迁移批次大小
      migration-timeout-ms: 30000 # 迁移超时
    
    # 监控配置
    metrics:
      report-interval-ms: 10000
      latency-buckets: [0, 20, 50, 100, 500, 1000, 5000]
    
    # 告警配置
    alarm:
      dispatch-latency-warn-ms: 500
      dispatch-latency-critical-ms: 1000
      success-rate-warn: 0.95
      success-rate-critical: 0.80
  
  # 数据库配置
  datasource:
    url: jdbc:mysql://xxljob-db:3306/xxljob_waimai
    username: xxljob_app
    password: ${XXLJOB_DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  # Zookeeper 配置
  zookeeper:
    connect-string: zk1:2181,zk2:2181,zk3:2181,zk4:2181,zk5:2181
    session-timeout-ms: 30000
    connection-timeout-ms: 5000
    retry-interval-ms: 1000
    max-retries: 3
    namespace: xxljob
  
  # Manager 配置
  manager:
    election-path: /xxljob/manager/master
    heartbeat-interval-ms: 5000
    max-heartbeat-failures: 3
```

### 29.2 Maven 依赖

```xml
<!-- pom.xml -->

<!-- Crane 客户端（必选） -->
<dependency>
    <groupId>com.sankuai.service.mobile</groupId>
    <artifactId>xxljob-client</artifactId>
    <version>2.1.0</version>
</dependency>

<!-- Crane 延迟任务客户端（可选） -->
<dependency>
    <groupId>com.sankuai.service.mobile</groupId>
    <artifactId>xxljob-delay-client</artifactId>
    <version>2.1.0</version>
</dependency>

<!-- Crane 分片任务支持（可选） -->
<dependency>
    <groupId>com.sankuai.service.mobile</groupId>
    <artifactId>xxljob-shard-support</artifactId>
    <version>2.1.0</version>
</dependency>
```

### 29.3 Spring Boot 集成示例

```java
// Spring Boot 配置类
@Configuration
@EnableCrane
public class CraneConfiguration {
    
    @Bean
    public CraneProperties xxljobProperties() {
        CraneProperties props = new CraneProperties();
        props.setAppKey("com.sankuai.order");
        props.setServerPort(8410);
        props.setZkConnectString("zk1:2181,zk2:2181,zk3:2181");
        return props;
    }
}

// 任务定义
@Component
@CraneConfiguration
public class OrderTasks {
    
    @Resource
    private OrderRepository orderRepository;
    
    @Resource
    private EmailService emailService;
    
    // 单节点任务：每天凌晨 2 点清理过期订单
    @Crane("com.sankuai.order.clean-expired-orders")
    public void cleanExpiredOrders() {
        int count = orderRepository.deleteByCreateTimeBefore(
            LocalDateTime.now().minusDays(30));
        log.info("清理过期订单完成，共删除 {} 条", count);
    }
    
    // 分片任务：每小时刷新订单状态
    @Crane(value = "com.sankuai.order.refresh-status", 
           scheduleType = ScheduleType.SHARD,
           shardCount = 4)
    public void refreshOrderStatus() {
        List<Integer> shards = ShardItemsContext.getShardItems();
        int shardCount = ShardItemsContext.getShardCount();
        
        for (Integer shard : shards) {
            List<Order> orders = orderRepository.findByIdMod(shardCount, shard);
            for (Order order : orders) {
                order.refreshStatus();
            }
            log.info("分片 {} 刷新完成，处理 {} 条订单", shard, orders.size());
        }
    }
    
    // 广播任务：每 5 分钟刷新本地缓存
    @Crane(value = "com.sankuai.order.refresh-cache",
           scheduleType = ScheduleType.BROADCAST)
    public void refreshLocalCache() {
        orderCache.refresh();
        log.info("本地缓存刷新完成");
    }
}

// DAG 任务定义
@Component
@CraneConfiguration
public class ReportTasks {
    
    // DAG 根任务：数据抽取
    @Crane(value = "com.sankuai.report.extract-data",
           dagId = "daily-report-dag",
           dagNode = "extract")
    public void extractData() {
        // 从数据源抽取数据
    }
    
    // DAG 子任务：数据清洗（依赖 extract）
    @Crane(value = "com.sankuai.report.clean-data",
           dagId = "daily-report-dag",
           dagNode = "clean",
           dependsOn = {"extract"})
    public void cleanData() {
        // 清洗数据
    }
    
    // DAG 子任务：生成报表（依赖 clean）
    @Crane(value = "com.sankuai.report.generate",
           dagId = "daily-report-dag",
           dagNode = "generate",
           dependsOn = {"clean"})
    public void generateReport() {
        // 生成报表并发送邮件
    }
}

// 延迟任务（注意：目前已停止新接入，建议使用 Mafka 延迟消息）
// @Crane("com.sankuai.order.check-payment-timeout")
// public void checkPaymentTimeout(String orderId) {
//     Order order = orderRepository.findById(orderId);
//     if (order.getStatus() == OrderStatus.UNPAID) {
//         order.cancel("超时未支付自动取消");
//     }
// }
```

### 29.4 API 文档

```java
// Crane OpenAPI 接口（HTTP）

// 1. 手动触发任务
// POST /api/v1/task/trigger
// Body:
{
    "taskName": "com.sankuai.order.clean-expired-orders",
    "params": {
        "days": "30"
    },
    "executeType": "IMMEDIATE"  // IMMEDIATE=立即执行, NEXT_CYCLE=下一周期
}
// Response:
{
    "code": 200,
    "data": {
        "instanceId": "ins_20250115_001",
        "triggerTime": "2025-01-15T10:30:00"
    }
}

// 2. 查询任务状态
// GET /api/v1/task/status?taskName=com.sankuai.order.clean-expired-orders
// Response:
{
    "code": 200,
    "data": {
        "taskName": "com.sankuai.order.clean-expired-orders",
        "status": "ENABLED",
        "lastTriggerTime": "2025-01-15T02:00:00",
        "lastExecStatus": "SUCCESS",
        "lastExecDuration": 3500,
        "nextTriggerTime": "2025-01-16T02:00:00"
    }
}

// 3. 暂停任务
// POST /api/v1/task/pause
// Body:
{
    "taskName": "com.sankuai.order.clean-expired-orders"
}

// 4. 恢复任务
// POST /api/v1/task/resume
// Body:
{
    "taskName": "com.sankuai.order.clean-expired-orders"
}

// 5. 查询调度历史
// GET /api/v1/task/history?taskName=xxx&startTime=xxx&endTime=xxx&page=1&size=20
// Response:
{
    "code": 200,
    "data": {
        "total": 100,
        "page": 1,
        "size": 20,
        "records": [
            {
                "triggerTime": "2025-01-15T02:00:00",
                "dispatchTime": "2025-01-15T02:00:00.015",
                "status": "SUCCESS",
                "execDuration": 3500,
                "clientIp": "10.0.0.100"
            }
        ]
    }
}
```

### 29.5 Cron 表达式大全

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                    Cron 表达式语法说明                            │
  ├─────────────────────────────────────────────────────────────────┤
  │                                                                 │
  │  格式：[秒] [分] [时] [日] [月] [周] [年]                        │
  │                                                                 │
  │  字段    允许值          允许的特殊字符                           │
  │  ─────  ──────────────  ──────────────────                       │
  │  秒      0-59            , - * /                                 │
  │  分      0-59            , - * /                                 │
  │  时      0-23            , - * /                                 │
  │  日      1-31            , - * ? / L W                           │
  │  月      1-12 or JAN-DEC , - * /                                 │
  │  周      1-7 or SUN-SAT  , - * ? / L #                           │
  │  年      1970-2099       , - * /                                 │
  │                                                                 │
  │  特殊字符说明：                                                  │
  │  *  表示所有值                                                    │
  │  ?  表示不指定（仅用于日和周，二者互斥）                           │
  │  -  表示范围                                                      │
  │  ,  表示列举                                                      │
  │  /  表示增量                                                      │
  │  L  表示最后（Last）                                              │
  │  W  表示最近的工作日（Weekday）                                   │
  │  #  表示第几周                                                    │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘
```

| Cron 表达式 | 含义 | 典型场景 |
|------------|------|---------|
| `0 * * * * ?` | 每分钟 | 高频缓存刷新 |
| `0 */5 * * * ?` | 每 5 分钟 | 缓存刷新、状态检查 |
| `0 */30 * * * ?` | 每 30 分钟 | 数据同步 |
| `0 0 * * * ?` | 每小时整点 | 定时同步 |
| `0 0 */2 * * ?` | 每 2 小时 | 日志归档 |
| `0 0 0 * * ?` | 每天凌晨 0 点 | 日终对账 |
| `0 0 2 * * ?` | 每天凌晨 2 点 | 数据清理 |
| `0 0 6 * * ?` | 每天早上 6 点 | 晨报生成 |
| `0 0 9 * * ?` | 每天上午 9 点 | 每日提醒 |
| `0 0 18 * * ?` | 每天下午 6 点 | 下班提醒 |
| `0 0 22 * * ?` | 每天晚上 10 点 | 夜间批处理 |
| `0 0 9 * * MON-FRI` | 工作日早上 9 点 | 工作日提醒 |
| `0 0 9 * * MON` | 每周一早上 9 点 | 周会提醒 |
| `0 0 18 ? * FRI` | 每周五下午 6 点 | 周报发送 |
| `0 0 0 1 * ?` | 每月 1 号凌晨 0 点 | 月初结算 |
| `0 0 2 1 * ?` | 每月 1 号凌晨 2 点 | 月报生成 |
| `0 0 0 L * ?` | 每月最后一天凌晨 0 点 | 月末结算 |
| `0 0 0 1 1 ?` | 每年 1 月 1 号凌晨 0 点 | 年初任务 |
| `0 0 0 1 1 ? 2026` | 2026 年 1 月 1 号凌晨 0 点 | 一次性任务 |
| `0 0 12 * * ?` | 每天中午 12 点 | 午间任务 |
| `0 15 10 * * ?` | 每天上午 10:15 | 定时检查 |
| `0 15 10 * * ? 2025` | 2025 年每天上午 10:15 | 年度定时任务 |
| `0 0 9 1W * ?` | 每月第一个工作日上午 9 点 | 月度例会提醒 |
| `0 0 10 ? * 6#3` | 每月第三个周六上午 10 点 | 月度活动 |
| `0 0/5 14 * * ?` | 每天下午 2 点到 2:55，每 5 分钟 | 下午时段任务 |
| `0 0/5 14,18 * * ?` | 每天下午 2 点和 6 点时段，每 5 分钟 | 多时段任务 |
| `0 0 12 ? * WED` | 每周三中午 12 点 | 周中任务 |
| `0 30 9 * * ?` | 每天上午 9:30 | 每日晨会 |
| `0 0 8,12,18 * * ?` | 每天 8 点、12 点、18 点 | 一天三次 |
| `0 0 0 1,15 * ?` | 每月 1 号和 15 号凌晨 0 点 | 半月任务 |
| `0 0 0 1 * JAN,JUL ?` | 每年 1 月和 7 月的 1 号 | 半年任务 |

### 29.6 常用运维命令

```bash
# 1. 查看调度器状态
curl http://xxljob-scheduler:8080/api/health

# 2. 查看调度器 Slot 分配
curl http://xxljob-scheduler:8080/api/slots

# 3. 查看调度器线程池状态
curl http://xxljob-scheduler:8080/api/metrics/threadpool

# 4. 查看调度延迟监控
curl http://xxljob-scheduler:8080/api/metrics/dispatch-latency

# 5. 手动触发任务
curl -X POST http://xxljob-portal:80/api/v1/task/trigger \
  -H "Content-Type: application/json" \
  -d '{"taskName": "com.sankuai.order.clean-expired-orders"}'

# 6. 暂停任务
curl -X POST http://xxljob-portal:80/api/v1/task/pause \
  -H "Content-Type: application/json" \
  -d '{"taskName": "com.sankuai.order.clean-expired-orders"}'

# 7. 查看任务调度历史
curl "http://xxljob-portal:80/api/v1/task/history?taskName=com.sankuai.order.clean-expired-orders&startTime=2025-01-15&endTime=2025-01-16"

# 8. 查看 ZK 节点
zkCli.sh -server zk1:2181
[zk: localhost:2181] ls /xxljob/scheduler
[zk: localhost:2181] ls /xxljob/client/com.sankuai.order
[zk: localhost:2181] get /xxljob/manager/master

# 9. 查看 GC 日志
tail -f /var/log/xxljob/gc.log

# 10. 查看调度器日志
tail -f /var/log/xxljob/scheduler.log | grep -E "ERROR|WARN"

# 11. jstack 分析线程状态
jstack <pid> > /tmp/jstack.txt

# 12. jmap 分析堆内存
jmap -histo <pid> | head -50
jmap -dump:format=b,file=/tmp/heap.hprof <pid>
```

### 29.7 Crane 核心概念索引表

| 概念 | 所在章节 | 关键要点 |
|------|---------|---------|
| DelayQueue | 四、十四 | 基于 PriorityQueue 的小顶堆，O(log n) 复杂度 |
| 调度实例 | 四、十四 | Delayed 接口实现，包含任务名和触发时间 |
| Slot 虚拟槽 | 五、十六 | 固定 1024 个槽，任务 hash 取模映射 |
| 租约机制 | 五、十七 | 双向约束防脑裂，15 秒租约期 |
| Manager 选主 | 五 | ZK 临时有序节点，6 秒切换 |
| BG 隔离 | 三、八 | 7 个 BG 集群完全隔离 |
| ZK 弱依赖 | 八、二十四 | 本地缓存 + Watcher 异步更新 |
| DB 弱依赖 | 八、二十五 | 内存缓存 + 异步写入 |
| 分片调度 | 七、二十 | 取模/范围/哈希分片，推荐分片数=机器数×2 |
| DAG 任务 | 六、十八 | 拓扑排序 + 并行执行 + 状态机 |
| 延迟任务 | 六、十九 | 分层时间轮 + DB 持久化 |
| 客户端框架 | 二十一 | 反射调用 + 线程隔离 + 超时控制 |
| 高可用 2.0 | 八、二十三 | Slot 灰度 + 秒级回滚 + 无状态运行时 |
| 监控告警 | 二十二 | 四级告警 + 分桶统计 + 滑动窗口 |
| 故障案例 | 二十六 | 延迟飙升/任务丢失/重复执行/脑裂恢复 |

---

> **补充说明**
> 
> 本文为 Crane 分布式任务调度深度解析的扩展篇，在前 13 章基础内容之上，深入到源码级别剖析了 DelayQueue、租约协议、Slot 分配、DAG 引擎等核心技术实现，并补充了数据库设计、故障案例、性能调优、面试问答等内容。
> 
> 如果你是第一次阅读本文，建议先通读前 13 章建立整体认识，再根据需要深入阅读感兴趣的章节。每一章都尽量做到自包含，可以独立阅读。
> 
> 文中涉及的源码和配置均为基于公开资料整理的**简化模型**，旨在帮助理解设计原理，不代表 Crane 系统的真实代码实现。如需了解最准确的信息，请参考美团内部文档。
> 
> **参考资料**（与前文一致，此处不再重复列出）
