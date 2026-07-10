# 数据同步与 CDC 架构设计

## 一、问题背景

### 1.1 数据同步的业务需求

在现代分布式系统中，数据往往不仅存储在单一的关系型数据库中，还需要同步到搜索引擎、缓存系统、消息队列、数据仓库等多种异构存储系统中。业务的演进带来了以下典型的数据同步需求：

**场景一：异构数据源同步**

```
典型的数据流转路径：

                         ┌──────────────────┐
                         │    MySQL (主存储)  │
                         └────────┬─────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │             │             │
           ┌───────▼──────┐ ┌────▼─────┐ ┌────▼──────┐
           │ Elasticsearch│ │  Redis   │ │  Kafka    │
           │ (全文搜索)    │ │ (热点缓存)│ │ (事件流)  │
           └──────────────┘ └──────────┘ └───────────┘
                    │                          │
           ┌───────▼──────┐            ┌──────▼──────┐
           │  推荐系统     │            │  数据仓库    │
           └──────────────┘            └─────────────┘
```

**场景二：数据库迁移与扩容**

当业务进行数据库迁移（如从单库迁移到分库分表、从 MySQL 迁移到分布式数据库）时，需要保证迁移过程中数据不丢失、不中断业务。

**场景三：实时数据分析**

传统的 ETL（Extract-Transform-Load）方式通常是 T+1 的离线批量同步，无法满足实时分析需求。业务需要秒级甚至毫秒级的数据变更感知能力。

### 1.2 传统数据同步方案的问题

在 CDC 技术出现之前，常见的数据同步方案存在明显的局限性：

**方案一：应用层双写**

```java
// 应用层双写的典型代码
public void createOrder(Order order) {
    // 写入 MySQL
    orderDao.insert(order);
    
    // 同步写入 ES（存在一致性问题）
    esClient.index(order);
    
    // 同步写入 Redis（存在一致性问题）
    redisClient.set("order:" + order.getId(), order);
}
```

问题：
- **一致性差**：任何一个写入失败都会导致数据不一致
- **侵入性强**：每增加一个下游系统都要修改业务代码
- **性能影响**：同步写入多个系统会增加响应时间
- **耦合严重**：下游系统故障可能影响主流程

**方案二：定时全量/增量查询**

```java
// 定时任务轮询变更
@Scheduled(fixedRate = 60000) // 每分钟执行
public void syncToES() {
    // 查询最近一分钟变更的数据
    LocalDateTime lastSyncTime = getLastSyncTime();
    List<Order> changedOrders = orderDao.findByUpdateTimeAfter(lastSyncTime);
    
    // 批量同步到 ES
    for (Order order : changedOrders) {
        esClient.index(order);
    }
}
```

问题：
- **实时性差**：存在分钟级延迟
- **性能消耗大**：频繁查询数据库
- **无法捕获删除操作**：物理删除的数据无法被查询到
- **依赖 update_time 字段**：遗漏未更新时间戳的修改

### 1.3 CDC 的核心价值

CDC（Change Data Capture，变更数据捕获）通过监听数据库的变更日志（如 MySQL 的 Binlog），以非侵入的方式捕获数据库中的每一次数据变更，并将变更事件有序地传递给下游消费者。

CDC 的核心优势：

| 优势 | 说明 |
|------|------|
| 非侵入性 | 不需要修改业务代码，直接读取数据库日志 |
| 完整性 | 能捕获所有变更操作（INSERT、UPDATE、DELETE） |
| 有序性 | 变更事件按照数据库提交顺序排列 |
| 实时性 | 毫秒级延迟捕获数据变更 |
| 低开销 | 对源数据库性能影响极小 |
| 时间戳精确 | 每条变更都携带精确的时间戳信息 |

CDC 的三大应用领域：

1. **数据同步/ETL**：结构化增量变更，减少同步数据量，支持实时数据管道
2. **增量备份**：支持 PITR（Point-In-Time Recovery，时间点恢复）能力
3. **实时数据订阅**：与流处理引擎（Kafka、Spark、Flink）集成，实现实时计算

---

## 二、整体架构设计

### 2.1 CDC 技术原理

不同数据库的 CDC 实现机制各有不同，但核心思路都是基于数据库的变更日志：

| 数据库 | CDC 机制 | 日志类型 |
|--------|---------|---------|
| MySQL | Binlog 解析 | Binary Log（ROW 格式） |
| PostgreSQL | WAL 解析 | Write-Ahead Log |
| TiDB | TiCDC | Raft Log |
| CockroachDB | Changefeed | 基于 MVCC 时间戳 |
| OceanBase | CDC | Commit Log（Clog） |

**MySQL Binlog CDC 原理**：

```
MySQL Binlog CDC 工作原理：

                    ┌──────────────────────────┐
                    │        MySQL Server      │
                    │                          │
                    │  ┌────────────────────┐  │
  SQL 写入 ───────► │  │    InnoDB Engine    │  │
                    │  └────────┬───────────┘  │
                    │           │               │
                    │  ┌────────▼───────────┐  │
                    │  │  Binlog (ROW格式)   │  │
                    │  │                    │  │
                    │  │  Event1: INSERT    │  │
                    │  │  Event2: UPDATE    │  │
                    │  │  Event3: DELETE    │  │
                    │  └────────┬───────────┘  │
                    └───────────┼──────────────┘
                                │
                    ┌───────────▼──────────────┐
                    │    CDC 组件（模拟从库）    │
                    │                          │
                    │  1. 伪装为 MySQL Slave    │
                    │  2. 请求 Binlog dump     │
                    │  3. 解析 Binlog Event    │
                    │  4. 转换为结构化变更事件   │
                    └───────────┬──────────────┘
                                │
                    ┌───────────▼──────────────┐
                    │      下游消费者           │
                    │  ES / Redis / Kafka /... │
                    └──────────────────────────┘
```

Binlog 事件格式（ROW 模式下）：

```java
/**
 * Binlog 事件结构
 * ROW 格式下，Binlog 记录每一行数据的变更前后值
 */
public class BinlogEvent {
    
    /**
     * INSERT 事件
     * 包含插入后的完整行数据
     */
    // WriteRowsEvent: {after: {id=1, name="张三", age=25}}
    
    /**
     * UPDATE 事件
     * 包含变更前和变更后的完整行数据
     */
    // UpdateRowsEvent: {
    //   before: {id=1, name="张三", age=25},
    //   after:  {id=1, name="张三", age=26}
    // }
    
    /**
     * DELETE 事件
     * 包含删除前的完整行数据
     */
    // DeleteRowsEvent: {before: {id=1, name="张三", age=26}}
}
```

### 2.2 整体架构总览

一个完整的 CDC 数据同步系统通常包含以下核心模块：

```
CDC 数据同步系统整体架构：

┌──────────────────────────────────────────────────────────────────┐
│                        管控平面                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ 任务管理  │  │ 监控告警  │  │ 元数据    │  │ Schema 管理   │   │
│  └──────────┘  └──────────┘  └──────────┘  └───────────────┘   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                        数据平面                                   │
│                                                                  │
│  ┌────────────┐                                                  │
│  │ Source DB   │                                                  │
│  │ (MySQL)     │                                                  │
│  └─────┬──────┘                                                  │
│        │ Binlog                                                  │
│  ┌─────▼──────┐   ┌──────────┐   ┌──────────┐   ┌───────────┐  │
│  │  Puller    │──►│  Sorter  │──►│ Mounter  │──►│  Sinker   │  │
│  │ (日志抽取)  │   │ (排序)    │   │ (装配)    │   │ (下发)    │  │
│  └────────────┘   └──────────┘   └──────────┘   └─────┬─────┘  │
│                                                        │        │
│                                              ┌─────────┼────────┤
│                                              │         │        │
│                                        ┌─────▼──┐ ┌────▼───┐   │
│                                        │  MySQL │ │  ES    │   │
│                                        │  (下游) │ │        │   │
│                                        └────────┘ └────────┘   │
│                                        ┌────────┐ ┌────────┐   │
│                                        │ Redis  │ │ Kafka  │   │
│                                        └────────┘ └────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

核心模块说明：

| 模块 | 职责 | 关键能力 |
|------|------|---------|
| Puller（日志抽取器） | 从源数据库拉取变更日志 | Binlog 监听、位点管理 |
| Sorter（排序器） | 按时间戳对变更事件排序 | 保证全局有序性 |
| Mounter（装配器） | 将原始日志转换为结构化变更事件 | Schema 解析、列族映射 |
| Sinker（下发器） | 将变更事件应用到下游系统 | 多下游适配、幂等写入 |

### 2.3 数据传输服务的核心能力

一个成熟的数据传输服务通常需要具备以下核心能力：

```
数据传输服务能力矩阵：

┌──────────────────────────────────────────────────────────────┐
│                    数据传输服务                                │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 核心能力一：实时传输（Binlog 监听）                       │  │
│  │   • 实时捕获数据库变更                                   │  │
│  │   • 支持行级有序性保证                                   │  │
│  │   • At-least-once 投递语义                              │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 核心能力二：全量迁移（JDBC 查询）                         │  │
│  │   • SELECT 全表数据批量读取                              │  │
│  │   • 分批次导出，控制内存占用                              │  │
│  │   • 支持断点续传                                        │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 核心能力三：全量校验（Checksum 对比）                     │  │
│  │   • 源端和目标端数据逐行校验                              │  │
│  │   • 支持全量校验和增量校验                                │  │
│  │   • 不一致数据自动修复                                   │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 高级特性                                                │  │
│  │   • 内置同步服务（分片同步、缓存同步、搜索同步）           │  │
│  │   • SDK 支持自定义 Binlog 消费                          │  │
│  │   • 多租户集群隔离                                      │  │
│  │   • 分片级水平扩展                                      │  │
│  │   • 基于时间的回放能力                                   │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、核心链路设计

### 3.1 Binlog 抽取模块（Puller）

Puller 是 CDC 系统的入口模块，负责从源数据库拉取 Binlog 并进行初步解析。

```java
/**
 * Binlog 抽取器
 * 伪装为 MySQL 从库，通过 Binlog dump 协议获取变更事件
 */
public class BinlogPuller implements Lifecycle {
    
    private final BinlogConfig config;
    private final EventQueue eventQueue;
    private final CheckpointManager checkpointManager;
    
    private BinaryLogClient client;
    private volatile boolean running = false;
    
    /**
     * Binlog 配置
     */
    public static class BinlogConfig {
        private String host;
        private int port;
        private String username;
        private String password;
        private long serverId;         // 伪装的 server-id，不能与其他从库冲突
        private String binlogFilename; // 起始 Binlog 文件名
        private long binlogPosition;   // 起始位点
        private List<String> includeSchemas; // 监听的数据库
        private List<String> includeTables;  // 监听的表
    }
    
    /**
     * 启动 Binlog 监听
     */
    @Override
    public void start() {
        // 从检查点恢复位点信息
        Checkpoint checkpoint = checkpointManager.getLatestCheckpoint();
        
        client = new BinaryLogClient(
            config.getHost(), config.getPort(),
            config.getUsername(), config.getPassword()
        );
        
        // 设置 server-id（模拟从库）
        client.setServerId(config.getServerId());
        
        // 设置起始位点
        if (checkpoint != null) {
            client.setBinlogFilename(checkpoint.getBinlogFile());
            client.setBinlogPosition(checkpoint.getBinlogPosition());
            log.info("Resuming from checkpoint: {}:{}", 
                checkpoint.getBinlogFile(), checkpoint.getBinlogPosition());
        } else {
            client.setBinlogFilename(config.getBinlogFilename());
            client.setBinlogPosition(config.getBinlogPosition());
        }
        
        // 注册事件监听器
        client.registerEventListener(this::handleEvent);
        
        // 注册生命周期监听器
        client.registerLifecycleListener(new BinaryLogClient.LifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient client) {
                log.info("Connected to MySQL for Binlog replication");
                running = true;
            }
            
            @Override
            public void onDisconnect(BinaryLogClient client) {
                log.warn("Disconnected from MySQL");
                running = false;
                // 触发自动重连
                scheduleReconnect();
            }
            
            @Override
            public void onCommunicationFailure(
                    BinaryLogClient client, Exception ex) {
                log.error("Communication failure", ex);
                metrics.counter("binlog.communication.failure").increment();
            }
            
            @Override
            public void onEventDeserializationFailure(
                    BinaryLogClient client, Exception ex) {
                log.error("Event deserialization failure", ex);
                metrics.counter("binlog.deserialization.failure").increment();
            }
        });
        
        // 异步连接（非阻塞）
        CompletableFuture.runAsync(() -> {
            try {
                client.connect();
            } catch (IOException e) {
                log.error("Failed to connect to MySQL", e);
                scheduleReconnect();
            }
        });
    }
    
    /**
     * 处理 Binlog 事件
     */
    private void handleEvent(Event event) {
        EventHeader header = event.getHeader();
        EventType eventType = header.getEventType();
        
        try {
            switch (eventType) {
                case TABLE_MAP:
                    // 表映射事件：记录表ID和表名的对应关系
                    handleTableMapEvent(event);
                    break;
                    
                case WRITE_ROWS:
                case EXT_WRITE_ROWS:
                    // INSERT 事件
                    handleInsertEvent(event);
                    break;
                    
                case UPDATE_ROWS:
                case EXT_UPDATE_ROWS:
                    // UPDATE 事件
                    handleUpdateEvent(event);
                    break;
                    
                case DELETE_ROWS:
                case EXT_DELETE_ROWS:
                    // DELETE 事件
                    handleDeleteEvent(event);
                    break;
                    
                case QUERY:
                    // DDL 事件
                    handleDDLEvent(event);
                    break;
                    
                case XID:
                    // 事务提交事件
                    handleTransactionCommit(event);
                    break;
                    
                case ROTATE:
                    // Binlog 文件切换事件
                    handleRotateEvent(event);
                    break;
                    
                default:
                    // 忽略其他事件类型
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to handle binlog event: type={}", 
                eventType, e);
            metrics.counter("binlog.handle.error").increment();
        }
    }
    
    /**
     * 处理 INSERT 事件
     */
    private void handleInsertEvent(Event event) {
        WriteRowsEventData data = event.getData();
        long tableId = data.getTableId();
        TableInfo tableInfo = tableInfoMap.get(tableId);
        
        if (tableInfo == null || !shouldCapture(tableInfo)) {
            return;
        }
        
        for (Serializable[] row : data.getRows()) {
            ChangeEvent changeEvent = new ChangeEvent();
            changeEvent.setType(ChangeType.INSERT);
            changeEvent.setSchema(tableInfo.getSchema());
            changeEvent.setTable(tableInfo.getTable());
            changeEvent.setTimestamp(event.getHeader().getTimestamp());
            changeEvent.setBinlogFile(client.getBinlogFilename());
            changeEvent.setBinlogPosition(
                ((EventHeaderV4) event.getHeader()).getPosition()
            );
            
            // 将行数据映射为列名-值的 Map
            Map<String, Object> afterValues = mapRowToColumns(
                row, tableInfo.getColumnDefinitions()
            );
            changeEvent.setAfter(afterValues);
            
            // 放入事件队列
            eventQueue.put(changeEvent);
        }
        
        metrics.counter("binlog.events.insert").increment();
    }
    
    /**
     * 处理 UPDATE 事件
     */
    private void handleUpdateEvent(Event event) {
        UpdateRowsEventData data = event.getData();
        long tableId = data.getTableId();
        TableInfo tableInfo = tableInfoMap.get(tableId);
        
        if (tableInfo == null || !shouldCapture(tableInfo)) {
            return;
        }
        
        for (Map.Entry<Serializable[], Serializable[]> entry : 
             data.getRows()) {
            
            ChangeEvent changeEvent = new ChangeEvent();
            changeEvent.setType(ChangeType.UPDATE);
            changeEvent.setSchema(tableInfo.getSchema());
            changeEvent.setTable(tableInfo.getTable());
            changeEvent.setTimestamp(event.getHeader().getTimestamp());
            
            // UPDATE 事件包含变更前后的值
            Map<String, Object> beforeValues = mapRowToColumns(
                entry.getKey(), tableInfo.getColumnDefinitions()
            );
            Map<String, Object> afterValues = mapRowToColumns(
                entry.getValue(), tableInfo.getColumnDefinitions()
            );
            
            changeEvent.setBefore(beforeValues);
            changeEvent.setAfter(afterValues);
            
            eventQueue.put(changeEvent);
        }
        
        metrics.counter("binlog.events.update").increment();
    }
    
    /**
     * 处理 DELETE 事件
     */
    private void handleDeleteEvent(Event event) {
        DeleteRowsEventData data = event.getData();
        long tableId = data.getTableId();
        TableInfo tableInfo = tableInfoMap.get(tableId);
        
        if (tableInfo == null || !shouldCapture(tableInfo)) {
            return;
        }
        
        for (Serializable[] row : data.getRows()) {
            ChangeEvent changeEvent = new ChangeEvent();
            changeEvent.setType(ChangeType.DELETE);
            changeEvent.setSchema(tableInfo.getSchema());
            changeEvent.setTable(tableInfo.getTable());
            changeEvent.setTimestamp(event.getHeader().getTimestamp());
            
            Map<String, Object> beforeValues = mapRowToColumns(
                row, tableInfo.getColumnDefinitions()
            );
            changeEvent.setBefore(beforeValues);
            
            eventQueue.put(changeEvent);
        }
        
        metrics.counter("binlog.events.delete").increment();
    }
    
    /**
     * 判断是否需要捕获该表的变更
     */
    private boolean shouldCapture(TableInfo tableInfo) {
        // 按配置的库表过滤规则判断
        if (!config.getIncludeSchemas().isEmpty() && 
            !config.getIncludeSchemas().contains(tableInfo.getSchema())) {
            return false;
        }
        
        if (!config.getIncludeTables().isEmpty() && 
            !config.getIncludeTables().contains(
                tableInfo.getSchema() + "." + tableInfo.getTable())) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 断点续传：定期保存 Binlog 位点
     */
    @Scheduled(fixedRate = 5000) // 每5秒保存一次
    public void saveCheckpoint() {
        if (!running) return;
        
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setBinlogFile(client.getBinlogFilename());
        checkpoint.setBinlogPosition(client.getBinlogPosition());
        checkpoint.setTimestamp(System.currentTimeMillis());
        
        checkpointManager.save(checkpoint);
        
        log.debug("Checkpoint saved: {}:{}", 
            checkpoint.getBinlogFile(), checkpoint.getBinlogPosition());
    }
    
    /**
     * 自动重连机制
     */
    private void scheduleReconnect() {
        ScheduledExecutorService scheduler = 
            Executors.newSingleThreadScheduledExecutor();
        
        scheduler.schedule(() -> {
            log.info("Attempting to reconnect to MySQL...");
            start();
        }, 5, TimeUnit.SECONDS);
    }
}
```

