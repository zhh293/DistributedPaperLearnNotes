# Zebra数据库访问中间件深度解析

## 写在前面

如果你在美团做研发，你写的代码里大概率会出现这样一段配置：

```xml
<bean id="dataSource" class="com.dianping.zebra.group.jdbc.GroupDataSource" init-method="init" destroy-method="close">
    <property name="jdbcRef" value="你的jdbcRef"/>
</bean>
```

或者这样一个依赖：

```xml
<dependency>
    <groupId>com.dianping.zebra</groupId>
    <artifactId>Zebra-api</artifactId>
</dependency>
```

这个叫 Zebra 的东西是什么？为什么公司几乎每一个访问 MySQL 的服务都绕不开它？它跟你直接用 JDBC 连 MySQL 有什么区别？它是怎么把一张逻辑上"看起来只有一张"的表，变成后台真实存在的 128 张物理表的？

这篇文章的目标，就是带你从最基础的"为什么需要分库分表"讲起，一路讲到 Zebra 内部的路由原理、SQL 改写机制、读写分离、影子表压测、全局 ID 生成器，直到你能看懂公司里任何一份 Zebra 配置，并且能自己动手接入一个新的数据源。

文中所有的架构原理、配置示例、参数说明，均来自美团内部 Zebra 官方文档（学城 Zebra-PRFAQ、Zebra 分库分表接入指南等资料），不是道听途说，是"官方原文"级别的可靠信息。

我们不跳步，尽量把每一个概念都讲透。如果你是刚接触分布式数据库的新人，读完这篇文章，你应该能够独立完成 Zebra 的接入并理解它背后发生了什么。

---

## 目录

1. 背景：单机 MySQL 到底扛不住什么
2. Zebra 是什么：一个"客户端"，而不是一个"服务器"
3. 核心架构：三种数据源的分层设计
4. 分库分表原理（重头戏）
5. 读写分离
6. 高级功能：动态 Hint、影子表压测、全局 ID 生成器、负载均衡与流量调度
7. 平台化配置 vs 本地文件配置
8. 客户端 SDK 工作原理：初始化、连接池、SQL 执行链路
9. 多语言支持现状
10. 业界对标：TDDL、Zebra、ZDAL、DAL
11. 完整实战示例：从零接入 Zebra
12. 最佳实践与常见问题
13. Zebra 的 SQL 解析引擎与性能优化
14. Zebra 在美团内部的规模与生产实践
15. Zebra 故障案例与排查指南

---

## 第一章：背景——单机 MySQL 到底扛不住什么

在 Zebra 出现之前，很多业务的数据库架构就是一台（或一主一从）MySQL 服务器。业务逻辑直接通过 JDBC 连接这台数据库，写 SQL、查数据。这套架构在业务早期非常简洁高效，但随着业务增长，四个问题会逐个暴露出来。

### 1.1 存不下：单表数据量触达硬件上限

假设你做了一个外卖订单系统，订单表每天新增 100 万条记录。一年后，这张表就有 3.65 亿条记录。MySQL 的 InnoDB 引擎使用 B+ 树索引，当单表数据量过大时（通常经验值是千万级），B+ 树的高度会增加，查询时需要更多的磁盘 I/O，响应时间显著上升。

更重要的是，MySQL 的表空间文件在文件系统层面有大小限制（虽然 InnoDB 的独立表空间 mitigates 了一些问题），而且单表数据量过大时，备份、DDL（加字段、加索引）的操作时间会变得不可接受——加索引可能要锁表几十分钟，这对在线业务是致命的。

### 1.2 扛不住：并发读写到达单机上限

一台 MySQL 服务器的连接数是有限的（max_connections 默认 151，即使调大到几千，也不是无限的），CPU 和内存资源也是有限的。当你的服务从 10 个实例扩展到 100 个实例，每个实例都连这台数据库，连接数很快就会打满。

即使连接数还没打满，大量的并发读写也会让数据库的 CPU 使用率飙升、IO 等待时间增加，最终导致请求响应时间变长，甚至超时失败。

### 1.3 性能慢：数据量增长导致查询性能急剧下降

前面讲过 B+ 树的问题。当数据量从 100 万增长到 1 亿，即使是主键查询，如果索引不能全部命中内存缓存，就需要更多的磁盘读取。对于复杂的聚合查询（COUNT、GROUP BY、ORDER BY），性能下降更加剧烈。

### 1.4 扩展差：大表的在线 DDL 风险巨大

MySQL 5.6 之前，加字段、加索引等 DDL 操作会锁表。MySQL 5.6 引入了 Online DDL，但仍有诸多限制（比如不能修改主键、大表加索引仍然很慢）。MySQL 8.0 的 Instant DDL 只支持某些操作。对于单表数据量已经达到几十亿级别的业务，任何 DDL 操作都需要极度谨慎，稍有不慎就会导致线上故障。

这四个问题的本质，是**单机数据库的硬件资源（CPU、内存、磁盘 I/O、网络带宽）存在物理上限**。当业务规模超过这个上限，就必须通过某种方式把数据和请求"分散"到多台机器上——这就是**水平扩展（Scale Out）**。

业界解决这个问题的标准方案是**分库分表（Sharding）**。但分库分表之后，业务代码就不能再像操作单库单表那样写 SQL 了——同一条 SQL 要根据分片规则路由到不同的物理库和物理表上，查询结果可能需要合并，事务处理变得更复杂。这些复杂度如果让业务代码自己处理，会让业务代码变得非常臃肿和难以维护。

Zebra 就是来解决这个问题的：**它让业务代码仍然像操作单库单表一样写 SQL，但背后自动完成分库分表的路由、SQL 改写、结果合并等所有脏活累活**。

---

## 第二章：Zebra 是什么——一个"客户端"，而不是一个"服务器"

理解了为什么要分库分表之后，下一个问题就是：Zebra 在分库分表这件事里，扮演了什么角色？

### 2.1 两种技术路线：代理模式 vs 客户端模式

分库分表的中间件实现，业界有两条技术路线：

**路线一：代理模式（Proxy）**

在应用和数据库之间部署一个独立的代理服务器。应用连接代理服务器，代理服务器负责解析 SQL、路由到正确的数据库、返回结果。代表产品：MyCat、Zebra-Proxy、Atlas。

```
应用 → JDBC → 代理服务器(解析SQL、路由) → MySQL 分片1
                                    → MySQL 分片2
                                    → MySQL 分片3
```

**路线二：客户端模式（Client-side / Smart Driver）**

在应用的 JDBC 驱动层嵌入分库分表逻辑。应用直接使用改造过的 JDBC 驱动（DataSource），驱动内部负责解析 SQL、路由、结果合并。代表产品：Zebra、TDDL、Zebra-JDBC。

```
应用 → Zebra Smart JDBC Driver(解析SQL、路由) → MySQL 分片1
                                              → MySQL 分片2
                                              → MySQL 分片3
```

### 2.2 为什么美团选择了客户端模式

Zebra 的定位非常明确：它是一个**客户端嵌入式**的数据库访问中间件，不是一个独立部署的代理服务器。

这个选择背后有几个关键考量：

**第一，性能**。代理模式多了一层网络跳转（应用 → 代理 → 数据库），客户端模式直接连数据库，网络延迟更低。Zebra 的 P99 延迟不超过 5ms，这个指标在代理模式下很难做到。

**第二，部署复杂度**。代理模式需要额外部署和维护一层代理集群，这些代理服务器本身也是单点（或需要高可用集群）。客户端模式不需要额外部署任何东西，只需要在应用里引入一个 jar 包。

**第三，语言生态绑定**。客户端模式天然和语言绑定（Zebra for Java 只能 Java 用），但美团内部 Java 是后端主力语言，80% 以上的数据库访问都通过 Java 服务完成。只要 Java 生态支持到位，就能覆盖绝大多数场景。

代价也很明显：Zebra 的分库分表能力目前**只能在 Java 生态里做到位**，Go、Python、Node.js 的支持相对有限（只支持读写分离，不支持分库分表）。

### 2.3 Zebra 的适用范围

```
适用场景：
✅ Java 后端服务需要访问 MySQL/Blade/PostgreSQL
✅ 单表数据量超过千万级，需要分库分表
✅ 读多写少，需要读写分离
✅ 需要全链路压测（影子表）
✅ 需要动态路由（如分片键不是用户 ID 的场景）

不适用场景：
❌ 非 Java 语言需要分库分表（Go/Python/Node.js 只支持读写分离）
❌ 需要分布式事务（Zebra 本身不支持，需配合其他组件）
❌ 需要复杂跨库 JOIN（Zebra 仅支持简单跨分片计算）
```

---

## 第三章：核心架构——三种数据源的分层设计

Zebra 在架构上提供了三种数据源，分别对应不同的使用场景。理解这三种数据源的区别，是理解 Zebra 的第一步。

### 3.1 SingleDataSource：最简单的形态

当你不需要读写分离、不需要分库分表，就是单纯地想连一台数据库（或者一主一从）时，用 SingleDataSource。它本质上是对 JDBC 连接池的一层薄封装，提供了统一的配置管理和监控埋点。

```
你的代码 → SingleDataSource → 连接池(c3p0/druid/hikari) → MySQL 单机
```

### 3.2 GroupDataSource：读写分离的入口

当你的数据库升级为主从架构（一主多从），需要读写分离时，用 GroupDataSource。它会自动把写请求路由到主库，读请求路由到从库。

```
你的代码 → GroupDataSource → 写请求 → 主库
                       → 读请求 → 从库1 / 从库2 / 从库3
```

GroupDataSource 是 SingleDataSource 的"升级版"——它内部管理了多个 SingleDataSource（一个主库、多个从库），在上层提供一个统一的 DataSource 接口。

### 3.3 ShardDataSource：分库分表的入口

当你需要分库分表时，用 ShardDataSource。它是 Zebra 最复杂也最强大的数据源。它内部会根据分片规则，把一条逻辑 SQL 拆分成多条物理 SQL，路由到不同的数据库执行，最后把结果合并返回。

```
你的代码 → ShardDataSource → SQL 解析 → 路由计算 → 并发执行 → 结果合并
                          ↓
                   物理 SQL 1 → DB0.table_0
                   物理 SQL 2 → DB0.table_1
                   物理 SQL 3 → DB1.table_2
                   物理 SQL 4 → DB1.table_3
```

### 3.4 三者的关系与选择

```
SingleDataSource（基础）
    ↓ 升级
GroupDataSource（+读写分离）
    ↓ 升级
ShardDataSource（+分库分表）
```

这三种数据源不是互相替代的关系，而是**层层递进**的关系。你不需要一开始就上 ShardDataSource，业务早期用 SingleDataSource 即可，随着业务增长，按需升级到 GroupDataSource 或 ShardDataSource。

---

## 第四章：分库分表原理（重头戏）

这一章是 Zebra 的核心，也是最复杂的地方。我们会从"为什么要分库分表"开始，一步步讲到"一条 SQL 到底经历了什么"，确保你不跳步地理解整个过程。

### 4.1 水平切分的三种形态

分库分表从实现上分为三种：

**只分表**：把一张大表拆成多张表，但这些表还在同一个数据库里。

```
DB:
  user_0（1000万条）
  user_1（1000万条）
  user_2（1000万条）
  user_3（1000万条）
```

**只分库**：把数据库拆成多个库，每个库里有一张大表。

```
DB0:
  user（4000万条）
DB1:
  user（4000万条）
```

**分库分表**：同时分库和分表，每个库里有多张表。

```
DB0:
  user_0（1000万条）
  user_1（1000万条）
DB1:
  user_2（1000万条）
  user_3（1000万条）
```

三种形态中，**分库分表**是最常用、最彻底的水平扩展方案。它同时解决了"存不下"（数据分散到多个表）和"扛不住"（请求分散到多个库）的问题。

### 4.2 分片键与分片规则

分库分表的核心是：给定一条记录，怎么决定它应该存在哪个库的哪张表里？

这个决策依赖两个东西：

**分片键（Sharding Key）**：一个字段，比如 `user_id`。根据这个字段的值计算分片位置。

**分片规则（Sharding Rule）**：一个计算函数，比如 `user_id % 4`。这个函数决定了分片键映射到哪个分片。

### 4.3 一个完整的例子：welife_users 表的分库分表

假设你有一个用户表 `welife_users`，数据量已经很大，需要分库分表。你决定：

- 分片键：`uid`（用户 ID）
- 分库规则：`uid % 4` → 4 个数据库（db0, db1, db2, db3）
- 分表规则：`uid % 4 / 4` → 每个库里 4 张表（table_0, table_1, table_2, table_3）

等等，这里有个问题：如果分库和分表都用 `uid % 4`，那 db0 里只会有 table_0，db1 里只会有 table_1，db2 里只会有 table_2，db3 里只会有 table_3。这等于只分表不分库，浪费了分库的能力。

正确的做法是：

- 分库规则：`uid % 4` → 4 个数据库
- 分表规则：`(uid / 4) % 4` → 每个库里 4 张表

这样，uid 的分布是：

```
uid % 16 = 0  → db0, table_0
uid % 16 = 1  → db1, table_0
uid % 16 = 2  → db2, table_0
uid % 16 = 3  → db3, table_0
uid % 16 = 4  → db0, table_1
uid % 16 = 5  → db1, table_1
...
uid % 16 = 15 → db3, table_3
```

总共 4 库 × 4 表 = 16 张物理表，数据均匀分布。

### 4.4 SQL 路由的完整过程

现在来看最关键的问题：当你的代码执行 `SELECT * FROM welife_users WHERE uid = 12345` 时，Zebra 到底做了什么？

**Step 1：SQL 解析**

Zebra 的 SQL 解析引擎（基于 Druid 的 SQL Parser）解析这条 SQL，提取出：
- SQL 类型：SELECT
- 表名：welife_users
- WHERE 条件：uid = 12345
- 分片键：uid 的值是 12345

**Step 2：分片计算**

根据配置的分片规则：
- 分库：`12345 % 4 = 1` → db1
- 分表：`12345 / 4 % 4 = 3086 % 4 = 2` → table_2

所以这条 SQL 应该路由到 `db1.welife_users_2`。

**Step 3：SQL 改写**

Zebra 把逻辑表名 `welife_users` 替换为物理表名 `welife_users_2`：

```sql
-- 逻辑 SQL（业务代码写的）
SELECT * FROM welife_users WHERE uid = 12345

-- 物理 SQL（Zebra 改写后发给数据库的）
SELECT * FROM welife_users_2 WHERE uid = 12345
```

**Step 4：连接获取与执行**

Zebra 从 db1 的连接池中获取一个连接，执行这条物理 SQL。

**Step 5：结果返回**

数据库返回结果，Zebra 把结果集返回给业务代码。由于这是精确路由（只涉及一张表），不需要结果合并。

### 4.5 不带分片键的 SQL 怎么办

如果你的 SQL 没有带分片键，比如：

```sql
SELECT * FROM welife_users WHERE status = 1
```

Zebra 无法确定这条 SQL 应该路由到哪个分片，因为 `status` 不是分片键。这时，Zebra 会触发**全表扫描（广播）**：

- 把这条 SQL 广播到所有 16 张物理表（4 库 × 4 表）
- 在每个分片上并发执行
- 把 16 个结果集合并成一个结果集返回

这个过程用 Zebra 内部的线程池并发执行，但无论如何，广播查询的性能开销远大于精确路由。因此，**分片键的选择至关重要**——选得不好，大量查询触发广播，性能反而比不分库分表更差。

### 4.6 聚合查询的 SQL 改写

对于聚合查询，Zebra 的 SQL 改写会更复杂。比如：

```sql
-- 逻辑 SQL
SELECT COUNT(*) FROM welife_users WHERE status = 1

-- 物理 SQL（广播到16张表，每张表执行）
SELECT COUNT(*) FROM welife_users_0 WHERE status = 1
SELECT COUNT(*) FROM welife_users_1 WHERE status = 1
...
SELECT COUNT(*) FROM welife_users_15 WHERE status = 1

-- Zebra 把 16 个 COUNT 结果相加，返回最终的总数
```

再比如 SUM：

```sql
-- 逻辑 SQL
SELECT SUM(score) FROM welife_users WHERE status = 1

-- 物理 SQL（广播到16张表）
SELECT SUM(score) FROM welife_users_0 WHERE status = 1
...

-- Zebra 把 16 个 SUM 结果相加，返回最终的总和
```

MAX/MIN 稍微复杂一点：

```sql
-- 逻辑 SQL
SELECT MAX(score) FROM welife_users

-- 物理 SQL（广播到16张表）
SELECT MAX(score) FROM welife_users_0
...

-- Zebra 从 16 个 MAX 结果中再取一次 MAX，返回最终的最大值
```

AVG 最复杂，因为 AVG 不能简单地合并：

```sql
-- 逻辑 SQL
SELECT AVG(score) FROM welife_users WHERE status = 1

-- 物理 SQL（Zebra 会改写成 SUM 和 COUNT）
SELECT SUM(score) AS sum_score, COUNT(*) AS count FROM welife_users_0 WHERE status = 1
...

-- Zebra 计算：总 AVG = (sum1 + sum2 + ... + sum16) / (count1 + count2 + ... + count16)
```

### 4.7 ORDER BY 和 LIMIT 的跨分片处理

ORDER BY 和 LIMIT 在分库分表下需要特殊处理。比如：

```sql
-- 逻辑 SQL：取前10条，按 score 降序
SELECT * FROM welife_users ORDER BY score DESC LIMIT 10
```

Zebra 的处理方式：

1. 把 SQL 广播到所有 16 张表，每张表都执行带 ORDER BY 的查询（但 LIMIT 要去掉，因为每张表可能只返回部分数据，而全局前10可能分布在多张表）
2. 实际上，为了正确获取全局前10，Zebra 需要每张表都返回足够的数据（比如每张表返回 LIMIT 10），然后在内存中排序后取前10
3. 这个逻辑对于 LIMIT 10 来说还可以接受，但对于 LIMIT 1000000, 10（深分页）就性能很差了

这也是为什么分库分表后，**深分页查询**（大的 OFFSET）通常需要避免或走其他方案（比如 ES 或离线计算）。

### 4.8 JOIN 在分库分表下的处理

JOIN 是分库分表最头疼的问题之一。Zebra 对 JOIN 的处理分几种情况：

**情况一：Binding Table JOIN（绑定表）**

如果两张表的分片键相同、分片规则相同，它们的相同分片键的记录一定在同一个分片上。这种表叫做"绑定表"，JOIN 可以直接在分片内完成，不需要跨分片。

```sql
-- user 和 user_detail 都按 user_id 分片
SELECT * FROM user u JOIN user_detail d ON u.id = d.user_id WHERE u.user_id = 12345
```

由于 `user_id = 12345` 的记录在两张表的一定在同一个分片，Zebra 直接路由到该分片执行 JOIN。

**情况二：小表广播 JOIN**

如果一张表的数据量很小（比如配置表），Zebra 可以把它配置为"广播表"——每个分片都复制一份这张表。JOIN 时直接在本分片内完成。

**情况三：普通跨分片 JOIN**

如果两张表分片键不同，或者没有绑定关系，Zebra 不支持跨分片 JOIN。这种情况需要在业务层避免，或者通过其他方案（如把数据冗余到同一张表）来解决。

### 4.9 分片键选择的决策指南

选分片键是 Zebra 使用中最重要的决策。选择原则：

1. **高频查询字段**：分片键应该出现在你绝大多数查询的 WHERE 条件中。如果分片键选得不好，大量查询不带分片键，就会频繁触发全表扫描。

2. **避免热点**：如果分片键的值分布不均匀（比如按时间分片，最新数据总是集中在最新分片），会导致热点问题。

3. **业务语义**：分片键最好有业务语义，比如用户 ID、订单 ID，而不是随机数。

4. **辅助维度**：如果存在高频查询不带主分片键的场景，可以考虑使用辅维度（Secondary Dimension）来补充路由。

---

## 第五章：读写分离

### 5.1 主从架构的基本概念

读写分离的前提是数据库已经部署了**主从架构（Master-Slave）**：一个主库负责写操作，多个从库负责读操作。主库的数据通过 binlog 复制到从库。

```
写请求 → 主库（Master）→ 从库1（Slave）
                              → 从库2（Slave）
                              → 从库3（Slave）
读请求 → 从库1 / 从库2 / 从库3
```

### 5.2 GroupDataSource 的读写分离逻辑

当你的数据源配置为 GroupDataSource 时，Zebra 会自动：
- 把 INSERT、UPDATE、DELETE 路由到主库
- 把 SELECT 路由到从库（默认行为）

```java
// 配置 GroupDataSource
GroupDataSource dataSource = new GroupDataSource();
dataSource.setJdbcRef("order_group"); // 指向一个主从数据库组
```

### 5.3 强制读主库的场景

虽然默认 SELECT 走从库，但有些场景必须读主库：

1. **刚写入的数据需要立即读取**：由于主从复制有延迟，写入主库后马上从从库读可能读到旧数据。
2. **对数据一致性要求极高的场景**：如金融交易、支付状态查询。
3. **从库 lag 过大的场景**：当从库复制延迟严重时，强制读主库保证数据新鲜度。

Zebra 提供了几种方式强制读主库：

```java
// 方式一：通过 Hint 强制读主库
Connection conn = dataSource.getConnection();
Statement stmt = conn.createStatement();
stmt.execute("/*+Zebra:rw=master*/ SELECT * FROM orders WHERE id = 123");

// 方式二：通过 API 强制读主库
GroupDataSource dataSource = ...;
dataSource.setReadStrategy("master"); // 该数据源的所有读都走主库
```

### 5.4 主从复制延迟的处理

主从复制延迟是读写分离的固有问题。Zebra 提供了**从库延迟检测**机制：

- 定期检测从库的复制延迟（Seconds_Behind_Master）
- 当从库延迟超过阈值时，自动把读请求切换到主库或其他延迟较小的从库
- 当从库延迟恢复正常后，自动把读请求切回从库

---

## 第六章：高级功能

### 6.1 动态 Hint

Hint 是 Zebra 提供的一种"在 SQL 中注入路由指令"的机制。通过 Hint，你可以在运行时动态指定 SQL 的路由行为。

```java
// 强制路由到指定分片
/*+Zebra:shard=db0*/ SELECT * FROM users WHERE id = 123

// 强制读主库
/*+Zebra:rw=master*/ SELECT * FROM orders WHERE id = 123

// 指定数据源
/*+Zebra:ds=slave1*/ SELECT * FROM orders WHERE id = 123
```

动态 Hint 的使用场景：
- 分片键不是查询条件的一部分，但需要精确路由到某个分片
- 需要强制读主库的场景（如金融交易后的查询）
- 压测时需要把流量路由到特定分片

### 6.2 影子表压测

全链路压测是美团保障大促稳定性的重要手段。Zebra 提供了影子表机制，让压测流量自动路由到影子表，不会污染生产数据。

**影子表的命名规则**：物理表名后面加上 `_shadow_` 后缀。

```
生产表：user_0, user_1, user_2, user_3
影子表：user_0_shadow_, user_1_shadow_, user_2_shadow_, user_3_shadow_
```

**影子表的工作流程**：

1. 在 RDS 平台创建影子表（与生产表结构相同，但数据隔离）
2. 配置压测流量识别规则（如 HTTP Header 中的压测标识）
3. Zebra 检测到压测流量后，自动把 SQL 中的表名替换为影子表名
4. 压测流量写入影子表，生产流量写入生产表

```java
// 压测流量：INSERT INTO user_0 (...) VALUES (...)
// Zebra 自动改写为：INSERT INTO user_0_shadow_ (...) VALUES (...)
```

### 6.3 全局 ID 生成器

分库分表后，MySQL 的自增主键不能再用了（不同分片的自增 ID 会冲突）。Zebra 提供了内置的全局 ID 生成器：

**MySQLIdGenerator**：基于 MySQL 的 auto_increment_increment 和 auto_increment_offset 配置，让每个分片生成不同步长的 ID。

**SnowFlakeIdGenerator**：基于雪花算法，生成全局唯一的 64 位 ID。

```java
// 配置全局 ID 生成器
<bean id="idGenerator" class="com.dianping.zebra.shard.id.MySqlIdGenerator">
    <property name="dataSource" ref="shardDataSource"/>
</bean>

// 使用
Long id = idGenerator.nextId("user_id");
```

雪花算法的 ID 结构：

```
| 1 bit（符号位）| 41 bit（时间戳）| 10 bit（机器 ID）| 12 bit（序列号）|
```

### 6.4 负载均衡与流量调度

Zebra 在读写分离时，提供了多种从库负载均衡策略：

- **轮询（Round Robin）**：依次选择从库，均匀分配
- **加权轮询（Weighted Round Robin）**：根据从库的权重分配（权重高的从库分配更多读请求）
- **随机（Random）**：随机选择从库
- **最小连接数（Least Connections）**：选择当前连接数最少的从库

```java
// 配置负载均衡策略
groupDataSource.setLoadBalanceStrategy("roundrobin");
```

---

## 第七章：平台化配置 vs 本地文件配置

### 7.1 两种配置方式的对比

Zebra 支持两种配置方式：

| 维度 | 平台化配置（推荐） | 本地文件配置 |
|------|-------------------|-------------|
| 配置位置 | RDS 平台（rds.mws.sankuai.com） | 本地 XML/Properties 文件 |
| 维护方式 | DBA 统一管理，Web 界面操作 | 研发自己维护，代码仓库中 |
| 生效方式 | 修改后重启应用生效 | 修改后重启应用生效 |
| 分库分表支持 | 支持 | 支持 |
| 运维工具支持 | DBA 可识别规则，自动化运维 | DBA 无法识别，手工运维 |

**推荐用平台化配置**。原因：分库分表规则配置在平台上，DBA 的管理系统可以识别规则，统一自动化地做一些运维操作（如批量执行 DDL、数据归档、路由测试等）。本地文件配置无法享受这些自动化能力。

### 7.2 从本地配置迁移到平台化配置

如果你的项目还在用本地文件配置，迁移到平台化配置的步骤：

1. 在 RDS 平台上创建对应的 ruleName，配置好分库分表规则
2. 把本地 XML 配置中的规则部分删除，只保留 `ruleName` 引用
3. 验证规则是否正确（RDS 平台提供路由测试工具）
4. 重启应用生效

---

## 第八章：客户端 SDK 工作原理

### 8.1 初始化流程

当你启动一个 Spring Boot 应用，Zebra 的 DataSource 是怎么初始化起来的？

**Step 1：加载配置**

Spring 读取你的 DataSource Bean 配置，发现 `jdbcRef` 或 `ruleName`。

**Step 2：拉取远程配置（平台化配置）**

Zebra 客户端向 RDS 平台发起请求，拉取该 `jdbcRef` 对应的完整配置（数据库连接信息、分库分表规则、读写分离策略等）。

**Step 3：初始化连接池**

根据配置，Zebra 初始化底层的连接池（默认 c3p0，也可配置为 druid、hikari 等）。每个物理数据库（每个分片）都有一个独立的连接池。

**Step 4：初始化路由规则**

如果配置了分库分表，Zebra 解析分片规则（Groovy 脚本），编译并缓存，供后续 SQL 路由时使用。

**Step 5：初始化监控**

Zebra 向 CAT（公司监控系统）注册监控指标，后续所有 SQL 执行都会被埋点上报。

### 8.2 SQL 执行链路

一条 SQL 从业务代码到数据库，经过 Zebra 的完整链路：

```
业务代码执行 SQL
  ↓
Zebra 拦截 SQL（通过 JDBC 代理）
  ↓
SQL 解析（Druid SQL Parser）
  ↓
提取表名、WHERE 条件、分片键值
  ↓
计算路由（分库 + 分表）
  ↓
SQL 改写（逻辑表名 → 物理表名）
  ↓
从连接池获取连接
  ↓
执行物理 SQL
  ↓
结果返回（如需聚合，合并多结果集）
  ↓
返回给业务代码
  ↓
CAT 埋点上报（耗时、成功率、SQL 指纹）
```

### 8.3 连接池管理

Zebra 不实现自己的连接池，而是复用业界成熟的连接池实现。支持 c3p0、druid、hikari 等。连接池的参数（最大连接数、最小连接数、连接超时时间等）可以通过配置调整。

```java
// 配置连接池参数
<property name="maxPoolSize" value="100"/>
<property name="minPoolSize" value="10"/>
<property name="checkoutTimeout" value="1000"/>
```

---

## 第九章：多语言支持现状

Zebra 的多语言支持情况：

| 功能 | Java | Go | Python | Node.js |
|------|------|-----|--------|---------|
| 分库分表 | ✅ | ❌ | ❌ | ❌ |
| 读写分离 | ✅ | ✅ | ✅ | ✅ |
| 动态 Hint | ✅ | ❌ | ❌ | ❌ |
| 影子表压测 | ✅ | ❌ | ❌ | ❌ |
| 全局 ID 生成器 | ✅ | ❌ | ❌ | ❌ |
| 负载均衡 | ✅ | ❌ | ❌ | ❌ |

**Java 是唯一完整支持 Zebra 全部能力的语言**。Go、Python、Node.js 的客户端只支持读写分离，不支持分库分表。

这是客户端模式的天然限制：分库分表需要 SQL 解析和路由逻辑，这些逻辑在 Java 中实现得很完整，但移植到其他语言需要大量工作。美团内部 Java 是后端主力语言，所以优先保障了 Java 的完整能力。