### 3.2 排序模块（Sorter）

Sorter 负责对来自 Puller 的变更事件进行排序，保证变更事件的全局有序性。

```java
/**
 * 事件排序器
 * 
 * 排序策略：
 * 1. 同一行的变更事件必须保持原始顺序
 * 2. 不同行的变更事件可以并行处理
 * 3. 事务内的所有变更事件保持原子性
 * 
 * 排序维度：
 * - 主键级别有序：同一主键的变更按 Binlog 位点排序
 * - 表级别有序：同一表的变更按 Binlog 位点排序（可选）
 * - 全局有序：所有变更按 Binlog 位点排序（性能最差）
 */
public class EventSorter {
    
    // 按主键分组的排序缓冲区
    private final Map<String, PriorityQueue<ChangeEvent>> bufferMap = 
        new ConcurrentHashMap<>();
    
    // 已确认的最小位点（watermark）
    private volatile long resolvedTimestamp = 0;
    
    /**
     * 接收变更事件并排序
     */
    public void addEvent(ChangeEvent event) {
        // 计算事件的路由键（表名 + 主键值）
        String routingKey = buildRoutingKey(event);
        
        // 放入对应的排序缓冲区
        bufferMap.computeIfAbsent(routingKey, k -> 
            new PriorityQueue<>(Comparator.comparingLong(
                ChangeEvent::getTimestamp
            ))
        ).add(event);
    }
    
    /**
     * 推进水位线（Watermark）
     * 
     * 当 Puller 确认某个时间点之前的所有事件都已经发送完毕时，
     * 推进水位线，允许该时间点之前的事件被下发
     * 
     * 这个机制保证了不会有更早的事件在水位线之后到来
     */
    public void advanceWatermark(long timestamp) {
        this.resolvedTimestamp = timestamp;
    }
    
    /**
     * 获取可以安全下发的事件
     * 只有时间戳小于水位线的事件才能被下发
     */
    public List<ChangeEvent> pollResolvedEvents() {
        List<ChangeEvent> resolvedEvents = new ArrayList<>();
        
        for (Map.Entry<String, PriorityQueue<ChangeEvent>> entry : 
             bufferMap.entrySet()) {
            
            PriorityQueue<ChangeEvent> queue = entry.getValue();
            
            while (!queue.isEmpty() && 
                   queue.peek().getTimestamp() <= resolvedTimestamp) {
                resolvedEvents.add(queue.poll());
            }
            
            // 清理空队列
            if (queue.isEmpty()) {
                bufferMap.remove(entry.getKey());
            }
        }
        
        // 按时间戳排序后返回
        resolvedEvents.sort(Comparator.comparingLong(
            ChangeEvent::getTimestamp
        ));
        
        return resolvedEvents;
    }
    
    /**
     * 构建路由键
     * 同一行数据的变更事件使用相同的路由键
     */
    private String buildRoutingKey(ChangeEvent event) {
        StringBuilder key = new StringBuilder();
        key.append(event.getSchema()).append(".")
           .append(event.getTable()).append(".");
        
        // 提取主键值
        Map<String, Object> values = event.getAfter() != null ? 
            event.getAfter() : event.getBefore();
        
        for (String pkColumn : event.getPrimaryKeyColumns()) {
            key.append(values.get(pkColumn)).append("_");
        }
        
        return key.toString();
    }
}
```

### 3.3 装配模块（Mounter）

Mounter 负责将原始的 Binlog 事件转换为业务可理解的结构化变更事件。

```java
/**
 * 事件装配器
 * 
 * 核心职责：
 * 1. Schema 解析：将二进制列数据映射到具体的列名和类型
 * 2. 列族映射：支持按列族（Column Family）选择性同步
 * 3. 数据转换：类型适配、编码转换
 * 4. 事件增强：添加元数据信息
 */
public class EventMounter {
    
    private final SchemaManager schemaManager;
    private final ColumnFamilyConfig columnFamilyConfig;
    
    /**
     * 装配变更事件
     */
    public MountedChangeEvent mount(ChangeEvent rawEvent) {
        // 获取表的 Schema 信息
        TableSchema schema = schemaManager.getSchema(
            rawEvent.getSchema(), rawEvent.getTable()
        );
        
        if (schema == null) {
            throw new SchemaNotFoundException(
                rawEvent.getSchema() + "." + rawEvent.getTable()
            );
        }
        
        MountedChangeEvent mountedEvent = new MountedChangeEvent();
        mountedEvent.setType(rawEvent.getType());
        mountedEvent.setSchema(rawEvent.getSchema());
        mountedEvent.setTable(rawEvent.getTable());
        mountedEvent.setTimestamp(rawEvent.getTimestamp());
        mountedEvent.setBinlogPosition(rawEvent.getBinlogPosition());
        
        // 装配变更前的值
        if (rawEvent.getBefore() != null) {
            Map<String, ColumnValue> beforeColumns = mountColumns(
                rawEvent.getBefore(), schema
            );
            mountedEvent.setBeforeColumns(beforeColumns);
        }
        
        // 装配变更后的值
        if (rawEvent.getAfter() != null) {
            Map<String, ColumnValue> afterColumns = mountColumns(
                rawEvent.getAfter(), schema
            );
            mountedEvent.setAfterColumns(afterColumns);
        }
        
        // 提取主键信息
        mountedEvent.setPrimaryKeys(extractPrimaryKeys(
            rawEvent, schema
        ));
        
        // 计算变更的列（用于增量更新场景）
        if (rawEvent.getType() == ChangeType.UPDATE) {
            Set<String> changedColumns = calculateChangedColumns(
                mountedEvent.getBeforeColumns(), 
                mountedEvent.getAfterColumns()
            );
            mountedEvent.setChangedColumns(changedColumns);
        }
        
        // 添加元数据
        mountedEvent.setMetadata(buildMetadata(rawEvent, schema));
        
        return mountedEvent;
    }
    
    /**
     * 将原始列数据映射为带类型的列值
     */
    private Map<String, ColumnValue> mountColumns(
            Map<String, Object> rawValues, TableSchema schema) {
        
        Map<String, ColumnValue> columns = new LinkedHashMap<>();
        
        for (ColumnDefinition colDef : schema.getColumns()) {
            Object rawValue = rawValues.get(colDef.getName());
            
            // 按列族过滤（可选：只同步特定列族的数据）
            if (!columnFamilyConfig.shouldInclude(
                    schema.getTableName(), colDef.getName())) {
                continue;
            }
            
            // 类型转换
            Object convertedValue = convertValue(rawValue, colDef);
            
            ColumnValue columnValue = new ColumnValue();
            columnValue.setName(colDef.getName());
            columnValue.setJdbcType(colDef.getJdbcType());
            columnValue.setValue(convertedValue);
            columnValue.setIsPrimaryKey(colDef.isPrimaryKey());
            columnValue.setIsNullable(colDef.isNullable());
            
            columns.put(colDef.getName(), columnValue);
        }
        
        return columns;
    }
    
    /**
     * 值类型转换
     * 将 Binlog 中的原始类型转换为 Java 类型
     */
    private Object convertValue(Object rawValue, ColumnDefinition colDef) {
        if (rawValue == null) return null;
        
        switch (colDef.getJdbcType()) {
            case Types.TIMESTAMP:
            case Types.DATE:
                // MySQL 时间类型转换
                if (rawValue instanceof Long) {
                    return new Timestamp((Long) rawValue);
                }
                break;
                
            case Types.DECIMAL:
                // 精确数值类型
                if (rawValue instanceof byte[]) {
                    return new BigDecimal(new String((byte[]) rawValue));
                }
                break;
                
            case Types.BLOB:
            case Types.BINARY:
                // 二进制类型，保持原始 byte[]
                return rawValue;
                
            case Types.VARCHAR:
            case Types.CHAR:
                // 字符串类型，处理编码
                if (rawValue instanceof byte[]) {
                    return new String((byte[]) rawValue, 
                        Charset.forName(colDef.getCharset()));
                }
                return rawValue.toString();
                
            default:
                return rawValue;
        }
        
        return rawValue;
    }
    
    /**
     * 计算变更的列
     * 对比 before 和 after，找出实际发生变化的列
     */
    private Set<String> calculateChangedColumns(
            Map<String, ColumnValue> before, 
            Map<String, ColumnValue> after) {
        
        Set<String> changed = new HashSet<>();
        
        for (Map.Entry<String, ColumnValue> entry : after.entrySet()) {
            String colName = entry.getKey();
            ColumnValue afterVal = entry.getValue();
            ColumnValue beforeVal = before.get(colName);
            
            if (beforeVal == null || !Objects.equals(
                    beforeVal.getValue(), afterVal.getValue())) {
                changed.add(colName);
            }
        }
        
        return changed;
    }
}
```

### 3.4 下发模块（Sinker）

Sinker 负责将装配好的变更事件应用到下游系统。

```java
/**
 * 事件下发器（Sinker）基础框架
 * 
 * 支持多种下游类型的适配
 */
public abstract class AbstractSinker implements Lifecycle {
    
    protected final SinkerConfig config;
    protected final CheckpointManager checkpointManager;
    
    /**
     * 批量下发变更事件
     * 子类实现具体的下发逻辑
     */
    public void sink(List<MountedChangeEvent> events) {
        if (events.isEmpty()) return;
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 预处理（过滤、转换）
            List<MountedChangeEvent> processedEvents = preProcess(events);
            
            // 批量写入下游
            doSink(processedEvents);
            
            // 更新检查点
            MountedChangeEvent lastEvent = events.get(events.size() - 1);
            checkpointManager.save(new Checkpoint(
                lastEvent.getBinlogFile(),
                lastEvent.getBinlogPosition(),
                lastEvent.getTimestamp()
            ));
            
            // 记录指标
            long elapsed = System.currentTimeMillis() - startTime;
            metrics.timer("sinker.batch.latency").record(
                elapsed, TimeUnit.MILLISECONDS
            );
            metrics.counter("sinker.events.total").increment(events.size());
            
        } catch (Exception e) {
            log.error("Failed to sink {} events", events.size(), e);
            metrics.counter("sinker.errors").increment();
            
            // 重试策略
            handleSinkFailure(events, e);
        }
    }
    
    protected abstract void doSink(List<MountedChangeEvent> events);
    
    protected abstract void handleSinkFailure(
        List<MountedChangeEvent> events, Exception e);
}
```

#### 3.4.1 MySQL 下游同步

```java
/**
 * MySQL Sinker - 同步到 MySQL 目标库
 * 
 * 应用场景：
 * 1. 数据库迁移（旧库 → 新库）
 * 2. 分库分表同步
 * 3. 主从同构同步
 */
public class MySQLSinker extends AbstractSinker {
    
    private final DataSource targetDataSource;
    
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        // 按表分组，批量执行
        Map<String, List<MountedChangeEvent>> tableGroups = events.stream()
            .collect(Collectors.groupingBy(
                e -> e.getSchema() + "." + e.getTable()
            ));
        
        for (Map.Entry<String, List<MountedChangeEvent>> entry : 
             tableGroups.entrySet()) {
            
            batchApplyToTable(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * 批量应用变更到目标表
     * 使用 REPLACE INTO 实现幂等写入
     */
    private void batchApplyToTable(String tableName, 
                                   List<MountedChangeEvent> events) {
        
        JdbcTemplate template = new JdbcTemplate(targetDataSource);
        
        for (MountedChangeEvent event : events) {
            switch (event.getType()) {
                case INSERT:
                case UPDATE:
                    // 使用 REPLACE INTO 实现幂等的 UPSERT
                    String columns = event.getAfterColumns().keySet()
                        .stream()
                        .collect(Collectors.joining(", "));
                    String placeholders = event.getAfterColumns().keySet()
                        .stream()
                        .map(c -> "?")
                        .collect(Collectors.joining(", "));
                    
                    String sql = String.format(
                        "REPLACE INTO %s (%s) VALUES (%s)",
                        tableName, columns, placeholders
                    );
                    
                    Object[] values = event.getAfterColumns().values()
                        .stream()
                        .map(ColumnValue::getValue)
                        .toArray();
                    
                    template.update(sql, values);
                    break;
                    
                case DELETE:
                    // 按主键删除
                    String whereClause = event.getPrimaryKeys().entrySet()
                        .stream()
                        .map(e -> e.getKey() + " = ?")
                        .collect(Collectors.joining(" AND "));
                    
                    String deleteSql = String.format(
                        "DELETE FROM %s WHERE %s", tableName, whereClause
                    );
                    
                    Object[] pkValues = event.getPrimaryKeys().values()
                        .toArray();
                    
                    template.update(deleteSql, pkValues);
                    break;
            }
        }
    }
    
    /**
     * 防回环标记
     * 在双向同步场景中，需要标记 CDC 产生的写入，
     * 避免产生循环同步
     */
    private void applyWithAntiLoopMark(MountedChangeEvent event) {
        JdbcTemplate template = new JdbcTemplate(targetDataSource);
        
        // 设置会话变量标记，CDC 组件识别此标记后跳过这些变更
        template.execute("SET @CDC_SYNC_MARK = 1");
        
        // 执行数据变更
        applyEvent(event);
        
        // 清除标记
        template.execute("SET @CDC_SYNC_MARK = 0");
    }
}
```

#### 3.4.2 Elasticsearch 下游同步

```java
/**
 * Elasticsearch Sinker - 同步到搜索引擎
 * 
 * 应用场景：
 * 1. 商品搜索索引构建
 * 2. 日志全文检索
 * 3. 用户画像索引
 */
public class ElasticsearchSinker extends AbstractSinker {
    
    private final RestHighLevelClient esClient;
    private final IndexMappingConfig mappingConfig;
    
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        // 使用 Bulk API 批量写入
        BulkRequest bulkRequest = new BulkRequest();
        
        for (MountedChangeEvent event : events) {
            // 获取 ES 索引名和文档ID的映射配置
            String indexName = mappingConfig.getIndexName(
                event.getSchema(), event.getTable()
            );
            String documentId = buildDocumentId(event);
            
            switch (event.getType()) {
                case INSERT:
                    // 新增文档
                    Map<String, Object> insertDoc = 
                        buildDocument(event.getAfterColumns());
                    
                    IndexRequest indexRequest = new IndexRequest(indexName)
                        .id(documentId)
                        .source(insertDoc);
                    bulkRequest.add(indexRequest);
                    break;
                    
                case UPDATE:
                    // 增量更新：只更新变更的字段
                    Map<String, Object> updateDoc = new HashMap<>();
                    for (String changedCol : event.getChangedColumns()) {
                        ColumnValue colVal = event.getAfterColumns()
                            .get(changedCol);
                        if (colVal != null) {
                            String esField = mappingConfig.mapColumnToField(
                                event.getTable(), changedCol
                            );
                            updateDoc.put(esField, colVal.getValue());
                        }
                    }
                    
                    UpdateRequest updateRequest = new UpdateRequest(
                        indexName, documentId
                    ).doc(updateDoc)
                     .docAsUpsert(true); // 不存在则插入
                    bulkRequest.add(updateRequest);
                    break;
                    
                case DELETE:
                    // 删除文档
                    DeleteRequest deleteRequest = new DeleteRequest(
                        indexName, documentId
                    );
                    bulkRequest.add(deleteRequest);
                    break;
            }
        }
        
        // 执行 Bulk 请求
        if (bulkRequest.numberOfActions() > 0) {
            try {
                BulkResponse response = esClient.bulk(
                    bulkRequest, RequestOptions.DEFAULT
                );
                
                if (response.hasFailures()) {
                    // 处理部分失败
                    handlePartialFailure(response, events);
                }
                
                metrics.counter("es.sink.docs").increment(
                    bulkRequest.numberOfActions()
                );
            } catch (IOException e) {
                throw new SinkException("ES bulk request failed", e);
            }
        }
    }
    
    /**
     * 构建 ES 文档
     * 将数据库列映射为 ES 文档字段
     */
    private Map<String, Object> buildDocument(
            Map<String, ColumnValue> columns) {
        
        Map<String, Object> doc = new HashMap<>();
        
        for (Map.Entry<String, ColumnValue> entry : columns.entrySet()) {
            String columnName = entry.getKey();
            ColumnValue columnValue = entry.getValue();
            
            // 列名到 ES 字段名的映射
            String esFieldName = mappingConfig.mapColumnToField(
                columnName, columnName
            );
            
            // 值类型转换（如日期格式转换）
            Object esValue = convertToESValue(columnValue);
            
            doc.put(esFieldName, esValue);
        }
        
        return doc;
    }
    
    /**
     * 处理部分写入失败
     */
    private void handlePartialFailure(BulkResponse response, 
                                      List<MountedChangeEvent> events) {
        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) {
                BulkItemResponse.Failure failure = item.getFailure();
                log.error("ES sink partial failure: index={}, id={}, " +
                    "cause={}", 
                    failure.getIndex(), failure.getId(), 
                    failure.getMessage());
                
                // 记录失败事件到重试队列
                int eventIndex = item.getItemId();
                if (eventIndex < events.size()) {
                    retryQueue.add(events.get(eventIndex));
                }
            }
        }
    }
}
```

#### 3.4.3 Redis 缓存同步

```java
/**
 * Redis Sinker - 同步到 KV 缓存
 * 
 * 应用场景：
 * 1. 热点数据缓存自动更新
 * 2. 缓存与数据库一致性保障
 * 3. 实时计数器/排行榜更新
 */
public class RedisSinker extends AbstractSinker {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final CacheKeyConfig keyConfig;
    
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        // 使用 Pipeline 批量写入
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (MountedChangeEvent event : events) {
                applyCacheChange(connection, event);
            }
            return null;
        });
    }
    
    /**
     * 应用缓存变更
     * 
     * 策略：删除缓存（Cache Invalidation）而非更新缓存
     * 原因：删除操作是幂等的，即使 CDC 事件重复投递也不会产生问题
     */
    private void applyCacheChange(RedisConnection connection, 
                                  MountedChangeEvent event) {
        // 构建缓存 Key
        List<String> cacheKeys = keyConfig.buildKeys(
            event.getSchema(), event.getTable(), 
            event.getPrimaryKeys()
        );
        
        switch (event.getType()) {
            case INSERT:
                // 可选：直接写入缓存（适用于高频读取的数据）
                if (keyConfig.isPreloadEnabled(event.getTable())) {
                    String value = JSON.toJSONString(
                        extractValues(event.getAfterColumns())
                    );
                    for (String key : cacheKeys) {
                        connection.setEx(
                            key.getBytes(), 
                            keyConfig.getTtlSeconds(event.getTable()),
                            value.getBytes()
                        );
                    }
                }
                break;
                
            case UPDATE:
            case DELETE:
                // 删除缓存，下次读取时从数据库加载
                for (String key : cacheKeys) {
                    connection.del(key.getBytes());
                }
                
                // 如果有相关的列表缓存也需要失效
                List<String> relatedKeys = keyConfig.getRelatedKeys(
                    event.getSchema(), event.getTable(), event
                );
                for (String key : relatedKeys) {
                    connection.del(key.getBytes());
                }
                break;
        }
        
        metrics.counter("redis.sink.operations").increment();
    }
}
```

#### 3.4.4 Kafka 消息队列同步

```java
/**
 * Kafka Sinker - 同步到消息队列
 * 
 * 应用场景：
 * 1. 数据变更事件广播
 * 2. 流处理引擎输入（Flink/Spark）
 * 3. 微服务间的数据同步
 */
public class KafkaSinker extends AbstractSinker {
    
    private final KafkaProducer<String, String> producer;
    private final TopicMappingConfig topicConfig;
    
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        List<CompletableFuture<RecordMetadata>> futures = new ArrayList<>();
        
        for (MountedChangeEvent event : events) {
            // 确定目标 Topic
            String topic = topicConfig.getTopic(
                event.getSchema(), event.getTable()
            );
            
            // 构建 Kafka 消息
            String key = buildMessageKey(event);
            String value = buildMessageValue(event);
            
            ProducerRecord<String, String> record = 
                new ProducerRecord<>(topic, key, value);
            
            // 添加消息头
            record.headers().add("schema", 
                event.getSchema().getBytes());
            record.headers().add("table", 
                event.getTable().getBytes());
            record.headers().add("changeType", 
                event.getType().name().getBytes());
            record.headers().add("timestamp", 
                String.valueOf(event.getTimestamp()).getBytes());
            
            // 异步发送
            CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(metadata);
                }
            });
            futures.add(future);
        }
        
        // 等待所有消息发送完成
        try {
            CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            ).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new SinkException("Kafka sink failed", e);
        }
    }
    
    /**
     * 构建消息 Key
     * 使用主键作为消息 Key，保证同一行数据的变更发送到同一分区
     * 从而保证行级别的有序性
     */
    private String buildMessageKey(MountedChangeEvent event) {
        return event.getSchema() + "." + event.getTable() + ":" + 
            event.getPrimaryKeys().values().stream()
                .map(Object::toString)
                .collect(Collectors.joining("_"));
    }
    
    /**
     * 构建消息体
     * 统一的变更事件格式
     */
    private String buildMessageValue(MountedChangeEvent event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", event.getType().name());
        message.put("schema", event.getSchema());
        message.put("table", event.getTable());
        message.put("timestamp", event.getTimestamp());
        message.put("primaryKeys", event.getPrimaryKeys());
        
        if (event.getBeforeColumns() != null) {
            message.put("before", extractValues(event.getBeforeColumns()));
        }
        if (event.getAfterColumns() != null) {
            message.put("after", extractValues(event.getAfterColumns()));
        }
        if (event.getChangedColumns() != null) {
            message.put("changedColumns", event.getChangedColumns());
        }
        
        return JSON.toJSONString(message);
    }
}
```

### 3.5 全量 + 增量同步流程

完整的数据同步通常分为全量迁移和增量同步两个阶段。

```java
/**
 * 全量 + 增量同步管理器
 * 
 * 同步流程：
 * 
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    同步流程时序                               │
 * │                                                             │
 * │  Phase 1: 全量迁移                                          │
 * │  ──────────────────────────                                 │
 * │  │ 记录当前Binlog位点 T0                                     │
 * │  │ SELECT * FROM source_table (分批)                        │
 * │  │ 写入目标表                                                │
 * │  ──────────────────────────                                 │
 * │                              Phase 2: 增量同步               │
 * │                              ─────────────────────────      │
 * │                              │ 从 T0 位点开始消费 Binlog    │
 * │                              │ 实时同步变更到目标             │
 * │                              ─────────────────────────      │
 * │                                                  Phase 3:   │
 * │                                                  业务切换    │
 * │                                                  ─────────  │
 * │                                                  │ 校验延迟 │
 * │                                                  │ 暂停写入 │
 * │                                                  │ 等延迟=0 │
 * │                                                  │ 切连接   │
 * │                                                  │ 验证     │
 * │                                                  │ 停止同步 │
 * │                                                  ─────────  │
 * └─────────────────────────────────────────────────────────────┘
 */
public class FullIncrementalSyncManager {
    
    private final DataSource sourceDataSource;
    private final AbstractSinker sinker;
    private final BinlogPuller binlogPuller;
    
    // 同步状态
    private volatile SyncPhase currentPhase = SyncPhase.IDLE;
    
    public enum SyncPhase {
        IDLE, FULL_MIGRATION, INCREMENTAL_SYNC, SWITCHOVER, COMPLETED
    }
    
    /**
     * 启动同步任务
     */
    public void startSync(SyncConfig config) {
        log.info("Starting sync task: {}", config.getTaskName());
        
        // Phase 1: 全量迁移
        currentPhase = SyncPhase.FULL_MIGRATION;
        BinlogPosition startPosition = fullMigration(config);
        
        // Phase 2: 增量同步
        currentPhase = SyncPhase.INCREMENTAL_SYNC;
        startIncrementalSync(startPosition, config);
        
        log.info("Sync task started, entering incremental sync phase");
    }
    
    /**
     * Phase 1: 全量数据迁移
     * 
     * 关键点：
     * 1. 迁移前记录 Binlog 位点，增量同步从此位点开始
     * 2. 分批查询，控制内存使用
     * 3. 支持断点续传
     */
    private BinlogPosition fullMigration(SyncConfig config) {
        log.info("Phase 1: Starting full migration");
        
        JdbcTemplate sourceTemplate = new JdbcTemplate(sourceDataSource);
        
        // 记录当前 Binlog 位点
        BinlogPosition startPosition = getCurrentBinlogPosition();
        log.info("Recorded start binlog position: {}:{}", 
            startPosition.getFile(), startPosition.getPosition());
        
        // 获取需要同步的表列表
        List<String> tables = config.getSourceTables();
        
        for (String tableName : tables) {
            fullMigrateTable(tableName, config.getBatchSize());
        }
        
        log.info("Phase 1 completed: full migration done");
        return startPosition;
    }
    
    /**
     * 单表全量迁移
     */
    private void fullMigrateTable(String tableName, int batchSize) {
        log.info("Full migrating table: {}", tableName);
        
        JdbcTemplate template = new JdbcTemplate(sourceDataSource);
        
        // 获取表的主键信息
        String primaryKey = getPrimaryKeyColumn(tableName);
        
        // 获取断点续传位置
        Object lastProcessedId = getFullMigrationCheckpoint(tableName);
        
        long totalRows = 0;
        long startTime = System.currentTimeMillis();
        
        while (true) {
            // 分批查询（基于主键范围扫描，避免 OFFSET）
            String sql;
            Object[] params;
            
            if (lastProcessedId == null) {
                sql = String.format(
                    "SELECT * FROM %s ORDER BY %s LIMIT ?",
                    tableName, primaryKey
                );
                params = new Object[]{batchSize};
            } else {
                sql = String.format(
                    "SELECT * FROM %s WHERE %s > ? ORDER BY %s LIMIT ?",
                    tableName, primaryKey, primaryKey
                );
                params = new Object[]{lastProcessedId, batchSize};
            }
            
            List<Map<String, Object>> batch = template.queryForList(
                sql, params
            );
            
            if (batch.isEmpty()) break;
            
            // 转换为 ChangeEvent 并下发
            List<MountedChangeEvent> events = batch.stream()
                .map(row -> {
                    MountedChangeEvent event = new MountedChangeEvent();
                    event.setType(ChangeType.INSERT);
                    event.setTable(tableName);
                    event.setAfterColumns(convertToColumnValues(row));
                    return event;
                })
                .collect(Collectors.toList());
            
            sinker.sink(events);
            
            // 更新断点
            lastProcessedId = batch.get(batch.size() - 1).get(primaryKey);
            saveFullMigrationCheckpoint(tableName, lastProcessedId);
            
            totalRows += batch.size();
            
            if (totalRows % 100000 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double speed = totalRows * 1000.0 / elapsed;
                log.info("Table {} migration progress: {} rows, " +
                    "speed: {:.0f} rows/s", tableName, totalRows, speed);
            }
        }
        
        log.info("Table {} migration completed: {} rows", 
            tableName, totalRows);
    }
    
    /**
     * Phase 2: 增量同步
     */
    private void startIncrementalSync(BinlogPosition startPosition, 
                                      SyncConfig config) {
        log.info("Phase 2: Starting incremental sync from {}:{}", 
            startPosition.getFile(), startPosition.getPosition());
        
        // 配置 Binlog 抽取器
        BinlogConfig binlogConfig = new BinlogConfig();
        binlogConfig.setBinlogFilename(startPosition.getFile());
        binlogConfig.setBinlogPosition(startPosition.getPosition());
        binlogConfig.setIncludeTables(config.getSourceTables());
        
        // 启动 Puller
        binlogPuller.start();
        
        // 启动消费线程
        Thread consumerThread = new Thread(() -> {
            while (currentPhase == SyncPhase.INCREMENTAL_SYNC) {
                try {
                    List<MountedChangeEvent> events = 
                        eventQueue.poll(1, TimeUnit.SECONDS);
                    
                    if (events != null && !events.isEmpty()) {
                        sinker.sink(events);
                        
                        // 更新延迟指标
                        long delay = System.currentTimeMillis() - 
                            events.get(events.size() - 1).getTimestamp();
                        metrics.gauge("sync.delay.ms", delay);
                    }
                } catch (Exception e) {
                    log.error("Incremental sync error", e);
                }
            }
        }, "incremental-sync-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
    
    /**
     * Phase 3: 业务切换
     * 
     * 切换步骤：
     * 1. 确认同步延迟 < 1秒
     * 2. 暂停业务写入（源库设为只读或通过应用层控制）
     * 3. 等待延迟归零
     * 4. 切换数据库连接
     * 5. 验证数据一致性
     * 6. 恢复业务写入
     * 7. 停止同步任务
     */
    public SwitchoverResult switchover() {
        log.info("Phase 3: Starting switchover");
        currentPhase = SyncPhase.SWITCHOVER;
        
        SwitchoverResult result = new SwitchoverResult();
        
        // Step 1: 检查延迟
        long currentDelay = getSyncDelay();
        if (currentDelay > 1000) {
            result.setSuccess(false);
            result.setMessage("Sync delay too high: " + currentDelay + "ms");
            currentPhase = SyncPhase.INCREMENTAL_SYNC;
            return result;
        }
        
        // Step 2: 暂停源库写入
        log.info("Pausing source writes...");
        pauseSourceWrites();
        
        // Step 3: 等待延迟归零
        log.info("Waiting for sync delay to reach zero...");
        long waitStart = System.currentTimeMillis();
        while (getSyncDelay() > 0) {
            if (System.currentTimeMillis() - waitStart > 30000) {
                // 超过30秒延迟仍未归零，回滚
                resumeSourceWrites();
                result.setSuccess(false);
                result.setMessage("Timeout waiting for zero delay");
                currentPhase = SyncPhase.INCREMENTAL_SYNC;
                return result;
            }
            sleep(100);
        }
        
        // Step 4: 切换连接
        log.info("Switching database connection...");
        switchDatabaseConnection();
        
        // Step 5: 数据一致性校验（抽样）
        log.info("Verifying data consistency...");
        boolean consistent = verifyConsistency();
        if (!consistent) {
            // 回滚连接切换
            rollbackConnectionSwitch();
            resumeSourceWrites();
            result.setSuccess(false);
            result.setMessage("Data consistency check failed");
            currentPhase = SyncPhase.INCREMENTAL_SYNC;
            return result;
        }
        
        // Step 6: 恢复写入（此时已经写入新库）
        log.info("Resuming writes to new database...");
        resumeSourceWrites();
        
        // Step 7: 停止同步
        binlogPuller.stop();
        
        currentPhase = SyncPhase.COMPLETED;
        result.setSuccess(true);
        result.setMessage("Switchover completed successfully");
        
        log.info("Switchover completed!");
        return result;
    }
    
    /**
     * 获取当前 Binlog 位点
     */
    private BinlogPosition getCurrentBinlogPosition() {
        JdbcTemplate template = new JdbcTemplate(sourceDataSource);
        Map<String, Object> result = template.queryForMap(
            "SHOW MASTER STATUS"
        );
        
        return new BinlogPosition(
            (String) result.get("File"),
            (Long) result.get("Position")
        );
    }
}
```

### 3.6 数据一致性校验