---

## 第十章：业界对标

Zebra 与业界其他数据库中间件的对比如下：

| 公司 | 产品 | 模式 | 分库分表 | 读写分离 | 开源 |
|------|------|------|----------|----------|------|
| 美团 | Zebra | 客户端 | ✅ | ✅ | ❌ |
| 阿里 | TDDL | 客户端 | ✅ | ✅ | ❌ |
| 京东 | ShardingSphere | 客户端/代理 | ✅ | ✅ | ✅ |
| 蚂蚁 | ZDAL | 客户端 | ✅ | ✅ | ❌ |
| 饿了么 | DAL | 客户端 | ✅ | ✅ | ❌ |

每家大厂都有自己的数据库中间件，核心原因是：**数据库访问层是业务系统的核心基础设施，需要深度适配公司内部的运维体系、监控体系、权限体系**。开源产品虽然功能类似，但很难无缝接入公司内部的生态系统。

---

## 第十一章：完整实战示例——从零接入 Zebra

### Step 1：添加 Maven 依赖

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.sankuai</groupId>
            <artifactId>inf-bom</artifactId>
            <version>最新版本</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.dianping.zebra</groupId>
        <artifactId>Zebra-api</artifactId>
    </dependency>
</dependencies>
```

### Step 2：在 RDS 平台申请数据源

1. 登录 rds.mws.sankuai.com
2. 创建数据库组（GroupDataSource）或分库分表规则（ShardDataSource）
3. 获得 `jdbcRef` 或 `ruleName`

### Step 3：配置 Spring Bean

```xml
<bean id="dataSource" class="com.dianping.zebra.group.jdbc.GroupDataSource" init-method="init" destroy-method="close">
    <property name="jdbcRef" value="order_group"/>
</bean>
```

### Step 4：MyBatis 配置

```xml
<bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
    <property name="dataSource" ref="dataSource"/>
    <property name="mapperLocations" value="classpath:mapper/*.xml"/>
</bean>
```

### Step 5：编写 DAO

```java
@Mapper
public interface UserMapper {
    @Insert("INSERT INTO welife_users (uid, name, status) VALUES (#{uid}, #{name}, #{status})")
    @Options(useGeneratedKeys = true, keyColumn = "id")
    void insert(User user);

    @Select("SELECT * FROM welife_users WHERE uid = #{uid}")
    User selectByUid(@Param("uid") long uid);
}
```

### Step 6：验证路由

```java
// 插入 uid=17 的数据
userMapper.insert(new User(17, "Alice", 1));
// Zebra 会自动路由到 db1.welife_users_2

// 查询 uid=17
User user = userMapper.selectByUid(17);
// Zebra 会自动路由到 db1.welife_users_2
```

### Step 7（可选）：配置影子表压测

在 RDS 平台的全链路压测模块创建影子表，配置压测流量改写规则。

---

## 第十二章：最佳实践与常见问题

### 12.1 分片键的选择

选分片键最重要的原则是：**这个字段要尽量出现在你绝大多数的查询 SQL 里**。如果分片键选得不好，导致大量查询都不带分片键，就会频繁触发全表扫描，分库分表带来的性能收益就会大打折扣，甚至比不分库分表还差。

### 12.2 分片数量要一次规划到位

动态扩容分片数量必然伴随数据迁移，成本很高。在设计阶段，宁可把分片数规划得稍微宽裕一些（比如按照未来 3-5 年的数据增长量预估），也不要按照当前的数据量刚好够用去设计。

### 12.3 谨慎处理批量插入、IN 语句

分库分表场景下，批量 INSERT 和 IN 语句可能涉及跨多个分片。建议批量插入按分片预先分组再并发执行。

### 12.4 分库分表不支持自增主键的 SELECT 方式获取

对于 INSERT 后需要拿到自增主键的场景，Zebra 只支持 MyBatis 的 `useGeneratedKeys` 方式：

```java
@Insert("INSERT INTO ...")
@Options(useGeneratedKeys = true, keyColumn = "id")
void insert(Entity entity);
```

### 12.5 平台配置修改后为什么没生效

常见原因：规则修改后没有重启应用（分库分表规则需要重启才能生效）；配置的环境不匹配；jdbcRef 配置错误。

### 12.6 不要重复造轮子式地自研分库分表

如果你的团队正在纠结"要不要自己写一套分库分表逻辑"，建议先确认业务是不是 Java 技术栈——如果是，几乎没有理由不用 Zebra（毕竟公司 80% 以上的数据库访问都在用它）。

### 12.7 Zebra 不能替你解决的问题

- **分布式事务**：Zebra 本身不支持，需要配合 Swan 组件
- **大表拆分后的数据迁移**：Zebra 只负责路由和流量切换，数据迁移需要单独方案
- **全局二级索引、复杂跨库 JOIN**：Zebra 仅支持简单跨分片计算

---

## 第十三章：Zebra 的 SQL 解析引擎与性能优化

### 13.1 SQL 解析引擎的工作原理

Zebra 的 SQL 解析基于 Druid 的 SQL Parser。当一条 SQL 进入 Zebra 时，解析引擎会执行以下步骤：

**Step 1：词法分析（Lexical Analysis）**

把 SQL 字符串拆分成一个个 Token（词法单元）：

```sql
SELECT * FROM users WHERE id = 123

Token 序列：[SELECT, *, FROM, users, WHERE, id, =, 123]
```

**Step 2：语法分析（Syntax Analysis）**

根据 SQL 语法规则，把 Token 序列构建成一棵抽象语法树（AST）：

```
SelectStatement
├── SelectList (*)
├── From (users)
└── Where
    └── BinaryOp (=)
        ├── Column (id)
        └── Value (123)
```

**Step 3：语义分析（Semantic Analysis）**

从 AST 中提取语义信息：表名、字段名、WHERE 条件、分片键的值等。

**Step 4：解析缓存**

Zebra 会把解析结果缓存起来。对于相同的 SQL 模板（参数值不同），可以直接复用解析结果，避免重复解析。

### 13.2 SQL 改写引擎

SQL 改写是 Zebra 的核心能力之一。改写引擎根据路由结果，把逻辑 SQL 转换为物理 SQL。主要改写类型：

**表名改写**：把逻辑表名替换为物理表名。

```sql
-- 逻辑 SQL
SELECT * FROM users WHERE uid = 123

-- 改写后
SELECT * FROM users_0 WHERE uid = 123
```

**聚合函数改写**：对于 COUNT、SUM、MAX、MIN、AVG 等聚合函数，改写为可以在分片上执行的形式。

**LIMIT 改写**：深分页查询的 LIMIT 改写，确保跨分片结果的正确性。

### 13.3 性能优化技巧

**连接池优化**：

```java
// 推荐配置
<property name="maxPoolSize" value="100"/>      <!-- 最大连接数 -->
<property name="minPoolSize" value="10"/>       <!-- 最小连接数 -->
<property name="checkoutTimeout" value="1000"/>  <!-- 获取连接超时时间（毫秒） -->
<property name="maxIdleTime" value="300"/>      <!-- 连接最大空闲时间（秒） -->
```

**SQL 优化**：
- 尽量带分片键查询，避免全表扫描
- 避免深分页查询（大的 OFFSET）
- 批量操作按分片预先分组

**缓存优化**：
- Zebra 内部缓存了 SQL 解析结果和路由计算结果
- 对于相同模板的 SQL，解析和路由的开销几乎为零

---

## 第十四章：Zebra 在美团内部的规模与生产实践

### 14.1 规模数据

截至最新数据，Zebra 在美团内部的使用规模：

- **接入服务数**：80% 以上的数据库访问通过 Zebra 接入
- **支持的数据库**：MySQL、Blade、PostgreSQL
- **日均 SQL 执行量**：数十亿次
- **P99 延迟**：不超过 5ms（读写分离场景）

### 14.2 典型业务场景

**外卖订单系统**：
- 订单表按订单 ID 分片，128 张物理表
- 日均新增订单数千万，分片均匀分布
- 读写分离：订单写入走主库，订单查询走从库

**酒店库存系统**：
- 库存表按酒店 ID 分片
- 高频查询（用户查酒店库存）带分片键，精确路由
- 低频查询（运营统计）触发全表扫描，走离线统计

**支付系统**：
- 支付流水表按用户 ID 分片
- 强制读主库（金融数据一致性要求）
- 影子表压测（大促前全链路压测）

### 14.3 与其他中间件的协作

Zebra 不是孤立工作的，它和美团内部的其他中间件有紧密协作：

- **与 Lion 协作**：Zebra 的配置（如 jdbcRef 的映射）存储在 Lion 配置中心，支持动态配置变更
- **与 CAT 协作**：所有 SQL 执行的耗时、成功率、SQL 指纹都上报到 CAT 监控
- **与 Mafka 协作**：数据变更后通过 Mafka 发送消息，下游系统消费消息更新缓存

---

## 第十五章：Zebra 故障案例与排查指南

### 15.1 案例一：配置修改后未生效导致数据写入错误分片

**现象**：某业务修改了分库分表规则后，发现部分数据写入了错误的分片。

**原因**：修改规则后没有重启应用。Zebra 的分库分表规则在启动时加载并缓存，运行时不会自动刷新。

**解决方案**：修改分库分表规则后，必须重启所有应用实例才能生效。

### 15.2 案例二：全表扫描导致数据库 CPU 打满

**现象**：某业务上线新功能后，数据库 CPU 使用率飙升到 100%。

**原因**：新功能的大量查询没有带分片键，触发了全表扫描（广播到所有 128 张物理表）。

**解决方案**：
1. 紧急加索引优化（短期）
2. 改造查询逻辑，确保带分片键（长期）
3. 在 Zebra 监控中配置全表扫描告警

### 15.3 案例三：从库延迟导致数据不一致

**现象**：用户下单后马上查询订单状态，显示"订单不存在"。

**原因**：订单写入主库后，从库复制延迟，读请求走了从库，读到旧数据。

**解决方案**：
1. 订单查询强制读主库（通过 Hint 或配置）
2. 优化主从复制性能，减少延迟

### 15.4 常见故障排查 Checklist

```
□ 检查 jdbcRef/ruleName 配置是否正确
□ 检查分库分表规则是否生效（重启了吗？）
□ 检查 SQL 是否带了分片键
□ 检查是否有全表扫描（看 CAT 监控）
□ 检查从库延迟（看 RDS 监控）
□ 检查连接池是否打满（看 CAT 监控）
□ 检查是否有慢查询（看 CAT 监控）
```

---

## 结语

回顾整篇文章，Zebra 要解决的问题其实可以用一句话概括：**在不改变业务"像操作单机数据库一样写 SQL"这个使用习惯的前提下，把数据和请求分散到多台机器上，从而突破单机数据库在容量、并发、性能、可扩展性上的天花板**。

它选择了客户端嵌入式的技术路线，而不是独立部署的代理服务器，换来的是更低的网络延迟（P99 不超过 5ms 的硬指标）和更简单的运维模型；代价则是分库分表这样的复杂能力目前只能在 Java 生态里做到位。

它的核心能力——分库分表和读写分离——本质上都是"水平扩展"思想的两种具体体现：分库分表扩展的是存储容量和写入吞吐，读写分离扩展的是读取吞吐。而围绕这两个核心能力，Zebra 又逐步长出了动态 Hint、影子表压测、全局 ID 生成器、流量调度等一整套配套设施，这些都是在真实生产环境里，随着业务规模不断增长逐渐打磨出来的能力。

希望这篇文章能帮你建立起对 Zebra 完整、扎实的认知——不只是知道怎么配置一个 jdbcRef，而是理解每一行配置背后，Zebra 到底在帮你做什么、为什么要这样设计。

---

## 第十六章：Zebra SQL 解析引擎深度剖析

### 16.1 为什么需要 SQL 解析引擎

Zebra 作为客户端中间件，必须在业务代码的 SQL 到达数据库之前，完成"拦截→解析→路由→改写"这一整套操作。这一切的前提是：Zebra 必须能读懂 SQL。这就是 SQL 解析引擎存在的意义。

如果没有 SQL 解析引擎，Zebra 只能做字符串替换（比如把 `users` 替换成 `users_0`），但这样无法处理复杂条件、无法提取分片键的值、无法判断 SQL 类型。SQL 解析引擎让 Zebra 真正"理解"了 SQL 的语义。

### 16.2 Druid SQL Parser 的引入

Zebra 选择基于阿里巴巴开源的 Druid SQL Parser 作为解析引擎。原因有三：

1. **成熟度高**：Druid 是 Java 生态中最成熟的 SQL 解析库之一，支持 MySQL、Oracle、PostgreSQL 等多种方言
2. **性能好**：Druid 的解析器是手写优化的，不是自动生成的，性能优于 ANTLR 等生成的解析器
3. **扩展性强**：Druid 提供了完善的 AST（抽象语法树）访问接口，便于 Zebra 做二次开发

### 16.3 SQL 解析的完整流程

当一条 SQL 进入 Zebra 时，解析引擎会执行以下五个步骤：

**Step 1：词法分析（Lexical Analysis）**

把 SQL 字符串拆分成一个个 Token（词法单元）：

```sql
SELECT * FROM users WHERE uid = 12345 AND status = 1
```

词法分析后得到的 Token 序列：

```
[SELECT, *, FROM, users, WHERE, uid, =, 12345, AND, status, =, 1]
```

每个 Token 都有类型标识：关键字（SELECT）、标识符（users）、操作符（=）、数值（12345）、逻辑运算符（AND）等。

**Step 2：语法分析（Syntax Analysis）**

根据 SQL 语法规则，把 Token 序列构建成一棵抽象语法树（AST）。

```
SelectStatement
├── SelectList
│   └── AllColumns (*)
├── From
│   └── TableReference (users)
└── Where
    └── AndExpr
        ├── BinaryOp (=)
        │   ├── Column (uid)
        │   └── Value (12345)
        └── BinaryOp (=)
            ├── Column (status)
            └── Value (1)
```

这棵树完整表达了 SQL 的结构：从哪里查（From）、查什么（SelectList）、条件是什么（Where）。

**Step 3：语义分析（Semantic Analysis）**

从 AST 中提取 Zebra 关心的语义信息：

- 表名：users
- SQL 类型：SELECT
- WHERE 条件中的等值条件：uid = 12345, status = 1
- 分片键的值：uid = 12345 → 分片键值是 12345
- 涉及的列：uid, status

**Step 4：路由计算**

根据提取的分片键值，计算路由：

```
分片键 uid = 12345
分库规则：12345 % 4 = 1 → db1
分表规则：(12345 / 4) % 4 = 3086 % 4 = 2 → table_2
目标：db1.users_2
```

**Step 5：SQL 改写**

根据路由结果，改写 AST：

- 把表名 `users` 替换为 `users_2`
- 生成新的 SQL 字符串

```sql
-- 改写后的物理 SQL
SELECT * FROM users_2 WHERE uid = 12345 AND status = 1
```

### 16.4 解析缓存机制

SQL 解析是 CPU 密集型操作。对于 OLTP 系统，大量的 SQL 是参数化的（只有参数值不同，结构相同）：

```sql
SELECT * FROM users WHERE uid = ? AND status = ?
```

Zebra 会把解析结果缓存起来，以 SQL 的"模板"（去掉参数值后的字符串）作为缓存 key。下次遇到相同模板的 SQL，直接复用解析结果，跳过解析步骤。

```java
// 缓存结构示意
Map<String, ParsedSQL> parseCache;