```java
/**
 * 数据一致性校验器
 * 
 * 校验方式：
 * 1. 全量校验：逐行对比源端和目标端数据
 * 2. Checksum 校验：对比数据块的校验和
 * 3. 行数校验：对比源端和目标端的总行数
 */
public class ConsistencyChecker {
    
    private final DataSource sourceDataSource;
    private final DataSource targetDataSource;
    
    /**
     * 全量数据校验
     * 逐行对比源端和目标端数据
     */
    public CheckResult fullCheck(String tableName) {
        CheckResult result = new CheckResult();
        result.setTableName(tableName);
        
        JdbcTemplate sourceTemplate = new JdbcTemplate(sourceDataSource);
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        
        String primaryKey = getPrimaryKeyColumn(tableName);
        Object lastId = null;
        int batchSize = 5000;
        
        while (true) {
            // 分批查询源端数据
            String sql;
            Object[] params;
            if (lastId == null) {
                sql = String.format(
                    "SELECT * FROM %s ORDER BY %s LIMIT ?",
                    tableName, primaryKey
                );
                params = new Object[]{batchSize};
            } else {
                sql = String.format(
                    "SELECT * FROM %s WHERE %s > ? ORDER BY %s LIMIT ?",
                    tableName, primaryKey, primaryKey
                );
                params = new Object[]{lastId, batchSize};
            }
            
            List<Map<String, Object>> sourceBatch = 
                sourceTemplate.queryForList(sql, params);
            
            if (sourceBatch.isEmpty()) break;
            
            // 收集主键，批量查询目标端
            List<Object> ids = sourceBatch.stream()
                .map(row -> row.get(primaryKey))
                .collect(Collectors.toList());
            
            String targetSql = String.format(
                "SELECT * FROM %s WHERE %s IN (%s) ORDER BY %s",
                tableName, primaryKey,
                ids.stream().map(id -> "?").collect(Collectors.joining(",")),
                primaryKey
            );
            
            List<Map<String, Object>> targetBatch = 
                targetTemplate.queryForList(targetSql, ids.toArray());
            
            // 转换为 Map 便于对比
            Map<Object, Map<String, Object>> targetMap = targetBatch.stream()
                .collect(Collectors.toMap(
                    row -> row.get(primaryKey), 
                    Function.identity()
                ));
            
            // 逐行对比
            for (Map<String, Object> sourceRow : sourceBatch) {
                Object id = sourceRow.get(primaryKey);
                Map<String, Object> targetRow = targetMap.get(id);
                
                result.incrementChecked();
                
                if (targetRow == null) {
                    result.addMissing(id);
                } else if (!deepEquals(sourceRow, targetRow)) {
                    result.addMismatch(id, sourceRow, targetRow);
                }
            }
            
            lastId = sourceBatch.get(sourceBatch.size() - 1).get(primaryKey);
        }
        
        // 反向检查：目标端是否有多余数据
        long sourceCount = sourceTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName, Long.class
        );
        long targetCount = targetTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName, Long.class
        );
        
        if (targetCount > sourceCount) {
            result.setExtraRows(targetCount - sourceCount);
        }
        
        log.info("Check result for {}: checked={}, missing={}, " +
            "mismatch={}, extra={}", 
            tableName, result.getCheckedCount(), 
            result.getMissingCount(), result.getMismatchCount(),
            result.getExtraRows());
        
        return result;
    }
    
    /**
     * Checksum 校验
     * 使用 CRC32 校验和对比数据块
     */
    public boolean checksumCheck(String tableName, 
                                 Object startId, Object endId) {
        
        JdbcTemplate sourceTemplate = new JdbcTemplate(sourceDataSource);
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        
        String primaryKey = getPrimaryKeyColumn(tableName);
        
        // 计算源端 Checksum
        Long sourceChecksum = sourceTemplate.queryForObject(
            String.format(
                "SELECT CRC32(GROUP_CONCAT(CONCAT_WS(',', *) " +
                "ORDER BY %s SEPARATOR '\\n')) FROM %s " +
                "WHERE %s BETWEEN ? AND ?",
                primaryKey, tableName, primaryKey
            ),
            new Object[]{startId, endId},
            Long.class
        );
        
        // 计算目标端 Checksum
        Long targetChecksum = targetTemplate.queryForObject(
            String.format(
                "SELECT CRC32(GROUP_CONCAT(CONCAT_WS(',', *) " +
                "ORDER BY %s SEPARATOR '\\n')) FROM %s " +
                "WHERE %s BETWEEN ? AND ?",
                primaryKey, tableName, primaryKey
            ),
            new Object[]{startId, endId},
            Long.class
        );
        
        return Objects.equals(sourceChecksum, targetChecksum);
    }
    
    /**
     * 自动修复不一致数据
     */
    public void autoRepair(CheckResult checkResult) {
        JdbcTemplate sourceTemplate = new JdbcTemplate(sourceDataSource);
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        
        String tableName = checkResult.getTableName();
        
        // 修复缺失的数据
        for (Object missingId : checkResult.getMissingIds()) {
            Map<String, Object> sourceRow = sourceTemplate.queryForMap(
                "SELECT * FROM " + tableName + " WHERE id = ?", missingId
            );
            
            if (sourceRow != null) {
                // 插入到目标端
                insertRow(targetTemplate, tableName, sourceRow);
                log.info("Repaired missing row: {}.{}", tableName, missingId);
            }
        }
        
        // 修复不一致的数据
        for (Object mismatchId : checkResult.getMismatchIds()) {
            Map<String, Object> sourceRow = sourceTemplate.queryForMap(
                "SELECT * FROM " + tableName + " WHERE id = ?", mismatchId
            );
            
            if (sourceRow != null) {
                // 用源端数据覆盖目标端
                updateRow(targetTemplate, tableName, sourceRow);
                log.info("Repaired mismatched row: {}.{}", 
                    tableName, mismatchId);
            }
        }
    }
}
```

### 3.7 Schema 变更同步

DDL 变更（如加列、改列类型）是 CDC 同步中的难点之一。

```java
/**
 * Schema 变更同步处理器
 * 
 * 挑战：
 * 1. DDL 事件需要在正确的位点应用
 * 2. 列变更可能影响正在处理的 DML 事件的解析
 * 3. 下游不同存储的 DDL 语法不同
 */
public class SchemaChangeHandler {
    
    private final SchemaManager schemaManager;
    private final Map<String, DownstreamDDLAdapter> ddlAdapters;
    
    /**
     * 处理 DDL 事件
     */
    public void handleDDL(String ddlSql, long binlogPosition) {
        log.info("Handling DDL at position {}: {}", binlogPosition, ddlSql);
        
        // 解析 DDL 类型
        DDLStatement ddlStatement = DDLParser.parse(ddlSql);
        
        switch (ddlStatement.getType()) {
            case ADD_COLUMN:
                handleAddColumn(ddlStatement);
                break;
            case DROP_COLUMN:
                handleDropColumn(ddlStatement);
                break;
            case MODIFY_COLUMN:
                handleModifyColumn(ddlStatement);
                break;
            case RENAME_TABLE:
                handleRenameTable(ddlStatement);
                break;
            case CREATE_TABLE:
                handleCreateTable(ddlStatement);
                break;
            case DROP_TABLE:
                handleDropTable(ddlStatement);
                break;
            case CREATE_INDEX:
            case DROP_INDEX:
                handleIndexChange(ddlStatement);
                break;
            default:
                log.warn("Unsupported DDL type: {}", ddlStatement.getType());
        }
        
        // 更新本地 Schema 缓存
        schemaManager.refreshSchema(
            ddlStatement.getSchemaName(), ddlStatement.getTableName()
        );
    }
    
    /**
     * 处理加列
     */
    private void handleAddColumn(DDLStatement ddlStatement) {
        String tableName = ddlStatement.getFullTableName();
        ColumnDefinition newColumn = ddlStatement.getNewColumn();
        
        // 对每个下游适配并执行 DDL
        for (Map.Entry<String, DownstreamDDLAdapter> entry : 
             ddlAdapters.entrySet()) {
            
            String downstreamType = entry.getKey();
            DownstreamDDLAdapter adapter = entry.getValue();
            
            try {
                adapter.addColumn(tableName, newColumn);
                log.info("Applied ADD COLUMN to downstream {}: {}.{}", 
                    downstreamType, tableName, newColumn.getName());
            } catch (Exception e) {
                log.error("Failed to apply ADD COLUMN to downstream {}", 
                    downstreamType, e);
                // DDL 失败通常需要人工介入
                alertService.sendDDLFailureAlert(
                    downstreamType, tableName, ddlStatement.getOriginalSql()
                );
            }
        }
    }
    
    /**
     * 处理列类型变更
     * 需要特别注意类型兼容性
     */
    private void handleModifyColumn(DDLStatement ddlStatement) {
        String tableName = ddlStatement.getFullTableName();
        ColumnDefinition oldColumn = ddlStatement.getOldColumn();
        ColumnDefinition newColumn = ddlStatement.getNewColumn();
        
        // 检查类型变更是否兼容
        if (!isCompatible(oldColumn.getJdbcType(), newColumn.getJdbcType())) {
            log.warn("Incompatible column type change detected: " +
                "{}.{} from {} to {}", 
                tableName, oldColumn.getName(), 
                oldColumn.getTypeName(), newColumn.getTypeName());
            
            // 不兼容的类型变更可能需要数据迁移
            alertService.sendSchemaIncompatibleAlert(
                tableName, oldColumn, newColumn
            );
            return;
        }
        
        for (DownstreamDDLAdapter adapter : ddlAdapters.values()) {
            adapter.modifyColumn(tableName, newColumn);
        }
    }
}

/**
 * Elasticsearch DDL 适配器
 */
public class ElasticsearchDDLAdapter implements DownstreamDDLAdapter {
    
    private final RestHighLevelClient esClient;
    
    @Override
    public void addColumn(String tableName, ColumnDefinition column) {
        String indexName = tableToIndex(tableName);
        String esFieldType = jdbcTypeToEsType(column.getJdbcType());
        
        // ES 通过 PUT Mapping 添加字段
        PutMappingRequest request = new PutMappingRequest(indexName);
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("type", esFieldType);
        properties.put(column.getName(), fieldMapping);
        
        Map<String, Object> mapping = new HashMap<>();
        mapping.put("properties", properties);
        request.source(mapping);
        
        try {
            esClient.indices().putMapping(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new DDLSyncException("Failed to add mapping for " + 
                column.getName(), e);
        }
    }
    
    /**
     * JDBC 类型到 ES 字段类型的映射
     */
    private String jdbcTypeToEsType(int jdbcType) {
        switch (jdbcType) {
            case Types.INTEGER:
            case Types.SMALLINT:
                return "integer";
            case Types.BIGINT:
                return "long";
            case Types.FLOAT:
                return "float";
            case Types.DOUBLE:
            case Types.DECIMAL:
                return "double";
            case Types.VARCHAR:
            case Types.CHAR:
                return "keyword";
            case Types.CLOB:
            case Types.LONGVARCHAR:
                return "text";
            case Types.TIMESTAMP:
            case Types.DATE:
                return "date";
            case Types.BOOLEAN:
                return "boolean";
            default:
                return "keyword";
        }
    }
}
```

### 3.8 防回环机制

在双向同步或多活场景中，必须防止 CDC 产生的变更被再次捕获，形成无限循环。

```java
/**
 * CDC 防回环机制
 * 
 * 原理：在变更事件中携带特殊标记，CDC 组件识别标记后跳过
 * 
 * 方案一：事务注释标记
 * 在 SQL 执行前设置会话变量，CDC 解析时识别并跳过
 * 
 * 方案二：特殊表标记
 * 在 CDC 写入的事务中同时更新一张标记表
 * 
 * 方案三：GTID 过滤
 * 基于 MySQL GTID 过滤特定来源的变更
 */
public class AntiLoopFilter {
    
    // 标记变量名
    private static final String SYNC_MARK_VARIABLE = "@__CDC_SYNC_MARK__";
    
    /**
     * 方案一：会话变量标记
     * Sinker 在写入目标库时设置会话变量
     */
    public void executeWithAntiLoopMark(JdbcTemplate template, 
                                        String sql, Object[] params) {
        // 设置标记（该语句会出现在 Binlog 中）
        template.execute("SET " + SYNC_MARK_VARIABLE + " = 1");
        
        // 执行数据变更
        template.update(sql, params);
    }
    
    /**
     * Puller 端过滤标记事件
     */
    public boolean shouldFilter(Event event) {
        if (event.getHeader().getEventType() == EventType.QUERY) {
            QueryEventData data = event.getData();
            String sql = data.getSql();
            
            // 检测到标记变量，跳过后续的 DML 事件
            if (sql.contains(SYNC_MARK_VARIABLE)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 方案二：特殊表标记
     * 
     * 创建标记表：
     * CREATE TABLE __cdc_heartbeat (
     *   id INT PRIMARY KEY,
     *   source_id VARCHAR(64),
     *   update_time TIMESTAMP
     * );
     * 
     * CDC 写入时在同一事务中更新标记表
     */
    public void executeWithTableMark(DataSource dataSource, 
                                     String sourceId,
                                     List<String> sqls) {
        
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            
            // 更新标记表（同一事务内）
            PreparedStatement markStmt = conn.prepareStatement(
                "UPDATE __cdc_heartbeat SET source_id = ?, " +
                "update_time = NOW() WHERE id = 1"
            );
            markStmt.setString(1, sourceId);
            markStmt.executeUpdate();
            
            // 执行业务 SQL
            for (String sql : sqls) {
                conn.createStatement().executeUpdate(sql);
            }
            
            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new RuntimeException("Execute with table mark failed", e);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }
    
    /**
     * Puller 端通过标记表事件过滤
     * 如果事务中包含对 __cdc_heartbeat 表的更新，
     * 则该事务的所有 DML 都来自 CDC 同步，应跳过
     */
    public boolean isFromCDCSync(TransactionEvents txEvents) {
        for (ChangeEvent event : txEvents.getEvents()) {
            if ("__cdc_heartbeat".equals(event.getTable())) {
                return true;
            }
        }
        return false;
    }
}
```

---

## 四、异常处理与容错机制

### 4.1 断点续传

```java
/**
 * 断点续传机制
 * 
 * CDC 任务在运行过程中可能因各种原因中断（进程崩溃、网络故障等），
 * 断点续传保证重启后能从中断点继续同步，不丢数据
 */
public class CheckpointManager {
    
    // 检查点存储（可选：文件、数据库、ZooKeeper）
    private final CheckpointStore store;
    
    // 检查点保存间隔
    private static final long CHECKPOINT_INTERVAL_MS = 5000;
    
    // 最新的检查点
    private volatile Checkpoint latestCheckpoint;
    
    /**
     * 保存检查点
     * 记录当前同步的 Binlog 位点和时间戳
     */
    public void save(Checkpoint checkpoint) {
        // 只有比当前检查点更新的才保存
        if (latestCheckpoint != null && 
            checkpoint.getPosition() <= latestCheckpoint.getPosition() &&
            checkpoint.getBinlogFile().equals(
                latestCheckpoint.getBinlogFile())) {
            return;
        }
        
        store.save(checkpoint);
        latestCheckpoint = checkpoint;
        
        log.debug("Checkpoint saved: file={}, position={}, timestamp={}", 
            checkpoint.getBinlogFile(), checkpoint.getPosition(),
            checkpoint.getTimestamp());
    }
    
    /**
     * 加载最新检查点
     */
    public Checkpoint getLatestCheckpoint() {
        if (latestCheckpoint == null) {
            latestCheckpoint = store.load();
        }
        return latestCheckpoint;
    }
    
    /**
     * 基于时间的回放
     * 支持从指定时间点重新开始同步
     */
    public Checkpoint findCheckpointByTime(long targetTimestamp) {
        List<Checkpoint> history = store.loadHistory();
        
        // 找到目标时间点之前最近的检查点
        return history.stream()
            .filter(cp -> cp.getTimestamp() <= targetTimestamp)
            .max(Comparator.comparingLong(Checkpoint::getTimestamp))
            .orElseThrow(() -> new RuntimeException(
                "No checkpoint found before timestamp: " + targetTimestamp
            ));
    }
}

/**
 * 基于数据库的检查点存储
 */
public class DatabaseCheckpointStore implements CheckpointStore {
    
    /*
     * CREATE TABLE cdc_checkpoint (
     *   task_id VARCHAR(64) PRIMARY KEY,
     *   binlog_file VARCHAR(128),
     *   binlog_position BIGINT,
     *   timestamp BIGINT,
     *   extra_info TEXT,
     *   update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     * );
     */
    
    private final JdbcTemplate template;
    private final String taskId;
    
    @Override
    public void save(Checkpoint checkpoint) {
        template.update(
            "REPLACE INTO cdc_checkpoint " +
            "(task_id, binlog_file, binlog_position, timestamp, " +
            "extra_info, update_time) " +
            "VALUES (?, ?, ?, ?, ?, NOW())",
            taskId, checkpoint.getBinlogFile(), 
            checkpoint.getPosition(), checkpoint.getTimestamp(),
            JSON.toJSONString(checkpoint.getExtraInfo())
        );
    }
    
    @Override
    public Checkpoint load() {
        try {
            return template.queryForObject(
                "SELECT * FROM cdc_checkpoint WHERE task_id = ?",
                new Object[]{taskId},
                (rs, rowNum) -> {
                    Checkpoint cp = new Checkpoint();
                    cp.setBinlogFile(rs.getString("binlog_file"));
                    cp.setPosition(rs.getLong("binlog_position"));
                    cp.setTimestamp(rs.getLong("timestamp"));
                    return cp;
                }
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
```

### 4.2 At-Least-Once 投递保障

```java
/**
 * At-Least-Once 投递语义保障
 * 
 * CDC 系统保证每条变更事件至少投递一次，但可能存在重复投递。
 * 下游消费者需要具备幂等处理能力。
 */
public class AtLeastOnceDelivery {
    
    /**
     * 幂等写入 MySQL
     * 使用 REPLACE INTO 或 INSERT ... ON DUPLICATE KEY UPDATE
     */
    public void idempotentWriteToMySQL(JdbcTemplate template, 
                                       MountedChangeEvent event) {
        switch (event.getType()) {
            case INSERT:
            case UPDATE:
                // REPLACE INTO 保证幂等
                String sql = buildReplaceIntoSQL(
                    event.getTable(), event.getAfterColumns()
                );
                Object[] values = event.getAfterColumns().values().stream()
                    .map(ColumnValue::getValue)
                    .toArray();
                template.update(sql, values);
                break;
                
            case DELETE:
                // DELETE 天然幂等
                String deleteSql = buildDeleteSQL(
                    event.getTable(), event.getPrimaryKeys()
                );
                template.update(deleteSql, 
                    event.getPrimaryKeys().values().toArray());
                break;
        }
    }
    
    /**
     * 幂等写入 ES
     * ES 的 Index API 天然支持幂等（相同 ID 会覆盖）
     */
    public void idempotentWriteToES(RestHighLevelClient client, 
                                    MountedChangeEvent event) {
        String indexName = getIndexName(event);
        String docId = buildDocId(event);
        
        switch (event.getType()) {
            case INSERT:
            case UPDATE:
                // Index API 按 ID 覆盖写入
                IndexRequest request = new IndexRequest(indexName)
                    .id(docId)
                    .source(buildDocument(event));
                try {
                    client.index(request, RequestOptions.DEFAULT);
                } catch (IOException e) {
                    throw new RuntimeException("ES index failed", e);
                }
                break;
                
            case DELETE:
                // Delete API 天然幂等
                try {
                    client.delete(new DeleteRequest(indexName, docId), 
                        RequestOptions.DEFAULT);
                } catch (IOException e) {
                    throw new RuntimeException("ES delete failed", e);
                }
                break;
        }
    }
    
    /**
     * 幂等写入 Redis
     * SET/DEL 操作天然幂等
     */
    public void idempotentWriteToRedis(RedisTemplate<String, String> redis, 
                                       MountedChangeEvent event) {
        String cacheKey = buildCacheKey(event);
        
        switch (event.getType()) {
            case INSERT:
            case UPDATE:
                // SET 操作天然幂等
                redis.opsForValue().set(cacheKey, 
                    JSON.toJSONString(extractValues(event.getAfterColumns())),
                    Duration.ofHours(24));
                break;
                
            case DELETE:
                // DEL 操作天然幂等
                redis.delete(cacheKey);
                break;
        }
    }
}
```

### 4.3 错误处理与重试

```java
/**
 * CDC 任务错误处理与重试策略
 */
public class CDCErrorHandler {
    
    private final RetryTemplate retryTemplate;
    private final DeadLetterQueue deadLetterQueue;
    
    public CDCErrorHandler() {
        // 配置重试策略
        this.retryTemplate = RetryTemplate.builder()
            .maxAttempts(5)
            .exponentialBackoff(1000, 2.0, 30000) // 1s, 2s, 4s, 8s, 16s
            .retryOn(TransientException.class)
            .build();
    }
    
    /**
     * 带重试的事件处理
     */
    public void handleWithRetry(MountedChangeEvent event, 
                                AbstractSinker sinker) {
        try {
            retryTemplate.execute(context -> {
                sinker.sink(Collections.singletonList(event));
                return null;
            });
        } catch (Exception e) {
            // 重试耗尽，放入死信队列
            log.error("Event processing failed after retries, " +
                "sending to dead letter queue: {}", event, e);
            deadLetterQueue.send(event, e);
            
            metrics.counter("cdc.dead_letter").increment();
        }
    }
    
    /**
     * 死信队列处理
     * 定时扫描死信队列，尝试重新处理失败的事件
     */
    @Scheduled(fixedRate = 60000) // 每分钟扫描
    public void processDeadLetterQueue() {
        List<DeadLetterMessage> messages = deadLetterQueue.poll(100);
        
        for (DeadLetterMessage message : messages) {
            if (message.getRetryCount() > 10) {
                // 超过最大重试次数，发送告警
                alertService.sendDeadLetterAlert(message);
                deadLetterQueue.markAsAbandoned(message);
                continue;
            }
            
            try {
                sinker.sink(Collections.singletonList(message.getEvent()));
                deadLetterQueue.markAsProcessed(message);
            } catch (Exception e) {
                message.incrementRetryCount();
                deadLetterQueue.requeue(message);
            }
        }
    }
}
```

---

## 五、性能优化

### 5.1 延迟监控

```java
/**
 * CDC 同步延迟监控
 * 
 * 延迟 = 当前时间 - 正在处理的变更事件的源端时间戳
 */
public class SyncDelayMonitor {
    
    private final MeterRegistry meterRegistry;
    
    // 滑动窗口计算平均延迟
    private final Queue<Long> delayWindow = new ConcurrentLinkedQueue<>();
    private static final int WINDOW_SIZE = 100;
    
    /**
     * 记录事件处理延迟
     */
    public void recordDelay(MountedChangeEvent event) {
        long delay = System.currentTimeMillis() - event.getTimestamp();
        
        // 更新滑动窗口
        delayWindow.offer(delay);
        while (delayWindow.size() > WINDOW_SIZE) {
            delayWindow.poll();
        }
        
        // 上报指标
        meterRegistry.gauge("cdc.sync.delay.ms", delay);
        meterRegistry.gauge("cdc.sync.delay.avg.ms", getAverageDelay());
        
        // 延迟告警
        if (delay > 5000) { // 5秒
            log.warn("CDC sync delay exceeds threshold: {}ms", delay);
            meterRegistry.counter("cdc.sync.delay.warning").increment();
        }
        
        if (delay > 30000) { // 30秒
            log.error("CDC sync delay critical: {}ms", delay);
            alertService.sendDelayAlert(delay);
        }
    }
    
    /**
     * 获取平均延迟
     */
    public double getAverageDelay() {
        if (delayWindow.isEmpty()) return 0;
        return delayWindow.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);
    }
    
    /**
     * 心跳检测
     * 定期向源库写入心跳记录，通过心跳事件的延迟判断 CDC 链路是否正常
     */
    @Scheduled(fixedRate = 10000) // 每10秒
    public void heartbeat() {
        JdbcTemplate template = new JdbcTemplate(sourceDataSource);
        
        long now = System.currentTimeMillis();
        template.update(
            "REPLACE INTO __cdc_heartbeat (id, heartbeat_time, " +
            "source_instance) VALUES (1, ?, ?)",
            now, instanceId
        );
    }
    
    /**
     * 处理心跳事件
     * 从 CDC 消费端接收心跳事件，计算端到端延迟
     */
    public void processHeartbeatEvent(MountedChangeEvent heartbeatEvent) {
        if (!"__cdc_heartbeat".equals(heartbeatEvent.getTable())) {
            return;
        }
        
        Object heartbeatTimeObj = heartbeatEvent.getAfterColumns()
            .get("heartbeat_time").getValue();
        long heartbeatTime = ((Number) heartbeatTimeObj).longValue();
        
        long endToEndDelay = System.currentTimeMillis() - heartbeatTime;
        
        meterRegistry.gauge("cdc.e2e.delay.ms", endToEndDelay);
        log.debug("CDC end-to-end delay: {}ms", endToEndDelay);
    }
}
```

### 5.2 吞吐量优化

```java
/**
 * CDC 吞吐量优化策略
 */
public class ThroughputOptimizer {
    
    /**
     * 策略一：批量处理
     * 将多个变更事件批量提交给 Sinker，减少网络往返次数
     */
    public void batchConsume(EventQueue eventQueue, AbstractSinker sinker) {
        int batchSize = 1000;
        long maxWaitMs = 100; // 最大等待100ms凑批
        
        List<MountedChangeEvent> batch = new ArrayList<>(batchSize);
        long batchStartTime = System.currentTimeMillis();
        
        while (true) {
            MountedChangeEvent event = eventQueue.poll(
                10, TimeUnit.MILLISECONDS
            );
            
            if (event != null) {
                batch.add(event);
            }
            
            boolean shouldFlush = 
                batch.size() >= batchSize ||
                (!batch.isEmpty() && 
                 System.currentTimeMillis() - batchStartTime > maxWaitMs);
            
            if (shouldFlush && !batch.isEmpty()) {
                sinker.sink(batch);
                batch = new ArrayList<>(batchSize);
                batchStartTime = System.currentTimeMillis();
            }
        }
    }
    
    /**
     * 策略二：并行分发
     * 按分片键将事件分发到多个并行消费者
     * 同一主键的事件保证顺序，不同主键的事件并行处理
     */
    public void parallelConsume(EventQueue eventQueue, 
                                AbstractSinker sinker,
                                int parallelism) {
        
        // 创建并行消费者
        ExecutorService[] workers = new ExecutorService[parallelism];
        for (int i = 0; i < parallelism; i++) {
            workers[i] = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "cdc-worker-" + i)
            );
        }
        
        while (true) {
            MountedChangeEvent event = eventQueue.poll(
                100, TimeUnit.MILLISECONDS
            );
            
            if (event != null) {
                // 按主键哈希分发到固定的 worker
                // 保证同一主键的事件在同一个 worker 中顺序处理
                String pk = event.getPrimaryKeys().values().toString();
                int workerIndex = Math.abs(pk.hashCode()) % parallelism;
                
                workers[workerIndex].submit(() -> {
                    try {
                        sinker.sink(Collections.singletonList(event));
                    } catch (Exception e) {
                        log.error("Worker processing failed", e);
                    }
                });
            }
        }
    }
    
    /**
     * 策略三：变更合并
     * 在一个批次内，合并同一行的多次变更，只保留最终状态
     * 
     * 例如：同一行数据在一个批次内被修改了3次
     * UPDATE id=1, name=A → name=B
     * UPDATE id=1, name=B → name=C  
     * UPDATE id=1, name=C → name=D
     * 合并为一次：UPDATE id=1, name=A → name=D
     */
    public List<MountedChangeEvent> mergeEvents(
            List<MountedChangeEvent> events) {
        
        // 按主键分组
        Map<String, List<MountedChangeEvent>> grouped = new LinkedHashMap<>();
        for (MountedChangeEvent event : events) {
            String key = event.getTable() + ":" + 
                event.getPrimaryKeys().values();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }
        
        List<MountedChangeEvent> merged = new ArrayList<>();
        
        for (List<MountedChangeEvent> group : grouped.values()) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            
            // 合并多个事件
            MountedChangeEvent first = group.get(0);
            MountedChangeEvent last = group.get(group.size() - 1);
            
            if (first.getType() == ChangeType.INSERT && 
                last.getType() == ChangeType.DELETE) {
                // INSERT + ... + DELETE = 无操作（抵消）
                continue;
            }
            
            if (last.getType() == ChangeType.DELETE) {
                // 最终是删除，只保留 DELETE 事件
                merged.add(last);
            } else {
                // 合并为一个事件：取第一个事件的 before + 最后一个事件的 after
                MountedChangeEvent mergedEvent = new MountedChangeEvent();
                mergedEvent.setType(first.getType() == ChangeType.INSERT ? 
                    ChangeType.INSERT : ChangeType.UPDATE);
                mergedEvent.setSchema(last.getSchema());
                mergedEvent.setTable(last.getTable());
                mergedEvent.setTimestamp(last.getTimestamp());
                mergedEvent.setPrimaryKeys(last.getPrimaryKeys());
                mergedEvent.setBeforeColumns(first.getBeforeColumns());
                mergedEvent.setAfterColumns(last.getAfterColumns());
                merged.add(mergedEvent);
            }
        }
        
        return merged;
    }
}
```

### 5.3 资源优化

```java
/**
 * CDC 资源管理与优化
 */
public class ResourceOptimizer {
    
    /**
     * Binlog 位点管理优化
     * 避免保留过多的 Binlog 文件占用磁盘空间
     */
    public void manageBinlogRetention(DataSource sourceDataSource) {
        JdbcTemplate template = new JdbcTemplate(sourceDataSource);
        
        // 获取所有 CDC 任务的最小位点
        Checkpoint oldestCheckpoint = checkpointManager
            .getOldestCheckpointAcrossAllTasks();
        
        if (oldestCheckpoint != null) {
            // 通知 DBA 可以清理该位点之前的 Binlog
            log.info("Oldest CDC checkpoint: {}:{}, " +
                "Binlog files before this can be purged",
                oldestCheckpoint.getBinlogFile(), 
                oldestCheckpoint.getPosition());
        }
    }
    
    /**
     * 内存使用优化
     * 控制事件队列的大小，避免 OOM
     */
    public EventQueue createBoundedEventQueue(int capacity) {
        return new EventQueue(capacity) {
            @Override
            public void put(MountedChangeEvent event) {
                // 队列满时阻塞 Puller，产生背压
                while (!offer(event, 1, TimeUnit.SECONDS)) {
                    log.warn("Event queue is full (size={}), " +
                        "Puller is being throttled", size());
                    metrics.counter("cdc.queue.full").increment();
                }
            }
        };
    }
    
    /**
     * 网络带宽优化
     * 对大字段进行压缩传输
     */
    public MountedChangeEvent compressLargeFields(
            MountedChangeEvent event) {
        
        for (Map.Entry<String, ColumnValue> entry : 
             event.getAfterColumns().entrySet()) {
            
            ColumnValue value = entry.getValue();
            
            // 对大于 1KB 的文本字段进行压缩
            if (value.getValue() instanceof String) {
                String strValue = (String) value.getValue();
                if (strValue.length() > 1024) {
                    byte[] compressed = compress(
                        strValue.getBytes(StandardCharsets.UTF_8)
                    );
                    value.setCompressed(true);
                    value.setCompressedValue(compressed);
                }
            }
        }
        
        return event;
    }
}
```

---

## 六、最佳实践与总结

### 6.1 CDC 任务配置建议

| 配置项 | 推荐值 | 说明 |
|--------|--------|------|
| Binlog 格式 | ROW | 必须使用 ROW 格式，STATEMENT 格式无法获取完整变更数据 |
| 批量大小 | 500 ~ 2000 | 平衡延迟和吞吐量 |
| 检查点间隔 | 5 ~ 10 秒 | 太短增加存储压力，太长增加重启恢复时间 |
| 并行度 | CPU核心数 x 2 | 根据下游写入能力调整 |
| 队列容量 | 10000 ~ 50000 | 根据可用内存和突发流量评估 |
| 心跳间隔 | 10 ~ 30 秒 | 检测 CDC 链路存活性 |
| 最大延迟告警 | 5 ~ 10 秒 | 根据业务 SLA 设置 |

### 6.2 常见问题排查指南

**问题一：同步延迟持续增大**

```
排查步骤：
1. 检查源库 Binlog 产生速率
   → SHOW MASTER STATUS; (持续观察 Position 变化)
   
2. 检查 Puller 消费速率
   → 监控 binlog.events.per.second 指标
   
3. 检查 Sinker 写入速率
   → 监控 sinker.batch.latency 指标
   
4. 检查下游系统性能
   → 下游数据库/ES/Redis 的负载和响应时间
   
5. 常见原因：
   a. 大事务：单个事务包含大量行变更，导致处理缓慢
   b. 下游瓶颈：ES/Redis 写入速度跟不上
   c. DDL 阻塞：Schema 变更导致处理暂停
   d. 网络问题：Puller 与源库之间网络不稳定
```

**问题二：数据不一致**

```
排查步骤：
1. 检查是否存在跳过的事件
   → 查看死信队列是否有未处理的事件
   
2. 检查检查点是否正确
   → 确认重启后是否从正确的位点恢复
   
3. 检查幂等性
   → 确认下游写入是否支持幂等
   
4. 运行全量校验
   → 对比源端和目标端数据
   
5. 常见原因：
   a. 检查点丢失：进程异常退出时检查点未保存
   b. 并发写入：下游被多个来源写入，造成覆盖
   c. Schema 不同步：DDL 变更未正确传播到下游
   d. 时区问题：时间字段的时区处理不一致
```

### 6.3 CDC 与业务架构的配合

```
CDC 在业务架构中的定位：

┌──────────────────────────────────────────────────────────────┐
│                     业务服务层                                │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │订单服务   │  │用户服务   │  │商品服务   │  │支付服务   │    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘    │
│       │             │             │             │           │
│       ▼             ▼             ▼             ▼           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │订单 DB   │  │用户 DB   │  │商品 DB   │  │支付 DB   │    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘    │
│       │             │             │             │           │
└───────┼─────────────┼─────────────┼─────────────┼───────────┘
        │             │             │             │
        └─────────────┴──────┬──────┴─────────────┘
                             │
                    ┌────────▼────────┐
                    │   CDC 平台      │
                    │                 │
                    │ ┌─────────────┐ │
                    │ │ Binlog 采集  │ │
                    │ │ 事件分发     │ │
                    │ │ 监控告警     │ │
                    │ └─────────────┘ │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼────┐  ┌─────▼─────┐  ┌─────▼─────┐
     │ 搜索引擎    │  │ KV 缓存   │  │ 数据仓库   │
     │(ES/Solr)   │  │ (Redis)   │  │ (Hive/CK) │
     └─────────────┘  └───────────┘  └───────────┘
```

### 6.4 监控指标体系

| 指标类别 | 具体指标 | 告警阈值 |
|---------|---------|---------|
| 延迟 | 端到端同步延迟 (e2e_delay_ms) | > 5s 警告, > 30s 严重 |
| 延迟 | Puller 拉取延迟 | > 2s |
| 延迟 | Sinker 写入延迟 | > 1s |
| 吞吐 | 事件消费速率 (events/s) | 持续低于生产速率 |
| 吞吐 | Sinker 批次大小 | < 10（说明负载过低）|
| 错误 | 事件处理失败率 | > 0.1% |
| 错误 | 死信队列堆积量 | > 100 |
| 资源 | 事件队列使用率 | > 80% |
| 资源 | Binlog 连接状态 | 断开 |
| 一致性 | 校验失败行数 | > 0 |

### 6.5 总结

CDC 数据同步是现代分布式系统中不可或缺的基础设施，它通过非侵入地监听数据库变更日志，实现了实时、可靠、低开销的异构数据同步。在实际应用中，需要关注以下核心原则：

1. **Binlog 必须是 ROW 格式**：只有 ROW 格式才能获取完整的行变更数据，STATEMENT 格式只记录 SQL 语句，无法准确还原数据变更。

2. **检查点是灵魂**：可靠的断点续传机制是 CDC 系统正确性的基石，必须保证检查点的持久化和正确恢复。

3. **幂等是前提**：At-Least-Once 投递语义意味着下游必须具备幂等写入能力，否则重复投递会导致数据错误。

4. **行级有序即可**：大多数业务场景只需要保证同一行数据的变更有序，全局有序会严重影响吞吐量。

5. **批量处理提升吞吐**：合理的批量大小和凑批策略可以显著提升同步性能。

6. **防回环不可忽视**：在双向同步或多活场景中，必须实现防回环机制，否则变更事件会无限循环。