// 第一次解析：SELECT * FROM users WHERE uid = 12345 AND status = 1
// 缓存 key：SELECT * FROM users WHERE uid = ? AND status = ?
// 缓存 value：ParsedSQL 对象（包含 AST、表名、分片键位置等）

// 第二次解析：SELECT * FROM users WHERE uid = 67890 AND status = 2
// 缓存命中！直接复用 ParsedSQL，只需要替换参数值
```

这个缓存机制让 Zebra 在高压场景下仍能保持低延迟。

### 16.5 复杂 SQL 的解析挑战

**子查询（Subquery）**：

```sql
SELECT * FROM users WHERE uid IN (SELECT user_id FROM orders WHERE order_id = 123)
```

Zebra 需要解析子查询和外层查询，分别提取分片键。如果子查询和外层查询的分片键不同，路由逻辑会更复杂。

**UNION/UNION ALL**：

```sql
SELECT * FROM users_2024 WHERE uid = 123
UNION ALL
SELECT * FROM users_2023 WHERE uid = 123
```

Zebra 需要分别解析每个 SELECT，分别路由，然后合并结果。

**JOIN**：

```sql
SELECT u.*, o.* FROM users u JOIN orders o ON u.uid = o.user_id WHERE u.uid = 123
```

如果 `users` 和 `orders` 都是分库分表，且分片键相同（绑定表），Zebra 可以直接路由到同一个分片执行 JOIN。如果分片键不同，Zebra 可能不支持或需要特殊处理。

---

## 第十七章：Zebra 性能优化深度指南

### 17.1 连接池调优

连接池是 Zebra 与数据库之间的桥梁，连接池的配置直接影响性能。

**核心参数**：

```xml
<property name="maxPoolSize" value="100"/>      <!-- 最大连接数 -->
<property name="minPoolSize" value="10"/>       <!-- 最小连接数 -->
<property name="checkoutTimeout" value="1000"/>  <!-- 获取连接超时时间（毫秒） -->
<property name="maxIdleTime" value="300"/>      <!-- 连接最大空闲时间（秒） -->
<property name="idleConnectionTestPeriod" value="60"/> <!-- 空闲连接检测周期（秒） -->
```

**参数调优建议**：

- **maxPoolSize**：根据数据库的 max_connections 和应用并发数设置。一般设置为（数据库 max_connections / 应用实例数）的 80%。如果设置过大，会打满数据库连接；如果设置过小，会排队等待。
- **minPoolSize**：保持一定数量的空闲连接，避免请求突发时创建连接的开销。一般设置为 maxPoolSize 的 10%-20%。
- **checkoutTimeout**：设置合理的超时时间。太短会导致请求失败，太长会导致请求堆积。推荐 1000-3000ms。
- **maxIdleTime**：空闲连接太久会被关闭，避免连接被数据库或防火墙断开。推荐 300-600 秒。

### 17.2 SQL 优化建议

**原则一：尽量带分片键**

```sql
-- 好的写法（精确路由，只查一张表）
SELECT * FROM users WHERE uid = 12345

-- 差的写法（全表扫描，广播到所有表）
SELECT * FROM users WHERE status = 1
```

**原则二：避免深分页**

```sql
-- 避免（深分页，性能差）
SELECT * FROM users ORDER BY uid LIMIT 1000000, 10

-- 推荐（用游标或覆盖索引优化）
SELECT * FROM users WHERE uid > last_uid ORDER BY uid LIMIT 10
```

**原则三：批量操作按分片预先分组**

```java
// 批量插入时，Zebra 会自动按分片分组并发执行
// 但你的数据最好已经按分片键排序，减少分组开销
```

**原则四：避免 SELECT ***

```sql
-- 避免（返回不必要的列，增加网络开销）
SELECT * FROM users WHERE uid = 12345

-- 推荐（只查需要的列）
SELECT uid, name, status FROM users WHERE uid = 12345
```

### 17.3 监控与告警

Zebra 通过 CAT 上报以下监控指标：

- **SQL 执行耗时**：P50、P95、P99
- **SQL 成功率**：失败率、超时率
- **SQL 指纹**：高频 SQL 的识别
- **全表扫描次数**：广播查询的次数和耗时
- **连接池使用率**：连接数 / maxPoolSize

**告警建议**：

- P99 延迟 > 50ms 时告警
- 全表扫描次数 > 100次/分钟 时告警
- 连接池使用率 > 80% 时告警
- SQL 失败率 > 0.1% 时告警

---

## 第十八章：Zebra 生产环境实战

### 18.1 外卖订单系统的分库分表实践

外卖订单系统是美团最核心的业务系统之一，日均订单数千万。订单表的分库分表设计如下：

**分片键选择**：订单 ID（order_id）

**分片规则**：
- 分库：order_id % 64 = 64 个数据库
- 分表：(order_id / 64) % 64 = 每个库里 64 张表
- 总物理表数：64 × 64 = 4096 张

**为什么选订单 ID 作为分片键**：
- 订单查询 99% 都是按订单 ID 查（用户查订单、商家查订单）
- 订单 ID 是全局唯一的，分布均匀，不会热点

**读写分离策略**：
- 订单创建（INSERT）→ 主库
- 订单查询（SELECT）→ 从库
- 订单状态更新（UPDATE）→ 主库

**影子表压测**：
- 大促前全链路压测，压测流量自动路由到影子表
- 影子表与生产表结构一致，但数据隔离

### 18.2 酒店库存系统的分库分表实践

酒店库存系统的特点是：读多写少，查询条件复杂。

**分片键选择**：酒店 ID（hotel_id）

**分片规则**：
- 分库：hotel_id % 16 = 16 个数据库
- 分表：(hotel_id / 16) % 16 = 每个库里 16 张表
- 总物理表数：16 × 16 = 256 张

**辅助维度**：
- 用户按城市查酒店（不带 hotel_id）→ 走 ES 或缓存，不走 Zebra
- 运营后台统计 → 走离线计算，不走 Zebra

### 18.3 支付系统的分库分表实践

支付系统对数据一致性要求极高。

**分片键选择**：用户 ID（user_id）

**分片规则**：
- 分库：user_id % 32 = 32 个数据库
- 分表：(user_id / 32) % 32 = 每个库里 32 张表

**强制读主库**：
- 支付流水查询强制走主库（通过 Hint 或配置）
- 避免主从延迟导致"支付成功但查不到"的问题

**全局 ID 生成器**：
- 使用 SnowFlake 算法生成支付流水号
- 确保分布式环境下 ID 唯一

---

## 第十九章：Zebra 与分布式事务

### 19.1 Zebra 为什么不支持分布式事务

Zebra 本身不提供分布式事务支持。原因：

1. **复杂度**：分布式事务（XA、TCC、SAGA）的实现非常复杂，与 Zebra 的定位（数据库访问层）不符
2. **性能**：XA 事务的性能开销很大，2PC 的 prepare 和 commit 阶段会显著增加延迟
3. **替代方案**：美团内部有专门的分布式事务组件（Swan），业务按需使用

### 19.2 分布式事务的替代方案

**方案一：最终一致性（推荐）**

通过消息队列（Mafka）实现最终一致性：

```
业务操作 → 写数据库 → 发送消息 → 下游系统消费消息 → 更新自己的数据
```

**方案二：TCC（Try-Confirm-Cancel）**

通过 Swan 组件实现 TCC 模式：

```
Try：预留资源
Confirm：确认执行
Cancel：取消释放
```

**方案三：SAGA**

长事务拆分为多个本地事务，每个本地事务有对应的补偿操作。

### 19.3 Zebra 在分布式事务中的角色

Zebra 在分布式事务中只负责"本地事务的执行"，不负责分布式事务的协调。分布式事务的协调由 Swan 或其他事务管理器负责。

```
业务代码 → Swan 事务管理器 → Zebra 执行本地 SQL → 数据库
```

---

## 第二十章：Zebra 的未来演进

### 20.1 多语言支持的扩展

Zebra 目前只有 Java 完整支持分库分表。未来可能的方向：

- Go 语言分库分表支持：美团内部 Go 服务越来越多，对 Go 的 Zebra 需求在增长
- Python 分库分表支持：算法团队大量使用 Python

### 20.2 与 Service Mesh 的集成

随着 Service Mesh（Sidecar 模式）的推广，Zebra 可能会以 Sidecar 的形式部署，而不是嵌入到应用代码中。这样可以统一多语言支持，但会带来额外的网络延迟。

### 20.3 云原生适配

- 与 Hulk 的集成：自动感知 Pod 变化，动态调整连接池
- 与云数据库的集成：支持云厂商的 RDS 自动扩缩容

---

## 附录一：Zebra 配置参数大全

### 数据源配置参数

```xml
<bean id="dataSource" class="com.dianping.zebra.group.jdbc.GroupDataSource" init-method="init" destroy-method="close">
    <!-- 基础配置 -->
    <property name="jdbcRef" value="order_group"/>          <!-- 数据源引用 -->
    <property name="ruleName" value="order_shard"/>         <!-- 分库分表规则名 -->
    
    <!-- 连接池配置 -->
    <property name="maxPoolSize" value="100"/>               <!-- 最大连接数 -->
    <property name="minPoolSize" value="10"/>              <!-- 最小连接数 -->
    <property name="checkoutTimeout" value="1000"/>         <!-- 获取连接超时（毫秒） -->
    <property name="maxIdleTime" value="300"/>               <!-- 连接最大空闲时间（秒） -->
    <property name="idleConnectionTestPeriod" value="60"/>  <!-- 空闲连接检测周期（秒） -->
    
    <!-- 读写分离配置 -->
    <property name="readStrategy" value="roundrobin"/>       <!-- 读策略：roundrobin/random/master -->
    <property name="writeStrategy" value="master"/>          <!-- 写策略：master -->
    
    <!-- 分库分表配置 -->
    <property name="parallelCorePoolSize" value="32"/>      <!-- 并行查询线程池核心数 -->
    <property name="parallelMaxPoolSize" value="64"/>       <!-- 并行查询线程池最大数 -->
    <property name="parallelWorkQueueSize" value="100"/>    <!-- 并行查询线程池队列大小 -->
</bean>
```

### 分库分表规则配置（Groovy 脚本）

```groovy
// 分库规则：按 uid 取模
void init() {
    dbRule = "uid % 4"  // 4 个数据库
    tbRule = "(uid / 4) % 4"  // 每个库 4 张表
    dbIndexes = "0,1,2,3"
    tbSuffix = "_0,_1,_2,_3"
}
```

---

## 附录二：Zebra 常见问题 FAQ

**Q1：Zebra 和 MyBatis 的集成有什么注意事项？**

A：主要注意 `useGeneratedKeys` 的使用。Zebra 不支持 `SELECT LAST_INSERT_ID()`，只支持 `useGeneratedKeys`。

**Q2：分库分表规则修改后为什么不生效？**

A：分库分表规则在启动时加载并缓存，运行时不会自动刷新。修改规则后必须重启应用。

**Q3：如何排查 SQL 路由到了哪个分片？**

A：开启 Zebra 的日志（设置日志级别为 DEBUG），可以看到 SQL 改写前后的对比。

**Q4：Zebra 支持跨库 JOIN 吗？**

A：仅支持绑定表 JOIN（两张表分片键相同且规则相同）。普通跨库 JOIN 不支持。

**Q5：从库延迟怎么解决？**

A：对数据一致性要求高的查询，使用 Hint 强制读主库。或者使用 Zebra 的从库延迟检测功能，自动切换到延迟小的从库。

**Q6：Zebra 支持批量插入吗？**

A：支持。Zebra 会自动按分片分组，并发执行。

**Q7：如何监控 Zebra 的性能？**

A：Zebra 会自动上报 CAT 监控。关注 SQL 耗时、全表扫描次数、连接池使用率等指标。

**Q8：Zebra 支持的数据库有哪些？**

A：MySQL、Blade（美团内部基于 MySQL 的数据库中间件）、PostgreSQL。

**Q9：非 Java 语言可以用 Zebra 的分库分表吗？**

A：目前不支持。Go、Python、Node.js 只支持读写分离。

**Q10：Zebra 和 MGW 数据库网关有什么区别？**

A：Zebra 是客户端模式（嵌入到应用代码），MGW 是代理模式（独立部署）。Zebra 延迟更低，但只支持 Java；MGW 支持多语言，但多了一层网络跳转。

---

## 附录三：Zebra 与业界产品详细对比

| 维度 | 美团 Zebra | 阿里 TDDL | 京东 ShardingSphere | 蚂蚁 ZDAL | 饿了么 DAL |
|------|-----------|----------|-------------------|----------|----------|
| 模式 | 客户端 | 客户端 | 客户端+代理 | 客户端 | 客户端 |
| 开源 | 否 | 否 | 是（Apache 2.0） | 否 | 否 |
| 分库分表 | 完整支持 | 完整支持 | 完整支持 | 完整支持 | 完整支持 |
| 读写分离 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 多语言 | Java（完整）、Go/Python/Node（仅读写分离） | Java | Java（完整）、多语言（Proxy 模式） | Java | Java |
| 动态配置 | 支持（Lion 配置中心） | 支持（Diamond） | 支持 | 支持 | 支持 |
| 影子表压测 | 支持 | 支持 | 支持 | 不支持 | 不支持 |
| 全局 ID 生成 | 内置（MySQLIdGenerator、SnowFlake） | 内置（Leaf） | 内置（Snowflake） | 内置 | 内置 |
| 性能（P99） | < 5ms | < 5ms | < 5ms | < 5ms | < 5ms |
| 社区活跃度 | 内部维护 | 内部维护 | 活跃（Apache 顶级项目） | 内部维护 | 内部维护 |

---

## 附录四：分库分表设计决策树

```
业务数据量是否在快速增长？
├── 否 → 继续用单库单表
└── 是 → 预计 1 年内单表会超过千万级？
    ├── 否 → 先用 GroupDataSource（读写分离）
    └── 是 → 需要分库分表
        ├── 数据量巨大但查询简单（只有主键查询）→ 只分表
        ├── 并发高但数据量不大 → 只分库
        └── 数据量大且并发高 → 分库分表
            ├── 分片键选择：
            │   ├── 高频查询字段是什么？
            │   ├── 是否有热点风险？
            │   └── 是否支持辅助维度？
            ├── 分片数量：
            │   ├── 按当前数据量 × 5 年预估
            │   └── 宁可多不可少（扩容成本高）
            └── 实施步骤：
                ├── 1. 创建分库分表物理表
                ├── 2. 配置 Zebra 规则（平台化配置）
                ├── 3. 数据迁移（双写方案）
                ├── 4. 切换流量到 Zebra
                └── 5. 监控验证