7. **Schema 变更需要特殊处理**：DDL 事件必须在正确的位点被应用，否则会导致后续 DML 事件解析失败。

8. **全量校验是安全网**：定期运行全量一致性校验，及时发现并修复数据不一致。

9. **监控先行**：完善的延迟监控、吞吐量监控和告警体系是 CDC 系统稳定运行的保障。

CDC 技术正在从单纯的数据同步工具演进为实时数据基础设施，与流处理引擎、事件驱动架构深度融合，在实时分析、微服务数据协作、数据湖等场景中发挥越来越重要的作用。

---

## 七、全链路实战案例

### 7.1 案例一：MySQL Binlog 实时同步到 ES 全链路

**业务场景**：电商平台的商品搜索系统需要将 MySQL 中的商品数据实时同步到 Elasticsearch，保证用户搜索到的商品信息与数据库一致，延迟控制在秒级。

```
全链路架构：

┌──────────────────────────────────────────────────────────────────┐
│                        商品搜索同步链路                             │
│                                                                  │
│  ┌──────────┐     Binlog      ┌──────────────┐                  │
│  │  MySQL    │──────────────► │   Puller     │                  │
│  │ 商品主库   │  (ROW 格式)     │  (Binlog监听) │                  │
│  └──────────┘                 └──────┬───────┘                  │
│                                       │                          │
│                                ┌──────▼───────┐                  │
│                                │   Mounter    │                  │
│                                │  (事件装配)   │                  │
│                                └──────┬───────┘                  │
│                                       │                          │
│                                ┌──────▼───────┐                  │
│                                │  ES Sinker   │                  │
│                                │ (批量写入ES)  │                  │
│                                └──────┬───────┘                  │
│                                       │                          │
│                                ┌──────▼───────┐                  │
│                                │Elasticsearch │                  │
│                                │  (搜索索引)   │                  │
│                                └──────────────┘                  │
└──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 商品搜索同步全链路实现
 *
 * 核心能力：
 * 1. Binlog 实时监听，毫秒级延迟感知数据变更
 * 2. 事件装配与字段映射，MySQL 列名映射为 ES 字段
 * 3. 批量 Bulk 写入 ES，兼顾吞吐量与延迟
 * 4. 幂等控制：基于主键的 docAsUpsert 保证重复投递安全
 * 5. 异常处理：指数退避重试 + 死信队列兜底
 * 6. 断点续传：检查点持久化，重启不丢数据
 */
public class ProductSyncPipeline implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(
        ProductSyncPipeline.class
    );

    private final BinlogPuller puller;
    private final EventMounter mounter;
    private final ProductESSinker sinker;
    private final CheckpointManager checkpointManager;
    private final CDCErrorHandler errorHandler;

    // 有界事件队列，防止 OOM
    private final BlockingQueue<MountedChangeEvent> eventQueue =
        new LinkedBlockingQueue<>(50000);

    private volatile boolean running = false;
    private ExecutorService consumerExecutor;

    /**
     * 启动同步链路
     */
    @Override
    public void start() {
        log.info("Starting product sync pipeline...");

        running = true;

        // 启动消费线程（4 个并行消费者，按主键哈希分发）
        consumerExecutor = Executors.newFixedThreadPool(4, r ->
            new Thread(r, "product-sync-consumer")
        );

        for (int i = 0; i < 4; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // 启动 Binlog 拉取
        puller.start();

        log.info("Product sync pipeline started successfully");
    }

    /**
     * 消费循环
     * 从事件队列拉取事件，批量写入 ES
     */
    private void consumeLoop() {
        List<MountedChangeEvent> batch = new ArrayList<>(500);
        long batchStartTime = System.currentTimeMillis();

        while (running) {
            try {
                MountedChangeEvent event = eventQueue.poll(
                    10, TimeUnit.MILLISECONDS
                );

                if (event != null) {
                    batch.add(event);
                }

                // 凑批条件：达到批量大小 或 等待超过 100ms
                boolean shouldFlush = batch.size() >= 500 ||
                    (!batch.isEmpty() &&
                     System.currentTimeMillis() - batchStartTime > 100);

                if (shouldFlush && !batch.isEmpty()) {
                    processBatch(batch);
                    batch = new ArrayList<>(500);
                    batchStartTime = System.currentTimeMillis();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Consumer thread interrupted, exiting loop");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in consume loop", e);
            }
        }

        // 退出前刷入剩余批次
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
    }

    /**
     * 处理一批事件
     * 包含幂等写入、异常处理、检查点更新
     */
    private void processBatch(List<MountedChangeEvent> batch) {
        long startTime = System.currentTimeMillis();

        try {
            // 批量写入 ES（幂等：docAsUpsert）
            sinker.sink(batch);

            // 更新检查点（取批次中最后一条事件的位点）
            MountedChangeEvent lastEvent = batch.get(batch.size() - 1);
            checkpointManager.save(new Checkpoint(
                lastEvent.getBinlogFile(),
                lastEvent.getBinlogPosition(),
                lastEvent.getTimestamp()
            ));

            long elapsed = System.currentTimeMillis() - startTime;
            long delay = System.currentTimeMillis() -
                batch.get(batch.size() - 1).getTimestamp();

            log.info("Batch processed: size={}, elapsed={}ms, delay={}ms",
                batch.size(), elapsed, delay);

        } catch (Exception e) {
            log.error("Batch processing failed, size={}, falling back to " +
                "single-event retry", batch.size(), e);

            // 批量失败，降级为逐条重试
            for (MountedChangeEvent event : batch) {
                errorHandler.handleWithRetry(event, sinker);
            }
        }
    }

    /**
     * 停止同步链路
     */
    @Override
    public void stop() {
        log.info("Stopping product sync pipeline...");
        running = false;

        puller.stop();

        consumerExecutor.shutdown();
        try {
            if (!consumerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            consumerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Product sync pipeline stopped");
    }
}

/**
 * 商品 ES Sinker
 *
 * 幂等策略：
 * - INSERT/UPDATE：使用 docAsUpsert(true)，文档不存在则插入，存在则覆盖
 * - DELETE：按文档 ID 删除，天然幂等
 *
 * 异常处理：
 * - Bulk 部分失败：逐条识别失败项，放入重试队列
 * - ES 不可用：指数退避重试，超时后进入死信队列
 */
public class ProductESSinker extends AbstractSinker {

    private static final Logger log = LoggerFactory.getLogger(
        ProductESSinker.class
    );

    private final RestHighLevelClient esClient;
    private final IndexMappingConfig mappingConfig;
    private final MeterRegistry metrics;

    /**
     * 批量写入 ES
     */
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        BulkRequest bulkRequest = new BulkRequest();

        for (MountedChangeEvent event : events) {
            String indexName = mappingConfig.getIndexName(
                event.getSchema(), event.getTable()
            );
            String docId = buildDocumentId(event);

            switch (event.getType()) {
                case INSERT:
                case UPDATE:
                    // 构建文档（幂等：相同 ID 覆盖）
                    Map<String, Object> doc = buildDocument(
                        event.getAfterColumns()
                    );
                    IndexRequest indexRequest = new IndexRequest(indexName)
                        .id(docId)
                        .opType("index") // 强制 index 语义，允许覆盖
                        .source(doc);
                    bulkRequest.add(indexRequest);
                    break;

                case DELETE:
                    DeleteRequest deleteRequest = new DeleteRequest(
                        indexName, docId
                    );
                    bulkRequest.add(deleteRequest);
                    break;

                default:
                    log.warn("Unsupported event type: {}", event.getType());
            }
        }

        if (bulkRequest.numberOfActions() == 0) return;

        try {
            BulkResponse response = esClient.bulk(
                bulkRequest, RequestOptions.DEFAULT
            );

            if (response.hasFailures()) {
                handlePartialFailure(response, events);
            }

            metrics.counter("product_es.sink.docs").increment(
                bulkRequest.numberOfActions()
            );
            metrics.counter("product_es.sink.success").increment(
                bulkRequest.numberOfActions() - countFailures(response)
            );

        } catch (IOException e) {
            metrics.counter("product_es.sink.failure").increment();
            throw new SinkException(
                "ES bulk request failed, actions=" +
                bulkRequest.numberOfActions(), e
            );
        }
    }

    /**
     * 处理 Bulk 部分失败
     * 识别失败项，记录日志，放入重试队列
     */
    private void handlePartialFailure(BulkResponse response,
                                       List<MountedChangeEvent> events) {
        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) {
                BulkItemResponse.Failure failure = item.getFailure();
                int itemIndex = item.getItemId();

                log.error("ES bulk partial failure: index={}, id={}, " +
                    "reason={}, eventIndex={}",
                    failure.getIndex(), failure.getId(),
                    failure.getMessage(), itemIndex);

                metrics.counter("product_es.sink.partial_failure")
                    .increment();

                // 将失败事件放入重试队列
                if (itemIndex < events.size()) {
                    retryQueue.add(events.get(itemIndex));
                }
            }
        }
    }

    /**
     * 构建文档 ID（使用主键值，保证幂等）
     */
    private String buildDocumentId(MountedChangeEvent event) {
        return event.getPrimaryKeys().values().stream()
            .map(Object::toString)
            .collect(Collectors.joining("_"));
    }

    /**
     * 构建 ES 文档
     * MySQL 列名 → ES 字段名的映射
     */
    private Map<String, Object> buildDocument(
            Map<String, ColumnValue> columns) {

        Map<String, Object> doc = new HashMap<>();
        for (Map.Entry<String, ColumnValue> entry : columns.entrySet()) {
            String esField = mappingConfig.mapColumnToField(
                entry.getKey(), entry.getKey()
            );
            doc.put(esField, entry.getValue().getValue());
        }
        return doc;
    }

    @Override
    protected void handleSinkFailure(List<MountedChangeEvent> events,
                                      Exception e) {
        // 降级为逐条重试
        for (MountedChangeEvent event : events) {
            log.warn("Retrying single event after batch failure: " +
                "table={}, type={}", event.getTable(), event.getType());
            retryQueue.add(event);
        }
    }

    private int countFailures(BulkResponse response) {
        int count = 0;
        for (BulkItemResponse item : response.getItems()) {
            if (item.isFailed()) count++;
        }
        return count;
    }
}

/**
 * 商品搜索同步的检查点管理
 *
 * 幂等保障：
 * 1. 检查点保存：每 5 秒持久化当前 Binlog 位点
 * 2. 重启恢复：从最后保存的位点继续消费
 * 3. 重复消费安全：ES 的 docAsUpsert 保证幂等
 */
public class ProductCheckpointManager extends CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(
        ProductCheckpointManager.class
    );

    /**
     * 定时保存检查点
     */
    @Scheduled(fixedRate = 5000)
    public void scheduledCheckpoint() {
        Checkpoint cp = getLatestCheckpoint();
        if (cp != null) {
            log.debug("Checkpoint: file={}, position={}, timestamp={}",
                cp.getBinlogFile(), cp.getPosition(), cp.getTimestamp());
        }
    }

    /**
     * 重启时恢复检查点
     */
    public Checkpoint recover() {
        Checkpoint cp = getLatestCheckpoint();
        if (cp != null) {
            log.info("Recovered checkpoint: file={}, position={}",
                cp.getBinlogFile(), cp.getPosition());
        } else {
            log.warn("No checkpoint found, will start from current " +
                "binlog position (new sync)");
        }
        return cp;
    }
}
```

**链路关键设计总结**：

| 环节 | 关键设计 | 幂等保障 |
|------|---------|---------|
| Puller | Binlog ROW 格式监听，断点续传 | 检查点持久化，重启从上次位点继续 |
| 事件队列 | 有界队列（50000），背压控制 | — |
| 凑批策略 | 500 条或 100ms，兼顾延迟与吞吐 | — |
| ES Sinker | Bulk API 批量写入，docAsUpsert | 相同文档 ID 覆盖，重复投递安全 |
| 异常处理 | 批量失败降级逐条重试，死信队列兜底 | 重试不改变数据最终状态 |
| 监控 | 延迟、吞吐量、失败率实时上报 | — |

---

### 7.2 案例二：分库分表数据聚合同步全链路

**业务场景**：订单系统按用户 ID 分库分表（4 库 x 8 表 = 32 个分片），数据分析平台需要将全量订单数据聚合同步到统一的 ClickHouse 数据仓库，支持实时报表查询。

```
分库分表聚合同步架构：

┌──────────────────────────────────────────────────────────────────┐
│                    分库分表聚合同步链路                               │
│                                                                  │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │ DB_0     │  │ DB_1     │  │ DB_2     │  │ DB_3     │            │
│  │ t_order  │  │ t_order  │  │ t_order  │  │ t_order  │            │
│  │ _0000~07 │  │ _0000~07 │  │ _0000~07 │  │ _0000~07 │            │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘            │
│       │             │             │             │                  │
│       └─────────────┴──────┬──────┴─────────────┘                  │
│                             │                                     │
│                    ┌────────▼────────┐                            │
│                    │  多源 Puller     │                            │
│                    │ (32分片并行监听)  │                            │
│                    └────────┬────────┘                            │
│                             │                                     │
│                    ┌────────▼────────┐                            │
│                    │  聚合 Mounter    │                            │
│                    │ (统一Schema映射) │                            │
│                    └────────┬────────┘                            │
│                             │                                     │
│                    ┌────────▼────────┐                            │
│                    │ ClickHouse      │                            │
│                    │  Sinker         │                            │
│                    │ (批量写入+幂等)  │                            │
│                    └────────┬────────┘                            │
│                             │                                     │
│                    ┌────────▼────────┐                            │
│                    │  ClickHouse     │                            │
│                    │  (聚合查询)      │                            │
│                    └─────────────────┘                            │
└──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * 分库分表聚合同步管理器
 *
 * 核心挑战：
 * 1. 32 个分片的 Binlog 并行监听，各自独立管理位点
 * 2. 不同分片的表结构相同但表名不同（t_order_0000 ~ t_order_0007）
 * 3. 聚合后统一写入 ClickHouse 单表，需要路由映射
 * 4. 各分片位点独立推进，保证单分片内有序
 *
 * 幂等策略：
 * ClickHouse 使用 ReplacingMergeTree 引擎，基于 ORDER BY 字段去重，
 * 配合版本号字段实现幂等写入（重复写入相同主键+版本号的行会被自动合并）
 */
public class ShardingSyncManager implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(
        ShardingSyncManager.class
    );

    // 分片配置：4 库 x 8 表 = 32 个分片
    private final List<ShardConfig> shardConfigs;
    private final Map<String, BinlogPuller> pullerMap = new ConcurrentHashMap<>();
    private final Map<String, CheckpointManager> checkpointMap =
        new ConcurrentHashMap<>();
    private final ClickHouseSinker sinker;
    private final CDCErrorHandler errorHandler;

    private volatile boolean running = false;

    /**
     * 分片配置
     */
    public static class ShardConfig {
        private String shardId;        // 分片标识，如 "db0_table0003"
        private String mysqlHost;
        private int mysqlPort;
        private String mysqlUsername;
        private String mysqlPassword;
        private long serverId;          // 唯一的 server-id
        private String schemaName;      // 数据库名
        private List<String> tableNames; // 监听的表名列表
    }

    /**
     * 启动全部分片的同步
     */
    @Override
    public void start() {
        log.info("Starting sharding sync, total shards: {}",
            shardConfigs.size());

        running = true;

        for (ShardConfig config : shardConfigs) {
            startShardSync(config);
        }

        log.info("All {} shards started successfully", shardConfigs.size());
    }

    /**
     * 启动单个分片的同步
     */
    private void startShardSync(ShardConfig config) {
        log.info("Starting sync for shard: {}", config.getShardId());

        // 创建该分片的检查点管理器
        CheckpointManager cpManager = new CheckpointManager(
            new DatabaseCheckpointStore(config.getShardId())
        );
        checkpointMap.put(config.getShardId(), cpManager);

        // 恢复检查点
        Checkpoint checkpoint = cpManager.getLatestCheckpoint();
        if (checkpoint != null) {
            log.info("Shard {} resuming from checkpoint: {}:{}",
                config.getShardId(),
                checkpoint.getBinlogFile(),
                checkpoint.getPosition());
        }

        // 创建并配置 Puller
        BinlogPuller puller = new BinlogPuller();
        puller.setConfig(buildBinlogConfig(config, checkpoint));
        puller.setEventHandler(event -> {
            // 在事件中标记来源分片
            event.setMetadata("shard_id", config.getShardId());
            handleShardEvent(config.getShardId(), event);
        });

        pullerMap.put(config.getShardId(), puller);

        // 异步启动，避免阻塞
        CompletableFuture.runAsync(() -> {
            try {
                puller.start();
            } catch (Exception e) {
                log.error("Failed to start puller for shard: {}",
                    config.getShardId(), e);
                scheduleShardReconnect(config);
            }
        });
    }

    /**
     * 处理分片事件
     * 将分片表的事件转换为统一的聚合事件，写入 ClickHouse
     */
    private void handleShardEvent(String shardId,
                                   MountedChangeEvent event) {
        try {
            // 添加分片来源信息
            event.setMetadata("source_shard", shardId);
            event.setMetadata("sync_time", System.currentTimeMillis());

            // 幂等写入 ClickHouse
            sinker.sink(Collections.singletonList(event));

            // 更新该分片的检查点
            CheckpointManager cpManager = checkpointMap.get(shardId);
            cpManager.save(new Checkpoint(
                event.getBinlogFile(),
                event.getBinlogPosition(),
                event.getTimestamp()
            ));

        } catch (Exception e) {
            log.error("Failed to process event from shard {}: " +
                "table={}, type={}", shardId,
                event.getTable(), event.getType(), e);

            // 错误处理：重试 + 死信队列
            errorHandler.handleWithRetry(event, sinker);
        }
    }

    /**
     * 分片级别自动重连
     */
    private void scheduleShardReconnect(ShardConfig config) {
        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        scheduler.schedule(() -> {
            log.info("Attempting to reconnect shard: {}",
                config.getShardId());
            startShardSync(config);
        }, 5, TimeUnit.SECONDS);

        scheduler.shutdown();
    }

    /**
     * 停止全部分片同步
     */
    @Override
    public void stop() {
        log.info("Stopping sharding sync...");
        running = false;

        for (Map.Entry<String, BinlogPuller> entry : pullerMap.entrySet()) {
            try {
                entry.getValue().stop();
                log.info("Stopped puller for shard: {}", entry.getKey());
            } catch (Exception e) {
                log.error("Error stopping puller for shard: {}",
                    entry.getKey(), e);
            }
        }

        log.info("Sharding sync stopped");
    }

    private BinlogConfig buildBinlogConfig(ShardConfig config,
                                            Checkpoint checkpoint) {
        BinlogConfig binlogConfig = new BinlogConfig();
        binlogConfig.setHost(config.getMysqlHost());
        binlogConfig.setPort(config.getMysqlPort());
        binlogConfig.setUsername(config.getMysqlUsername());
        binlogConfig.setPassword(config.getMysqlPassword());
        binlogConfig.setServerId(config.getServerId());
        binlogConfig.setIncludeSchemas(
            Collections.singletonList(config.getSchemaName())
        );
        binlogConfig.setIncludeTables(config.getTableNames());

        if (checkpoint != null) {
            binlogConfig.setBinlogFilename(checkpoint.getBinlogFile());
            binlogConfig.setBinlogPosition(checkpoint.getPosition());
        }

        return binlogConfig;
    }
}

/**
 * ClickHouse Sinker
 *
 * 幂等策略：
 * ClickHouse 使用 ReplacingMergeTree 引擎，基于 ORDER BY (order_id, shard_id)
 * 字段去重，配合 version 字段实现幂等：
 * - 重复写入相同 (order_id, shard_id) 的行，CH 自动保留 version 最大的行
 * - version 使用 Binlog 时间戳，保证最新变更有最大的 version
 *
 * 异常处理：
 * - 写入失败：指数退避重试
 * - 批量失败：降级逐条写入
 * - CH 不可用：事件积压在内存队列，恢复后追赶
 */
public class ClickHouseSinker extends AbstractSinker {

    private static final Logger log = LoggerFactory.getLogger(
        ClickHouseSinker.class
    );

    private final ClickHouseDataSource clickHouseDataSource;
    private final MeterRegistry metrics;

    // 目标表 DDL（ReplacingMergeTree 引擎）
    /*
     * CREATE TABLE order_analytics (
     *     order_id        UInt64,
     *     shard_id        String,
     *     user_id         UInt64,
     *     order_status    String,
     *     total_amount    Decimal(10,2),
     *     create_time     DateTime,
     *     update_time     DateTime,
     *     sync_time       DateTime,
     *     version         UInt64,
     *     source_shard    String
     * ) ENGINE = ReplacingMergeTree(version)
     * ORDER BY (order_id, shard_id)
     * PARTITION BY toYYYYMM(create_time);
     */

    /**
     * 批量写入 ClickHouse
     */
    @Override
    protected void doSink(List<MountedChangeEvent> events) {
        if (events.isEmpty()) return;

        long startTime = System.currentTimeMillis();

        Connection conn = null;
        try {
            conn = clickHouseDataSource.getConnection();
            conn.setAutoCommit(false);

            // 构建批量 INSERT 语句
            String sql = "INSERT INTO order_analytics " +
                "(order_id, shard_id, user_id, order_status, " +
                "total_amount, create_time, update_time, " +
                "sync_time, version, source_shard) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            PreparedStatement pstmt = conn.prepareStatement(sql);

            for (MountedChangeEvent event : events) {
                Map<String, ColumnValue> columns =
                    event.getAfterColumns() != null ?
                    event.getAfterColumns() : event.getBeforeColumns();

                if (columns == null) continue;

                // 填充参数
                pstmt.setLong(1, getLong(columns, "order_id"));
                pstmt.setString(2, getString(event, "shard_id"));
                pstmt.setLong(3, getLong(columns, "user_id"));
                pstmt.setString(4, getString(columns, "order_status"));
                pstmt.setBigDecimal(5, getBigDecimal(columns, "total_amount"));
                pstmt.setTimestamp(6, getTimestamp(columns, "create_time"));
                pstmt.setTimestamp(7, getTimestamp(columns, "update_time"));
                pstmt.setTimestamp(8, new Timestamp(System.currentTimeMillis()));
                // version = Binlog 时间戳，保证最新变更有最大 version
                pstmt.setLong(9, event.getTimestamp());
                pstmt.setString(10, getString(event, "source_shard"));

                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();
            conn.commit();

            long elapsed = System.currentTimeMillis() - startTime;
            int successCount = countSuccess(results);

            log.info("ClickHouse batch insert: total={}, success={}, " +
                "elapsed={}ms", events.size(), successCount, elapsed);

            metrics.counter("clickhouse.sink.success")
                .increment(successCount);
            metrics.counter("clickhouse.sink.total")
                .increment(events.size());
            metrics.timer("clickhouse.sink.latency")
                .record(elapsed, TimeUnit.MILLISECONDS);

        } catch (SQLException e) {
            log.error("ClickHouse batch insert failed: {}",
                e.getMessage(), e);
            metrics.counter("clickhouse.sink.failure").increment();

            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            throw new SinkException("ClickHouse batch insert failed", e);

        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * 处理 DELETE 事件
     * ClickHouse 不支持物理删除，使用标记删除
     */
    public void handleDelete(MountedChangeEvent event) {
        Connection conn = null;
        try {
            conn = clickHouseDataSource.getConnection();

            // 标记删除：写入一条 order_status='DELETED' 的记录
            // ReplacingMergeTree 会保留 version 最大的行
            String sql = "INSERT INTO order_analytics " +
                "(order_id, shard_id, order_status, version, sync_time) " +
                "VALUES (?, ?, 'DELETED', ?, ?)";

            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, getLong(event.getBeforeColumns(), "order_id"));
            pstmt.setString(2, getString(event, "shard_id"));
            pstmt.setLong(3, event.getTimestamp()); // version
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.executeUpdate();

            log.info("Marked as deleted: order_id={}, shard={}",
                getLong(event.getBeforeColumns(), "order_id"),
                getString(event, "shard_id"));

        } catch (SQLException e) {
            log.error("Failed to handle delete event", e);
            throw new SinkException("ClickHouse delete failed", e);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    @Override
    protected void handleSinkFailure(List<MountedChangeEvent> events,
                                      Exception e) {
        log.error("Batch sink failed ({} events), falling back to " +
            "single-event processing", events.size(), e);

        for (MountedChangeEvent event : events) {
            try {
                doSink(Collections.singletonList(event));
            } catch (Exception ex) {
                log.error("Single event sink also failed: table={}, type={}",
                    event.getTable(), event.getType(), ex);
                retryQueue.add(event);
            }
        }
    }

    // --- 类型安全的数据提取方法 ---

    private long getLong(Map<String, ColumnValue> columns, String name) {
        ColumnValue cv = columns.get(name);
        return cv != null && cv.getValue() != null ?
            ((Number) cv.getValue()).longValue() : 0L;
    }

    private String getString(Map<String, ColumnValue> columns, String name) {
        ColumnValue cv = columns.get(name);
        return cv != null && cv.getValue() != null ?
            cv.getValue().toString() : "";
    }

    private String getString(MountedChangeEvent event, String metaKey) {
        Object val = event.getMetadata().get(metaKey);
        return val != null ? val.toString() : "";
    }

    private BigDecimal getBigDecimal(Map<String, ColumnValue> columns,
                                      String name) {
        ColumnValue cv = columns.get(name);
        if (cv == null || cv.getValue() == null) return BigDecimal.ZERO;
        if (cv.getValue() instanceof BigDecimal) return (BigDecimal) cv.getValue();
        return new BigDecimal(cv.getValue().toString());
    }

    private Timestamp getTimestamp(Map<String, ColumnValue> columns,
                                    String name) {
        ColumnValue cv = columns.get(name);
        if (cv == null || cv.getValue() == null) return new Timestamp(0);
        if (cv.getValue() instanceof Timestamp) return (Timestamp) cv.getValue();
        if (cv.getValue() instanceof Long) return new Timestamp((Long) cv.getValue());
        return Timestamp.valueOf(cv.getValue().toString());
    }

    private int countSuccess(int[] results) {
        int count = 0;
        for (int r : results) {
            if (r >= 0 || r == java.sql.Statement.SUCCESS_NO_INFO) count++;
        }
        return count;
    }
}

/**
 * 分库分表聚合同步的检查点管理
 *
 * 每个分片独立管理位点，互不影响
 */
public class ShardingCheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(
        ShardingCheckpointManager.class
    );

    private final Map<String, CheckpointManager> shardCheckpoints =
        new ConcurrentHashMap<>();

    /**
     * 保存指定分片的检查点
     */
    public void saveCheckpoint(String shardId, Checkpoint checkpoint) {
        CheckpointManager manager = shardCheckpoints.computeIfAbsent(
            shardId,
            id -> new CheckpointManager(new DatabaseCheckpointStore(id))
        );
        manager.save(checkpoint);

        log.debug("Checkpoint saved for shard {}: file={}, position={}",
            shardId, checkpoint.getBinlogFile(), checkpoint.getPosition());
    }

    /**
     * 获取指定分片的检查点
     */
    public Checkpoint getCheckpoint(String shardId) {
        CheckpointManager manager = shardCheckpoints.get(shardId);
        return manager != null ? manager.getLatestCheckpoint() : null;
    }

    /**
     * 获取所有分片中最旧的检查点
     * 用于判断整体同步进度
     */
    public Checkpoint getOldestCheckpoint() {
        return shardCheckpoints.values().stream()
            .map(CheckpointManager::getLatestCheckpoint)
            .filter(Objects::nonNull)
            .min(Comparator.comparingLong(Checkpoint::getTimestamp))
            .orElse(null);
    }

    /**
     * 获取所有分片的同步延迟统计
     */
    public void logAllShardDelay() {
        long now = System.currentTimeMillis();
        shardCheckpoints.forEach((shardId, manager) -> {
            Checkpoint cp = manager.getLatestCheckpoint();
            if (cp != null) {
                long delay = now - cp.getTimestamp();
                log.info("Shard {} delay: {}ms (file={}, position={})",
                    shardId, delay, cp.getBinlogFile(), cp.getPosition());
            } else {
                log.warn("Shard {} has no checkpoint", shardId);
            }
        });
    }
}
```

**链路关键设计总结**：

| 环节 | 关键设计 | 幂等保障 |
|------|---------|---------|
| 多源 Puller | 32 分片各自独立监听 Binlog，独立 server-id | 每分片独立检查点 |
| 事件标记 | 事件携带 shard_id 来源标识 | — |
| 聚合路由 | 不同分片表名映射到统一 ClickHouse 表 | — |
| ClickHouse Sinker | ReplacingMergeTree(version) 引擎，批量 INSERT | 相同 ORDER BY + version 去重 |
| DELETE 处理 | 标记删除（写入 DELETED 状态），CH 不支持物理删除 | version 保证标记删除不被覆盖 |
| 异常处理 | 批量失败降级逐条，重试队列兜底 | 重复 INSERT 被 ReplacingMergeTree 合并 |
| 监控 | 各分片独立延迟统计，整体进度取最慢分片 | — |

---

### 7.3 案例三：CDC 异常处理与恢复全链路

**业务场景**：金融交易系统的 CDC 同步链路在运行过程中遭遇多种异常（网络抖动、下游限流、Schema 变更冲突、进程崩溃），需要完整的异常处理与自动恢复机制，保证数据最终一致。