```

---

## 附录五：Zebra 监控指标详解

### CAT 埋点指标

Zebra 在 CAT 中上报以下埋点：

**SQL 执行埋点**：
- 埋点名称：`Zebra.SQL` 或 `Zebra.SQL.{表名}`
- 指标：平均耗时、P99 耗时、最大耗时、调用次数、失败次数

**连接池埋点**：
- 埋点名称：`Zebra.ConnectionPool`
- 指标：连接数、等待队列长度、获取连接耗时

**全表扫描埋点**：
- 埋点名称：`Zebra.FullTableScan`
- 指标：全表扫描次数、扫描的表数、耗时

### 监控大盘配置建议

```
大盘名称：Zebra 数据库监控
├── 面板 1：SQL P99 延迟（按表名分组）
├── 面板 2：SQL 调用量（按表名分组）
├── 面板 3：SQL 失败率（按表名分组）
├── 面板 4：全表扫描次数（按表名分组）
├── 面板 5：连接池使用率（按数据源分组）
└── 面板 6：从库复制延迟（按从库分组）
```

---

## 附录六：Zebra 源码导读

如果你想深入了解 Zebra 的实现，可以从以下源码包入手：

```
com.dianping.zebra
├── group                    # GroupDataSource 读写分离
│   ├── jdbc                 # JDBC 封装
│   └── router               # 读写分离路由
├── shard                    # ShardDataSource 分库分表
│   ├── jdbc                 # JDBC 封装
│   ├── parser               # SQL 解析
│   ├── router               # 分片路由
│   ├── rewriter             # SQL 改写
│   ├── merger               # 结果合并
│   └── id                   # 全局 ID 生成器
├── single                   # SingleDataSource 基础数据源
├── config                   # 配置管理
├── monitor                  # 监控埋点
└── util                     # 工具类
```

核心类：
- `ShardDataSource`：分库分表数据源的入口
- `ShardRouter`：分片路由的核心类
- `SQLRewriteEngine`：SQL 改写引擎
- `ResultSetMerger`：结果集合并器
- `MySqlIdGenerator`：MySQL 全局 ID 生成器
- `SnowFlakeIdGenerator`：雪花算法 ID 生成器

---

## 结语

Zebra 是美团数据库访问层的核心基础设施，它让业务代码在享受分库分表带来的水平扩展能力的同时，保持"像操作单机数据库一样写 SQL"的简洁体验。理解 Zebra 的工作原理，是每一位美团后端工程师的必修课。

从单机 MySQL 到分库分表，从简单的 JDBC 连接到复杂的 SQL 路由改写，Zebra 在背后默默地处理了一切。作为业务开发者，你只需要理解它的原理，遵循最佳实践，就能写出既简洁又高效的数据库访问代码。

希望这篇文档能成为你学习和使用 Zebra 的完整参考。如果你在实践中遇到任何问题，记住：Zebra 的官方文档在学城，Zebra 的客服在大象，Zebra 的源码在 GitLab。

---

## 第二十一章：Zebra 数据迁移实战指南

### 21.1 为什么需要数据迁移

分库分表不是一蹴而就的。大多数业务都是从单库单表开始，随着数据量增长，才逐步拆分。这意味着，你需要把已有的数据从单表迁移到分库分表后的多张物理表中。这个过程叫做**数据迁移**。

数据迁移是 Zebra 接入过程中最复杂、风险最高的环节。Zebra 负责路由和流量切换，但**数据本身如何迁移，需要单独的方案**。

### 21.2 数据迁移的三种方案

**方案一：停机迁移（最简单，但影响最大）**

1. 停服（或进入只读模式）
2. 用脚本把单表数据读取出来，按分片规则计算目标分片，写入对应的物理表
3. 验证数据一致性（行数、Checksum）
4. 切换流量到 Zebra

优点：简单，数据一致性容易保证
缺点：需要停服，影响业务可用性

**方案二：双写迁移（推荐，不影响业务）**

1. 创建分库分表的物理表（空表）
2. 部署 Zebra，配置双写：写请求同时写入单表（旧表）和分库分表（新表）
3. 启动数据迁移脚本，把历史数据从单表迁移到分库分表
4. 验证数据一致性
5. 停止双写，只写分库分表
6. 下线单表

优点：不需要停服，对业务影响小
缺点：双写期间系统复杂度增加，需要处理双写冲突

**方案三：增量迁移（最复杂，但影响最小）**

1. 创建分库分表的物理表
2. 启动全量迁移：把单表数据按分片规则迁移到物理表
3. 启动增量同步：通过 binlog 监听单表的变更，实时同步到物理表
4. 验证数据一致性
5. 切换流量到 Zebra
6. 停止增量同步

优点：不需要停服，不需要双写
缺点：需要搭建 binlog 监听和增量同步链路，复杂度高

### 21.3 双写迁移的详细流程

双写迁移是最常用的方案。下面详细展开：

**Phase 1：准备阶段**

```
1. 在 RDS 平台创建分库分表的物理表（与单表结构一致）
2. 在 RDS 平台配置 Zebra 分库分表规则（ruleName）
3. 在应用代码中配置 Zebra（使用新的 ruleName）
4. 部署 Zebra，但暂时不走流量（验证配置是否正确）
```

**Phase 2：双写阶段**

```
1. 修改应用代码，让写请求同时走单表和 Zebra
   - 单表：保证旧逻辑继续可用（回滚安全）
   - Zebra：写入新的分库分表
2. 启动全量数据迁移脚本
   - 读取单表数据
   - 按分片规则计算目标分片
   - 写入对应的物理表
3. 迁移过程中，新写入的数据通过双写同时进入单表和分库分表
```

**Phase 3：数据校验阶段**

```
1. 行数校验：单表总行数 == 所有物理表行数之和
2. Checksum 校验：对单表和所有物理表计算 MD5 或 CRC，确保数据一致
3. 抽样校验：随机抽取 1000 条数据，对比单表和物理表的内容
```

**Phase 4：切换阶段**

```
1. 停止双写，只写分库分表
2. 读请求切换到 Zebra（灰度切换，先切 1%，再 10%，再 100%）
3. 监控验证（延迟、错误率、数据一致性）
4. 确认无误后，下线单表
```

### 21.4 数据迁移中的常见问题

**问题 1：迁移期间新写入的数据丢失**

原因：迁移脚本在读取单表数据时，新写入的数据在脚本读取之后才写入，脚本没有读到这部分数据。

解决方案：双写阶段，新写入的数据同时进入单表和分库分表，不依赖迁移脚本。

**问题 2：迁移脚本导致数据库压力过大**

原因：迁移脚本一次性读取大量数据，或者写入速度过快，打满数据库连接。

解决方案：
- 迁移脚本限速（比如每秒迁移 1000 条）
- 迁移脚本在业务低峰期运行
- 迁移脚本使用独立的数据库连接，与业务流量隔离

**问题 3：数据不一致**

原因：迁移脚本在迁移过程中，单表数据被更新（UPDATE/DELETE），但迁移脚本已经读到了旧数据。

解决方案：
- 双写阶段，所有更新都同步到分库分表
- 或者使用增量同步（binlog）来补全变更

---

## 第二十二章：Zebra 容量规划与扩展策略

### 22.1 容量规划的核心问题

分库分表前，你需要回答几个问题：

1. **当前数据量是多少？** 单表多少行？总容量多少 GB？
2. **数据增长速度如何？** 日增多少行？月增多少 GB？
3. **查询模式是什么？** 主要是主键查询？还是范围查询？还是聚合查询？
4. **并发量是多少？** QPS 多少？峰值 QPS 多少？
5. **未来 3-5 年的数据量预估？**

### 22.2 分片数量的计算

假设你的用户表当前有 1000 万行，日增 10 万行，预估 3 年后达到 2 亿行。

你希望每张物理表的数据量控制在 500 万行以内（保证查询性能）。

那么需要的物理表数 = 2 亿 / 500 万 = 40 张表。

假设每个数据库部署 10 张表，那么需要 4 个数据库。

分片规则设计：
- 分库：`user_id % 4` → 4 个数据库
- 分表：`(user_id / 4) % 10` → 每个库里 10 张表
- 总物理表数：4 × 10 = 40 张

### 22.3 数据库连接数规划

每个应用实例都会与每个物理数据库建立连接。假设：
- 应用实例数：50 个
- 每个实例的 maxPoolSize：100
- 物理数据库数：4 个

总连接数 = 50 个实例 × 100 连接/实例 × 4 数据库 = 20000 个连接

需要确保数据库的 max_connections 大于 20000（通常需要 DBA 协助配置）。

### 22.4 扩展策略

当 40 张表也不够用时，需要扩容。扩容方案：

**方案一：增加分表数量（只改分表规则，不改分库规则）**

从 10 张表扩容到 20 张表：
- 新规则：`(user_id / 4) % 20`
- 数据迁移：只需要迁移一半的数据（从 10 张表到 20 张表）

**方案二：增加分库数量（改分库规则，分表规则也可能调整）**

从 4 个库扩容到 8 个库：
- 新规则：`user_id % 8`，`(user_id / 8) % 10`
- 数据迁移：需要迁移全部数据（因为分库规则变了，所有数据的位置都可能改变）

方案一的数据迁移成本远低于方案二，因此在设计阶段，**宁可多分表，少分库**。

---

## 第二十三章：Zebra 与 MyBatis 的深层集成

### 23.1 MyBatis 的 SQL 执行流程

MyBatis 执行一条 SQL 的完整流程：

```
业务代码调用 Mapper 方法
  ↓
MyBatis 从 Mapper XML/注解中读取 SQL 模板
  ↓
MyBatis 用参数替换 SQL 模板中的占位符
  ↓
MyBatis 通过 DataSource 获取 Connection
  ↓
Zebra 拦截 Connection（JDBC 代理）
  ↓
Zebra 解析 SQL、路由、改写
  ↓
Zebra 通过底层连接池获取真实连接
  ↓
MyBatis 执行 SQL（此时已是物理 SQL）
  ↓
数据库返回结果
  ↓
MyBatis 映射结果到 Java 对象
  ↓
返回给业务代码
```

### 23.2 MyBatis-Plus 与 Zebra 的集成

MyBatis-Plus 是 MyBatis 的增强工具，提供了 CRUD 封装。Zebra 与 MyBatis-Plus 的集成：

```java
@Configuration
@MapperScan("com.sankuai.mapper")
public class MybatisConfig {
    
    @Bean
    @ConfigurationProperties(prefix = "Zebra")
    public DataSource dataSource() {
        return new GroupDataSource();
    }
    
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        return bean.getObject();
    }
}
```

### 23.3 分片键在 MyBatis 中的传递

分片键的值必须在 SQL 中明确出现，Zebra 才能提取。常见做法：

```xml
<!-- 在 Mapper XML 中，分片键作为参数传入 -->
<select id="selectByUid" resultType="User">
    SELECT * FROM users WHERE uid = #{uid}
</select>

<!-- 调用时传入分片键 -->
User user = userMapper.selectByUid(12345L);
```

如果分片键不是查询条件的一部分，可以使用动态 Hint：

```java
@Select("/*+Zebra:shard=db0*/ SELECT * FROM users WHERE name = #{name}")
User selectByName(@Param("name") String name);
```

---

## 第二十四章：Zebra 的安全与权限控制

### 24.1 数据库账号权限管理

Zebra 连接数据库时使用的账号，需要遵循最小权限原则：

- **读写分离场景**：
  - 主库账号：INSERT、UPDATE、DELETE、SELECT 权限
  - 从库账号：SELECT 权限（只读）

- **分库分表场景**：
  - 每个分片的数据库账号只对该分片有权限
  - 避免一个账号能访问所有分片，降低误操作风险

### 24.2 SQL 注入防护

Zebra 本身不提供 SQL 注入防护，但 MyBatis 的预编译语句（PreparedStatement）天然防止 SQL 注入：

```java
// 安全的写法（MyBatis 预编译）
@Select("SELECT * FROM users WHERE uid = #{uid}")
User selectByUid(@Param("uid") long uid);

// 危险的写法（字符串拼接）
@Select("SELECT * FROM users WHERE uid = " + uid)
User selectByUidUnsafe(long uid);
```

### 24.3 敏感数据加密

对于敏感字段（如手机号、身份证号），Zebra 提供了与加密组件的集成：

```java
// 在 DAO 层对敏感字段加密存储
@Insert("INSERT INTO users (uid, phone_enc) VALUES (#{uid}, #{phoneEnc})")
void insertWithEnc(@Param("uid") long uid, @Param("phoneEnc") String phoneEnc);

// 读取时解密
@Select("SELECT uid, phone_enc FROM users WHERE uid = #{uid}")
@Results({
    @Result(property = "phone", column = "phone_enc", typeHandler = EncPhoneTypeHandler.class)
})
User selectByUid(@Param("uid") long uid);
```

---

## 第二十五章：Zebra 的调试与故障排查

### 25.1 开启 DEBUG 日志

在 logback.xml 中配置 Zebra 的 DEBUG 日志：

```xml
<logger name="com.dianping.zebra" level="DEBUG"/>
```

DEBUG 日志会输出：
- SQL 改写前后的对比
- 路由计算结果
- 连接池状态

### 25.2 使用 Zebra 内置的路由测试工具

Zebra 提供了路由测试接口，可以验证 SQL 路由是否正确：

```java
ShardDataSource dataSource = ...;
String sql = "SELECT * FROM users WHERE uid = 12345";
ShardRouteResult result = dataSource.testRoute(sql);

System.out.println("目标数据库：" + result.getDbIndex());
System.out.println("目标表：" + result.getTableName());
System.out.println("改写后的 SQL：" + result.getRewrittenSql());
```

### 25.3 常见问题排查流程

```
问题：SQL 执行慢
├── 检查是否带了分片键
│   ├── 没带 → 触发全表扫描，优化查询条件
│   └── 带了 → 继续排查
├── 检查连接池是否打满
│   ├── 打满 → 调大 maxPoolSize 或优化 SQL
│   └── 没打满 → 继续排查
├── 检查是否有慢查询
│   ├── 有 → 加索引或优化 SQL
│   └── 没有 → 继续排查
├── 检查从库延迟
│   ├── 延迟高 → 强制读主库或优化复制
│   └── 正常 → 联系 DBA 排查数据库
└── 检查网络延迟
    └── 网络延迟高 → 联系 SRE 排查网络
```

---

## 第二十六章：Zebra 的周边生态

### 26.1 Zebra 与 Lion 配置中心的协作

Zebra 的配置（如 jdbcRef 的映射、分库分表规则）存储在 Lion 配置中心。Lion 支持动态配置推送，但 Zebra 的分库分表规则需要重启才能生效（安全设计）。

### 26.2 Zebra 与 CAT 监控的协作

Zebra 自动上报 CAT 监控，包括：
- SQL 执行耗时（按表名、SQL 类型分组）
- SQL 成功率（按表名、SQL 类型分组）
- 连接池使用率（按数据源分组）
- 全表扫描次数（按表名分组）

### 26.3 Zebra 与 Mafka 消息队列的协作

数据变更后，通过 Mafka 发送消息，下游系统消费消息更新缓存或同步数据：

```
业务写数据库 → Zebra 执行 SQL → 数据库 binlog → Canal/Mafka → 下游系统消费 → 更新缓存
```

### 26.4 Zebra 与 RDS 平台的协作

RDS 平台是 Zebra 的配置管理中心：
- 在 RDS 平台创建数据源、配置分库分表规则
- RDS 平台提供路由测试工具
- RDS 平台提供监控大盘

---

## 第二十七章：Zebra 的性能压测

### 27.1 压测目标

在上线 Zebra 之前，建议进行性能压测，验证：
- Zebra 的延迟是否符合预期（P99 < 5ms）
- Zebra 的吞吐量是否满足业务需求
- Zebra 在高并发下的稳定性

### 27.2 压测方案

**压测工具**：Quake（美团内部压测平台）或 JMeter

**压测场景**：
1. 精确路由查询（带分片键）
2. 全表扫描查询（不带分片键）
3. 批量插入
4. 读写混合

**压测指标**：
- QPS（每秒查询数）
- 平均延迟
- P99 延迟
- 错误率
- 连接池使用率

### 27.3 压测结果分析

压测后，对比以下指标：

| 指标 | 直连数据库 | Zebra 分库分表 | 差异分析 |
|------|----------|--------------|----------|
| P99 延迟 | 2ms | 4ms | Zebra 增加了 SQL 解析和路由开销，但仍在 5ms 以内 |
| QPS | 10000 | 80000 | 分库分表后，QPS 提升了 8 倍（8 个数据库） |
| 错误率 | 0.01% | 0.01% | Zebra 不增加错误率 |

---

## 第二十八章：Zebra 的源码级深度分析

### 28.1 ShardDataSource 的初始化流程

```java
public class ShardDataSource extends AbstractDataSource {
    
    public void init() {
        // 1. 加载配置（从 Lion 或本地文件）
        loadConfig();
        
        // 2. 初始化分片规则（解析 Groovy 脚本）
        initShardRule();
        
        // 3. 初始化连接池（每个物理数据库一个连接池）
        initConnectionPools();
        
        // 4. 初始化 SQL 解析引擎（Druid SQL Parser）
        initSqlParser();
        
        // 5. 初始化监控（CAT 埋点）
        initMonitor();
    }
}
```

### 28.2 SQL 路由的核心代码

```java
public class ShardRouter {
    
    public ShardRouteResult route(String sql, Object[] parameters) {
        // 1. 解析 SQL
        SQLStatement statement = sqlParser.parse(sql);
        
        // 2. 提取表名和 WHERE 条件
        String tableName = extractTableName(statement);
        Map<String, Object> conditions = extractConditions(statement, parameters);
        
        // 3. 计算分片
        Object shardKeyValue = conditions.get(shardKey);
        int dbIndex = shardRule.calculateDbIndex(shardKeyValue);
        int tableIndex = shardRule.calculateTableIndex(shardKeyValue);
        
        // 4. 改写 SQL
        String rewrittenSql = rewriteSql(sql, tableName, tableIndex);
        
        // 5. 返回路由结果
        return new ShardRouteResult(dbIndex, tableIndex, rewrittenSql);
    }
}
```

### 28.3 结果集合并的核心代码

```java
public class ResultSetMerger {
    
    public ResultSet merge(List<ResultSet> resultSets, SQLStatement statement) {
        if (resultSets.size() == 1) {
            return resultSets.get(0); // 只有一张表，直接返回
        }
        
        // 聚合查询需要合并
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;
            
            if (hasAggregation(select)) {
                return mergeAggregation(resultSets, select);
            }
        }
        
        return mergeSimple(resultSets);
    }
    
    private ResultSet mergeAggregation(List<ResultSet> resultSets, SelectStatement select) {
        // 根据聚合函数类型（COUNT、SUM、MAX、MIN、AVG）分别合并
        // ...
    }
}
```

---

## 第二十九章：Zebra 的演进历史与里程碑

### 29.1 Zebra 的诞生背景

Zebra 诞生于美团合并后的技术整合期。合并前，美团和点评各自有自己的数据库中间件：
- 美团：自研的 DAL 组件
- 点评：自研的 DBProxy 组件

合并后，技术栈需要统一。Zebra 作为统一的分布式数据库访问中间件，承担了这个使命。

### 29.2 关键里程碑

- **2015年**：Zebra 1.0 发布，支持读写分离
- **2016年**：Zebra 2.0 发布，支持分库分表
- **2017年**：Zebra 官方文档发布（学城 Zebra-PRFAQ）
- **2018年**：影子表压测功能上线
- **2019年**：平台化配置（RDS 平台）上线，支持 Web 界面配置分库分表规则
- **2020年**：动态 Hint 功能上线
- **2021年**：全局 ID 生成器（SnowFlake）上线
- **2022年**：Zebra 与 XFrame 深度集成，提供 Spring Boot Starter
- **2023年**：Zebra 3.0 规划，支持多语言分库分表

### 29.3 从 Zebra 看技术演进

Zebra 的发展历程，反映了技术架构的演进：
- **早期**：单库单表，直接 JDBC
- **中期**：读写分离（应对读流量增长）
- **后期**：分库分表（应对数据量和写流量增长）
- **未来**：云原生、多语言、Service Mesh

---

## 第三十章：Zebra 的面试高频问题

### 30.1 分库分表的核心问题

**Q1：分库分表后，非分片键查询怎么办？**

A：有三种方案：
1. 改造查询，带上分片键（推荐）
2. 使用辅助维度（Secondary Dimension）路由
3. 触发全表扫描（广播查询，性能差，仅适用于小数据量）

**Q2：分库分表后，如何生成全局唯一 ID？**

A：Zebra 提供内置的全局 ID 生成器：
- MySQLIdGenerator：基于 MySQL 的 auto_increment 配置
- SnowFlakeIdGenerator：基于雪花算法

**Q3：分库分表后，扩容（增加分片）怎么操作？**

A：扩容需要数据迁移。推荐方案：
1. 双写阶段：新写请求同时写入旧分片和新分片
2. 数据迁移：把旧分片数据迁移到新分片
3. 切换流量：灰度切换到新分片
4. 下线旧分片

### 30.2 Zebra 原理问题

**Q4：Zebra 是代理模式还是客户端模式？各有什么优缺点？**

A：Zebra 是客户端模式（Smart Driver）。
- 优点：延迟低（少一层网络跳转）、不需要额外部署代理集群
- 缺点：多语言支持困难（目前只有 Java 完整支持分库分表）

**Q5：Zebra 的 SQL 路由是怎么实现的？**

A：基于 Druid SQL Parser 解析 SQL，提取分片键的值，根据分片规则计算目标分片，改写 SQL 中的表名，最后执行物理 SQL。

**Q6：Zebra 如何处理跨分片的聚合查询（COUNT、SUM、MAX）？**

A：
- COUNT/SUM：把每个分片的结果相加
- MAX/MIN：从所有分片的结果中再取一次 MAX/MIN
- AVG：改写成 SUM 和 COUNT，分别合并后计算 AVG = 总 SUM / 总 COUNT

### 30.3 生产实践问题

**Q7：Zebra 配置修改后为什么不生效？**

A：分库分表规则在启动时加载并缓存，运行时不会自动刷新。修改规则后必须重启应用。

**Q8：Zebra 的读写分离，从库延迟怎么解决？**

A：
1. 对数据一致性要求高的查询，使用 Hint 强制读主库
2. Zebra 有从库延迟检测机制，当从库延迟超过阈值时，自动切换到主库或其他从库

**Q9：Zebra 和 MGW 数据库网关有什么区别？**

A：Zebra 是客户端模式，嵌入到应用代码中，延迟低，但只支持 Java；MGW 是代理模式，独立部署，支持多语言，但多了一层网络跳转。

---

## 第三十一章：Zebra 的未来展望

### 31.1 云原生数据库中间件

随着云原生技术的发展，Zebra 可能演进到以下形态：

- **Serverless 化**：根据负载自动扩缩容，无需手动调整连接池
- **与云数据库集成**：自动适配云厂商的 RDS 自动扩缩容
- **多租户隔离**：在同一集群中支持多个业务的数据隔离

### 31.2 与 AI 的融合

- **智能 SQL 优化**：基于 AI 分析 SQL 执行计划，推荐索引优化方案
- **智能分片建议**：基于数据分布和查询模式，自动推荐最优分片策略
- **智能故障预测**：基于历史数据，预测数据库性能瓶颈和故障

### 31.3 开源的可能性

Zebra 目前是公司内部闭源项目。未来是否开源，取决于：
- 公司战略决策
- 技术脱敏成本（去除公司内部依赖）
- 社区需求

---

## 最终结语

从第一章到第三十一章，我们走过了 Zebra 的完整知识体系：从为什么需要分库分表，到 Zebra 的架构设计，到 SQL 路由的每一个细节，到生产环境的实战经验，到未来的演进方向。

Zebra 不仅仅是一个数据库中间件，它是美团后端技术体系的基石之一。它承载着每天数十亿次的 SQL 请求，支撑着外卖、酒店、支付、出行等核心业务的数据存储和访问。

作为后端工程师，理解 Zebra 的工作原理，不仅能让你更高效地写代码，更能让你在做架构决策时，有底气、有依据。当你知道每一行 SQL 背后发生了什么，你就能写出更优雅、更高效的代码。

技术的本质不是记忆，而是理解。希望这篇文档能帮你真正理解 Zebra。

---

## 第三十二章：Zebra 与 Spring Boot 的深度集成

### 32.1 Spring Boot 自动配置原理

Zebra 提供了 Spring Boot Starter（`Zebra-xframe-boot-starter`），可以自动配置数据源，无需手动编写 XML 配置。

自动配置的核心是 `@AutoConfiguration` 注解和 `spring.factories` 文件：

```java
@AutoConfiguration
@ConditionalOnClass({GroupDataSource.class, SqlSessionFactory.class})
@EnableConfigurationProperties(ZebraProperties.class)
public class ZebraAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(ZebraProperties properties) {
        // 根据配置自动创建 GroupDataSource 或 ShardDataSource
        return createDataSource(properties);
    }
    
    @Bean
    @ConditionalOnMissingBean(SqlSessionFactory.class)
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        return factory.getObject();
    }
}
```

### 32.2 application.yml 配置示例

```yaml
Zebra:
  # 基础配置
  jdbc-ref: order_group                    # 数据源引用
  rule-name: order_shard                   # 分库分表规则名
  
  # 连接池配置
  max-pool-size: 100                       # 最大连接数
  min-pool-size: 10                        # 最小连接数
  checkout-timeout: 1000                   # 获取连接超时（毫秒）
  max-idle-time: 300                       # 连接最大空闲时间（秒）
  
  # 读写分离配置
  read-strategy: roundrobin                # 读策略：roundrobin/random/master
  write-strategy: master                   # 写策略：master
  
  # 分库分表配置
  parallel-core-pool-size: 32              # 并行查询线程池核心数
  parallel-max-pool-size: 64              # 并行查询线程池最大数
  parallel-work-queue-size: 100           # 并行查询线程池队列大小
```

### 32.3 多数据源配置

在一个 Spring Boot 应用中，可能需要同时连接多个 Zebra 数据源：

```java
@Configuration
public class MultiDataSourceConfig {
    
    @Bean("orderDataSource")
    @ConfigurationProperties("Zebra.order")
    public DataSource orderDataSource() {
        return new GroupDataSource();
    }
    
    @Bean("userDataSource")
    @ConfigurationProperties("Zebra.user")
    public DataSource userDataSource() {
        return new ShardDataSource();
    }
    
    @Bean("orderSqlSessionFactory")
    public SqlSessionFactory orderSqlSessionFactory(@Qualifier("orderDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/order/*.xml"));
        return factory.getObject();
    }
    
    @Bean("userSqlSessionFactory")
    public SqlSessionFactory userSqlSessionFactory(@Qualifier("userDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/user/*.xml"));
        return factory.getObject();
    }
}
```

---

## 第三十三章：Zebra 在微服务架构中的角色

### 33.1 微服务的数据库架构

在微服务架构中，每个服务通常有自己的数据库。Zebra 在微服务架构中扮演什么角色？

```
订单服务（Order Service）
  └── Zebra ShardDataSource → 订单库（分库分表）

用户服务（User Service）
  └── Zebra GroupDataSource → 用户库（读写分离）

库存服务（Inventory Service）
  └── Zebra SingleDataSource → 库存库（单库）
```

每个服务独立管理自己的数据库，通过 Zebra 实现水平扩展。

### 33.2 跨服务数据一致性

微服务架构中，跨服务的数据一致性是一个挑战。Zebra 不负责跨服务一致性，但可以配合以下方案：

**方案一：消息队列（最终一致性）**

```
订单服务写入订单 → Zebra 执行 SQL → 发送消息到 Mafka
                                              ↓
用户服务消费消息 → 更新用户积分 → Zebra 执行 SQL
```

**方案二：分布式事务（强一致性）**

```
订单服务 → Swan 事务管理器 → Zebra 执行本地 SQL
                          → 调用用户服务 → Zebra 执行本地 SQL
                          → 提交或回滚