```
CDC 异常处理与恢复全链路：

┌──────────────────────────────────────────────────────────────────┐
│                      异常处理与恢复体系                               │
│                                                                  │
│  ┌──────────────────┐                                           │
│  │  正常同步链路      │                                           │
│  │  Puller→Mounter  │                                           │
│  │  →Sinker         │                                           │
│  └────────┬─────────┘                                           │
│           │ 异常                                                 │
│           ▼                                                     │
│  ┌──────────────────┐                                           │
│  │  异常分类与路由    │                                           │
│  │  ┌──────┐┌──────┐│                                           │
│  │  │瞬时异常││永久  ││                                           │
│  │  │      ││异常  ││                                           │
│  │  └──┬───┘└──┬───┘│                                           │
│  └─────┼───────┼────┘                                           │
│        │       │                                                 │
│        ▼       ▼                                                 │
│  ┌──────────┐ ┌──────────┐                                      │
│  │ 指数退避   │ │ 死信队列  │                                      │
│  │ 重试      │ │ + 告警    │                                      │
│  └────┬─────┘ └────┬─────┘                                      │
│       │            │                                             │
│       ▼            ▼                                             │
│  ┌──────────────────────┐                                       │
│  │  恢复流程              │                                       │
│  │  ┌────────┐┌────────┐│                                       │
│  │  │位点恢复 ││全量校验 ││                                       │
│  │  │+补偿同步││+修复   ││                                       │
│  │  └────────┘└────────┘│                                       │
│  └──────────────────────┘                                       │
└──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * CDC 异常处理与恢复管理器
 *
 * 核心能力：
 * 1. 异常分类：瞬时异常（网络抖动、限流）vs 永久异常（Schema 冲突、数据格式错误）
 * 2. 指数退避重试：瞬时异常自动重试，最大 5 次
 * 3. 死信队列：永久异常或重试耗尽的事件进入死信队列
 * 4. 自动恢复：进程崩溃后从检查点恢复，补偿遗漏事件
 * 5. 全量校验修复：定期运行一致性校验，自动修复不一致数据
 */
public class CDCRecoveryManager implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(
        CDCRecoveryManager.class
    );

    private final BinlogPuller puller;
    private final AbstractSinker sinker;
    private final CheckpointManager checkpointManager;
    private final DeadLetterQueue deadLetterQueue;
    private final ConsistencyChecker consistencyChecker;
    private final AlertService alertService;
    private final MeterRegistry metrics;

    // 重试配置
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    // 恢复状态
    private volatile RecoveryState recoveryState = RecoveryState.NORMAL;

    private enum RecoveryState {
        NORMAL,           // 正常同步
        RECONNECTING,     // 正在重连
        RECOVERING,       // 正在恢复
        FULL_CHECKING     // 正在全量校验
    }

    // ==================== 异常分类与处理 ====================

    /**
     * 异常分类器
     * 根据异常类型决定处理策略
     */
    public ExceptionType classifyException(Exception e) {
        if (e instanceof TransientException) {
            return ExceptionType.TRANSIENT;
        }
        if (e instanceof SchemaNotFoundException ||
            e instanceof DDLSyncException) {
            return ExceptionType.SCHEMA_ERROR;
        }
        if (e instanceof DataFormatException) {
            return ExceptionType.DATA_ERROR;
        }
        if (e instanceof java.net.SocketTimeoutException ||
            e instanceof java.net.ConnectException) {
            return ExceptionType.NETWORK_ERROR;
        }
        if (e instanceof RateLimitException) {
            return ExceptionType.RATE_LIMITED;
        }
        return ExceptionType.UNKNOWN;
    }

    public enum ExceptionType {
        TRANSIENT,       // 瞬时异常：可重试
        NETWORK_ERROR,   // 网络异常：可重试
        RATE_LIMITED,    // 限流：可重试（需等待）
        SCHEMA_ERROR,    // Schema 错误：需人工介入
        DATA_ERROR,      // 数据格式错误：需人工介入
        UNKNOWN          // 未知异常：需人工介入
    }

    /**
     * 统一异常处理入口
     */
    public void handleException(MountedChangeEvent event,
                                 Exception e) {
        ExceptionType type = classifyException(e);
        metrics.counter("cdc.exception." + type.name().toLowerCase())
            .increment();

        switch (type) {
            case TRANSIENT:
            case NETWORK_ERROR:
            case RATE_LIMITED:
                // 可重试异常
                handleWithRetry(event, e, type);
                break;

            case SCHEMA_ERROR:
                // Schema 错误，需要人工介入
                handleSchemaError(event, e);
                break;

            case DATA_ERROR:
            case UNKNOWN:
            default:
                // 不可恢复异常，进入死信队列
                handleUnrecoverable(event, e);
                break;
        }
    }

    /**
     * 指数退避重试
     *
     * 幂等保障：
     * 重试可能导致同一事件被多次投递，下游 Sinker 必须支持幂等写入。
     * - MySQL：REPLACE INTO / ON DUPLICATE KEY UPDATE
     * - ES：docAsUpsert(true)
     * - Redis：SET / DEL（天然幂等）
     * - ClickHouse：ReplacingMergeTree(version)
     */
    private void handleWithRetry(MountedChangeEvent event,
                                  Exception e,
                                  ExceptionType type) {
        int attempt = 0;
        boolean success = false;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            long backoff = calculateBackoff(attempt);

            log.warn("Retrying event (attempt={}/{}, backoff={}ms, " +
                "type={}): table={}, position={}:{}",
                attempt, MAX_RETRY_ATTEMPTS, backoff, type,
                event.getTable(), event.getBinlogFile(),
                event.getBinlogPosition());

            try {
                Thread.sleep(backoff);

                // 重新写入（幂等）
                sinker.sink(Collections.singletonList(event));
                success = true;

                log.info("Retry succeeded on attempt {}: table={}",
                    attempt, event.getTable());

                metrics.counter("cdc.retry.success").increment();
                break;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Retry interrupted");
                break;
            } catch (Exception retryError) {
                log.error("Retry attempt {} failed: {}",
                    attempt, retryError.getMessage());

                // 重新分类，可能升级为不可恢复
                ExceptionType newType = classifyException(retryError);
                if (newType == ExceptionType.SCHEMA_ERROR ||
                    newType == ExceptionType.DATA_ERROR) {
                    log.error("Exception escalated to unrecoverable " +
                        "during retry: {}", newType);
                    handleUnrecoverable(event, retryError);
                    return;
                }
            }
        }

        if (!success) {
            log.error("All {} retry attempts exhausted for event: " +
                "table={}, type={}", MAX_RETRY_ATTEMPTS,
                event.getTable(), event.getType());
            metrics.counter("cdc.retry.exhausted").increment();
            handleUnrecoverable(event, e);
        }
    }

    /**
     * 计算退避时间（指数退避 + 随机抖动）
     */
    private long calculateBackoff(int attempt) {
        long backoff = (long) (INITIAL_BACKOFF_MS *
            Math.pow(BACKOFF_MULTIPLIER, attempt - 1));
        backoff = Math.min(backoff, MAX_BACKOFF_MS);
        // 添加 0~20% 的随机抖动，避免重试风暴
        backoff += (long) (backoff * 0.2 * Math.random());
        return backoff;
    }

    /**
     * Schema 错误处理
     * 暂停同步，通知人工介入
     */
    private void handleSchemaError(MountedChangeEvent event,
                                    Exception e) {
        log.error("Schema error detected, pausing sync: table={}, " +
            "position={}:{}, error={}",
            event.getTable(), event.getBinlogFile(),
            event.getBinlogPosition(), e.getMessage());

        metrics.counter("cdc.schema.error").increment();

        // 暂停 Puller（不丢数据，Binlog 位点不变）
        puller.pause();

        // 发送告警
        alertService.sendSchemaErrorAlert(
            event.getSchema(),
            event.getTable(),
            event.getBinlogFile() + ":" + event.getBinlogPosition(),
            e.getMessage()
        );

        // 将事件放入死信队列，等待人工修复 Schema 后重放
        deadLetterQueue.send(event, e);
    }

    /**
     * 不可恢复异常处理
     * 进入死信队列，发送告警
     */
    private void handleUnrecoverable(MountedChangeEvent event,
                                      Exception e) {
        log.error("Unrecoverable error, sending to dead letter queue: " +
            "table={}, type={}, position={}:{}, error={}",
            event.getTable(), event.getType(),
            event.getBinlogFile(), event.getBinlogPosition(),
            e.getMessage());

        deadLetterQueue.send(event, e);
        metrics.counter("cdc.dead_letter.sent").increment();

        // 死信队列堆积超过阈值，发送严重告警
        long dlqSize = deadLetterQueue.size();
        if (dlqSize > 100) {
            alertService.sendDeadLetterAlert(
                "Dead letter queue size exceeds threshold: " + dlqSize
            );
        }
    }

    // ==================== 死信队列处理 ====================

    /**
     * 定时处理死信队列
     * 重新尝试处理失败的事件
     */
    @Scheduled(fixedRate = 60000)
    public void processDeadLetterQueue() {
        List<DeadLetterMessage> messages = deadLetterQueue.poll(100);

        if (messages.isEmpty()) return;

        log.info("Processing dead letter queue: {} messages", messages.size());

        for (DeadLetterMessage message : messages) {
            // 超过最大重试次数，放弃并发送告警
            if (message.getRetryCount() >= 10) {
                log.error("Dead letter message abandoned after {} retries: " +
                    "table={}, type={}", message.getRetryCount(),
                    message.getEvent().getTable(),
                    message.getEvent().getType());

                alertService.sendDeadLetterAbandonedAlert(message);
                deadLetterQueue.markAsAbandoned(message);
                metrics.counter("cdc.dead_letter.abandoned").increment();
                continue;
            }

            try {
                // 尝试重新处理
                sinker.sink(Collections.singletonList(message.getEvent()));
                deadLetterQueue.markAsProcessed(message);

                log.info("Dead letter message processed successfully: " +
                    "table={}, retryCount={}",
                    message.getEvent().getTable(), message.getRetryCount());

                metrics.counter("cdc.dead_letter.recovered").increment();

            } catch (Exception e) {
                message.incrementRetryCount();
                deadLetterQueue.requeue(message);

                log.warn("Dead letter retry failed (attempt={}): {}",
                    message.getRetryCount(), e.getMessage());
            }
        }
    }

    // ==================== 崩溃恢复 ====================

    /**
     * 进程崩溃后恢复
     *
     * 恢复流程：
     * 1. 加载最后保存的检查点
     * 2. 从检查点位点开始重新消费 Binlog
     * 3. 由于 At-Least-Once 语义，可能有少量重复事件，下游幂等处理
     * 4. 恢复后运行一次增量校验，确保数据一致
     */
    public void recoverFromCrash() {
        log.info("Starting crash recovery...");
        recoveryState = RecoveryState.RECOVERING;

        try {
            // Step 1: 加载检查点
            Checkpoint checkpoint = checkpointManager.getLatestCheckpoint();

            if (checkpoint == null) {
                log.error("No checkpoint found! Cannot recover. " +
                    "Manual intervention required.");
                alertService.sendCriticalAlert(
                    "CDC Recovery Failed",
                    "No checkpoint found, manual intervention required"
                );
                recoveryState = RecoveryState.NORMAL;
                return;
            }

            log.info("Recovered checkpoint: file={}, position={}, " +
                "timestamp={}",
                checkpoint.getBinlogFile(), checkpoint.getPosition(),
                checkpoint.getTimestamp());

            // Step 2: 计算恢复窗口（检查点时间到现在）
            long recoveryWindowMs = System.currentTimeMillis() -
                checkpoint.getTimestamp();
            log.info("Recovery window: {}ms ({})", recoveryWindowMs,
                formatDuration(recoveryWindowMs));

            // Step 3: 从检查点位点重新启动 Puller
            puller.setStartCheckpoint(checkpoint);
            puller.start();

            log.info("Puller restarted from checkpoint, consuming binlog...");

            // Step 4: 等待追赶完成（延迟降至 1 秒以内）
            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < 300000) {
                // 最多等待 5 分钟
                long currentDelay = getCurrentSyncDelay();
                if (currentDelay < 1000) {
                    log.info("Recovery catch-up completed, " +
                        "current delay: {}ms", currentDelay);
                    break;
                }
                Thread.sleep(1000);
                log.info("Recovery in progress, current delay: {}ms",
                    currentDelay);
            }

            // Step 5: 增量校验
            log.info("Running incremental consistency check...");
            recoveryState = RecoveryState.FULL_CHECKING;
            CheckResult result = consistencyChecker.incrementalCheck(
                checkpoint.getTimestamp()
            );

            if (result.getMismatchCount() > 0 ||
                result.getMissingCount() > 0) {
                log.warn("Consistency check found issues: " +
                    "missing={}, mismatch={}, extra={}",
                    result.getMissingCount(), result.getMismatchCount(),
                    result.getExtraRows());

                // 自动修复
                consistencyChecker.autoRepair(result);
                log.info("Auto repair completed");
            } else {
                log.info("Consistency check passed, no issues found");
            }

            recoveryState = RecoveryState.NORMAL;
            log.info("Crash recovery completed successfully");

        } catch (Exception e) {
            log.error("Crash recovery failed", e);
            alertService.sendCriticalAlert(
                "CDC Recovery Failed",
                "Recovery failed: " + e.getMessage()
            );
            recoveryState = RecoveryState.NORMAL;
        }
    }

    /**
     * Binlog 连接断开恢复
     */
    public void handleBinlogDisconnection(Exception cause) {
        log.warn("Binlog connection lost, initiating reconnection: {}",
            cause.getMessage());

        recoveryState = RecoveryState.RECONNECTING;
        metrics.counter("cdc.binlog.disconnection").increment();

        int reconnectAttempts = 0;
        while (reconnectAttempts < 10) {
            reconnectAttempts++;
            long backoff = calculateBackoff(reconnectAttempts);

            try {
                Thread.sleep(backoff);
                log.info("Reconnect attempt {}/{}", reconnectAttempts, 10);

                puller.reconnect();

                log.info("Binlog reconnection succeeded on attempt {}",
                    reconnectAttempts);
                metrics.counter("cdc.binlog.reconnect.success").increment();
                recoveryState = RecoveryState.NORMAL;
                return;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Reconnection interrupted");
                return;
            } catch (Exception e) {
                log.error("Reconnect attempt {} failed: {}",
                    reconnectAttempts, e.getMessage());
            }
        }

        // 重连失败，发送严重告警
        log.error("All reconnection attempts failed, sending critical alert");
        metrics.counter("cdc.binlog.reconnect.failure").increment();
        alertService.sendCriticalAlert(
            "CDC Binlog Connection Lost",
            "Failed to reconnect after 10 attempts. " +
            "Last checkpoint: " +
            checkpointManager.getLatestCheckpoint()
        );

        recoveryState = RecoveryState.NORMAL;
    }

    // ==================== 定期全量校验 ====================

    /**
     * 定期全量校验（每天凌晨 2 点执行）
     * 作为数据一致性的安全网
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledFullCheck() {
        log.info("Starting scheduled full consistency check...");
        recoveryState = RecoveryState.FULL_CHECKING;

        try {
            List<String> tables = getSyncedTables();
            int totalIssues = 0;

            for (String table : tables) {
                log.info("Checking table: {}", table);

                CheckResult result = consistencyChecker.fullCheck(table);

                if (result.getMismatchCount() > 0 ||
                    result.getMissingCount() > 0) {

                    log.warn("Table {} has issues: missing={}, " +
                        "mismatch={}, extra={}",
                        table, result.getMissingCount(),
                        result.getMismatchCount(), result.getExtraRows());

                    // 自动修复
                    consistencyChecker.autoRepair(result);
                    totalIssues += result.getMissingCount() +
                        result.getMismatchCount();

                    log.info("Table {} auto repair completed", table);
                } else {
                    log.info("Table {} check passed", table);
                }
            }

            if (totalIssues > 0) {
                alertService.sendDataConsistencyAlert(
                    "Full check completed with " + totalIssues +
                    " issues found and repaired"
                );
            }

            log.info("Scheduled full check completed: total issues={}",
                totalIssues);

        } catch (Exception e) {
            log.error("Scheduled full check failed", e);
            alertService.sendAlert(
                "Full check failed: " + e.getMessage()
            );
        } finally {
            recoveryState = RecoveryState.NORMAL;
        }
    }

    // ==================== 辅助方法 ====================

    private long getCurrentSyncDelay() {
        Checkpoint cp = checkpointManager.getLatestCheckpoint();
        if (cp == null) return Long.MAX_VALUE;
        return System.currentTimeMillis() - cp.getTimestamp();
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%dh %dm %ds",
            hours, minutes % 60, seconds % 60);
    }

    private List<String> getSyncedTables() {
        // 返回所有正在同步的表
        return Arrays.asList("t_order", "t_user", "t_product");
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void start() {
        log.info("Starting CDC recovery manager...");

        // 启动时检查是否需要恢复
        Checkpoint cp = checkpointManager.getLatestCheckpoint();
        if (cp != null) {
            long gap = System.currentTimeMillis() - cp.getTimestamp();
            if (gap > 60000) {
                // 超过 60 秒未同步，触发崩溃恢复
                log.warn("Detected potential crash (gap={}ms), " +
                    "triggering recovery", gap);
                recoverFromCrash();
            } else {
                // 正常启动
                puller.setStartCheckpoint(cp);
                puller.start();
                log.info("CDC started normally from checkpoint");
            }
        } else {
            log.info("No checkpoint found, starting fresh sync");
            puller.start();
        }
    }

    @Override
    public void stop() {
        log.info("Stopping CDC recovery manager...");

        // 停止前保存最终检查点
        Checkpoint finalCheckpoint = puller.getCurrentCheckpoint();
        if (finalCheckpoint != null) {
            checkpointManager.save(finalCheckpoint);
            log.info("Final checkpoint saved: {}:{}",
                finalCheckpoint.getBinlogFile(),
                finalCheckpoint.getPosition());
        }

        puller.stop();
        log.info("CDC recovery manager stopped");
    }
}

/**
 * 死信队列实现（基于数据库）
 *
 * 幂等保障：
 * 死信队列中的事件在重新处理时，下游 Sinker 的幂等写入保证不会产生重复数据
 */
public class DatabaseDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(
        DatabaseDeadLetterQueue.class
    );

    private final JdbcTemplate template;

    /*
     * CREATE TABLE cdc_dead_letter (
     *     id BIGINT AUTO_INCREMENT PRIMARY KEY,
     *     event_data TEXT,          -- 事件 JSON 序列化
     *     error_message TEXT,        -- 错误信息
     *     error_type VARCHAR(64),    -- 异常类型
     *     retry_count INT DEFAULT 0, -- 重试次数
     *     status VARCHAR(16) DEFAULT 'PENDING', -- PENDING/PROCESSED/ABANDONED
     *     create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
     *     update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
     * );
     */

    public DatabaseDeadLetterQueue(DataSource dataSource) {
        this.template = new JdbcTemplate(dataSource);
    }

    @Override
    public void send(MountedChangeEvent event, Exception error) {
        String eventData = JSON.toJSONString(event);
        String errorMessage = error.getMessage();
        String errorType = error.getClass().getSimpleName();

        template.update(
            "INSERT INTO cdc_dead_letter " +
            "(event_data, error_message, error_type, status) " +
            "VALUES (?, ?, ?, 'PENDING')",
            eventData, errorMessage, errorType
        );

        log.warn("Event sent to dead letter queue: table={}, type={}, " +
            "error={}", event.getTable(), event.getType(), errorType);
    }

    @Override
    public List<DeadLetterMessage> poll(int limit) {
        // 查询 PENDING 状态的消息，按时间排序
        List<DeadLetterMessage> messages = template.query(
            "SELECT * FROM cdc_dead_letter WHERE status = 'PENDING' " +
            "ORDER BY create_time ASC LIMIT ?",
            new Object[]{limit},
            (rs, rowNum) -> {
                DeadLetterMessage msg = new DeadLetterMessage();
                msg.setId(rs.getLong("id"));
                msg.setEvent(JSON.parseObject(
                    rs.getString("event_data"), MountedChangeEvent.class
                ));
                msg.setErrorMessage(rs.getString("error_message"));
                msg.setErrorType(rs.getString("error_type"));
                msg.setRetryCount(rs.getInt("retry_count"));
                return msg;
            }
        );

        // 标记为处理中（避免重复拉取）
        for (DeadLetterMessage msg : messages) {
            template.update(
                "UPDATE cdc_dead_letter SET status = 'PROCESSING' " +
                "WHERE id = ? AND status = 'PENDING'",
                msg.getId()
            );
        }

        return messages;
    }

    @Override
    public void markAsProcessed(DeadLetterMessage message) {
        template.update(
            "UPDATE cdc_dead_letter SET status = 'PROCESSED' " +
            "WHERE id = ?",
            message.getId()
        );
        log.info("Dead letter message marked as processed: id={}",
            message.getId());
    }

    @Override
    public void markAsAbandoned(DeadLetterMessage message) {
        template.update(
            "UPDATE cdc_dead_letter SET status = 'ABANDONED' " +
            "WHERE id = ?",
            message.getId()
        );
        log.error("Dead letter message marked as abandoned: id={}, " +
            "retryCount={}", message.getId(), message.getRetryCount());
    }

    @Override
    public void requeue(DeadLetterMessage message) {
        template.update(
            "UPDATE cdc_dead_letter SET status = 'PENDING', " +
            "retry_count = retry_count + 1 WHERE id = ?",
            message.getId()
        );
    }

    @Override
    public long size() {
        return template.queryForObject(
            "SELECT COUNT(*) FROM cdc_dead_letter " +
            "WHERE status IN ('PENDING', 'PROCESSING')",
            Long.class
        );
    }
}
```

**链路关键设计总结**：

| 环节 | 关键设计 | 幂等保障 |
|------|---------|---------|
| 异常分类 | 瞬时/永久/Schema/数据错误分类路由 | — |
| 重试策略 | 指数退避 + 随机抖动，最大 5 次 | 下游幂等写入保证重试安全 |
| 死信队列 | 数据库持久化，定时扫描重试 | 重试时幂等写入不会产生重复 |
| 崩溃恢复 | 检查点恢复 + 延迟追赶 + 增量校验 | At-Least-Once + 幂等 = 最终一致 |
| 断线重连 | 10 次指数退避重连，失败告警 | 重连后从检查点继续 |
| 全量校验 | 每天凌晨定时全量校验 + 自动修复 | 修复操作使用 REPLACE INTO 幂等 |
| 告警体系 | 死信堆积、重连失败、校验异常告警 | — |