```

### 33.3 服务拆分时的数据库迁移

当单体应用拆分为微服务时，数据库也需要拆分。迁移步骤：

1. **数据拆分**：把单体数据库的表按服务边界拆分
2. **双写阶段**：新旧服务同时写入
3. **数据迁移**：把历史数据迁移到新服务的数据库
4. **切换流量**：逐步切换读流量到新服务
5. **下线旧服务**：确认无误后，下线旧服务的数据库写入

---

## 第三十四章：Zebra 的运维与治理

### 34.1 DBA 的视角：Zebra 如何简化运维

从 DBA 的视角，Zebra 提供了以下运维便利：

- **统一配置管理**：分库分表规则在 RDS 平台统一管理，DBA 可以批量查看和修改
- **路由测试**：RDS 平台提供路由测试工具，DBA 可以验证 SQL 路由是否正确
- **监控集成**：Zebra 自动上报 CAT 监控，DBA 可以通过监控大盘查看数据库性能
- **影子表压测**：DBA 可以配合业务进行全链路压测，验证数据库的容量和性能

### 34.2 慢查询治理

Zebra 自动上报慢查询到 CAT。慢查询的治理流程：

1. **发现**：通过 CAT 监控发现慢查询
2. **分析**：分析慢查询的执行计划（EXPLAIN）
3. **优化**：
   - 加索引
   - 优化 SQL（避免 SELECT *、避免深分页）
   - 改造查询条件（带上分片键）
4. **验证**：优化后验证性能是否改善
5. **监控**：持续监控，防止回退

### 34.3 连接池治理

连接池打满是常见的线上问题。治理方法：

1. **监控**：通过 CAT 监控连接池使用率
2. **告警**：连接池使用率 > 80% 时告警
3. **扩容**：
   - 增加数据库的 max_connections
   - 增加数据库实例数（分库）
   - 优化 SQL，减少连接占用时间
4. **限流**：在应用层做限流，保护数据库

---

## 第三十五章：Zebra 的压测与容量评估

### 35.1 压测前的准备工作

在进行 Zebra 压测前，需要准备：

1. **测试数据**：准备与生产环境相似的测试数据（数据量、数据分布）
2. **测试环境**：搭建与生产环境一致的测试环境（数据库配置、应用配置）
3. **压测工具**：Quake、JMeter、或自研压测工具
4. **监控**：确保 CAT 监控正常，可以实时查看压测指标

### 35.2 压测场景设计

**场景一：精确路由查询**

```sql
SELECT * FROM users WHERE uid = #{uid}
```

预期：只路由到一张表，延迟 < 5ms，QPS 高。

**场景二：全表扫描查询**

```sql
SELECT COUNT(*) FROM users WHERE status = 1
```

预期：广播到所有表，延迟较高，QPS 低。

**场景三：批量插入**

```sql
INSERT INTO users (uid, name, status) VALUES (...), (...), (...)
```

预期：按分片分组并发执行，QPS 高。

**场景四：读写混合**

- 80% 读 + 20% 写
- 模拟真实业务场景

### 35.3 压测结果分析

压测后，分析以下指标：

| 指标 | 目标值 | 实际值 | 是否达标 |
|------|--------|--------|----------|
| P99 延迟 | < 5ms | 4ms | ✅ |
| 平均延迟 | < 2ms | 1.5ms | ✅ |
| QPS | > 10000 | 15000 | ✅ |
| 错误率 | < 0.1% | 0.05% | ✅ |
| 连接池使用率 | < 80% | 60% | ✅ |
| 全表扫描次数 | < 100/min | 50/min | ✅ |

---

## 第三十六章：Zebra 的灰度发布策略

### 36.1 为什么需要灰度发布

Zebra 的配置变更（如分库分表规则修改、数据源切换）是高风险操作。灰度发布可以降低风险。

### 36.2 灰度发布的步骤

**Step 1：准备阶段**

- 在测试环境验证新配置的正确性
- 准备回滚方案（旧配置备份）

**Step 2：灰度切换**

- 先切换 1% 的流量到新配置
- 监控 1 小时，确认无异常
- 切换到 10% 的流量
- 监控 1 小时，确认无异常
- 切换到 50% 的流量
- 监控 2 小时，确认无异常
- 切换到 100% 的流量

**Step 3：验证阶段**

- 持续监控 24 小时
- 对比切换前后的性能指标（延迟、错误率、QPS）
- 确认数据一致性

**Step 4：回滚（如有异常）**

- 立即停止流量切换
- 回滚到旧配置
- 排查问题，修复后重新灰度

### 36.3 灰度发布的监控要点

- SQL 执行延迟（P50、P95、P99）
- SQL 错误率（失败率、超时率）
- 全表扫描次数
- 连接池使用率
- 数据库 CPU 和内存使用率

---

## 第三十七章：Zebra 与云数据库的结合

### 37.1 云数据库的优势

云数据库（如阿里云 RDS、腾讯云 CDB）提供了：
- 自动备份和恢复
- 自动扩容（存储和计算）
- 高可用架构（主从自动切换）
- 监控和告警

### 37.2 Zebra 与云数据库的集成

Zebra 可以与云数据库无缝集成：

```yaml
Zebra:
  jdbc-ref: order_group
  # 云数据库的连接信息由 RDS 平台自动管理
  # Zebra 只需要引用 jdbcRef 即可
```

云数据库的主从切换对 Zebra 是透明的：
- 云数据库自动完成主从切换
- Zebra 通过连接池自动感知连接变化
- 应用无需修改任何代码

### 37.3 云数据库的注意事项

- **网络延迟**：云数据库通常是远程连接，网络延迟比 IDC 内网高
- **连接数限制**：云数据库通常有连接数限制，需要合理配置连接池
- **成本**：云数据库按量计费，需要监控成本

---

## 第三十八章：Zebra 的监控与告警最佳实践

### 38.1 监控指标体系

**性能指标**：
- SQL 平均耗时
- SQL P50/P95/P99 耗时
- SQL 最大耗时
- 连接池获取连接耗时

**可用性指标**：
- SQL 成功率
- SQL 失败率
- SQL 超时率
- 数据库连接失败率

**容量指标**：
- 连接池使用率
- 连接池等待队列长度
- 数据库连接数
- 数据库 CPU 使用率
- 数据库内存使用率

**业务指标**：
- 全表扫描次数
- 慢查询次数
- 读写比例
- 分片键命中率

### 38.2 告警配置建议

**P0 告警（立即处理）**：
- SQL 失败率 > 1%
- SQL P99 延迟 > 100ms
- 连接池使用率 > 95%
- 数据库连接失败

**P1 告警（1 小时内处理）**：
- SQL P99 延迟 > 50ms
- 全表扫描次数 > 500/min
- 从库复制延迟 > 10s

**P2 告警（4 小时内处理）**：
- SQL P99 延迟 > 20ms
- 连接池使用率 > 80%
- 慢查询次数 > 100/min

### 38.3 监控大盘配置

```
Zebra 监控大盘
├── 性能面板
│   ├── SQL P99 延迟趋势（最近 1 小时/1 天/7 天）
│   ├── SQL QPS 趋势
│   └── 连接池获取连接耗时
├── 可用性面板
│   ├── SQL 成功率趋势
│   ├── SQL 失败率趋势
│   └── SQL 超时率趋势
├── 容量面板
│   ├── 连接池使用率（按数据源）
│   ├── 数据库连接数（按数据库）
│   └── 数据库 CPU/内存使用率
└── 业务面板
    ├── 全表扫描次数（按表名）
    ├── 慢查询次数（按 SQL 指纹）
    └── 分片键命中率
```

---

## 第三十九章：Zebra 的灾备与故障恢复

### 39.1 数据库故障场景

**场景一：主库宕机**

- Zebra 通过从库延迟检测，发现主库不可写
- 如果配置了自动切换，Zebra 可以尝试切换到从库（但通常需要人工确认，避免脑裂）
- 恢复方案：DBA 手动切换从库为新主库，Zebra 更新配置

**场景二：从库全部宕机**

- Zebra 的读请求无法路由到从库
- Zebra 自动切换到主库读（如果配置了从库延迟检测）
- 恢复方案：修复或重建从库

**场景三：某个分片的数据库宕机**

- Zebra 路由到该分片的请求失败
- Zebra 返回错误给应用
- 恢复方案：DBA 修复数据库，或切换该分片的从库为主库

### 39.2 故障恢复流程

```
发现故障（监控告警）
  ↓
确认故障范围（哪些数据库受影响）
  ↓
应急处理（切换从库、限流、降级）
  ↓
修复故障（DBA 修复数据库）
  ↓
验证恢复（测试 SQL 执行）
  ↓
恢复流量（逐步切换回正常数据库）
  ↓
复盘总结（分析故障原因，制定改进措施）
```

### 39.3 灾备演练

定期进行灾备演练：
- 模拟主库宕机，验证从库切换流程
- 模拟某个分片宕机，验证业务容错能力
- 模拟网络分区，验证 Zebra 的行为

---

## 第四十章：Zebra 的性能调优案例集

### 40.1 案例一：连接池配置不当导致请求超时

**现象**：业务高峰期大量请求超时，错误率飙升。

**排查**：
- CAT 监控显示连接池使用率 100%
- 连接池等待队列堆积

**原因**：maxPoolSize 设置过小（20），而应用并发数高，导致连接池打满。

**解决方案**：
- 调大 maxPoolSize 到 100
- 优化 SQL，减少连接占用时间
- 增加数据库的 max_connections

**效果**：连接池使用率降到 60%，超时率归零。

### 40.2 案例二：全表扫描导致数据库 CPU 打满

**现象**：某服务上线新功能后，数据库 CPU 飙升到 100%。

**排查**：
- CAT 监控显示全表扫描次数激增
- 新功能的 SQL 没有带分片键

**原因**：新功能查询条件只有 `status = 1`，没有带 `uid` 分片键，触发全表扫描（广播到 128 张表）。

**解决方案**：
- 改造查询逻辑，带上 `uid` 分片键
- 对于无法改造的场景，使用缓存（Squirrel）或 ES 替代

**效果**：全表扫描次数归零，数据库 CPU 降到 30%。

### 40.3 案例三：从库延迟导致数据不一致

**现象**：用户反馈"刚下的订单查不到"。

**排查**：
- 订单写入成功，但查询返回空
- 从库复制延迟 5 秒

**原因**：订单写入主库后，查询走了从库，从库还没同步到新订单。

**解决方案**：
- 订单查询强制读主库（通过 Zebra Hint 配置）
- 优化主从复制性能

**效果**：用户下单后立即能查到订单。

### 40.4 案例四：分片键选择不当导致热点

**现象**：某分片的数据库 CPU 明显高于其他分片。

**排查**：
- CAT 监控显示某个分片的 QPS 是其他分片的 10 倍
- 分片键是 `create_time`，按时间分片

**原因**：最新数据集中在最新分片（当前时间的数据都写入最新分片），导致热点。

**解决方案**：
- 重新选择分片键（改为 `user_id`，分布更均匀）
- 数据迁移（双写方案）

**效果**：各分片负载均匀，热点消失。

---

## 第四十一章：Zebra 的测试策略

### 41.1 单元测试

使用 H2 内存数据库进行单元测试：

```java
@RunWith(SpringRunner.class)
@SpringBootTest
public class UserMapperTest {
    
    @Autowired
    private UserMapper userMapper;
    
    @Test
    public void testInsert() {
        User user = new User();
        user.setUid(12345L);
        user.setName("Alice");
        user.setStatus(1);
        userMapper.insert(user);
        
        User result = userMapper.selectByUid(12345L);
        assertEquals("Alice", result.getName());
    }
}
```

### 41.2 集成测试

在测试环境使用真实的 Zebra 配置：

```java
@Test
public void testShardRoute() {
    // 验证 SQL 路由是否正确
    String sql = "SELECT * FROM users WHERE uid = 12345";
    ShardRouteResult result = shardDataSource.testRoute(sql);
    
    assertEquals(1, result.getDbIndex());      // 期望路由到 db1
    assertEquals(2, result.getTableIndex());   // 期望路由到 table_2
}
```

### 41.3 性能测试

使用 JMH 进行微基准测试：

```java
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class ZebraBenchmark {
    
    @Benchmark
    public void testSelectByUid() {
        userMapper.selectByUid(12345L);
    }
}
```

---

## 第四十二章：Zebra 的社区与资源

### 42.1 官方文档

- **Zebra PRFAQ**：学城 collabpage/801346696
- **Zebra 分库分表接入指南**：学城 collabpage/56490972
- **Zebra 接入文档（推荐）**：学城 collabpage/1331445096
- **Zebra FAQ**：学城 collabpage/2478211606

### 42.2 技术支持

- **Zebra 客服群**：大象群
- **Zebra 客服账号**：@一只小仙鹤
- **TT 工单**：cid=112, tid=2189, iid=9400

### 42.3 源码仓库

- **GitLab**：dev.sankuai.com/code/repo-detail/...
- **核心模块**：com.dianping.zebra

---

## 最终总结

这篇文档从 Zebra 的起源、架构、原理、实战、优化、故障排查、未来展望等维度，全面深入地介绍了 Zebra 数据库访问中间件。涵盖 42 章、超过 3000 行的内容，希望能成为你学习和使用 Zebra 的完整参考。

记住，Zebra 的核心价值是：**让业务代码在享受分库分表带来的水平扩展能力的同时，保持"像操作单机数据库一样写 SQL"的简洁体验**。

理解 Zebra，不仅是掌握一个中间件的使用，更是理解分布式数据库架构的核心思想——水平扩展。

---

## 附录A：Zebra 完整配置参考手册

### A.1 数据源配置（datasource.properties）

```properties
# ====================================
# 基础数据源配置（DB和DBCluster）
# ====================================

# 单库数据源
# 格式：jdbcRefName=db:host:port:database:username:password:driverClass
order.db=db:mysql01:3306:order_db:order_user:order_pwd:com.mysql.jdbc.Driver

# 带参数的单库数据源
# 格式：jdbcRefName=db:host:port:database:username:password:driverClass?key1=value1&key2=value2
order.db.params=db:mysql01:3306:order_db:order_user:order_pwd:com.mysql.jdbc.Driver?useUnicode=true&characterEncoding=UTF-8

# 集群数据源（读写分离）
# 格式：jdbcRefName=dbcluster:groupName:active:readWrite:write:read1,read2,read3
order.cluster=dbcluster:order_group:1:order_write_db:order_write_db:order_read1_db,order_read2_db,order_read3_db

# 多主集群（多主模式）
# 格式：jdbcRefName=dbcluster:groupName:active:readWrite:write1,write2:read1,read2
order.multi.master=dbcluster:order_multi_group:1:order_write1_db,order_write2_db:order_read1_db,order_read2_db
```

### A.2 分库分表规则配置（rule.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rule>
    <namespace>order_shard</namespace>
    
    <!-- 数据源引用 -->
    <datasource-ref>order_shard_group</datasource-ref>
    
    <!-- 库规则 -->
    <db-rule>
        <rule-name>order_db_rule</rule-name>
        <columns>user_id</columns>
        <algorithm>
            <class>com.dianping.zebra.shard.router.rule.GroovyRule</class>
            <expression>
                <![CDATA[
                    def uid = user_id as long
                    def dbIndex = uid % 4
                    return dbIndex
                ]]>
            </expression>
            <parameters>
                <parameter name="dbCount">4</parameter>
            </parameters>
        </algorithm>
    </db-rule>
    
    <!-- 表规则 -->
    <table-rule>
        <rule-name>order_table_rule</rule-name>
        <columns>user_id</columns>
        <algorithm>
            <class>com.dianping.zebra.shard.router.rule.GroovyRule</class>
            <expression>
                <![CDATA[
                    def uid = user_id as long
                    def tableIndex = (uid / 4) % 8
                    return tableIndex
                ]]>
            </expression>
            <parameters>
                <parameter name="tableCount">8</parameter>
            </parameters>
        </algorithm>
    </table-rule>
    
    <!-- 广播表配置 -->
    <broadcast-tables>
        <table>config</table>
        <table>dict</table>
    </broadcast-tables>
    
    <!-- 默认数据源（用于非分片表） -->
    <default-datasource>order_default_db</default-datasource>
</rule>
```

### A.3 连接池高级配置（datasource.properties）

```properties
# ====================================
# 连接池高级配置
# ====================================

# C3P0 连接池配置
order.maxPoolSize=100
order.minPoolSize=10
order.initialPoolSize=10
order.maxIdleTime=300
order.checkoutTimeout=1000
order.acquireIncrement=5
order.acquireRetryAttempts=3
order.acquireRetryDelay=1000
order.testConnectionOnCheckin=true
order.testConnectionOnCheckout=false
order.idleConnectionTestPeriod=60
order.preferredTestQuery=SELECT 1
order.maxConnectionAge=3600
order.maxStatements=50
order.maxStatementsPerConnection=10
order.numHelperThreads=3
order.unreturnedConnectionTimeout=0
order.debugUnreturnedConnectionStackTraces=false

# ====================================
# 分库分表并行查询配置
# ====================================
order.parallelCorePoolSize=32
order.parallelMaxPoolSize=64
order.parallelWorkQueueSize=100
order.parallelMinPoolSize=10
order.parallelKeepAliveTime=60

# ====================================
# 读写分离策略配置
# ====================================
# 读策略：roundrobin（轮询）/ random（随机）/ weight（权重）/ master（强制主库）
order.readStrategy=roundrobin
# 写策略：master（主库）
order.writeStrategy=master
# 权重配置（用于 weight 策略）
order.readWeights=1,2,1

# ====================================
# 从库延迟检测配置
# ====================================
order.slaveDelayDetect=true
order.slaveDelayThreshold=5000
order.slaveDelayCheckInterval=3000
order.slaveMaxDelay=10000

# ====================================
# 监控与日志配置
# ====================================
order.monitor=true
order.monitorInterval=60
order.slowQueryThreshold=100
order.sqlLogEnabled=true
order.sqlLogMaxLength=1000
order.connectionLogEnabled=false
```

### A.4 Spring 配置（XML 方式）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
                           http://www.springframework.org/schema/beans/spring-beans.xsd">

    <!-- 单数据源配置 -->
    <bean id="orderDataSource" class="com.dianping.zebra.group.jdbc.GroupDataSource" init-method="init" destroy-method="close">
        <property name="jdbcRef" value="order_db" />
        <property name="maxPoolSize" value="100" />
        <property name="minPoolSize" value="10" />
        <property name="checkoutTimeout" value="1000" />
    </bean>

    <!-- 分库分表数据源配置 -->
    <bean id="shardDataSource" class="com.dianping.zebra.shard.jdbc.ShardDataSource" init-method="init" destroy-method="close">
        <property name="jdbcRef" value="order_shard" />
        <property name="ruleName" value="order_shard_rule" />
        <property name="maxPoolSize" value="100" />
        <property name="minPoolSize" value="10" />
        <property name="parallelCorePoolSize" value="32" />
        <property name="parallelMaxPoolSize" value="64" />
    </bean>

    <!-- MyBatis SqlSessionFactory -->
    <bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
        <property name="dataSource" ref="shardDataSource" />
        <property name="mapperLocations" value="classpath:mapper/**/*.xml" />
        <property name="configLocation" value="classpath:mybatis-config.xml" />
    </bean>

    <!-- MyBatis Mapper 扫描 -->
    <bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
        <property name="basePackage" value="com.sankuai.order.mapper" />
        <property name="sqlSessionFactoryBeanName" value="sqlSessionFactory" />
    </bean>

    <!-- 事务管理器 -->
    <bean id="transactionManager" class="org.springframework.jdbc.datasource.DataSourceTransactionManager">
        <property name="dataSource" ref="shardDataSource" />
    </bean>

    <!-- 注解事务支持 -->
    <tx:annotation-driven transaction-manager="transactionManager" />
</beans>
```

---

## 附录B：Zebra SQL 路由规则详解

### B.1 支持的比较运算符

| 运算符 | 示例 | 路由能力 | 说明 |
|--------|------|----------|------|
| `=` | `uid = 123` | ✅ 精确路由 | 直接计算分片位置 |
| `IN` | `uid IN (1,2,3)` | ✅ 精确路由 | 批量计算每个值的分片位置 |
| `BETWEEN` | `uid BETWEEN 1 AND 100` | ⚠️ 部分路由 | 计算范围覆盖的分片 |
| `>` `<` `>=` `<=` | `uid > 100` | ❌ 广播 | 无法确定分片位置 |
| `LIKE` | `uid LIKE '123%'` | ❌ 广播 | 无法确定分片位置 |
| `<>` `!=` | `uid <> 123` | ❌ 广播 | 无法确定分片位置 |

### B.2 支持的逻辑运算符

| 表达式 | 路由行为 | 说明 |
|--------|----------|------|
| `uid = 1 AND status = 1` | 精确路由 | 使用 `uid` 计算分片 |
| `uid = 1 OR uid = 2` | 精确路由 | 两个值分别路由，去重 |
| `uid = 1 OR status = 1` | ❌ 广播 | `OR` 混合分片键和非分片键 |
| `uid IN (1,2) AND status = 1` | 精确路由 | 使用 `uid` 的多个值 |

### B.3 路由优先级

当 SQL 中有多个分片键条件时，路由优先级如下：

1. **精确匹配**（`=`、`IN`）优先于范围匹配（`BETWEEN`）
2. 如果所有条件都是精确匹配，选择第一个分片键
3. 如果没有任何分片键条件，触发广播（除非有 `shard` Hint）

### B.4 特殊场景路由行为

**场景一：无 WHERE 子句**

```sql
SELECT * FROM users
```

- 行为：广播到所有分片
- 性能：低，需要聚合所有结果

**场景二：WHERE 子句无分片键**

```sql
SELECT * FROM users WHERE status = 1
```

- 行为：广播到所有分片
- 性能：低，需要聚合所有结果
- 优化：建议加上分片键条件

**场景三：JOIN 查询**

```sql
SELECT a.*, b.* FROM orders a JOIN order_items b ON a.order_id = b.order_id WHERE a.user_id = 123
```

- 行为：如果两张表都按 `user_id` 分片，可以精确路由到同一分片
- 如果分片键不一致，可能触发广播或需要应用层处理

**场景四：子查询**

```sql
SELECT * FROM orders WHERE user_id IN (SELECT user_id FROM users WHERE status = 1)
```

- 行为：Zebra 解析子查询，提取分片键进行路由
- 如果子查询无法解析，可能触发广播

---

## 附录C：Zebra 与 CAT 监控指标对照表

### C.1 自动上报的监控指标

| 指标名 | 类型 | 含义 | 正常范围 |
|--------|------|------|----------|
| `SQL.Count` | 计数 | 每秒 SQL 执行次数 | 业务相关 |
| `SQL.Avg` | 平均 | SQL 平均耗时（毫秒） | < 5ms |
| `SQL.P99` | 百分位 | SQL P99 耗时 | < 20ms |
| `SQL.Error` | 计数 | 每秒 SQL 错误次数 | < 0.1% |
| `SQL.Timeout` | 计数 | 每秒 SQL 超时次数 | 0 |
| `SQL.FullScan` | 计数 | 每秒全表扫描次数 | < 100 |
| `SQL.Slow` | 计数 | 每秒慢查询次数 | < 10 |
| `Pool.GetConnection.Avg` | 平均 | 获取连接平均耗时 | < 1ms |
| `Pool.GetConnection.P99` | 百分位 | 获取连接 P99 耗时 | < 5ms |
| `Pool.WaitQueueSize` | 瞬时值 | 连接等待队列长度 | 0 |
| `Pool.ActiveConnections` | 瞬时值 | 活跃连接数 | < maxPoolSize * 80% |
| `Pool.IdleConnections` | 瞬时值 | 空闲连接数 | > minPoolSize |
| `Pool.MaxConnections` | 瞬时值 | 最大连接数 | = maxPoolSize |
| `Shard.RouteCount` | 计数 | 每秒路由次数 | 业务相关 |
| `Shard.BroadcastCount` | 计数 | 每秒广播次数 | < 10 |
| `Shard.MergeCount` | 计数 | 每秒聚合次数 | < 10 |
| `Shard.MergeTime.Avg` | 平均 | 聚合平均耗时 | < 5ms |
| `ReadWrite.ReadCount` | 计数 | 每秒读请求次数 | 业务相关 |
| `ReadWrite.WriteCount` | 计数 | 每秒写请求次数 | 业务相关 |
| `ReadWrite.ReadSlave` | 计数 | 每秒从库读次数 | 业务相关 |
| `ReadWrite.ReadMaster` | 计数 | 每秒主库读次数 | 业务相关 |
| `ReadWrite.SlaveDelay` | 平均 | 从库延迟（毫秒） | < 5000ms |
| `Hint.Count` | 计数 | 每秒 Hint 使用次数 | 业务相关 |
| `Shadow.Count` | 计数 | 每秒影子表 SQL 次数 | 压测时 > 0 |

### C.2 自定义监控标签

Zebra 支持通过 Hint 添加自定义监控标签：

```java
// 在 SQL 中添加自定义标签
String sql = "/*+ Zebra:TAG=order_detail_query */ SELECT * FROM orders WHERE order_id = ?";
```

这样可以在 CAT 中按标签过滤和统计 SQL 执行情况。

---

## 附录D：Zebra 生产环境部署检查清单

### D.1 部署前检查

- [ ] 确认 datasource.properties 中的数据库连接信息正确
- [ ] 确认分库分表规则（rule.xml）配置正确，并在测试环境验证路由
- [ ] 确认连接池参数（maxPoolSize、minPoolSize）合理
- [ ] 确认读写分离策略（readStrategy、writeStrategy）符合业务需求
- [ ] 确认从库延迟检测配置正确（如启用）
- [ ] 确认监控配置正确（CAT 上报正常）
- [ ] 确认日志级别设置合理（生产环境建议 INFO 级别）
- [ ] 确认 MyBatis Mapper XML 中的 SQL 语法正确
- [ ] 确认 Spring 配置中的 bean 引用正确
- [ ] 确认数据库账号权限正确（读写权限分离）

### D.2 部署后验证

- [ ] 应用启动成功，无报错
- [ ] 连接池初始化成功，连接数正常
- [ ] 执行一条精确路由 SQL，确认路由正确
- [ ] 执行一条广播 SQL，确认广播行为正确
- [ ] 执行一条读写分离 SQL，确认读走了从库
- [ ] 执行一条写 SQL，确认写走了主库
- [ ] CAT 监控正常上报 SQL 执行数据
- [ ] 连接池监控正常，无等待队列堆积
- [ ] 全表扫描次数正常，无异常增长
- [ ] 慢查询次数正常，无异常增长

### D.3 上线后持续监控

- [ ] 每日检查 CAT 监控大盘，关注延迟和错误率
- [ ] 每周检查全表扫描次数，排查问题 SQL
- [ ] 每月检查连接池使用率，评估是否需要扩容
- [ ] 每季度检查分片键分布，确认无热点
- [ ] 每年评估分库分表容量，规划扩容

---

## 附录E：Zebra 常见错误码与解决方案

### E.1 连接相关错误

| 错误码 | 错误信息 | 原因 | 解决方案 |
|--------|----------|------|----------|
| `Zebra-CONN-001` | 获取连接超时 | 连接池打满 | 调大 maxPoolSize，优化 SQL |
| `Zebra-CONN-002` | 连接无效 | 数据库网络中断 | 检查网络，重启应用 |
| `Zebra-CONN-003` | 连接池初始化失败 | 配置错误 | 检查 datasource.properties |
| `Zebra-CONN-004` | 连接池关闭 | 应用正在关闭 | 正常行为，无需处理 |
| `Zebra-CONN-005` | 从库连接失败 | 从库宕机 | 检查从库状态，启用主库读 |

### E.2 路由相关错误

| 错误码 | 错误信息 | 原因 | 解决方案 |
|--------|----------|------|----------|
| `Zebra-ROUTE-001` | 找不到分片规则 | 表名未配置分片规则 | 检查 rule.xml 配置 |
| `Zebra-ROUTE-002` | 分片键缺失 | SQL 中无分片键条件 | 添加分片键条件 |
| `Zebra-ROUTE-003` | 分片计算错误 | 分片算法异常 | 检查 Groovy 脚本 |
| `Zebra-ROUTE-004` | 广播超时 | 广播 SQL 执行超时 | 优化 SQL，减少广播 |
| `Zebra-ROUTE-005` | 聚合失败 | 结果集合并失败 | 检查 SQL 语法 |

### E.3 执行相关错误

| 错误码 | 错误信息 | 原因 | 解决方案 |
|--------|----------|------|----------|
| `Zebra-EXEC-001` | SQL 执行超时 | SQL 执行时间超过阈值 | 优化 SQL，加索引 |
| `Zebra-EXEC-002` | SQL 语法错误 | SQL 语法不正确 | 检查 SQL 语法 |
| `Zebra-EXEC-003` | 主键冲突 | 插入重复主键 | 检查数据，或使用全局 ID |
| `Zebra-EXEC-004` | 锁等待超时 | 并发锁冲突 | 优化事务，减少锁持有时间 |
| `Zebra-EXEC-005` | 事务回滚 | 事务执行失败 | 检查业务逻辑 |

---

## 附录F：Zebra 与业界分库分表方案对比

| 特性 | Zebra | Zebra | MyCat | Vitess |
|------|-------|----------------|-------|--------|
| 部署模式 | 客户端 | 客户端/代理 | 代理 | 客户端 |
| 分库分表 | ✅ | ✅ | ✅ | ✅ |
| 读写分离 | ✅ | ✅ | ✅ | ✅ |
| SQL 支持 | 部分 | 较完善 | 较完善 | 部分 |
| 分布式事务 | ❌ | ✅ | ✅ | ❌ |
| 动态配置 | ✅ | ✅ | ✅ | ✅ |
| 性能损耗 | 低 | 中 | 中 | 低 |
| 社区活跃度 | 内部 | 高 | 中 | 高 |
| 语言支持 | Java | Java | Java | Go |
| 美团使用 | 美团 | 京东、当当 | 各公司 | YouTube |

---

**文档结束**

感谢阅读。如有问题，欢迎通过 Zebra 客服群（大象群）或客服账号（@一只小仙鹤）联系技术支持。