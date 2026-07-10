# 数据仓库与 OLAP 架构设计

## 一、问题背景

### 1.1 数据分析的核心诉求

随着业务规模的持续增长，企业积累了海量数据资产。如何高效地组织、管理和分析这些数据，已成为技术架构中的核心挑战。传统的关系型数据库（OLTP）在面对复杂分析查询时存在明显瓶颈：

- **查询性能不足**：复杂多表 JOIN、大范围扫描导致查询超时
- **数据组织混乱**：业务库分散，缺乏统一的数据视图
- **历史数据缺失**：OLTP 系统以当前状态为主，历史变更难以追溯
- **分析与事务冲突**：分析查询占用大量 IO 和 CPU，影响在线交易

### 1.2 数据仓库的核心特征

数据仓库（Data Warehouse）是为分析和决策而设计的数据存储系统，具有四大核心特征：

| 特征 | 说明 | 典型体现 |
|------|------|----------|
| **面向主题（Subject-Oriented）** | 围绕业务主题组织数据 | 交易主题、用户主题、商品主题 |
| **集成性（Integrated）** | 来自多个异构源的数据统一整合 | 命名规范、编码统一、度量一致 |
| **时变性（Time-Variant）** | 保留历史数据的时间维度 | 拉链表、快照表记录数据变化 |
| **非易失性（Non-Volatile）** | 数据一旦加载，不做频繁修改 | 追加写入，不做 UPDATE/DELETE |

### 1.3 OLTP vs OLAP 对比

| 维度 | OLTP（事务处理） | OLAP（分析处理） |
|------|-----------------|-----------------|
| **目标** | 支持日常事务操作 | 支持分析决策 |
| **数据范围** | 当前数据 | 历史数据 + 当前数据 |
| **操作类型** | INSERT/UPDATE/DELETE 为主 | SELECT 为主 |
| **查询复杂度** | 简单查询，少量行 | 复杂查询，大范围扫描 |
| **响应时间** | 毫秒级 | 秒级~分钟级 |
| **并发量** | 高并发，短事务 | 低并发，长查询 |
| **数据模型** | 高度范式化（3NF） | 反范式化（星型/雪花） |
| **数据量** | GB~TB 级 | TB~PB 级 |

### 1.4 核心挑战

构建数据仓库体系面临以下挑战：

1. **架构选型**：Lambda vs Kappa，如何权衡时效性与准确性
2. **数据建模**：维度建模 vs 范式建模，如何平衡查询性能与数据冗余
3. **数据治理**：元数据管理、数据质量、成本控制
4. **实时化演进**：传统离线数仓如何向实时数仓平滑过渡
5. **查询优化**：PB 级数据量下如何保证查询性能

---

## 二、整体架构设计

### 2.1 分层架构概览

数据仓库采用经典的四层分层架构，从原始数据到最终应用逐层提炼：

```
┌─────────────────────────────────────────────────────────────────┐
│                      数据仓库分层架构                              │
│                                                                 │
│  ┌─────────┐                                                    │
│  │ 数据源    │  MySQL / Oracle / 日志 / 埋点 / 三方API             │
│  └────┬────┘                                                    │
│       │ ETL (Sqoop / Canal / Flume / Kafka)                     │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  ODS层 (Operational Data Store) - 操作数据层               │    │
│  │  原始数据落地,基础清洗(去重、格式化、空值处理)                   │    │
│  │  存储: Hive (ORC/Parquet), Kafka                          │    │
│  └────┬────────────────────────────────────────────────────┘    │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  DW层 (Data Warehouse) - 数据仓库层                        │    │
│  │  ┌─────────────────────────────────────────────────┐     │    │
│  │  │ DWD (Detail): 明细层 - 维度退化、事实表标准化        │     │    │
│  │  │ DWS (Summary): 汇总层 - 轻度聚合、公共指标计算       │     │    │
│  │  └─────────────────────────────────────────────────┘     │    │
│  │  面向主题建模,保留全量历史明细                               │    │
│  └────┬────────────────────────────────────────────────────┘    │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  DM层 (Data Mart) - 数据集市层                             │    │
│  │  面向业务方/部门的聚合数据                                    │    │
│  │  按业务域划分: 交易集市、用户集市、财务集市                     │    │
│  └────┬────────────────────────────────────────────────────┘    │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  APP层 (Application) - 应用层                              │    │
│  │  直接服务于报表、API、业务系统                                │    │
│  │  输出: MySQL / Redis / ES / Druid / Doris                  │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 架构范式对比

#### 2.2.1 Lambda 架构

```
┌────────────────────────────────────────────────────────────┐
│                     Lambda 架构                             │
│                                                            │
│                    ┌──────────┐                             │
│                    │  数据源    │                             │
│                    └─────┬────┘                             │
│              ┌───────────┼───────────┐                     │
│              ▼                       ▼                     │
│     ┌─────────────────┐    ┌─────────────────┐            │
│     │  Batch Layer     │    │  Speed Layer     │            │
│     │  Spark / Hive    │    │  Storm / Flink   │            │
│     │  高精度,高延迟    │    │  低延迟,实时处理   │            │
│     │  T+1 / 小时级     │    │  秒级~分钟级      │            │
│     └────────┬────────┘    └────────┬────────┘            │
│              │                      │                      │
│              ▼                      ▼                      │
│     ┌─────────────────┐    ┌─────────────────┐            │
│     │  Batch View      │    │  Real-time View  │            │
│     │  HDFS / Hive     │    │  Redis / HBase   │            │
│     └────────┬────────┘    └────────┬────────┘            │
│              └───────────┬──────────┘                      │
│                          ▼                                 │
│                 ┌─────────────────┐                        │
│                 │  Serving Layer   │                        │
│                 │  查询合并层       │                        │
│                 │  (实时 + 批 合并) │                        │
│                 └─────────────────┘                        │
└────────────────────────────────────────────────────────────┘
```

**Lambda 架构特点**：
- **Batch Layer**：使用 Spark / Hive 处理全量数据，保证最终准确性
- **Speed Layer**：使用 Storm / Flink 处理增量数据，保证低延迟
- **Serving Layer**：合并批处理和实时处理的结果，对外提供查询服务

**优点**：数据精度高，批处理可修正实时计算的误差
**缺点**：需要维护两套处理逻辑，开发运维成本高

#### 2.2.2 Kappa 架构

```
┌────────────────────────────────────────────────────────────┐
│                     Kappa 架构                              │
│                                                            │
│                    ┌──────────┐                             │
│                    │  数据源    │                             │
│                    └─────┬────┘                             │
│                          ▼                                 │
│                 ┌─────────────────┐                        │
│                 │  消息队列         │                        │
│                 │  (Kafka 持久化)   │                        │
│                 └────────┬────────┘                        │
│                          ▼                                 │
│                 ┌─────────────────┐                        │
│                 │  Real-time Layer │                        │
│                 │  Kafka + Flink   │                        │
│                 │  统一流处理       │                        │
│                 └────────┬────────┘                        │
│                          ▼                                 │
│                 ┌─────────────────┐                        │
│                 │  Serving Layer   │                        │
│                 │  Doris / ES      │                        │
│                 └─────────────────┘                        │
└────────────────────────────────────────────────────────────┘
```

**Kappa 架构特点**：
- 所有数据处理通过流处理引擎完成
- Kafka 作为消息总线，保留完整历史数据
- 通过重放 Kafka 消息实现数据修正

**优点**：架构简单，一套代码逻辑
**缺点**：复杂聚合逻辑精度有限，强依赖 Kafka 长期存储

### 2.3 架构选型决策矩阵

| 评估维度 | Lambda 架构 | Kappa 架构 | 权重 |
|----------|------------|------------|------|
| 数据精度 | 高（批处理修正） | 中（流处理局限） | 30% |
| 开发效率 | 低（双套逻辑） | 高（单套逻辑） | 25% |
| 运维成本 | 高 | 低 | 20% |
| 时效性 | 中（批层延迟） | 高（全实时） | 15% |
| 复杂查询支持 | 高 | 中 | 10% |

---

## 三、核心链路设计

### 3.1 数据建模方法论

数据建模是数据仓库的核心，决定了数据的组织方式、查询效率和可维护性。主流建模方法包括：

#### 3.1.1 范式建模（Bill Inmon 方法）

范式建模遵循数据库第三范式（3NF），核心思想是消除数据冗余：

```java
/**
 * 范式建模示例 - 遵循 3NF
 * 
 * 特点:
 * - 低数据冗余
 * - 查询需要多表 JOIN
 * - 维护成本低,数据一致性好
 * - 适合自顶向下(Top-Down)的数据仓库建设
 */
public class NormativeModelingExample {

    /**
     * 3NF 表结构设计
     * 每个实体独立建表,通过外键关联
     */

    // 用户表 (消除传递依赖)
    @Data
    public static class User {
        private Long userId;          // PK
        private String userName;
        private Long cityId;          // FK -> City
        private Long levelId;         // FK -> UserLevel
        private Date createTime;
    }

    // 城市表 (独立维度)
    @Data
    public static class City {
        private Long cityId;          // PK
        private String cityName;
        private Long provinceId;      // FK -> Province
    }

    // 省份表 (独立维度)
    @Data
    public static class Province {
        private Long provinceId;      // PK
        private String provinceName;
        private String regionCode;    // 大区编码
    }

    // 用户等级表 (独立维度)
    @Data
    public static class UserLevel {
        private Long levelId;         // PK
        private String levelName;
        private Integer levelValue;
        private String levelDesc;
    }

    // 订单表 (事实表)
    @Data
    public static class Order {
        private Long orderId;         // PK
        private Long userId;          // FK -> User
        private Long shopId;          // FK -> Shop
        private Long productId;       // FK -> Product
        private BigDecimal amount;
        private Date orderTime;
        private Integer orderStatus;
    }

    /**
     * 3NF 模式的查询示例
     * 需要大量 JOIN 操作
     */
    public static final String QUERY_SQL =
        "SELECT o.order_id, o.amount, u.user_name, " +
        "       c.city_name, p.province_name, l.level_name " +
        "FROM orders o " +
        "JOIN users u ON o.user_id = u.user_id " +
        "JOIN city c ON u.city_id = c.city_id " +
        "JOIN province p ON c.province_id = p.province_id " +
        "JOIN user_level l ON u.level_id = l.level_id " +
        "WHERE o.order_time >= '2024-01-01'";
}
```

#### 3.1.2 维度建模（Ralph Kimball 方法）

维度建模是数据仓库最常用的建模方法，核心是事实表 + 维度表：

```java
/**
 * 维度建模示例 - 事实表 + 维度表
 * 
 * 核心概念:
 * - 事实表 (Fact Table): 存储业务度量值(金额、数量等)
 * - 维度表 (Dimension Table): 存储描述性属性(用户、商品、时间等)
 * - 星型模型、雪花模型、星座模型
 */
public class DimensionalModelingExample {

    /**
     * ============= 星型模型 (Star Schema) =============
     * 
     * 一个事实表 + 多个一级维度表
     * 维度表之间无直接关联
     * 查询简单,最多一次 JOIN
     * 
     *          dim_user
     *             │
     * dim_time ── fact_order ── dim_product
     *             │
     *          dim_shop
     */

    // 事实表: 订单事实表
    @Data
    public static class FactOrder {
        private Long orderId;            // 业务主键
        private Long userId;             // 维度外键
        private Long productId;          // 维度外键
        private Long shopId;             // 维度外键
        private Long dateKey;            // 时间维度外键 (yyyyMMdd)
        // ---- 度量值 ----
        private BigDecimal orderAmount;  // 订单金额
        private Integer orderQuantity;   // 订单数量
        private BigDecimal discountAmount; // 优惠金额
        private BigDecimal payAmount;    // 实付金额
    }

    // 维度表: 用户维度 (宽表,包含冗余)
    @Data
    public static class DimUser {
        private Long userId;
        private String userName;
        private String phone;
        private String cityName;       // 冗余: 不需要再 JOIN 城市表
        private String provinceName;   // 冗余: 不需要再 JOIN 省份表
        private String regionName;     // 冗余: 不需要再 JOIN 大区表
        private String userLevel;      // 冗余: 不需要再 JOIN 等级表
        private Date firstOrderTime;
        private Date lastOrderTime;
    }

    // 维度表: 商品维度
    @Data
    public static class DimProduct {
        private Long productId;
        private String productName;
        private String categoryLevel1;  // 一级类目
        private String categoryLevel2;  // 二级类目
        private String categoryLevel3;  // 三级类目
        private String brandName;
        private BigDecimal standardPrice;
    }

    // 维度表: 时间维度
    @Data
    public static class DimDate {
        private Long dateKey;          // yyyyMMdd
        private Date fullDate;
        private Integer year;
        private Integer quarter;
        private Integer month;
        private Integer weekOfYear;
        private Integer dayOfWeek;
        private Boolean isHoliday;
        private String holidayName;
    }

    /**
     * 星型模型查询示例
     * JOIN 次数少,查询性能好
     */
    public static final String STAR_QUERY =
        "SELECT d.month, u.city_name, p.category_level1, " +
        "       SUM(f.order_amount) as total_amount, " +
        "       COUNT(*) as order_count " +
        "FROM fact_order f " +
        "JOIN dim_user u ON f.user_id = u.user_id " +
        "JOIN dim_product p ON f.product_id = p.product_id " +
        "JOIN dim_date d ON f.date_key = d.date_key " +
        "GROUP BY d.month, u.city_name, p.category_level1";
}
```

#### 3.1.3 雪花模型与星座模型

```java
/**
 * ============= 雪花模型 (Snowflake Schema) =============
 * 
 * 维度表进一步规范化,维度表之间有多级关联
 * 减少了维度表冗余,但增加了 JOIN 复杂度
 * 
 *   province ── city ── dim_user
 *                           │
 *              dim_time ── fact_order ── dim_product ── category
 *                           │                            │
 *                        dim_shop                      brand
 */
public class SnowflakeSchemaExample {

    // 维度表: 用户 (规范化,不含冗余)
    @Data
    public static class DimUserNormalized {
        private Long userId;
        private String userName;
        private Long cityId;       // FK -> dim_city (需要 JOIN)
        private Long levelId;      // FK -> dim_level (需要 JOIN)
    }

    // 维度表: 城市 (独立表)
    @Data
    public static class DimCity {
        private Long cityId;
        private String cityName;
        private Long provinceId;   // FK -> dim_province
    }

    /**
     * 雪花模型查询 - 需要更多 JOIN
     */
    public static final String SNOWFLAKE_QUERY =
        "SELECT p.province_name, c.city_name, " +
        "       SUM(f.order_amount) as total_amount " +
        "FROM fact_order f " +
        "JOIN dim_user u ON f.user_id = u.user_id " +
        "JOIN dim_city c ON u.city_id = c.city_id " +
        "JOIN dim_province p ON c.province_id = p.province_id " +
        "GROUP BY p.province_name, c.city_name";
}

/**
 * ============= 星座模型 (Constellation Schema) =============
 * 
 * 多个事实表共享维度表
 * 实际生产中最常用的模型
 * 
 *                     dim_user
 *                    ╱        ╲
 *   dim_time ── fact_order    fact_payment ── dim_payment_method
 *                    ╲        ╱
 *                     dim_shop
 */
public class ConstellationSchemaExample {

    // 事实表1: 订单事实表
    @Data
    public static class FactOrder {
        private Long orderId;
        private Long userId;
        private Long shopId;
        private Long dateKey;
        private BigDecimal orderAmount;
        private Integer orderQuantity;
    }

    // 事实表2: 支付事实表 (共享 dim_user, dim_shop 维度)
    @Data
    public static class FactPayment {
        private Long paymentId;
        private Long orderId;
        private Long userId;          // 共享用户维度
        private Long shopId;          // 共享门店维度
        private Long dateKey;         // 共享时间维度
        private Long payMethodId;     // 独有: 支付方式维度
        private BigDecimal payAmount;
        private Date payTime;
    }

    /**
     * 星座模型查询 - 跨事实表关联分析
     */
    public static final String CONSTELLATION_QUERY =
        "SELECT u.city_name, " +
        "       SUM(o.order_amount) as gmv, " +
        "       SUM(p.pay_amount) as real_pay, " +
        "       SUM(o.order_amount) - SUM(p.pay_amount) as discount " +
        "FROM fact_order o " +
        "JOIN fact_payment p ON o.order_id = p.order_id " +
        "JOIN dim_user u ON o.user_id = u.user_id " +
        "GROUP BY u.city_name";
}
```

#### 3.1.4 Data Vault 模型

```java
/**
 * Data Vault 建模
 * 
 * 三类表:
 * - Hub: 业务实体的唯一标识
 * - Link: 实体之间的关联关系
 * - Satellite: 实体的描述性属性
 * 
 * 优点: 可审计性强,适合频繁变化的数据源
 * 缺点: 表数量多,查询复杂
 */
public class DataVaultModelExample {

    // Hub 表: 存储业务键 (不含属性)
    @Data
    public static class HubUser {
        private String hashKey;        // 哈希主键
        private String businessKey;    // 业务键 (如 user_id)
        private Date loadDate;         // 加载时间
        private String recordSource;   // 数据来源
    }

    @Data
    public static class HubOrder {
        private String hashKey;
        private String businessKey;    // order_id
        private Date loadDate;
        private String recordSource;
    }

    // Link 表: 存储实体间关系
    @Data
    public static class LinkUserOrder {
        private String hashKey;        // 关联哈希键
        private String userHashKey;    // FK -> hub_user
        private String orderHashKey;   // FK -> hub_order
        private Date loadDate;
        private String recordSource;
    }

    // Satellite 表: 存储属性和历史变更
    @Data
    public static class SatUser {
        private String userHashKey;    // FK -> hub_user
        private Date loadDate;         // 版本时间
        private Date endDate;          // 失效时间
        private String userName;
        private String phone;
        private String address;
        private String hashDiff;       // 属性哈希(判断是否变更)
        private String recordSource;
    }

    /**
     * Data Vault ETL 加载逻辑
     */
    public static class DataVaultLoader {

        /**
         * 加载 Hub 表
         * 只插入新的业务键,保证幂等
         */
        public void loadHub(JdbcTemplate jdbc, String hubTable,
                           List<Map<String, Object>> sourceData) {
            String insertSql =
                "INSERT INTO " + hubTable + " (hash_key, business_key, load_date, record_source) " +
                "SELECT ?, ?, ?, ? " +
                "WHERE NOT EXISTS (SELECT 1 FROM " + hubTable + " WHERE hash_key = ?)";

            for (Map<String, Object> row : sourceData) {
                String businessKey = (String) row.get("business_key");
                String hashKey = DigestUtils.md5Hex(businessKey);

                jdbc.update(insertSql,
                    hashKey, businessKey, new Date(), "source_system", hashKey);
            }
        }

        /**
         * 加载 Satellite 表
         * 检测属性变更,只插入有变化的记录
         */
        public void loadSatellite(JdbcTemplate jdbc, String satTable,
                                   List<Map<String, Object>> sourceData) {
            for (Map<String, Object> row : sourceData) {
                String hashKey = (String) row.get("hash_key");
                String newHashDiff = computeHashDiff(row);

                // 查询当前最新版本
                String currentHashDiff = jdbc.queryForObject(
                    "SELECT hash_diff FROM " + satTable +
                    " WHERE hash_key = ? AND end_date IS NULL",
                    String.class, hashKey);

                // 属性有变化才插入新版本
                if (!newHashDiff.equals(currentHashDiff)) {
                    // 关闭旧版本
                    jdbc.update(
                        "UPDATE " + satTable +
                        " SET end_date = ? WHERE hash_key = ? AND end_date IS NULL",
                        new Date(), hashKey);

                    // 插入新版本
                    jdbc.update(
                        "INSERT INTO " + satTable +
                        " (hash_key, load_date, end_date, hash_diff, ...) " +
                        "VALUES (?, ?, NULL, ?, ...)",
                        hashKey, new Date(), newHashDiff);
                }
            }
        }

        private String computeHashDiff(Map<String, Object> row) {
            StringBuilder sb = new StringBuilder();
            row.entrySet().stream()
                .filter(e -> !e.getKey().equals("hash_key"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getValue()));
            return DigestUtils.md5Hex(sb.toString());
        }
    }
}
```

### 3.2 ETL 管道设计

#### 3.2.1 数据抽取层

```java
/**
 * ETL 数据抽取框架
 * 支持多种数据源的统一抽取
 */
public class DataExtractionFramework {

    /**
     * 数据抽取器接口
     */
    public interface DataExtractor {
        DataStream extract(ExtractionConfig config);
        String getSourceType();
    }

    /**
     * MySQL 全量抽取 (基于 JDBC)
     */
    public static class MySQLFullExtractor implements DataExtractor {

        @Override
        public DataStream extract(ExtractionConfig config) {
            String sql = String.format(
                "SELECT * FROM %s WHERE update_time >= ? AND update_time < ?",
                config.getSourceTable());

            List<Map<String, Object>> data = jdbcTemplate.queryForList(
                sql, config.getStartTime(), config.getEndTime());

            return new DataStream(data, config.getSourceTable());
        }

        @Override
        public String getSourceType() { return "MYSQL_FULL"; }
    }

    /**
     * MySQL 增量抽取 (基于 Binlog CDC)
     */
    public static class MySQLCDCExtractor implements DataExtractor {

        @Override
        public DataStream extract(ExtractionConfig config) {
            // 使用 Canal/Debezium 监听 Binlog
            MySqlSource<String> mySqlSource = MySqlSource.<String>builder()
                    .hostname(config.getHost())
                    .port(config.getPort())
                    .databaseList(config.getDatabase())
                    .tableList(config.getSourceTable())
                    .username(config.getUsername())
                    .password(config.getPassword())
                    .deserializer(new JsonDebeziumDeserializationSchema())
                    .build();

            return new DataStream(mySqlSource, config.getSourceTable());
        }

        @Override
        public String getSourceType() { return "MYSQL_CDC"; }
    }

    /**
     * 日志文件抽取 (基于 Flume/FileSource)
     */
    public static class LogFileExtractor implements DataExtractor {

        @Override
        public DataStream extract(ExtractionConfig config) {
            // 按行读取日志文件,解析为结构化数据
            Path logDir = Paths.get(config.getLogDirectory());
            List<Map<String, Object>> parsedData = new ArrayList<>();

            try (Stream<Path> files = Files.list(logDir)
                    .filter(f -> f.toString().endsWith(".log"))
                    .sorted()) {
                files.forEach(file -> {
                    try (BufferedReader reader = Files.newBufferedReader(file)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Map<String, Object> parsed = parseLogLine(line, config.getLogFormat());
                            if (parsed != null) {
                                parsedData.add(parsed);
                            }
                        }
                    } catch (IOException e) {
                        log.error("日志文件读取失败: {}", file, e);
                    }
                });
            } catch (IOException e) {
                throw new ExtractionException("日志目录扫描失败", e);
            }

            return new DataStream(parsedData, "log_" + config.getLogDirectory());
        }

        private Map<String, Object> parseLogLine(String line, String format) {
            // 根据日志格式解析
            try {
                if ("JSON".equals(format)) {
                    return JSON.parseObject(line, new TypeReference<Map<String, Object>>(){});
                } else {
                    // 正则解析
                    return RegexLogParser.parse(line, format);
                }
            } catch (Exception e) {
                log.warn("日志行解析失败: {}", line.substring(0, Math.min(100, line.length())));
                return null;
            }
        }

        @Override
        public String getSourceType() { return "LOG_FILE"; }
    }

    /**
     * 统一抽取调度器
     */
    public static class ExtractionScheduler {

        private final Map<String, DataExtractor> extractors = new HashMap<>();

        public ExtractionScheduler() {
            extractors.put("MYSQL_FULL", new MySQLFullExtractor());
            extractors.put("MYSQL_CDC", new MySQLCDCExtractor());
            extractors.put("LOG_FILE", new LogFileExtractor());
        }

        public DataStream executeExtraction(ExtractionConfig config) {
            DataExtractor extractor = extractors.get(config.getSourceType());
            if (extractor == null) {
                throw new UnsupportedOperationException(
                    "不支持的数据源类型: " + config.getSourceType());
            }

            log.info("开始数据抽取: type={}, source={}, timeRange=[{}, {}]",
                config.getSourceType(), config.getSourceTable(),
                config.getStartTime(), config.getEndTime());

            long start = System.currentTimeMillis();
            DataStream result = extractor.extract(config);
            long elapsed = System.currentTimeMillis() - start;

            log.info("数据抽取完成: source={}, records={}, elapsed={}ms",
                config.getSourceTable(), result.getRecordCount(), elapsed);

            return result;
        }
    }
}
```

#### 3.2.2 数据转换层

```java
/**
 * 数据转换框架
 * 支持清洗、标准化、维度退化、指标计算等操作
 */
public class DataTransformationFramework {

    /**
     * 转换规则接口
     */
    public interface TransformRule {
        Map<String, Object> apply(Map<String, Object> record, TransformContext context);
        int getOrder(); // 执行顺序
    }

    /**
     * 空值处理规则
     */
    public static class NullHandlingRule implements TransformRule {
        private final Map<String, Object> defaultValues;

        public NullHandlingRule(Map<String, Object> defaultValues) {
            this.defaultValues = defaultValues;
        }

        @Override
        public Map<String, Object> apply(Map<String, Object> record, TransformContext context) {
            for (Map.Entry<String, Object> entry : defaultValues.entrySet()) {
                if (record.get(entry.getKey()) == null) {
                    record.put(entry.getKey(), entry.getValue());
                    context.getMetrics().incrementNullFilled(entry.getKey());
                }
            }
            return record;
        }

        @Override
        public int getOrder() { return 10; }
    }

    /**
     * 数据类型转换规则
     */
    public static class TypeConversionRule implements TransformRule {
        private final Map<String, String> typeMapping;

        public TypeConversionRule(Map<String, String> typeMapping) {
            this.typeMapping = typeMapping;
        }

        @Override
        public Map<String, Object> apply(Map<String, Object> record, TransformContext context) {
            for (Map.Entry<String, String> entry : typeMapping.entrySet()) {
                String field = entry.getKey();
                String targetType = entry.getValue();
                Object value = record.get(field);

                if (value != null) {
                    try {
                        Object converted = convertType(value, targetType);
                        record.put(field, converted);
                    } catch (Exception e) {
                        context.getMetrics().incrementConversionError(field);
                        log.warn("类型转换失败: field={}, value={}, targetType={}",
                            field, value, targetType);
                    }
                }
            }
            return record;
        }

        private Object convertType(Object value, String targetType) {
            switch (targetType) {
                case "LONG": return Long.parseLong(value.toString());
                case "DOUBLE": return Double.parseDouble(value.toString());
                case "STRING": return value.toString();
                case "DATE": return new SimpleDateFormat("yyyy-MM-dd").parse(value.toString());
                case "DECIMAL": return new BigDecimal(value.toString());
                default: return value;
            }
        }

        @Override
        public int getOrder() { return 20; }
    }

    /**
     * 维度退化规则 (将常用维度属性冗余到事实表)
     */
    public static class DimensionDegenerationRule implements TransformRule {
        private final DimensionCache dimCache;
        private final List<DegenerationConfig> configs;

        public DimensionDegenerationRule(DimensionCache dimCache,
                                         List<DegenerationConfig> configs) {
            this.dimCache = dimCache;
            this.configs = configs;
        }

        @Override
        public Map<String, Object> apply(Map<String, Object> record, TransformContext context) {
            for (DegenerationConfig config : configs) {
                Object joinKey = record.get(config.getJoinField());
                if (joinKey != null) {
                    Map<String, Object> dimRecord = dimCache.get(
                        config.getDimTable(), joinKey.toString());
                    if (dimRecord != null) {
                        for (String field : config.getDegenerateFields()) {
                            record.put(config.getPrefix() + "_" + field,
                                      dimRecord.get(field));
                        }
                    }
                }
            }
            return record;
        }

        @Override
        public int getOrder() { return 30; }
    }

    /**
     * 转换流水线
     */
    public static class TransformPipeline {
        private final List<TransformRule> rules;

        public TransformPipeline(List<TransformRule> rules) {
            // 按优先级排序
            this.rules = rules.stream()
                    .sorted(Comparator.comparingInt(TransformRule::getOrder))
                    .collect(Collectors.toList());
        }

        public List<Map<String, Object>> execute(List<Map<String, Object>> records) {
            TransformContext context = new TransformContext();
            List<Map<String, Object>> result = new ArrayList<>();

            for (Map<String, Object> record : records) {
                Map<String, Object> transformed = record;
                boolean valid = true;

                for (TransformRule rule : rules) {
                    transformed = rule.apply(transformed, context);
                    if (transformed == null) {
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    result.add(transformed);
                }
            }

            log.info("转换完成: input={}, output={}, filtered={}",
                records.size(), result.size(), records.size() - result.size());

            return result;
        }
    }
}
```

### 3.3 数据治理体系

#### 3.3.1 治理目标与框架

```
┌─────────────────────────────────────────────────────┐
│                数据治理四大目标                        │
│                                                     │
│  ┌───────────┐  ┌───────────┐  ┌──────┐  ┌──────┐ │
│  │ 高质量     │  │ 易使用     │  │ 低成本 │  │ 安全  │ │
│  │ SLA       │  │ 元数据     │  │ 存储   │  │ 分级  │ │
│  │ DQC       │  │ 搜索      │  │ 计算   │  │ 加密  │ │
│  │ 数据校验   │  │ 血缘      │  │ 生命周期│  │ 脱敏  │ │
│  └───────────┘  └───────────┘  └──────┘  └──────┘ │
└─────────────────────────────────────────────────────┘
```

```java
/**
 * 数据治理平台核心组件
 */
public class DataGovernancePlatform {

    /**
     * 元数据管理服务
     * 管理: 表字段、调度信息、热度统计、血缘关系、资源使用
     */
    public static class MetadataService {

        /**
         * 表元数据
         */
        @Data
        @Builder
        public static class TableMetadata {
            private String database;
            private String tableName;
            private String owner;           // 负责人
            private String description;     // 业务描述
            private String storageFormat;   // ORC/Parquet/Text
            private Long totalRows;         // 总行数
            private Long storageSize;       // 存储大小(bytes)
            private Date lastAccessTime;    // 最近访问时间
            private Integer heatScore;      // 热度分(0-100)
            private List<FieldMetadata> fields;
            private ScheduleInfo scheduleInfo;
            private LineageInfo lineageInfo;
        }

        /**
         * 字段级元数据
         */
        @Data
        public static class FieldMetadata {
            private String fieldName;
            private String fieldType;
            private String description;
            private Boolean isPrimaryKey;
            private Boolean isPartitionKey;
            private Double nullRatio;       // 空值比例
            private Long distinctCount;     // 去重数
            private String sampleValues;    // 采样值
        }

        /**
         * 血缘关系查询
         * 支持表级血缘和字段级血缘
         */
        public LineageInfo queryLineage(String tableName, LineageDirection direction) {
            LineageInfo lineage = new LineageInfo();

            if (direction == LineageDirection.UPSTREAM) {
                // 上游血缘: 找到产生该表数据的所有源表
                List<TableDependency> upstreams = lineageDao.findUpstream(tableName);
                lineage.setUpstreamTables(upstreams);
            } else {
                // 下游血缘: 找到消费该表数据的所有目标
                List<TableDependency> downstreams = lineageDao.findDownstream(tableName);
                lineage.setDownstreamTables(downstreams);

                // 下游可能包括 BI 看板
                List<BIDashboard> dashboards = lineageDao.findDependentDashboards(tableName);
                lineage.setDependentDashboards(dashboards);
            }

            return lineage;
        }

        /**
         * 表热度计算
         * 综合访问频率、查询次数、依赖方数量等
         */
        public int calculateHeatScore(String tableName) {
            long queryCount30d = metricsDao.getQueryCount(tableName, 30);
            int downstreamCount = lineageDao.findDownstream(tableName).size();
            int userCount = accessLogDao.getDistinctUserCount(tableName, 30);

            // 加权计算热度分
            double score = queryCount30d * 0.4 + downstreamCount * 0.3 + userCount * 0.3;
            return (int) Math.min(100, Math.max(0, score));
        }
    }

    /**
     * 数据质量检查 (DQC)
     */
    public static class DataQualityChecker {

        /**
         * DQC 规则定义
         */
        @Data
        @Builder
        public static class DQCRule {
            private String ruleId;
            private String tableName;
            private String ruleType;     // NULL_CHECK / UNIQUE_CHECK / RANGE_CHECK / CUSTOM_SQL
            private String ruleExpression;
            private String severity;     // BLOCK / WARN / INFO
            private double threshold;    // 阈值
        }

        /**
         * 执行数据质量检查
         */
        public DQCResult executeCheck(String tableName, String partition) {
            List<DQCRule> rules = ruleDao.findByTable(tableName);
            DQCResult result = new DQCResult(tableName, partition);

            for (DQCRule rule : rules) {
                try {
                    boolean passed = evaluateRule(rule, tableName, partition);
                    result.addRuleResult(rule.getRuleId(), passed,
                        passed ? "通过" : "不通过");

                    if (!passed && "BLOCK".equals(rule.getSeverity())) {
                        result.setBlocked(true);
                        log.error("DQC 阻断规则不通过: table={}, rule={}, partition={}",
                            tableName, rule.getRuleId(), partition);
                    }
                } catch (Exception e) {
                    result.addRuleResult(rule.getRuleId(), false,
                        "执行异常: " + e.getMessage());
                }
            }

            return result;
        }

        private boolean evaluateRule(DQCRule rule, String tableName, String partition) {
            switch (rule.getRuleType()) {
                case "NULL_CHECK":
                    double nullRatio = hiveClient.calculateNullRatio(
                        tableName, partition, rule.getRuleExpression());
                    return nullRatio <= rule.getThreshold();

                case "UNIQUE_CHECK":
                    long duplicateCount = hiveClient.countDuplicates(
                        tableName, partition, rule.getRuleExpression());
                    return duplicateCount == 0;

                case "RANGE_CHECK":
                    // 检查数据量是否在正常范围内 (基于历史同期)
                    long currentCount = hiveClient.countRows(tableName, partition);
                    long historyAvg = historyDao.getAvgCount(tableName, 7);
                    double ratio = (double) currentCount / historyAvg;
                    return ratio >= (1 - rule.getThreshold())
                        && ratio <= (1 + rule.getThreshold());

                case "CUSTOM_SQL":
                    long result = hiveClient.executeCountSql(
                        rule.getRuleExpression()
                            .replace("${TABLE}", tableName)
                            .replace("${PARTITION}", partition));
                    return result <= rule.getThreshold();

                default:
                    throw new IllegalArgumentException("未知规则类型: " + rule.getRuleType());
            }
        }
    }

    /**
     * SLA 管理
     */
    public static class SLAManager {

        /**
         * 检查任务 SLA
         */
        public SLAStatus checkTaskSLA(String taskId) {
            TaskInfo task = taskDao.findById(taskId);
            if (task == null) {
                return SLAStatus.unknown(taskId);
            }

            // 检查任务是否按时完成
            Date expectedTime = task.getSlaDeadline();
            Date actualTime = task.getActualFinishTime();

            if (actualTime == null) {
                // 任务尚未完成
                if (new Date().after(expectedTime)) {
                    return SLAStatus.breached(taskId,
                        "任务超时未完成,SLA期望: " + expectedTime);
                }
                return SLAStatus.running(taskId);
            }

            if (actualTime.after(expectedTime)) {
                long delayMinutes = (actualTime.getTime() - expectedTime.getTime()) / 60000;
                return SLAStatus.breached(taskId,
                    "任务延迟 " + delayMinutes + " 分钟完成");
            }

            return SLAStatus.met(taskId);
        }
    }
}
```

### 3.4 OLAP 引擎设计

#### 3.4.1 Doris 实时 OLAP 引擎

```java
/**
 * Doris 实时 OLAP 引擎集成
 * 
 * Doris 数据模型:
 * - Unique Model: 主键去重,保留最新值 (适合业务数据变更场景)
 * - Aggregate Model: 预聚合,按维度自动 rollup (适合指标聚合场景)
 * - Duplicate Model: 不去重,全量存储 (适合明细查询场景)
 */
public class DorisOLAPEngine {

    /**
     * Unique Model - 适合有状态变更的业务数据
     * 订单状态变更场景: 同一订单多次写入,自动保留最新版本
     */
    public static class UniqueModelSetup {

        public static final String CREATE_UNIQUE_TABLE =
            "CREATE TABLE IF NOT EXISTS order_detail (" +
            "    order_id BIGINT," +
            "    order_time DATETIME," +
            "    user_id BIGINT," +
            "    city_id INT," +
            "    order_status INT," +
            "    amount DECIMAL(10, 2)," +
            "    pay_amount DECIMAL(10, 2)," +
            "    update_time DATETIME" +
            ") " +
            "UNIQUE KEY(order_id) " +
            "DISTRIBUTED BY HASH(order_id) BUCKETS 16 " +
            "PROPERTIES (" +
            "    'replication_num' = '3'," +
            "    'enable_unique_key_merge_on_write' = 'true'" +  // 写时合并
            ")";
    }

    /**
     * Aggregate Model - 适合指标预聚合场景
     * 按维度自动聚合,减少存储和查询开销
     */
    public static class AggregateModelSetup {

        public static final String CREATE_AGG_TABLE =
            "CREATE TABLE IF NOT EXISTS order_agg_metrics (" +
            "    date_key DATE," +
            "    city_id INT," +
            "    category_id INT," +
            "    order_count BIGINT SUM," +          // 自动求和
            "    total_amount DECIMAL(18, 2) SUM," +  // 自动求和
            "    max_amount DECIMAL(10, 2) MAX," +    // 自动取最大
            "    min_amount DECIMAL(10, 2) MIN," +    // 自动取最小
            "    user_bitmap BITMAP BITMAP_UNION" +    // 去重计数
            ") " +
            "AGGREGATE KEY(date_key, city_id, category_id) " +
            "DISTRIBUTED BY HASH(date_key, city_id) BUCKETS 8 " +
            "PROPERTIES ('replication_num' = '3')";
    }

    /**
     * 实时数据写入 Doris
     */
    public static class DorisStreamWriter {

        /**
         * 通过 Stream Load 实时写入
         */
        public void streamLoad(String tableName, List<Map<String, Object>> data) {
            String url = String.format("http://%s:%d/api/%s/%s/_stream_load",
                dorisHost, dorisPort, database, tableName);

            // 将数据转为 JSON 格式
            String jsonData = JSON.toJSONString(data);

            HttpPut put = new HttpPut(url);
            put.setHeader("Authorization", getAuthHeader());
            put.setHeader("format", "json");
            put.setHeader("strip_outer_array", "true");
            put.setHeader("max_filter_ratio", "0.1"); // 允许10%错误率
            put.setEntity(new StringEntity(jsonData, "UTF-8"));

            try (CloseableHttpResponse response = httpClient.execute(put)) {
                String result = EntityUtils.toString(response.getEntity());
                StreamLoadResult loadResult = JSON.parseObject(result, StreamLoadResult.class);

                if (!"Success".equals(loadResult.getStatus())) {
                    log.error("Doris Stream Load 失败: table={}, msg={}",
                        tableName, loadResult.getMessage());
                    throw new DorisLoadException(loadResult.getMessage());
                }

                log.info("Doris Stream Load 成功: table={}, loaded={}, filtered={}",
                    tableName, loadResult.getNumberLoadedRows(),
                    loadResult.getNumberFilteredRows());
            } catch (IOException e) {
                throw new DorisLoadException("HTTP请求失败", e);
            }
        }

        /**
         * Flink-Doris-Connector 写入
         */
        public static void flinkToDoris(DataStream<MetricResult> stream) {
            DorisSink.Builder<MetricResult> builder = DorisSink.builder();
            DorisOptions dorisOptions = DorisOptions.builder()
                    .setFenodes("doris-fe:8030")
                    .setTableIdentifier("analytics.order_agg_metrics")
                    .setUsername("root")
                    .setPassword("")
                    .build();

            DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
                    .setLabelPrefix("flink_load_" + System.currentTimeMillis())
                    .setStreamLoadProp(new Properties() {{
                        setProperty("format", "json");
                        setProperty("read_json_by_line", "true");
                    }})
                    .build();

            builder.setDorisReadOptions(DorisReadOptions.builder().build())
                   .setDorisOptions(dorisOptions)
                   .setDorisExecutionOptions(executionOptions)
                   .setSerializer(new JsonDebeziumSchemaSerializer(dorisOptions, null));

            stream.sinkTo(builder.build());
        }
    }
}
```

### 3.5 实时数仓设计

```java
/**
 * 实时数仓核心设计
 * 基于 Kafka + Flink 构建实时数据链路
 */
public class RealTimeDataWarehouse {

    /**
     * 实时数仓分层
     * ODS -> DWD (实时明细) -> DWS (实时聚合) -> ADS (应用服务)
     * 
     * 关键原则: 层数尽量精简,每增加一层都会增加端到端延迟
     */

    /**
     * 实时去重处理
     * 流式场景下的去重比批处理更复杂
     */
    public static class StreamDeduplication {

        /**
         * 基于 Flink State 的精确去重
         */
        public static class StateBasedDedup
                extends KeyedProcessFunction<String, OrderEvent, OrderEvent> {

            private ValueState<Boolean> seenState;

            @Override
            public void open(Configuration parameters) {
                ValueStateDescriptor<Boolean> descriptor =
                    new ValueStateDescriptor<>("seen", Boolean.class);
                // 设置 24 小时 TTL
                StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(Time.hours(24))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .build();
                descriptor.enableTimeToLive(ttlConfig);
                seenState = getRuntimeContext().getState(descriptor);
            }

            @Override
            public void processElement(OrderEvent event, Context ctx,
                                        Collector<OrderEvent> out) throws Exception {
                Boolean seen = seenState.value();
                if (seen == null || !seen) {
                    seenState.update(true);
                    out.collect(event);
                }
            }
        }

        /**
         * 基于布隆过滤器的近似去重 (大数据量场景)
         */
        public static class BloomFilterDedup
                extends KeyedProcessFunction<String, OrderEvent, OrderEvent> {

            private transient BloomFilter<String> bloomFilter;
            private static final int EXPECTED_INSERTIONS = 10_000_000;
            private static final double FPP = 0.001; // 0.1% 误判率

            @Override
            public void open(Configuration parameters) {
                bloomFilter = BloomFilter.create(
                    Funnels.stringFunnel(Charset.defaultCharset()),
                    EXPECTED_INSERTIONS, FPP);
            }

            @Override
            public void processElement(OrderEvent event, Context ctx,
                                        Collector<OrderEvent> out) throws Exception {
                String dedupKey = event.getOrderId() + "_" + event.getEventType();
                if (!bloomFilter.mightContain(dedupKey)) {
                    bloomFilter.put(dedupKey);
                    out.collect(event);
                }
            }
        }
    }

    /**
     * 实时维表关联
     * 流式数据关联维度信息
     */
    public static class StreamDimJoin {

        /**
         * 异步 IO 维表查询
         * 异步查询 + 本地缓存,降低延迟
         */
        public static class AsyncDimJoinFunction
                extends RichAsyncFunction<OrderEvent, EnrichedOrderEvent> {

            private transient AsyncDimService dimService;
            private transient Cache<String, DimInfo> localCache;

            @Override
            public void open(Configuration parameters) {
                dimService = new AsyncDimService();
                localCache = CacheBuilder.newBuilder()
                    .maximumSize(10000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            }

            @Override
            public void asyncInvoke(OrderEvent event,
                                     ResultFuture<EnrichedOrderEvent> resultFuture) {
                // 先查本地缓存
                DimInfo cached = localCache.getIfPresent(event.getUserId());
                if (cached != null) {
                    EnrichedOrderEvent enriched = enrichEvent(event, cached);
                    resultFuture.complete(Collections.singletonList(enriched));
                    return;
                }

                // 缓存未命中,异步查询维表
                CompletableFuture<DimInfo> future = dimService.queryAsync(event.getUserId());
                future.thenAccept(dimInfo -> {
                    localCache.put(event.getUserId(), dimInfo);
                    EnrichedOrderEvent enriched = enrichEvent(event, dimInfo);
                    resultFuture.complete(Collections.singletonList(enriched));
                }).exceptionally(throwable -> {
                    log.error("维表查询失败: userId={}", event.getUserId(), throwable);
                    // 降级: 使用空维度信息
                    EnrichedOrderEvent enriched = enrichEvent(event, DimInfo.empty());
                    resultFuture.complete(Collections.singletonList(enriched));
                    return null;
                });
            }

            @Override
            public void timeout(OrderEvent event,
                                ResultFuture<EnrichedOrderEvent> resultFuture) {
                // 超时降级
                EnrichedOrderEvent enriched = enrichEvent(event, DimInfo.empty());
                resultFuture.complete(Collections.singletonList(enriched));
            }

            private EnrichedOrderEvent enrichEvent(OrderEvent event, DimInfo dimInfo) {
                return EnrichedOrderEvent.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .amount(event.getAmount())
                    .eventTime(event.getEventTime())
                    .userName(dimInfo.getUserName())
                    .cityName(dimInfo.getCityName())
                    .userLevel(dimInfo.getUserLevel())
                    .build();
            }
        }
    }
}
```

### 3.6 拉链表设计

```java
/**
 * 拉链表 (Slowly Changing Dimension Type 2)
 * 完整记录维度数据的历史变化
 */
public class ZipperTableDesign {

    /**
     * 拉链表结构
     */
    @Data
    public static class ZipperRecord {
        private Long userId;
        private String userName;
        private String phone;
        private String address;
        private String userLevel;
        private Date startDate;      // 生效开始日期
        private Date endDate;        // 生效结束日期 (9999-12-31 表示当前有效)
        private Boolean isCurrent;   // 是否当前有效记录
    }

    /**
     * 拉链表更新逻辑
     * 每日增量更新拉链表
     */
    public static class ZipperTableUpdater {

        private static final Date MAX_DATE;
        static {
            try {
                MAX_DATE = new SimpleDateFormat("yyyy-MM-dd").parse("9999-12-31");
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * 拉链表日度更新 SQL (Hive)
         */
        public static final String ZIPPER_UPDATE_SQL =
            "-- Step 1: 合并当日变更数据和历史拉链表\n" +
            "INSERT OVERWRITE TABLE dwd_user_zipper\n" +
            "SELECT * FROM (\n" +
            "    -- 当日变更数据: 作为新版本记录\n" +
            "    SELECT user_id, user_name, phone, address, user_level,\n" +
            "           '${today}' as start_date,\n" +
            "           '9999-12-31' as end_date,\n" +
            "           true as is_current\n" +
            "    FROM ods_user_delta\n" +
            "    WHERE dt = '${today}'\n" +
            "    \n" +
            "    UNION ALL\n" +
            "    \n" +
            "    -- 历史数据: 如果有变更则关闭旧版本\n" +
            "    SELECT z.user_id, z.user_name, z.phone, z.address, z.user_level,\n" +
            "           z.start_date,\n" +
            "           CASE\n" +
            "               WHEN d.user_id IS NOT NULL AND z.end_date = '9999-12-31'\n" +
            "               THEN '${yesterday}'\n" +
            "               ELSE z.end_date\n" +
            "           END as end_date,\n" +
            "           CASE\n" +
            "               WHEN d.user_id IS NOT NULL AND z.end_date = '9999-12-31'\n" +
            "               THEN false\n" +
            "               ELSE z.is_current\n" +
            "           END as is_current\n" +
            "    FROM dwd_user_zipper z\n" +
            "    LEFT JOIN ods_user_delta d\n" +
            "    ON z.user_id = d.user_id AND d.dt = '${today}'\n" +
            ") t";

        /**
         * Java 代码实现拉链表更新
         */
        public void updateZipperTable(JdbcTemplate jdbc, String today) {
            String yesterday = LocalDate.parse(today).minusDays(1).toString();

            // Step 1: 获取当日变更数据
            List<Map<String, Object>> deltaData = jdbc.queryForList(
                "SELECT * FROM ods_user_delta WHERE dt = ?", today);

            // Step 2: 关闭旧版本记录
            for (Map<String, Object> delta : deltaData) {
                Long userId = (Long) delta.get("user_id");
                jdbc.update(
                    "UPDATE dwd_user_zipper SET end_date = ?, is_current = false " +
                    "WHERE user_id = ? AND end_date = '9999-12-31'",
                    yesterday, userId);
            }

            // Step 3: 插入新版本记录
            String insertSql =
                "INSERT INTO dwd_user_zipper (user_id, user_name, phone, address, " +
                "user_level, start_date, end_date, is_current) " +
                "VALUES (?, ?, ?, ?, ?, ?, '9999-12-31', true)";

            jdbc.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Map<String, Object> row = deltaData.get(i);
                    ps.setLong(1, (Long) row.get("user_id"));
                    ps.setString(2, (String) row.get("user_name"));
                    ps.setString(3, (String) row.get("phone"));
                    ps.setString(4, (String) row.get("address"));
                    ps.setString(5, (String) row.get("user_level"));
                    ps.setString(6, today);
                }

                @Override
                public int getBatchSize() { return deltaData.size(); }
            });
        }
    }
}
```

---

## 四、异常处理

### 4.1 ETL 异常处理

```java
/**
 * ETL 异常处理框架
 */
public class ETLExceptionHandler {

    /**
     * 分级异常处理策略
     */
    public enum ExceptionLevel {
        RECORD_LEVEL,   // 记录级: 跳过问题记录,继续处理
        BATCH_LEVEL,    // 批次级: 当前批次重试,超限报警
        TASK_LEVEL      // 任务级: 任务失败,触发告警和降级
    }

    /**
     * 异常处理器
     */
    public void handleException(ETLContext context, Exception e, ExceptionLevel level) {
        switch (level) {
            case RECORD_LEVEL:
                // 记录级异常: 写入死信队列,继续处理
                deadLetterQueue.send(context.getCurrentRecord(), e.getMessage());
                context.getMetrics().incrementErrorCount();
                log.warn("记录处理异常(已跳过): record={}, error={}",
                    context.getCurrentRecordId(), e.getMessage());
                break;

            case BATCH_LEVEL:
                // 批次级异常: 重试 3 次
                int retryCount = context.getRetryCount();
                if (retryCount < 3) {
                    context.incrementRetry();
                    log.warn("批次处理异常(第{}次重试): batch={}, error={}",
                        retryCount + 1, context.getBatchId(), e.getMessage());
                    // 指数退避重试
                    sleepWithBackoff(retryCount);
                    retryBatch(context);
                } else {
                    // 重试耗尽,升级为任务级异常
                    handleException(context, e, ExceptionLevel.TASK_LEVEL);
                }
                break;

            case TASK_LEVEL:
                // 任务级异常: 标记失败,触发告警
                context.setStatus(TaskStatus.FAILED);
                alertService.sendAlert(
                    AlertLevel.P1,
                    "ETL任务失败: " + context.getTaskId(),
                    "错误信息: " + e.getMessage() + "\n" +
                    "已处理记录数: " + context.getProcessedCount() + "\n" +
                    "错误记录数: " + context.getErrorCount()
                );
                log.error("ETL任务失败: task={}", context.getTaskId(), e);
                break;
        }
    }

    private void sleepWithBackoff(int retryCount) {
        try {
            Thread.sleep((long) Math.pow(2, retryCount) * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 4.2 数据倾斜异常处理

```java
/**
 * 数据倾斜诊断与处理
 */
public class DataSkewHandler {

    /**
     * 诊断数据倾斜
     */
    public SkewDiagnosis diagnoseSkew(String tableName, String keyColumn) {
        // 查询 Key 分布
        String sql = String.format(
            "SELECT %s, COUNT(*) as cnt FROM %s " +
            "GROUP BY %s ORDER BY cnt DESC LIMIT 20",
            keyColumn, tableName, keyColumn);

        List<Map<String, Object>> distribution = hiveClient.query(sql);

        long maxCount = (Long) distribution.get(0).get("cnt");
        long minCount = (Long) distribution.get(distribution.size() - 1).get("cnt");
        double skewRatio = (double) maxCount / Math.max(minCount, 1);

        return SkewDiagnosis.builder()
            .isSkewed(skewRatio > 10) // 倾斜比超过10倍
            .skewRatio(skewRatio)
            .hotKeys(distribution.subList(0, Math.min(5, distribution.size())))
            .build();
    }

    /**
     * Map Join 解决倾斜 (小表广播)
     */
    public static final String MAP_JOIN_HINT =
        "/*+ MAPJOIN(dim_table) */ " +
        "SELECT f.*, d.dim_name " +
        "FROM fact_table f " +
        "JOIN dim_table d ON f.dim_id = d.dim_id";

    /**
     * 两阶段聚合解决倾斜
     */
    public static final String TWO_STAGE_AGG_SQL =
        "-- 第一阶段: 加随机前缀打散\n" +
        "SELECT CONCAT(category, '_', CAST(FLOOR(RAND() * 10) AS STRING)) as category_rand,\n" +
        "       SUM(amount) as partial_sum,\n" +
        "       COUNT(*) as partial_count\n" +
        "FROM order_table\n" +
        "GROUP BY CONCAT(category, '_', CAST(FLOOR(RAND() * 10) AS STRING))\n" +
        "\n" +
        "-- 第二阶段: 去掉随机前缀,合并结果\n" +
        "SELECT SUBSTR(category_rand, 1, INSTR(category_rand, '_') - 1) as category,\n" +
        "       SUM(partial_sum) as total_sum,\n" +
        "       SUM(partial_count) as total_count\n" +
        "FROM stage1_result\n" +
        "GROUP BY SUBSTR(category_rand, 1, INSTR(category_rand, '_') - 1)";
}
```

---

## 五、性能优化

### 5.1 存储优化

```java
/**
 * 存储格式与压缩优化
 */
public class StorageOptimization {

    /**
     * ORC 列存储优化建议
     */
    public static final Map<String, String> ORC_OPTIMAL_CONFIG = Map.of(
        "orc.compress", "ZSTD",               // 压缩算法: ZSTD 压缩比高
        "orc.compress.size", "262144",         // 压缩块大小: 256KB
        "orc.stripe.size", "67108864",         // Stripe 大小: 64MB
        "orc.row.index.stride", "10000",       // 索引步长: 10000行
        "orc.bloom.filter.columns", "user_id,order_id",  // 布隆过滤器列
        "orc.bloom.filter.fpp", "0.05"         // 布隆过滤器误判率: 5%
    );

    /**
     * 分区策略优化
     */
    public static class PartitionStrategy {

        /**
         * 建表时设置合理的分区
         */
        public static final String CREATE_PARTITIONED_TABLE =
            "CREATE TABLE IF NOT EXISTS dwd_order_detail (" +
            "    order_id BIGINT," +
            "    user_id BIGINT," +
            "    amount DECIMAL(10, 2)," +
            "    category STRING," +
            "    city STRING" +
            ") " +
            "PARTITIONED BY (dt STRING, hour STRING) " + // 日期 + 小时分区
            "STORED AS ORC " +
            "TBLPROPERTIES ('orc.compress' = 'ZSTD')";

        /**
         * 分区裁剪: 查询时指定分区,避免全表扫描
         */
        public static final String PARTITION_PRUNING_QUERY =
            "SELECT category, SUM(amount) as total " +
            "FROM dwd_order_detail " +
            "WHERE dt = '2024-01-15' AND hour BETWEEN '09' AND '18' " + // 分区裁剪
            "GROUP BY category";
    }
}
```

### 5.2 查询优化

```java
/**
 * 查询性能优化
 */
public class QueryOptimization {

    /**
     * 物化视图优化 - 预计算常用聚合
     */
    public static final String CREATE_MATERIALIZED_VIEW =
        "CREATE MATERIALIZED VIEW IF NOT EXISTS mv_daily_category_summary AS " +
        "SELECT dt, category, " +
        "       COUNT(*) as order_count, " +
        "       SUM(amount) as total_amount, " +
        "       AVG(amount) as avg_amount " +
        "FROM dwd_order_detail " +
        "GROUP BY dt, category";

    /**
     * 谓词下推优化
     * 将过滤条件尽可能下推到存储层
     */
    public static class PredicatePushdownOptimizer {

        public String optimizeQuery(String originalSql) {
            // 分析 WHERE 条件,尽可能下推到子查询
            // 示例: 将外层过滤条件下推到 JOIN 的子查询中
            String optimized = originalSql
                .replace(
                    "SELECT * FROM (SELECT ... FROM big_table) t WHERE t.dt = '2024-01-15'",
                    "SELECT * FROM (SELECT ... FROM big_table WHERE dt = '2024-01-15') t"
                );
            return optimized;
        }
    }

    /**
     * YARN 队列管理
     * 合理分配计算资源,避免资源争抢
     */
    public static class YarnQueueConfig {

        public static final Map<String, QueueConfig> QUEUE_CONFIGS = Map.of(
            "etl_daily",     new QueueConfig(0.3, 100, "T+1 日常ETL"),
            "etl_hourly",    new QueueConfig(0.2, 50,  "小时级ETL"),
            "adhoc_query",   new QueueConfig(0.2, 30,  "临时查询"),
            "realtime",      new QueueConfig(0.2, 80,  "实时计算"),
            "ml_training",   new QueueConfig(0.1, 20,  "机器学习训练")
        );

        @Data
        @AllArgsConstructor
        public static class QueueConfig {
            private double capacityFraction;
            private int maxConcurrency;
            private String description;
        }
    }
}
```

### 5.3 OOM 处理与内存优化

```java
/**
 * Hive/Spark 任务 OOM 诊断与优化
 */
public class OOMDiagnostics {

    /**
     * OOM 常见场景与解决方案
     */
    public enum OOMScenario {
        MAP_SIDE_OOM("Map端OOM", "单个Map处理数据量过大",
            "SET mapreduce.input.fileinputformat.split.maxsize=67108864;"), // 64MB
        REDUCE_SIDE_OOM("Reduce端OOM", "单个Reduce接收数据过多",
            "SET mapreduce.reduce.memory.mb=4096; SET mapreduce.reduce.java.opts=-Xmx3276m;"),
        DRIVER_OOM("Driver端OOM", "collect/broadcast数据量过大",
            "避免collect大数据集; 使用broadcast变量时注意大小限制"),
        SHUFFLE_OOM("Shuffle阶段OOM", "中间数据膨胀",
            "SET spark.shuffle.spill.compress=true; SET spark.memory.fraction=0.8;");

        private final String name;
        private final String cause;
        private final String solution;

        OOMScenario(String name, String cause, String solution) {
            this.name = name;
            this.cause = cause;
            this.solution = solution;
        }
    }

    /**
     * 自动化 OOM 诊断
     */
    public OOMDiagnosis diagnose(String taskId) {
        TaskLog log = logService.getTaskLog(taskId);

        // 解析错误日志
        if (log.contains("java.lang.OutOfMemoryError: Java heap space")) {
            return analyzeHeapOOM(log);
        }
        if (log.contains("Container killed by YARN for exceeding memory limits")) {
            return analyzeContainerOOM(log);
        }
        if (log.contains("GC overhead limit exceeded")) {
            return analyzeGCOverhead(log);
        }

        return OOMDiagnosis.unknown(taskId);
    }

    private OOMDiagnosis analyzeHeapOOM(TaskLog log) {
        // 分析内存使用模式
        long peakMemory = log.getPeakMemoryUsage();
        long configuredMemory = log.getConfiguredHeapSize();

        if (peakMemory > configuredMemory * 0.9) {
            return OOMDiagnosis.builder()
                .scenario(OOMScenario.MAP_SIDE_OOM)
                .suggestion("增大 Heap 内存: -Xmx" + (configuredMemory * 2 / 1024 / 1024) + "m")
                .build();
        }

        return OOMDiagnosis.builder()
            .scenario(OOMScenario.SHUFFLE_OOM)
            .suggestion("检查数据倾斜或中间结果膨胀")
            .build();
    }
}
```

---

## 六、最佳实践

### 6.1 建模规范

| 原则 | 说明 | 示例 |
|------|------|------|
| 一致性维度 | 相同维度在所有事实表中定义一致 | dim_user 表结构统一 |
| 总线架构 | 基于企业数据总线定义维度和事实 | 统一的维度矩阵 |
| 缓慢变化维度 | 明确 SCD 处理策略(Type1/2/3) | 用户地址变更用 Type2 拉链 |
| 事实表粒度 | 明确定义事实表的最细粒度 | 一行 = 一个订单项 |
| 无事实事实表 | 记录事件发生,不含度量值 | 用户浏览记录(只记录发生) |

### 6.2 命名规范

```
数据库层级命名:
  ods_[源系统]_[表名]           -> ods_trade_order
  dwd_[业务域]_[主题]_[描述]_df  -> dwd_trade_order_detail_df (日全量)
  dwd_[业务域]_[主题]_[描述]_di  -> dwd_trade_order_detail_di (日增量)
  dws_[业务域]_[主题]_[描述]_td  -> dws_trade_order_summary_td (历史累计)
  dm_[业务域]_[主题]_[描述]      -> dm_trade_daily_report
  app_[应用]_[描述]             -> app_dashboard_order_stats

字段命名:
  主键:      [表名简写]_id    -> order_id, user_id
  外键:      [维度表]_id     -> city_id, product_id
  金额:      [描述]_amount   -> order_amount, pay_amount
  数量:      [描述]_count    -> order_count, user_count
  时间:      [描述]_time     -> create_time, update_time
  日期分区:   dt              -> 统一用 dt (格式: yyyy-MM-dd)
```

### 6.3 上线 Checklist

1. **建模评审**：数据模型经过 DBA 和数仓架构师评审
2. **数据质量**：DQC 规则配置完整（空值、唯一性、范围检查）
3. **SLA 配置**：任务 SLA 时间配置合理，告警规则生效
4. **血缘关系**：血缘图谱更新，上下游依赖明确
5. **存储优化**：使用 ORC/Parquet 列存，分区策略合理
6. **倾斜处理**：热点 Key 识别并有应对方案
7. **监控告警**：数据量波动、延迟、失败告警配置
8. **权限管理**：敏感表/字段权限申请完成
9. **元数据维护**：表和字段的业务描述完整
10. **回滚方案**：数据异常时可快速回滚到上一版本

---

## 七、全链路实战案例

### 7.1 案例一：离线数据 ETL 全链路

本案例演示一个完整的离线 ETL 流程：从业务数据源抽取订单数据，经过数据清洗、维度建模，分层加载到 ODS/DWD/DWS/ADS 各层，最终执行数据质量校验。

```
┌─────────────────────────────────────────────────────────────────────┐
│                   离线数据 ETL 全链路流程                              │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │ 数据源抽取 │───▶│ 数据清洗  │───▶│ 维度建模  │───▶│ 分层加载      │  │
│  │ MySQL/日志│    │ 去重/脱敏 │    │ 星型模型  │    │ ODS→DWD→DWS  │  │
│  └──────────┘    └──────────┘    └──────────┘    │ →ADS          │  │
│                                                   └───────┬──────┘  │
│                                                           │         │
│                                                           ▼         │
│                                                   ┌──────────────┐  │
│                                                   │ 数据质量校验   │  │
│                                                   │ DQC Rules     │  │
│                                                   └──────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

#### 7.1.1 建表 SQL（各层表结构）

```sql
-- ============================================================
-- ODS 层: 操作数据层 - 原始数据落地
-- ============================================================
CREATE TABLE IF NOT EXISTS ods_trade_order (
    order_id         BIGINT       COMMENT '订单ID',
    user_id          BIGINT       COMMENT '用户ID',
    shop_id          BIGINT       COMMENT '门店ID',
    product_id       BIGINT       COMMENT '商品ID',
    order_amount     DECIMAL(18,2) COMMENT '订单金额',
    discount_amount  DECIMAL(18,2) COMMENT '优惠金额',
    pay_amount       DECIMAL(18,2) COMMENT '实付金额',
    order_status     INT          COMMENT '订单状态: 1-已创建 2-已支付 3-已完成 4-已取消',
    pay_type         INT          COMMENT '支付方式: 1-微信 2-支付宝 3-银行卡',
    create_time      STRING       COMMENT '创建时间',
    update_time      STRING       COMMENT '更新时间',
    raw_data         STRING       COMMENT '原始JSON(保留原始字段便于回溯)',
    etl_load_time    TIMESTAMP    COMMENT 'ETL加载时间',
    etl_batch_id     STRING       COMMENT 'ETL批次号'
)
COMMENT 'ODS-交易订单原始表'
PARTITIONED BY (dt STRING COMMENT '数据日期分区')
STORED AS ORC
TBLPROPERTIES ('orc.compress' = 'SNAPPY');

-- ============================================================
-- DWD 层: 明细数据层 - 维度退化、事实标准化
-- ============================================================
CREATE TABLE IF NOT EXISTS dwd_trade_order_detail_df (
    order_id         BIGINT       COMMENT '订单ID',
    user_id          BIGINT       COMMENT '用户ID',
    user_name        STRING       COMMENT '用户名(维度退化)',
    user_level       STRING       COMMENT '用户等级(维度退化)',
    city_id          BIGINT       COMMENT '城市ID',
    city_name        STRING       COMMENT '城市名(维度退化)',
    province_name    STRING       COMMENT '省份名(维度退化)',
    shop_id          BIGINT       COMMENT '门店ID',
    shop_name        STRING       COMMENT '门店名(维度退化)',
    category_level1  STRING       COMMENT '一级类目(维度退化)',
    product_id       BIGINT       COMMENT '商品ID',
    product_name     STRING       COMMENT '商品名(维度退化)',
    order_amount     DECIMAL(18,2) COMMENT '订单金额',
    discount_amount  DECIMAL(18,2) COMMENT '优惠金额',
    pay_amount       DECIMAL(18,2) COMMENT '实付金额',
    order_status     INT          COMMENT '订单状态',
    order_status_name STRING      COMMENT '订单状态名(码表转换)',
    pay_type         INT          COMMENT '支付方式',
    pay_type_name    STRING       COMMENT '支付方式名(码表转换)',
    create_time      TIMESTAMP    COMMENT '创建时间(标准化)',
    update_time      TIMESTAMP    COMMENT '更新时间(标准化)',
    is_valid         INT          COMMENT '是否有效(过滤测试数据): 1-有效 0-无效'
)
COMMENT 'DWD-交易订单明细日全量表'
PARTITIONED BY (dt STRING COMMENT '数据日期分区')
STORED AS ORC
TBLPROPERTIES ('orc.compress' = 'SNAPPY');

-- ============================================================
-- DWS 层: 汇总数据层 - 轻度聚合、公共指标
-- ============================================================
CREATE TABLE IF NOT EXISTS dws_trade_shop_daily_summary (
    shop_id          BIGINT       COMMENT '门店ID',
    shop_name        STRING       COMMENT '门店名',
    city_name        STRING       COMMENT '城市名',
    province_name    STRING       COMMENT '省份名',
    category_level1  STRING       COMMENT '一级类目',
    order_count      BIGINT       COMMENT '订单数',
    pay_user_count   BIGINT       COMMENT '支付用户数(去重)',
    gmv              DECIMAL(18,2) COMMENT 'GMV(总交易额)',
    discount_total   DECIMAL(18,2) COMMENT '优惠总额',
    pay_total        DECIMAL(18,2) COMMENT '实付总额',
    avg_order_amount DECIMAL(18,2) COMMENT '客单价',
    cancel_count     BIGINT       COMMENT '取消订单数',
    cancel_rate      DECIMAL(5,4) COMMENT '取消率'
)
COMMENT 'DWS-门店维度交易日汇总表'
PARTITIONED BY (dt STRING COMMENT '数据日期分区')
STORED AS ORC
TBLPROPERTIES ('orc.compress' = 'SNAPPY');

-- ============================================================
-- ADS 层: 应用数据层 - 面向报表和API的最终聚合
-- ============================================================
CREATE TABLE IF NOT EXISTS ads_trade_city_report (
    city_name        STRING       COMMENT '城市名',
    province_name    STRING       COMMENT '省份名',
    total_gmv        DECIMAL(18,2) COMMENT '城市GMV',
    total_orders     BIGINT       COMMENT '订单总数',
    total_users      BIGINT       COMMENT '支付用户数',
    avg_order_amount DECIMAL(18,2) COMMENT '城市客单价',
    gmv_rank         INT          COMMENT 'GMV排名',
    gmv_mom_rate     DECIMAL(5,4) COMMENT 'GMV月环比增长率',
    report_date      STRING       COMMENT '报表日期'
)
COMMENT 'ADS-城市维度交易日报'
PARTITIONED BY (dt STRING COMMENT '数据日期分区')
STORED AS ORC;
```

#### 7.1.2 ETL 全链路 Java 实现

```java
/**
 * 离线数据 ETL 全链路实现
 *
 * 完整流程: 数据源抽取 -> 数据清洗 -> 维度建模 -> 分层加载 -> 数据质量校验
 * 
 * 设计要点:
 * - 每个阶段幂等可重跑(基于分区覆盖写入)
 * - 异常分级处理(记录级/批次级/任务级)
 * - 全链路日志追踪(统一 batchId)
 * - DQC 数据质量校验(阻断 + 告警)
 */
@Slf4j
public class OfflineETLPipeline {

    private final JdbcTemplate sourceJdbc;         // 业务数据源
    private final HiveClient hiveClient;           // Hive 客户端
    private final AlertService alertService;       // 告警服务
    private final IdempotentHelper idempotent;     // 幂等控制
    private final DataQualityChecker dqcChecker;   // DQC 校验器

    /**
     * ETL 批次上下文 - 贯穿全链路的状态信息
     */
    @Data
    @Builder
    public static class ETLBatchContext {
        private String batchId;           // 批次号(幂等键)
        private String bizDate;           // 业务日期 yyyy-MM-dd
        private long totalExtracted;      // 抽取总记录数
        private long totalCleaned;        // 清洗后记录数
        private long totalDropped;        // 丢弃记录数
        private long totalLoaded;         // 加载记录数
        private TaskStatus status;        // 任务状态
        private long startTime;           // 开始时间戳
        private List<String> errorMessages; // 错误信息列表
    }

    public enum TaskStatus {
        INIT, EXTRACTING, CLEANING, LOADING_ODS, LOADING_DWD,
        LOADING_DWS, LOADING_ADS, DQC_CHECKING, SUCCESS, FAILED
    }

    // ================================================================
    // 1. ETL 主入口 - 全链路编排
    // ================================================================

    /**
     * 执行完整的 ETL 流程
     *
     * @param bizDate 业务日期(格式: yyyy-MM-dd)
     */
    public void executeFullPipeline(String bizDate) {
        String batchId = generateBatchId(bizDate);
        ETLBatchContext context = ETLBatchContext.builder()
                .batchId(batchId)
                .bizDate(bizDate)
                .status(TaskStatus.INIT)
                .startTime(System.currentTimeMillis())
                .errorMessages(new ArrayList<>())
                .build();

        log.info("[ETL] 全链路启动: batchId={}, bizDate={}", batchId, bizDate);

        try {
            // 幂等检查: 同一批次不重复执行
            if (idempotent.isDuplicate("etl_pipeline", batchId)) {
                log.warn("[ETL] 批次已执行过,跳过: batchId={}", batchId);
                return;
            }
            idempotent.markProcessing("etl_pipeline", batchId);

            // 阶段1: 数据源抽取
            List<Map<String, Object>> rawData = executeExtraction(context);

            // 阶段2: 数据清洗
            List<Map<String, Object>> cleanedData = executeDataCleaning(context, rawData);

            // 阶段3: 分层加载 ODS -> DWD -> DWS -> ADS
            loadToODS(context, rawData);
            loadToDWD(context, bizDate);
            loadToDWS(context, bizDate);
            loadToADS(context, bizDate);

            // 阶段4: 数据质量校验
            executeDataQualityCheck(context, bizDate);

            // 标记完成
            context.setStatus(TaskStatus.SUCCESS);
            idempotent.markSuccess("etl_pipeline", batchId);

            long elapsed = System.currentTimeMillis() - context.getStartTime();
            log.info("[ETL] 全链路完成: batchId={}, bizDate={}, " +
                    "extracted={}, cleaned={}, dropped={}, elapsed={}ms",
                    batchId, bizDate, context.getTotalExtracted(),
                    context.getTotalCleaned(), context.getTotalDropped(), elapsed);

        } catch (Exception e) {
            context.setStatus(TaskStatus.FAILED);
            idempotent.markFailed("etl_pipeline", batchId);

            log.error("[ETL] 全链路失败: batchId={}, bizDate={}, stage={}",
                    batchId, bizDate, context.getStatus(), e);

            alertService.sendAlert(AlertLevel.P1,
                    String.format("ETL全链路失败 | batchId=%s | bizDate=%s | stage=%s",
                            batchId, bizDate, context.getStatus()),
                    e.getMessage());
            throw new ETLPipelineException("ETL全链路执行失败", e);
        }
    }

    // ================================================================
    // 2. 数据源抽取
    // ================================================================

    /**
     * 从业务 MySQL 数据库抽取订单数据
     * 按日期范围增量抽取,支持断点续传
     */
    private List<Map<String, Object>> executeExtraction(ETLBatchContext context) {
        context.setStatus(TaskStatus.EXTRACTING);
        log.info("[ETL-Extract] 开始抽取: batchId={}, bizDate={}",
                context.getBatchId(), context.getBizDate());

        String extractSql =
                "SELECT order_id, user_id, shop_id, product_id, " +
                "       order_amount, discount_amount, pay_amount, " +
                "       order_status, pay_type, " +
                "       create_time, update_time " +
                "FROM t_order " +
                "WHERE DATE(create_time) = ? " +
                "ORDER BY order_id";

        List<Map<String, Object>> rawData;
        try {
            rawData = sourceJdbc.queryForList(extractSql, context.getBizDate());
        } catch (DataAccessException e) {
            log.error("[ETL-Extract] 数据源查询失败: bizDate={}", context.getBizDate(), e);
            throw new ExtractionException("数据源抽取失败: " + e.getMessage(), e);
        }

        context.setTotalExtracted(rawData.size());
        log.info("[ETL-Extract] 抽取完成: bizDate={}, records={}",
                context.getBizDate(), rawData.size());

        // 空数据校验
        if (rawData.isEmpty()) {
            log.warn("[ETL-Extract] 抽取数据为空, 可能数据源异常: bizDate={}", context.getBizDate());
            alertService.sendAlert(AlertLevel.P2,
                    "ETL抽取数据为空", "bizDate=" + context.getBizDate());
        }

        return rawData;
    }

    // ================================================================
    // 3. 数据清洗
    // ================================================================

    /**
     * 数据清洗: 去重、空值处理、格式标准化、异常数据过滤
     */
    private List<Map<String, Object>> executeDataCleaning(
            ETLBatchContext context, List<Map<String, Object>> rawData) {
        context.setStatus(TaskStatus.CLEANING);
        log.info("[ETL-Clean] 开始清洗: batchId={}, inputRecords={}",
                context.getBatchId(), rawData.size());

        // 去重: 基于 order_id 去重,保留最新记录
        Map<Long, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> row : rawData) {
            Long orderId = ((Number) row.get("order_id")).longValue();
            deduped.put(orderId, row); // 后出现的覆盖先出现的
        }
        int dupCount = rawData.size() - deduped.size();
        if (dupCount > 0) {
            log.info("[ETL-Clean] 去重完成: 去除{}条重复记录", dupCount);
        }

        List<Map<String, Object>> cleanedData = new ArrayList<>();
        int droppedCount = 0;

        for (Map<String, Object> row : deduped.values()) {
            try {
                // 空值检查: 核心字段不能为空
                if (row.get("order_id") == null || row.get("user_id") == null) {
                    log.warn("[ETL-Clean] 核心字段为空,丢弃: order_id={}, user_id={}",
                            row.get("order_id"), row.get("user_id"));
                    droppedCount++;
                    continue;
                }

                // 金额合法性检查
                BigDecimal orderAmount = new BigDecimal(String.valueOf(row.get("order_amount")));
                if (orderAmount.compareTo(BigDecimal.ZERO) < 0 ||
                        orderAmount.compareTo(new BigDecimal("999999")) > 0) {
                    log.warn("[ETL-Clean] 金额异常,丢弃: order_id={}, amount={}",
                            row.get("order_id"), orderAmount);
                    droppedCount++;
                    continue;
                }

                // 标准化: 金额保留2位小数
                row.put("order_amount", orderAmount.setScale(2, RoundingMode.HALF_UP));
                row.put("discount_amount",
                        new BigDecimal(String.valueOf(
                                row.getOrDefault("discount_amount", "0")))
                                .setScale(2, RoundingMode.HALF_UP));
                row.put("pay_amount",
                        new BigDecimal(String.valueOf(
                                row.getOrDefault("pay_amount", "0")))
                                .setScale(2, RoundingMode.HALF_UP));

                // 状态码标准化
                Integer status = (Integer) row.get("order_status");
                if (status == null || status < 1 || status > 4) {
                    row.put("order_status", 0); // 未知状态
                }

                cleanedData.add(row);

            } catch (Exception e) {
                log.warn("[ETL-Clean] 清洗异常,丢弃记录: order_id={}, error={}",
                        row.get("order_id"), e.getMessage());
                droppedCount++;
            }
        }

        context.setTotalCleaned(cleanedData.size());
        context.setTotalDropped(droppedCount);
        log.info("[ETL-Clean] 清洗完成: input={}, output={}, dropped={}",
                rawData.size(), cleanedData.size(), droppedCount);

        return cleanedData;
    }

    // ================================================================
    // 4. 分层加载
    // ================================================================

    /**
     * 加载 ODS 层: 原始数据落地
     * 幂等策略: INSERT OVERWRITE 分区覆盖写入
     */
    private void loadToODS(ETLBatchContext context, List<Map<String, Object>> rawData) {
        context.setStatus(TaskStatus.LOADING_ODS);
        log.info("[ETL-ODS] 开始加载ODS层: batchId={}, records={}",
                context.getBatchId(), rawData.size());

        String insertSql =
                "INSERT OVERWRITE TABLE ods_trade_order PARTITION (dt = ?) " +
                "SELECT order_id, user_id, shop_id, product_id, " +
                "       order_amount, discount_amount, pay_amount, " +
                "       order_status, pay_type, " +
                "       create_time, update_time, " +
                "       to_json(struct(*)) as raw_data, " +
                "       current_timestamp() as etl_load_time, " +
                "       ? as etl_batch_id " +
                "FROM tmp_extraction_data";

        try {
            // 先将抽取数据写入临时表
            hiveClient.createTempTable("tmp_extraction_data", rawData);
            // INSERT OVERWRITE 保证幂等: 同一分区重跑结果一致
            hiveClient.execute(insertSql, context.getBizDate(), context.getBatchId());
            log.info("[ETL-ODS] ODS层加载完成: dt={}", context.getBizDate());
        } catch (Exception e) {
            log.error("[ETL-ODS] ODS层加载失败: dt={}", context.getBizDate(), e);
            throw new LoadException("ODS层加载失败", e);
        }
    }

    /**
     * 加载 DWD 层: 维度退化 + 码表转换 + 事实标准化
     * 通过 SQL 关联维度表实现维度退化,避免应用层再次 JOIN
     */
    private void loadToDWD(ETLBatchContext context, String bizDate) {
        context.setStatus(TaskStatus.LOADING_DWD);
        log.info("[ETL-DWD] 开始加载DWD层: batchId={}, dt={}", context.getBatchId(), bizDate);

        String dwdSql =
                "INSERT OVERWRITE TABLE dwd_trade_order_detail_df PARTITION (dt = ?) " +
                "SELECT " +
                "    o.order_id, " +
                "    o.user_id, " +
                "    u.user_name, " +
                "    u.user_level, " +
                "    u.city_id, " +
                "    u.city_name, " +
                "    u.province_name, " +
                "    o.shop_id, " +
                "    s.shop_name, " +
                "    s.category_level1, " +
                "    o.product_id, " +
                "    p.product_name, " +
                "    o.order_amount, " +
                "    o.discount_amount, " +
                "    o.pay_amount, " +
                "    o.order_status, " +
                "    CASE o.order_status " +
                "        WHEN 1 THEN '已创建' " +
                "        WHEN 2 THEN '已支付' " +
                "        WHEN 3 THEN '已完成' " +
                "        WHEN 4 THEN '已取消' " +
                "        ELSE '未知' END AS order_status_name, " +
                "    o.pay_type, " +
                "    CASE o.pay_type " +
                "        WHEN 1 THEN '微信' " +
                "        WHEN 2 THEN '支付宝' " +
                "        WHEN 3 THEN '银行卡' " +
                "        ELSE '未知' END AS pay_type_name, " +
                "    CAST(o.create_time AS TIMESTAMP) AS create_time, " +
                "    CAST(o.update_time AS TIMESTAMP) AS update_time, " +
                "    CASE WHEN o.user_id < 10000 THEN 0 ELSE 1 END AS is_valid " +
                "FROM ods_trade_order o " +
                "LEFT JOIN dim_user u ON o.user_id = u.user_id " +
                "LEFT JOIN dim_shop s ON o.shop_id = s.shop_id " +
                "LEFT JOIN dim_product p ON o.product_id = p.product_id " +
                "WHERE o.dt = ?";

        try {
            hiveClient.execute(dwdSql, bizDate, bizDate);
            log.info("[ETL-DWD] DWD层加载完成: dt={}", bizDate);
        } catch (Exception e) {
            log.error("[ETL-DWD] DWD层加载失败: dt={}", bizDate, e);
            throw new LoadException("DWD层加载失败", e);
        }
    }

    /**
     * 加载 DWS 层: 轻度聚合,生成门店维度日汇总
     */
    private void loadToDWS(ETLBatchContext context, String bizDate) {
        context.setStatus(TaskStatus.LOADING_DWS);
        log.info("[ETL-DWS] 开始加载DWS层: batchId={}, dt={}", context.getBatchId(), bizDate);

        String dwsSql =
                "INSERT OVERWRITE TABLE dws_trade_shop_daily_summary PARTITION (dt = ?) " +
                "SELECT " +
                "    shop_id, " +
                "    shop_name, " +
                "    city_name, " +
                "    province_name, " +
                "    category_level1, " +
                "    COUNT(order_id) AS order_count, " +
                "    COUNT(DISTINCT CASE WHEN order_status = 2 THEN user_id END) AS pay_user_count, " +
                "    SUM(order_amount) AS gmv, " +
                "    SUM(discount_amount) AS discount_total, " +
                "    SUM(pay_amount) AS pay_total, " +
                "    CASE WHEN COUNT(order_id) > 0 " +
                "         THEN ROUND(SUM(pay_amount) / COUNT(order_id), 2) " +
                "         ELSE 0 END AS avg_order_amount, " +
                "    SUM(CASE WHEN order_status = 4 THEN 1 ELSE 0 END) AS cancel_count, " +
                "    CASE WHEN COUNT(order_id) > 0 " +
                "         THEN ROUND(SUM(CASE WHEN order_status = 4 THEN 1 ELSE 0 END) " +
                "                    / COUNT(order_id), 4) " +
                "         ELSE 0 END AS cancel_rate " +
                "FROM dwd_trade_order_detail_df " +
                "WHERE dt = ? AND is_valid = 1 " +
                "GROUP BY shop_id, shop_name, city_name, province_name, category_level1";

        try {
            hiveClient.execute(dwsSql, bizDate, bizDate);
            log.info("[ETL-DWS] DWS层加载完成: dt={}", bizDate);
        } catch (Exception e) {
            log.error("[ETL-DWS] DWS层加载失败: dt={}", bizDate, e);
            throw new LoadException("DWS层加载失败", e);
        }
    }

    /**
     * 加载 ADS 层: 面向报表的城市维度日报
     */
    private void loadToADS(ETLBatchContext context, String bizDate) {
        context.setStatus(TaskStatus.LOADING_ADS);
        log.info("[ETL-ADS] 开始加载ADS层: batchId={}, dt={}", context.getBatchId(), bizDate);

        String adsSql =
                "INSERT OVERWRITE TABLE ads_trade_city_report PARTITION (dt = ?) " +
                "SELECT " +
                "    city_name, " +
                "    province_name, " +
                "    SUM(gmv) AS total_gmv, " +
                "    SUM(order_count) AS total_orders, " +
                "    SUM(pay_user_count) AS total_users, " +
                "    CASE WHEN SUM(order_count) > 0 " +
                "         THEN ROUND(SUM(pay_total) / SUM(order_count), 2) " +
                "         ELSE 0 END AS avg_order_amount, " +
                "    ROW_NUMBER() OVER (ORDER BY SUM(gmv) DESC) AS gmv_rank, " +
                "    ROUND((SUM(gmv) - COALESCE(prev.prev_gmv, 0)) " +
                "          / GREATEST(COALESCE(prev.prev_gmv, 1), 1), 4) AS gmv_mom_rate, " +
                "    ? AS report_date " +
                "FROM dws_trade_shop_daily_summary cur " +
                "LEFT JOIN ( " +
                "    SELECT city_name, SUM(gmv) AS prev_gmv " +
                "    FROM dws_trade_shop_daily_summary " +
                "    WHERE dt = DATE_SUB(?, 1) " +
                "    GROUP BY city_name " +
                ") prev ON cur.city_name = prev.city_name " +
                "WHERE cur.dt = ? " +
                "GROUP BY city_name, province_name, prev.prev_gmv";

        try {
            hiveClient.execute(adsSql, bizDate, bizDate, bizDate, bizDate);
            log.info("[ETL-ADS] ADS层加载完成: dt={}", bizDate);
        } catch (Exception e) {
            log.error("[ETL-ADS] ADS层加载失败: dt={}", bizDate, e);
            throw new LoadException("ADS层加载失败", e);
        }
    }

    // ================================================================
    // 5. 数据质量校验 (DQC)
    // ================================================================

    /**
     * 数据质量校验
     * 规则包括: 空值率、唯一性、波动率、跨层一致性
     * 校验失败分两级: BLOCK(阻断) 和 WARN(告警)
     */
    private void executeDataQualityCheck(ETLBatchContext context, String bizDate) {
        context.setStatus(TaskStatus.DQC_CHECKING);
        log.info("[ETL-DQC] 开始数据质量校验: batchId={}, dt={}", context.getBatchId(), bizDate);

        List<DQCRule> rules = buildDQCRules(bizDate);
        List<DQCResult> results = new ArrayList<>();

        for (DQCRule rule : rules) {
            try {
                DQCResult result = dqcChecker.check(rule);
                results.add(result);

                if (!result.isPassed()) {
                    log.warn("[ETL-DQC] 校验未通过: rule={}, actual={}, expected={}",
                            rule.getRuleName(), result.getActualValue(), rule.getExpectedValue());
                }
            } catch (Exception e) {
                log.error("[ETL-DQC] 校验执行异常: rule={}", rule.getRuleName(), e);
                results.add(DQCResult.error(rule.getRuleName(), e.getMessage()));
            }
        }

        // 处理校验结果
        List<DQCResult> blockers = results.stream()
                .filter(r -> !r.isPassed() && r.getLevel() == DQCLevel.BLOCK)
                .collect(Collectors.toList());
        List<DQCResult> warnings = results.stream()
                .filter(r -> !r.isPassed() && r.getLevel() == DQCLevel.WARN)
                .collect(Collectors.toList());

        if (!warnings.isEmpty()) {
            String warnMsg = warnings.stream()
                    .map(w -> w.getRuleName() + ": " + w.getMessage())
                    .collect(Collectors.joining("\n"));
            alertService.sendAlert(AlertLevel.P2,
                    "DQC告警 | bizDate=" + bizDate, warnMsg);
        }

        if (!blockers.isEmpty()) {
            String blockMsg = blockers.stream()
                    .map(b -> b.getRuleName() + ": " + b.getMessage())
                    .collect(Collectors.joining("\n"));
            log.error("[ETL-DQC] 阻断级校验未通过: \n{}", blockMsg);
            alertService.sendAlert(AlertLevel.P1,
                    "DQC阻断 | bizDate=" + bizDate, blockMsg);
            throw new DQCBlockException("数据质量校验阻断: " + blockMsg);
        }

        log.info("[ETL-DQC] 数据质量校验通过: total={}, passed={}, warn={}, block={}",
                results.size(),
                results.stream().filter(DQCResult::isPassed).count(),
                warnings.size(), blockers.size());
    }

    /**
     * 构建 DQC 校验规则列表
     */
    private List<DQCRule> buildDQCRules(String bizDate) {
        List<DQCRule> rules = new ArrayList<>();

        // 规则1: ODS 与 DWD 记录数一致性(BLOCK级)
        rules.add(DQCRule.builder()
                .ruleName("ods_dwd_count_consistency")
                .ruleType(DQCRuleType.CROSS_LAYER_CONSISTENCY)
                .level(DQCLevel.BLOCK)
                .checkSql("SELECT ABS(a.cnt - b.cnt) / GREATEST(a.cnt, 1) " +
                        "FROM (SELECT COUNT(*) cnt FROM ods_trade_order WHERE dt = '" + bizDate + "') a, " +
                        "     (SELECT COUNT(*) cnt FROM dwd_trade_order_detail_df WHERE dt = '" + bizDate + "') b")
                .expectedValue("0.05")  // 差异率不超过5%
                .comparator(DQCComparator.LESS_THAN)
                .build());

        // 规则2: DWD 层 order_id 唯一性(BLOCK级)
        rules.add(DQCRule.builder()
                .ruleName("dwd_order_id_uniqueness")
                .ruleType(DQCRuleType.UNIQUENESS)
                .level(DQCLevel.BLOCK)
                .checkSql("SELECT COUNT(*) - COUNT(DISTINCT order_id) " +
                        "FROM dwd_trade_order_detail_df WHERE dt = '" + bizDate + "'")
                .expectedValue("0")
                .comparator(DQCComparator.EQUALS)
                .build());

        // 规则3: DWD 层核心字段空值率(WARN级)
        rules.add(DQCRule.builder()
                .ruleName("dwd_user_name_null_rate")
                .ruleType(DQCRuleType.NULL_RATE)
                .level(DQCLevel.WARN)
                .checkSql("SELECT COUNT(CASE WHEN user_name IS NULL THEN 1 END) / COUNT(*) " +
                        "FROM dwd_trade_order_detail_df WHERE dt = '" + bizDate + "'")
                .expectedValue("0.01")  // 空值率不超过1%
                .comparator(DQCComparator.LESS_THAN)
                .build());

        // 规则4: DWS 层 GMV 日波动率(WARN级)
        rules.add(DQCRule.builder()
                .ruleName("dws_gmv_daily_fluctuation")
                .ruleType(DQCRuleType.FLUCTUATION)
                .level(DQCLevel.WARN)
                .checkSql("SELECT ABS(today.gmv - yesterday.gmv) / GREATEST(yesterday.gmv, 1) " +
                        "FROM (SELECT SUM(gmv) gmv FROM dws_trade_shop_daily_summary " +
                        "      WHERE dt = '" + bizDate + "') today, " +
                        "     (SELECT SUM(gmv) gmv FROM dws_trade_shop_daily_summary " +
                        "      WHERE dt = DATE_SUB('" + bizDate + "', 1)) yesterday")
                .expectedValue("0.5")  // 波动不超过50%
                .comparator(DQCComparator.LESS_THAN)
                .build());

        return rules;
    }

    // ================================================================
    // 6. 幂等控制
    // ================================================================

    /**
     * 幂等控制辅助类
     * 基于数据库记录 ETL 执行状态,防止重复执行
     */
    public static class IdempotentHelper {

        private final JdbcTemplate jdbc;

        public IdempotentHelper(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public boolean isDuplicate(String taskType, String batchId) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM etl_execution_log " +
                    "WHERE task_type = ? AND batch_id = ? AND status = 'SUCCESS'",
                    Integer.class, taskType, batchId);
            return count != null && count > 0;
        }

        public void markProcessing(String taskType, String batchId) {
            jdbc.update(
                    "INSERT INTO etl_execution_log (task_type, batch_id, status, start_time) " +
                    "VALUES (?, ?, 'PROCESSING', NOW()) " +
                    "ON DUPLICATE KEY UPDATE status = 'PROCESSING', start_time = NOW()",
                    taskType, batchId);
        }

        public void markSuccess(String taskType, String batchId) {
            jdbc.update(
                    "UPDATE etl_execution_log SET status = 'SUCCESS', end_time = NOW() " +
                    "WHERE task_type = ? AND batch_id = ?",
                    taskType, batchId);
        }

        public void markFailed(String taskType, String batchId) {
            jdbc.update(
                    "UPDATE etl_execution_log SET status = 'FAILED', end_time = NOW() " +
                    "WHERE task_type = ? AND batch_id = ?",
                    taskType, batchId);
        }
    }

    private String generateBatchId(String bizDate) {
        return "ETL_" + bizDate.replace("-", "") + "_" +
                System.currentTimeMillis() % 100000;
    }
}
```

### 7.2 案例二：OLAP 多维分析查询全链路

本案例演示一个完整的 OLAP 查询流程：从用户发起多维分析请求，经过查询解析、缓存判断，路由到 ClickHouse/Doris 执行查询，利用预聚合物化视图加速，结果通过多级缓存返回。

```
┌─────────────────────────────────────────────────────────────────────┐
│                  OLAP 多维分析查询全链路                               │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────┐  │
│  │ 用户查询  │───▶│ 查询解析  │───▶│ 缓存层       │───▶│ 命中返回  │  │
│  │ 多维条件  │    │ SQL生成   │    │ L1本地/L2Redis│    │          │  │
│  └──────────┘    └──────────┘    └──────┬───────┘    └──────────┘  │
│                                         │ 未命中                    │
│                                         ▼                          │
│                                  ┌──────────────┐                  │
│                                  │ OLAP 引擎     │                  │
│                                  │ ClickHouse    │                  │
│                                  │ /Doris        │                  │
│                                  │ (物化视图加速) │                  │
│                                  └──────┬───────┘                  │
│                                         │                          │
│                                         ▼                          │
│                                  ┌──────────────┐                  │
│                                  │ 结果封装+缓存  │                  │
│                                  │ 写入+返回     │                  │
│                                  └──────────────┘                  │
└─────────────────────────────────────────────────────────────────────┘
```

#### 7.2.1 物化视图建表 SQL

```sql
-- ============================================================
-- ClickHouse 基础明细表 (MergeTree引擎)
-- ============================================================
CREATE TABLE IF NOT EXISTS dws_order_detail ON CLUSTER default_cluster (
    order_id       UInt64       COMMENT '订单ID',
    user_id        UInt64       COMMENT '用户ID',
    city_name      String       COMMENT '城市',
    province_name  String       COMMENT '省份',
    shop_id        UInt64       COMMENT '门店ID',
    shop_name      String       COMMENT '门店名',
    category_l1    String       COMMENT '一级类目',
    order_amount   Decimal(18,2) COMMENT '订单金额',
    pay_amount     Decimal(18,2) COMMENT '实付金额',
    order_status   UInt8        COMMENT '订单状态',
    pay_type       UInt8        COMMENT '支付方式',
    create_date    Date         COMMENT '创建日期',
    create_time    DateTime     COMMENT '创建时间'
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(create_date)
ORDER BY (create_date, city_name, shop_id)
TTL create_date + INTERVAL 12 MONTH
SETTINGS index_granularity = 8192;

-- ============================================================
-- 预聚合物化视图: 城市+类目维度日汇总 (自动增量聚合)
-- ============================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_city_category_daily
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(stat_date)
ORDER BY (stat_date, province_name, city_name, category_l1)
AS SELECT
    create_date                              AS stat_date,
    province_name,
    city_name,
    category_l1,
    count()                                  AS order_count,
    uniqState(user_id)                       AS pay_user_count,
    sum(order_amount)                        AS gmv,
    sum(pay_amount)                          AS pay_total,
    sumIf(order_amount, order_status = 4)    AS cancel_amount
FROM dws_order_detail
GROUP BY create_date, province_name, city_name, category_l1;

-- ============================================================
-- 预聚合物化视图: 门店维度小时级汇总 (支持实时看板)
-- ============================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_shop_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(stat_date)
ORDER BY (stat_date, stat_hour, shop_id)
AS SELECT
    create_date                              AS stat_date,
    toHour(create_time)                      AS stat_hour,
    shop_id,
    shop_name,
    city_name,
    count()                                  AS order_count,
    sum(order_amount)                        AS gmv,
    sum(pay_amount)                          AS pay_total
FROM dws_order_detail
GROUP BY create_date, toHour(create_time), shop_id, shop_name, city_name;

-- ============================================================
-- Doris 物化视图 (Rollup方式)
-- ============================================================
-- Doris 基础表
CREATE TABLE IF NOT EXISTS dws_order_detail_doris (
    create_date    DATE         COMMENT '创建日期',
    city_name      VARCHAR(64)  COMMENT '城市',
    province_name  VARCHAR(64)  COMMENT '省份',
    category_l1    VARCHAR(64)  COMMENT '一级类目',
    shop_id        BIGINT       COMMENT '门店ID',
    order_id       BIGINT       COMMENT '订单ID',
    user_id        BIGINT       COMMENT '用户ID',
    order_amount   DECIMAL(18,2) COMMENT '订单金额',
    pay_amount     DECIMAL(18,2) COMMENT '实付金额',
    order_status   TINYINT      COMMENT '订单状态'
) ENGINE = OLAP
DUPLICATE KEY(create_date, city_name, province_name, category_l1, shop_id)
PARTITION BY RANGE(create_date) ()
DISTRIBUTED BY HASH(shop_id) BUCKETS 16
PROPERTIES ("dynamic_partition.enable" = "true",
             "dynamic_partition.time_unit" = "DAY",
             "dynamic_partition.end" = "3",
             "dynamic_partition.prefix" = "p",
             "replication_num" = "3");

-- Doris Rollup 物化视图: 城市+类目日汇总
ALTER TABLE dws_order_detail_doris ADD ROLLUP rollup_city_category (
    create_date, city_name, province_name, category_l1,
    order_id, user_id, order_amount, pay_amount
);
```

#### 7.2.2 OLAP 查询全链路 Java 实现

```java
/**
 * OLAP 多维分析查询全链路实现
 *
 * 完整流程: 用户查询 -> 查询解析 -> 缓存判断 -> OLAP引擎执行 -> 结果缓存 -> 返回
 *
 * 设计要点:
 * - 两级缓存(L1本地Caffeine + L2 Redis)降低OLAP引擎压力
 * - 自动识别并路由到物化视图加速查询
 * - 查询超时熔断,避免慢查询打满连接池
 * - 幂等查询(相同查询条件返回相同结果)
 * - 结果分页与流式返回支持
 */
@Slf4j
public class OlapQueryPipeline {

    private final ClickHouseClient clickHouseClient;
    private final DorisClient dorisClient;
    private final Cache<String, QueryResult> localCache;    // L1: Caffeine 本地缓存
    private final RedisTemplate<String, String> redisTemplate; // L2: Redis 分布式缓存
    private final QueryRouter queryRouter;                  // 查询路由器
    private final CircuitBreaker circuitBreaker;            // 熔断器

    private static final int LOCAL_CACHE_MAX_SIZE = 1000;
    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration REDIS_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    public OlapQueryPipeline(ClickHouseClient clickHouseClient,
                             DorisClient dorisClient,
                             RedisTemplate<String, String> redisTemplate) {
        this.clickHouseClient = clickHouseClient;
        this.dorisClient = dorisClient;
        this.redisTemplate = redisTemplate;
        this.queryRouter = new QueryRouter();
        this.circuitBreaker = CircuitBreaker.ofDefaults("olap-query");

        // L1 本地缓存配置
        this.localCache = Caffeine.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterWrite(LOCAL_CACHE_TTL)
                .recordStats()
                .build();
    }

    /**
     * 多维分析查询请求
     */
    @Data
    @Builder
    public static class AnalysisRequest {
        private String queryId;              // 查询ID(幂等键)
        private List<String> dimensions;     // 维度列表: ["city_name", "category_l1"]
        private List<String> metrics;        // 指标列表: ["gmv", "order_count"]
        private Map<String, Object> filters; // 筛选条件: {"province_name": "北京"}
        private String dateStart;            // 开始日期
        private String dateEnd;              // 结束日期
        private String orderBy;              // 排序字段
        private String orderDir;             // 排序方向 ASC/DESC
        private int pageNo;                  // 页码
        private int pageSize;                // 每页大小
    }

    /**
     * 查询结果
     */
    @Data
    @Builder
    public static class QueryResult {
        private String queryId;
        private List<Map<String, Object>> data;
        private long total;
        private long queryTimeMs;
        private String queryEngine;          // 使用的引擎: clickhouse/doris
        private String cacheHit;             // 缓存命中: L1/L2/NONE
        private boolean usedMaterializedView; // 是否命中物化视图
    }

    // ================================================================
    // 1. 查询主入口
    // ================================================================

    /**
     * 执行多维分析查询
     */
    public QueryResult executeQuery(AnalysisRequest request) {
        long startTime = System.currentTimeMillis();
        String cacheKey = buildCacheKey(request);

        log.info("[OLAP] 查询开始: queryId={}, dimensions={}, metrics={}, dateRange=[{},{}]",
                request.getQueryId(), request.getDimensions(),
                request.getMetrics(), request.getDateStart(), request.getDateEnd());

        try {
            // 阶段1: L1 本地缓存查询
            QueryResult cached = localCache.getIfPresent(cacheKey);
            if (cached != null) {
                cached.setCacheHit("L1");
                cached.setQueryTimeMs(System.currentTimeMillis() - startTime);
                log.info("[OLAP] L1缓存命中: queryId={}, elapsed={}ms",
                        request.getQueryId(), cached.getQueryTimeMs());
                return cached;
            }

            // 阶段2: L2 Redis 缓存查询
            String redisValue = redisTemplate.opsForValue().get("olap:" + cacheKey);
            if (redisValue != null) {
                cached = JSON.parseObject(redisValue, QueryResult.class);
                cached.setCacheHit("L2");
                cached.setQueryTimeMs(System.currentTimeMillis() - startTime);
                // 回填 L1 缓存
                localCache.put(cacheKey, cached);
                log.info("[OLAP] L2缓存命中: queryId={}, elapsed={}ms",
                        request.getQueryId(), cached.getQueryTimeMs());
                return cached;
            }

            // 阶段3: 生成SQL并路由到OLAP引擎执行
            QueryResult result = circuitBreaker.executeSupplier(() ->
                    executeOlapQuery(request));

            result.setCacheHit("NONE");
            result.setQueryTimeMs(System.currentTimeMillis() - startTime);

            // 阶段4: 写入两级缓存
            localCache.put(cacheKey, result);
            redisTemplate.opsForValue().set(
                    "olap:" + cacheKey,
                    JSON.toJSONString(result),
                    REDIS_CACHE_TTL);

            log.info("[OLAP] 查询完成: queryId={}, engine={}, " +
                    "usedMV={}, records={}, elapsed={}ms",
                    request.getQueryId(), result.getQueryEngine(),
                    result.isUsedMaterializedView(), result.getTotal(),
                    result.getQueryTimeMs());

            return result;

        } catch (CallNotPermittedException e) {
            log.error("[OLAP] 熔断器已打开,查询被拒绝: queryId={}", request.getQueryId());
            throw new OlapQueryException("OLAP查询服务暂时不可用(熔断中)", e);
        } catch (Exception e) {
            log.error("[OLAP] 查询异常: queryId={}", request.getQueryId(), e);
            throw new OlapQueryException("OLAP查询执行失败: " + e.getMessage(), e);
        }
    }

    // ================================================================
    // 2. SQL 生成与物化视图匹配
    // ================================================================

    /**
     * 查询路由器: 生成 SQL 并匹配物化视图
     */
    public static class QueryRouter {

        /**
         * 物化视图匹配规则
         * Key: 维度组合的规范化标识
         * Value: 物化视图表名
         */
        private final Map<String, String> mvMappings = new HashMap<>() {{
            put("city_name,category_l1,create_date", "mv_city_category_daily");
            put("province_name,city_name,category_l1,create_date", "mv_city_category_daily");
            put("shop_id,create_date,stat_hour", "mv_shop_hourly");
            put("shop_id,shop_name,city_name,create_date", "mv_shop_hourly");
        }};

        /**
         * 指标到聚合表达式的映射
         */
        private final Map<String, String> metricExpressions = new HashMap<>() {{
            put("gmv", "sum(order_amount)");
            put("pay_total", "sum(pay_amount)");
            put("order_count", "count()");
            put("pay_user_count", "uniqMerge(pay_user_count)");
            put("cancel_amount", "sum(cancel_amount)");
            put("avg_order_amount", "sum(pay_amount) / count()");
        }};

        /**
         * 物化视图中指标的聚合表达式(与明细表不同)
         */
        private final Map<String, String> mvMetricExpressions = new HashMap<>() {{
            put("gmv", "sum(gmv)");
            put("pay_total", "sum(pay_total)");
            put("order_count", "sum(order_count)");
            put("pay_user_count", "uniqMerge(pay_user_count)");
            put("cancel_amount", "sum(cancel_amount)");
            put("avg_order_amount", "sum(pay_total) / sum(order_count)");
        }};

        /**
         * 生成查询 SQL
         * 优先匹配物化视图,未命中则查询明细表
         *
         * @return Pair<SQL, 是否命中物化视图>
         */
        public Pair<String, Boolean> buildQuerySql(AnalysisRequest request) {
            // 尝试匹配物化视图
            String dimKey = buildDimensionKey(request.getDimensions());
            String mvTable = matchMaterializedView(dimKey);

            boolean useMV = (mvTable != null);
            String sourceTable = useMV ? mvTable : "dws_order_detail";
            Map<String, String> expressions = useMV ? mvMetricExpressions : metricExpressions;
            String dateColumn = useMV ? "stat_date" : "create_date";

            StringBuilder sql = new StringBuilder();

            // SELECT 子句
            sql.append("SELECT ");
            StringJoiner selectJoiner = new StringJoiner(", ");
            for (String dim : request.getDimensions()) {
                selectJoiner.add(dim);
            }
            for (String metric : request.getMetrics()) {
                String expr = expressions.getOrDefault(metric, metric);
                selectJoiner.add(expr + " AS " + metric);
            }
            sql.append(selectJoiner);

            // FROM 子句
            sql.append(" FROM ").append(sourceTable);

            // WHERE 子句
            sql.append(" WHERE ").append(dateColumn)
               .append(" >= '").append(request.getDateStart()).append("'")
               .append(" AND ").append(dateColumn)
               .append(" <= '").append(request.getDateEnd()).append("'");

            // 筛选条件
            if (request.getFilters() != null) {
                for (Map.Entry<String, Object> filter : request.getFilters().entrySet()) {
                    sql.append(" AND ").append(filter.getKey())
                       .append(" = '").append(filter.getValue()).append("'");
                }
            }

            // GROUP BY 子句
            sql.append(" GROUP BY ");
            sql.append(String.join(", ", request.getDimensions()));

            // ORDER BY 子句
            if (request.getOrderBy() != null) {
                sql.append(" ORDER BY ").append(request.getOrderBy())
                   .append(" ").append(request.getOrderDir() != null ? request.getOrderDir() : "DESC");
            }

            // 分页
            int offset = (request.getPageNo() - 1) * request.getPageSize();
            sql.append(" LIMIT ").append(request.getPageSize())
               .append(" OFFSET ").append(offset);

            return Pair.of(sql.toString(), useMV);
        }

        private String matchMaterializedView(String dimKey) {
            return mvMappings.get(dimKey);
        }

        private String buildDimensionKey(List<String> dimensions) {
            List<String> sorted = new ArrayList<>(dimensions);
            Collections.sort(sorted);
            return String.join(",", sorted);
        }
    }

    // ================================================================
    // 3. OLAP 引擎执行
    // ================================================================

    /**
     * 执行 OLAP 查询(带超时控制)
     */
    private QueryResult executeOlapQuery(AnalysisRequest request) {
        Pair<String, Boolean> sqlPair = queryRouter.buildQuerySql(request);
        String sql = sqlPair.getLeft();
        boolean usedMV = sqlPair.getRight();

        log.info("[OLAP-Engine] 执行查询: queryId={}, usedMV={}, sql={}",
                request.getQueryId(), usedMV,
                sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

        try {
            // 优先使用 ClickHouse 执行
            List<Map<String, Object>> data = clickHouseClient.query(sql, QUERY_TIMEOUT);

            // 查询总数(去掉 LIMIT 重新查)
            String countSql = buildCountSql(sql);
            Long total = clickHouseClient.queryForObject(countSql, Long.class, QUERY_TIMEOUT);

            return QueryResult.builder()
                    .queryId(request.getQueryId())
                    .data(data)
                    .total(total != null ? total : data.size())
                    .queryEngine("clickhouse")
                    .usedMaterializedView(usedMV)
                    .build();

        } catch (QueryTimeoutException e) {
            log.warn("[OLAP-Engine] ClickHouse查询超时,降级到Doris: queryId={}",
                    request.getQueryId());
            return fallbackToDoris(request, sql, usedMV);
        } catch (Exception e) {
            log.error("[OLAP-Engine] ClickHouse查询异常: queryId={}", request.getQueryId(), e);
            return fallbackToDoris(request, sql, usedMV);
        }
    }

    /**
     * 降级到 Doris 查询
     */
    private QueryResult fallbackToDoris(AnalysisRequest request, String sql, boolean usedMV) {
        try {
            // Doris SQL 语法微调(count() -> count(*))
            String dorisSql = sql.replace("count()", "count(*)");

            List<Map<String, Object>> data = dorisClient.query(dorisSql, QUERY_TIMEOUT);

            return QueryResult.builder()
                    .queryId(request.getQueryId())
                    .data(data)
                    .total(data.size())
                    .queryEngine("doris")
                    .usedMaterializedView(usedMV)
                    .build();

        } catch (Exception e) {
            log.error("[OLAP-Engine] Doris降级查询也失败: queryId={}", request.getQueryId(), e);
            throw new OlapQueryException("所有OLAP引擎查询失败", e);
        }
    }

    // ================================================================
    // 4. 缓存管理
    // ================================================================

    /**
     * 生成缓存 Key
     * 基于查询条件生成唯一的缓存标识,保证相同查询幂等返回
     */
    private String buildCacheKey(AnalysisRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("olap:query:");
        sb.append(String.join(",", request.getDimensions()));
        sb.append("|");
        sb.append(String.join(",", request.getMetrics()));
        sb.append("|");
        sb.append(request.getDateStart()).append("-").append(request.getDateEnd());
        sb.append("|");
        if (request.getFilters() != null) {
            request.getFilters().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append(","));
        }
        sb.append("|").append(request.getPageNo()).append(":").append(request.getPageSize());
        return DigestUtils.md5Hex(sb.toString());
    }

    /**
     * 构建 COUNT 查询
     */
    private String buildCountSql(String sql) {
        // 去掉 ORDER BY 和 LIMIT 子句,包装为 COUNT 查询
        String coreSql = sql.replaceAll("ORDER BY.*", "").trim();
        return "SELECT COUNT(*) FROM (" + coreSql + ") t";
    }

    /**
     * 缓存失效: 当数据更新时主动清除缓存
     */
    public void invalidateCache(String dateStart, String dateEnd) {
        // 清除 L1 本地缓存
        localCache.invalidateAll();

        // 清除 L2 Redis 中匹配日期范围的缓存
        Set<String> keys = redisTemplate.keys("olap:query:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        log.info("[OLAP-Cache] 缓存已清除: dateRange=[{},{}]", dateStart, dateEnd);
    }
}
```

### 7.3 案例三：数据血缘追踪全链路

本案例演示一个完整的数据血缘管理系统：从表级血缘和字段级血缘的采集与解析，到变更时的影响分析，再到自动通知相关负责人并关联数据质量规则，形成闭环的血缘治理流程。

```
┌─────────────────────────────────────────────────────────────────────┐
│                    数据血缘追踪全链路                                 │
│                                                                     │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐              │
│  │ SQL解析   │───▶│ 表级血缘构建  │───▶│ 字段级血缘    │              │
│  │ AST分析   │    │ DAG图谱      │    │ 映射关系      │              │
│  └──────────┘    └──────────────┘    └──────┬───────┘              │
│                                              │                      │
│                                              ▼                      │
│  ┌──────────────┐    ┌──────────────┐  ┌──────────────┐            │
│  │ DQC规则关联   │◀───│ 变更通知     │◀──│ 影响分析      │            │
│  │ 质量联动      │    │ 邮件/IM      │   │ 上下游识别    │            │
│  └──────────────┘    └──────────────┘  └──────────────┘            │
└─────────────────────────────────────────────────────────────────────┘
```

#### 7.3.1 血缘存储表结构

```sql
-- ============================================================
-- 表级血缘关系表
-- ============================================================
CREATE TABLE IF NOT EXISTS meta_table_lineage (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_db        VARCHAR(128)  NOT NULL  COMMENT '上游库名',
    source_table     VARCHAR(256)  NOT NULL  COMMENT '上游表名',
    target_db        VARCHAR(128)  NOT NULL  COMMENT '下游库名',
    target_table     VARCHAR(256)  NOT NULL  COMMENT '下游表名',
    etl_job_name     VARCHAR(256)  NOT NULL  COMMENT 'ETL任务名',
    lineage_type     VARCHAR(32)   NOT NULL  COMMENT '血缘类型: ETL/VIEW/MANUAL',
    sql_hash         VARCHAR(64)   NULL      COMMENT 'SQL指纹(MD5)',
    create_time      DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lineage (source_db, source_table, target_db, target_table, etl_job_name)
) COMMENT '表级血缘关系';

-- ============================================================
-- 字段级血缘关系表
-- ============================================================
CREATE TABLE IF NOT EXISTS meta_column_lineage (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_db           VARCHAR(128)  NOT NULL  COMMENT '上游库名',
    source_table        VARCHAR(256)  NOT NULL  COMMENT '上游表名',
    source_column       VARCHAR(256)  NOT NULL  COMMENT '上游字段名',
    target_db           VARCHAR(128)  NOT NULL  COMMENT '下游库名',
    target_table        VARCHAR(256)  NOT NULL  COMMENT '下游表名',
    target_column       VARCHAR(256)  NOT NULL  COMMENT '下游字段名',
    transform_expr      VARCHAR(1024) NULL      COMMENT '转换表达式',
    etl_job_name        VARCHAR(256)  NOT NULL  COMMENT 'ETL任务名',
    create_time         DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_col_lineage (source_db, source_table, source_column,
                               target_db, target_table, target_column, etl_job_name)
) COMMENT '字段级血缘关系';

-- ============================================================
-- 表元数据与负责人
-- ============================================================
CREATE TABLE IF NOT EXISTS meta_table_info (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_name          VARCHAR(128)  NOT NULL  COMMENT '库名',
    table_name       VARCHAR(256)  NOT NULL  COMMENT '表名',
    owner            VARCHAR(64)   NOT NULL  COMMENT '表负责人(MIS)',
    owner_email      VARCHAR(128)  NULL      COMMENT '负责人邮箱',
    description      VARCHAR(512)  NULL      COMMENT '表描述',
    create_time      DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_table (db_name, table_name)
) COMMENT '表元数据';

-- ============================================================
-- 血缘变更记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS meta_lineage_change_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_type      VARCHAR(32)   NOT NULL  COMMENT '变更类型: ADD_COLUMN/DROP_COLUMN/MODIFY_TYPE/DROP_TABLE',
    db_name          VARCHAR(128)  NOT NULL  COMMENT '变更库名',
    table_name       VARCHAR(256)  NOT NULL  COMMENT '变更表名',
    column_name      VARCHAR(256)  NULL      COMMENT '变更字段名',
    change_detail    TEXT          NULL      COMMENT '变更详情JSON',
    operator         VARCHAR(64)   NULL      COMMENT '操作人',
    affected_count   INT           NULL      COMMENT '影响下游数量',
    notify_status    VARCHAR(32)   NOT NULL  DEFAULT 'PENDING' COMMENT '通知状态',
    create_time      DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP
) COMMENT '血缘变更日志';
```

#### 7.3.2 数据血缘追踪全链路 Java 实现

```java
/**
 * 数据血缘追踪全链路实现
 *
 * 完整流程: SQL解析 -> 表级血缘 -> 字段级血缘 -> 影响分析 -> 变更通知 -> DQC关联
 *
 * 设计要点:
 * - 基于 SQL AST 解析自动提取血缘关系,支持 INSERT/CTAS/VIEW
 * - 表级+字段级双层血缘图谱,支持 BFS 全链路追踪
 * - Schema 变更自动触发影响分析并通知下游负责人
 * - 血缘与 DQC 规则关联,上游异常自动扩散校验
 * - 幂等写入: 基于唯一键保证血缘关系不重复
 */
@Slf4j
public class DataLineageTracker {

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;
    private final DQCRuleEngine dqcRuleEngine;

    public DataLineageTracker(JdbcTemplate jdbc,
                              NotificationService notificationService,
                              DQCRuleEngine dqcRuleEngine) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
        this.dqcRuleEngine = dqcRuleEngine;
    }

    // ================================================================
    // 1. 表级血缘解析与构建
    // ================================================================

    /**
     * 血缘关系节点
     */
    @Data
    @Builder
    public static class LineageNode {
        private String dbName;
        private String tableName;
        private String owner;
        private int depth;  // 距离变更源的层级深度
    }

    /**
     * 字段级血缘关系
     */
    @Data
    @Builder
    public static class ColumnLineage {
        private String sourceDb;
        private String sourceTable;
        private String sourceColumn;
        private String targetDb;
        private String targetTable;
        private String targetColumn;
        private String transformExpr;
    }

    /**
     * 从 ETL SQL 中解析并注册表级血缘
     * 支持 INSERT INTO...SELECT / CREATE TABLE AS SELECT / CREATE VIEW
     *
     * @param etlJobName ETL任务名
     * @param sql        ETL SQL语句
     */
    public void parseAndRegisterTableLineage(String etlJobName, String sql) {
        log.info("[Lineage] 开始解析表级血缘: job={}", etlJobName);

        try {
            // 使用 Druid SQL Parser 解析 AST
            List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, DbType.hive);

            for (SQLStatement stmt : stmtList) {
                String targetDb = null;
                String targetTable = null;
                List<String[]> sourceTables = new ArrayList<>();

                if (stmt instanceof SQLInsertStatement) {
                    SQLInsertStatement insert = (SQLInsertStatement) stmt;
                    // 解析目标表
                    SQLExprTableSource tableSource = (SQLExprTableSource) insert.getTableSource();
                    targetDb = extractDbName(tableSource);
                    targetTable = extractTableName(tableSource);
                    // 解析来源表
                    sourceTables = extractSourceTables(insert.getQuery());

                } else if (stmt instanceof SQLCreateViewStatement) {
                    SQLCreateViewStatement view = (SQLCreateViewStatement) stmt;
                    targetDb = extractDbName(view.getTableSource());
                    targetTable = extractTableName(view.getTableSource());
                    sourceTables = extractSourceTables(view.getSubQuery());
                }

                if (targetTable == null || sourceTables.isEmpty()) {
                    log.warn("[Lineage] 无法解析血缘: job={}, sql={}",
                            etlJobName, sql.substring(0, Math.min(100, sql.length())));
                    continue;
                }

                // 幂等写入表级血缘
                String sqlHash = DigestUtils.md5Hex(sql);
                for (String[] source : sourceTables) {
                    registerTableLineage(source[0], source[1],
                            targetDb, targetTable, etlJobName,
                            "ETL", sqlHash);
                }

                log.info("[Lineage] 表级血缘注册完成: job={}, target={}.{}, sources={}",
                        etlJobName, targetDb, targetTable, sourceTables.size());
            }
        } catch (Exception e) {
            log.error("[Lineage] 表级血缘解析异常: job={}", etlJobName, e);
            throw new LineageParseException("表级血缘解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 幂等注册表级血缘(ON DUPLICATE KEY UPDATE)
     */
    private void registerTableLineage(String sourceDb, String sourceTable,
                                      String targetDb, String targetTable,
                                      String etlJobName, String lineageType,
                                      String sqlHash) {
        String insertSql =
                "INSERT INTO meta_table_lineage " +
                "(source_db, source_table, target_db, target_table, etl_job_name, lineage_type, sql_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE sql_hash = VALUES(sql_hash), update_time = NOW()";

        jdbc.update(insertSql, sourceDb, sourceTable,
                targetDb, targetTable, etlJobName, lineageType, sqlHash);

        log.debug("[Lineage] 表级血缘已注册: {}.{} -> {}.{} (job={})",
                sourceDb, sourceTable, targetDb, targetTable, etlJobName);
    }

    /**
     * 从 SQL 查询中提取来源表列表
     */
    private List<String[]> extractSourceTables(SQLSelect query) {
        List<String[]> tables = new ArrayList<>();
        if (query == null) return tables;

        SchemaStatVisitor visitor = new HiveSchemaStatVisitor();
        query.accept(visitor);

        for (TableStat.Name tableName : visitor.getTables().keySet()) {
            String fullName = tableName.getName();
            String[] parts = fullName.contains(".")
                    ? fullName.split("\\.", 2)
                    : new String[]{"default", fullName};
            tables.add(parts);
        }
        return tables;
    }

    private String extractDbName(SQLExprTableSource tableSource) {
        if (tableSource.getSchema() != null) return tableSource.getSchema();
        return "default";
    }

    private String extractTableName(SQLExprTableSource tableSource) {
        return tableSource.getTableName().replace("`", "");
    }

    // ================================================================
    // 2. 字段级血缘解析
    // ================================================================

    /**
     * 解析字段级血缘
     * 通过 AST 分析 SELECT 列与来源字段的映射关系
     */
    public List<ColumnLineage> parseColumnLineage(String etlJobName, String sql) {
        log.info("[Lineage] 开始解析字段级血缘: job={}", etlJobName);
        List<ColumnLineage> columnLineages = new ArrayList<>();

        try {
            List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, DbType.hive);

            for (SQLStatement stmt : stmtList) {
                if (!(stmt instanceof SQLInsertStatement)) continue;

                SQLInsertStatement insert = (SQLInsertStatement) stmt;
                String targetDb = extractDbName((SQLExprTableSource) insert.getTableSource());
                String targetTable = extractTableName((SQLExprTableSource) insert.getTableSource());

                // 解析 SELECT 列
                SQLSelectQueryBlock queryBlock = extractQueryBlock(insert.getQuery());
                if (queryBlock == null) continue;

                // 解析来源表别名映射
                Map<String, String[]> aliasMap = buildTableAliasMap(queryBlock);

                // 逐列解析血缘
                List<SQLSelectItem> selectItems = queryBlock.getSelectList();
                for (int i = 0; i < selectItems.size(); i++) {
                    SQLSelectItem item = selectItems.get(i);
                    String targetColumn = resolveTargetColumnName(item, i);

                    // 提取表达式中引用的源字段
                    List<SQLPropertyExpr> sourceColumns = new ArrayList<>();
                    item.getExpr().accept(new MySqlASTVisitorAdapter() {
                        @Override
                        public boolean visit(SQLPropertyExpr x) {
                            sourceColumns.add(x);
                            return true;
                        }
                    });

                    String transformExpr = SQLUtils.toSQLString(item.getExpr());

                    for (SQLPropertyExpr srcCol : sourceColumns) {
                        String tableAlias = srcCol.getOwnerName();
                        String colName = srcCol.getName();
                        String[] srcTable = aliasMap.getOrDefault(
                                tableAlias, new String[]{"default", tableAlias});

                        ColumnLineage lineage = ColumnLineage.builder()
                                .sourceDb(srcTable[0])
                                .sourceTable(srcTable[1])
                                .sourceColumn(colName)
                                .targetDb(targetDb)
                                .targetTable(targetTable)
                                .targetColumn(targetColumn)
                                .transformExpr(transformExpr)
                                .build();

                        columnLineages.add(lineage);

                        // 幂等写入字段级血缘
                        registerColumnLineage(lineage, etlJobName);
                    }
                }
            }

            log.info("[Lineage] 字段级血缘解析完成: job={}, columns={}",
                    etlJobName, columnLineages.size());

        } catch (Exception e) {
            log.error("[Lineage] 字段级血缘解析异常: job={}", etlJobName, e);
            throw new LineageParseException("字段级血缘解析失败", e);
        }

        return columnLineages;
    }

    /**
     * 幂等注册字段级血缘
     */
    private void registerColumnLineage(ColumnLineage lineage, String etlJobName) {
        String insertSql =
                "INSERT INTO meta_column_lineage " +
                "(source_db, source_table, source_column, target_db, target_table, " +
                " target_column, transform_expr, etl_job_name) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE transform_expr = VALUES(transform_expr), " +
                "update_time = NOW()";

        jdbc.update(insertSql,
                lineage.getSourceDb(), lineage.getSourceTable(), lineage.getSourceColumn(),
                lineage.getTargetDb(), lineage.getTargetTable(), lineage.getTargetColumn(),
                lineage.getTransformExpr(), etlJobName);
    }

    private SQLSelectQueryBlock extractQueryBlock(SQLSelect select) {
        if (select.getQuery() instanceof SQLSelectQueryBlock) {
            return (SQLSelectQueryBlock) select.getQuery();
        }
        return null;
    }

    private Map<String, String[]> buildTableAliasMap(SQLSelectQueryBlock queryBlock) {
        Map<String, String[]> aliasMap = new HashMap<>();
        SQLTableSource from = queryBlock.getFrom();
        collectTableAliases(from, aliasMap);
        return aliasMap;
    }

    private void collectTableAliases(SQLTableSource tableSource,
                                     Map<String, String[]> aliasMap) {
        if (tableSource instanceof SQLExprTableSource) {
            SQLExprTableSource t = (SQLExprTableSource) tableSource;
            String alias = t.getAlias() != null ? t.getAlias() : extractTableName(t);
            aliasMap.put(alias, new String[]{extractDbName(t), extractTableName(t)});
        } else if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            collectTableAliases(join.getLeft(), aliasMap);
            collectTableAliases(join.getRight(), aliasMap);
        }
    }

    private String resolveTargetColumnName(SQLSelectItem item, int index) {
        if (item.getAlias() != null) return item.getAlias();
        if (item.getExpr() instanceof SQLPropertyExpr) {
            return ((SQLPropertyExpr) item.getExpr()).getName();
        }
        return "col_" + index;
    }

    // ================================================================
    // 3. 影响分析 (BFS 全链路追踪)
    // ================================================================

    /**
     * 影响分析结果
     */
    @Data
    @Builder
    public static class ImpactAnalysisResult {
        private String changeDb;
        private String changeTable;
        private String changeType;
        private List<LineageNode> affectedNodes;   // 受影响的下游节点
        private int totalAffected;                 // 受影响总数
        private int maxDepth;                      // 最大影响深度
    }

    /**
     * 执行影响分析
     * 基于 BFS 遍历血缘图,找出所有直接和间接受影响的下游表
     *
     * @param dbName    变更表所在库
     * @param tableName 变更表名
     * @param maxDepth  最大追溯深度(防止循环引用和超深遍历)
     * @return 影响分析结果
     */
    public ImpactAnalysisResult analyzeDownstreamImpact(String dbName, String tableName,
                                                        int maxDepth) {
        log.info("[Lineage-Impact] 开始影响分析: table={}.{}, maxDepth={}",
                dbName, tableName, maxDepth);

        List<LineageNode> affectedNodes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<LineageNode> queue = new LinkedList<>();

        // BFS 初始节点
        LineageNode root = LineageNode.builder()
                .dbName(dbName).tableName(tableName).depth(0).build();
        queue.offer(root);
        visited.add(dbName + "." + tableName);

        while (!queue.isEmpty()) {
            LineageNode current = queue.poll();

            if (current.getDepth() >= maxDepth) continue;

            // 查询当前表的直接下游
            List<Map<String, Object>> downstreams = jdbc.queryForList(
                    "SELECT target_db, target_table FROM meta_table_lineage " +
                    "WHERE source_db = ? AND source_table = ?",
                    current.getDbName(), current.getTableName());

            for (Map<String, Object> downstream : downstreams) {
                String dDb = (String) downstream.get("target_db");
                String dTable = (String) downstream.get("target_table");
                String key = dDb + "." + dTable;

                if (visited.contains(key)) continue;
                visited.add(key);

                // 查询表负责人
                String owner = queryTableOwner(dDb, dTable);

                LineageNode node = LineageNode.builder()
                        .dbName(dDb)
                        .tableName(dTable)
                        .owner(owner)
                        .depth(current.getDepth() + 1)
                        .build();

                affectedNodes.add(node);
                queue.offer(node);
            }
        }

        int maxAffectedDepth = affectedNodes.stream()
                .mapToInt(LineageNode::getDepth)
                .max().orElse(0);

        log.info("[Lineage-Impact] 影响分析完成: source={}.{}, affected={}, maxDepth={}",
                dbName, tableName, affectedNodes.size(), maxAffectedDepth);

        return ImpactAnalysisResult.builder()
                .changeDb(dbName)
                .changeTable(tableName)
                .affectedNodes(affectedNodes)
                .totalAffected(affectedNodes.size())
                .maxDepth(maxAffectedDepth)
                .build();
    }

    /**
     * 查询字段级下游影响
     */
    public List<ColumnLineage> analyzeColumnImpact(String dbName, String tableName,
                                                    String columnName) {
        log.info("[Lineage-Impact] 字段级影响分析: {}.{}.{}",
                dbName, tableName, columnName);

        List<ColumnLineage> affected = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String[]> queue = new LinkedList<>();

        queue.offer(new String[]{dbName, tableName, columnName});
        visited.add(dbName + "." + tableName + "." + columnName);

        while (!queue.isEmpty()) {
            String[] current = queue.poll();

            List<Map<String, Object>> downstreams = jdbc.queryForList(
                    "SELECT target_db, target_table, target_column, transform_expr " +
                    "FROM meta_column_lineage " +
                    "WHERE source_db = ? AND source_table = ? AND source_column = ?",
                    current[0], current[1], current[2]);

            for (Map<String, Object> ds : downstreams) {
                String tDb = (String) ds.get("target_db");
                String tTable = (String) ds.get("target_table");
                String tColumn = (String) ds.get("target_column");
                String key = tDb + "." + tTable + "." + tColumn;

                if (visited.contains(key)) continue;
                visited.add(key);

                ColumnLineage lineage = ColumnLineage.builder()
                        .sourceDb(current[0]).sourceTable(current[1]).sourceColumn(current[2])
                        .targetDb(tDb).targetTable(tTable).targetColumn(tColumn)
                        .transformExpr((String) ds.get("transform_expr"))
                        .build();

                affected.add(lineage);
                queue.offer(new String[]{tDb, tTable, tColumn});
            }
        }

        log.info("[Lineage-Impact] 字段级影响分析完成: source={}.{}.{}, affected={}",
                dbName, tableName, columnName, affected.size());

        return affected;
    }

    private String queryTableOwner(String dbName, String tableName) {
        try {
            return jdbc.queryForObject(
                    "SELECT owner FROM meta_table_info WHERE db_name = ? AND table_name = ?",
                    String.class, dbName, tableName);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ================================================================
    // 4. 变更通知
    // ================================================================

    /**
     * 变更事件
     */
    @Data
    @Builder
    public static class SchemaChangeEvent {
        private String changeType;  // ADD_COLUMN / DROP_COLUMN / MODIFY_TYPE / DROP_TABLE
        private String dbName;
        private String tableName;
        private String columnName;  // 字段级变更时有值
        private String changeDetail;
        private String operator;
    }

    /**
     * 处理 Schema 变更事件
     * 全流程: 记录变更 -> 影响分析 -> 通知负责人 -> 关联DQC
     */
    public void handleSchemaChange(SchemaChangeEvent event) {
        log.info("[Lineage-Change] 接收到Schema变更: type={}, table={}.{}, column={}, operator={}",
                event.getChangeType(), event.getDbName(), event.getTableName(),
                event.getColumnName(), event.getOperator());

        try {
            // 步骤1: 执行影响分析
            ImpactAnalysisResult impact = analyzeDownstreamImpact(
                    event.getDbName(), event.getTableName(), 10);

            // 步骤2: 记录变更日志(幂等: 基于唯一的变更事件)
            Long changeLogId = recordChangeLog(event, impact.getTotalAffected());

            // 步骤3: 发送通知
            sendChangeNotifications(event, impact, changeLogId);

            // 步骤4: 关联DQC规则
            triggerDQCForAffectedTables(event, impact);

            // 步骤5: 更新通知状态
            jdbc.update(
                    "UPDATE meta_lineage_change_log SET notify_status = 'SENT' WHERE id = ?",
                    changeLogId);

            log.info("[Lineage-Change] 变更处理完成: changeLogId={}, affected={}",
                    changeLogId, impact.getTotalAffected());

        } catch (Exception e) {
            log.error("[Lineage-Change] 变更处理异常: table={}.{}",
                    event.getDbName(), event.getTableName(), e);
            throw new LineageChangeException("变更处理失败", e);
        }
    }

    /**
     * 记录变更日志
     */
    private Long recordChangeLog(SchemaChangeEvent event, int affectedCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO meta_lineage_change_log " +
                    "(change_type, db_name, table_name, column_name, change_detail, " +
                    " operator, affected_count, notify_status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.getChangeType());
            ps.setString(2, event.getDbName());
            ps.setString(3, event.getTableName());
            ps.setString(4, event.getColumnName());
            ps.setString(5, event.getChangeDetail());
            ps.setString(6, event.getOperator());
            ps.setInt(7, affectedCount);
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    /**
     * 发送变更通知给所有受影响的下游负责人
     */
    private void sendChangeNotifications(SchemaChangeEvent event,
                                          ImpactAnalysisResult impact,
                                          Long changeLogId) {
        // 按负责人去重聚合
        Map<String, List<LineageNode>> ownerToTables = impact.getAffectedNodes().stream()
                .filter(n -> n.getOwner() != null)
                .collect(Collectors.groupingBy(LineageNode::getOwner));

        for (Map.Entry<String, List<LineageNode>> entry : ownerToTables.entrySet()) {
            String owner = entry.getKey();
            List<LineageNode> tables = entry.getValue();

            String title = String.format("[数据血缘变更通知] %s.%s 发生 %s",
                    event.getDbName(), event.getTableName(), event.getChangeType());

            StringBuilder body = new StringBuilder();
            body.append("变更详情:\n");
            body.append("  变更表: ").append(event.getDbName()).append(".").append(event.getTableName()).append("\n");
            body.append("  变更类型: ").append(event.getChangeType()).append("\n");
            if (event.getColumnName() != null) {
                body.append("  变更字段: ").append(event.getColumnName()).append("\n");
            }
            body.append("  操作人: ").append(event.getOperator()).append("\n");
            body.append("  变更详情: ").append(event.getChangeDetail()).append("\n\n");
            body.append("您负责的以下表受到影响:\n");
            for (LineageNode table : tables) {
                body.append("  - ").append(table.getDbName()).append(".")
                    .append(table.getTableName())
                    .append(" (层级: L").append(table.getDepth()).append(")\n");
            }
            body.append("\n请评估变更影响并及时处理。");
            body.append("\n变更记录ID: ").append(changeLogId);

            try {
                notificationService.send(owner, title, body.toString());
                log.info("[Lineage-Notify] 通知已发送: owner={}, affectedTables={}",
                        owner, tables.size());
            } catch (Exception e) {
                log.error("[Lineage-Notify] 通知发送失败: owner={}", owner, e);
            }
        }
    }

    // ================================================================
    // 5. 数据质量关联 (DQC)
    // ================================================================

    /**
     * 对受影响的下游表触发 DQC 校验
     * 上游 Schema 变更后,自动对一级下游执行关键质量规则
     */
    private void triggerDQCForAffectedTables(SchemaChangeEvent event,
                                              ImpactAnalysisResult impact) {
        // 只对直接下游(depth=1)触发 DQC
        List<LineageNode> directDownstreams = impact.getAffectedNodes().stream()
                .filter(n -> n.getDepth() == 1)
                .collect(Collectors.toList());

        if (directDownstreams.isEmpty()) {
            log.info("[Lineage-DQC] 无直接下游,跳过DQC触发");
            return;
        }

        log.info("[Lineage-DQC] 开始触发下游DQC: source={}.{}, directDownstreams={}",
                event.getDbName(), event.getTableName(), directDownstreams.size());

        for (LineageNode node : directDownstreams) {
            try {
                List<DQCRule> rules = buildChangeTriggeredDQCRules(
                        event, node.getDbName(), node.getTableName());

                if (rules.isEmpty()) {
                    log.info("[Lineage-DQC] 无关联DQC规则: table={}.{}",
                            node.getDbName(), node.getTableName());
                    continue;
                }

                // 执行 DQC 校验
                List<DQCResult> results = new ArrayList<>();
                for (DQCRule rule : rules) {
                    try {
                        DQCResult result = dqcRuleEngine.check(rule);
                        results.add(result);
                        if (!result.isPassed()) {
                            log.warn("[Lineage-DQC] 校验未通过: table={}.{}, rule={}, actual={}",
                                    node.getDbName(), node.getTableName(),
                                    rule.getRuleName(), result.getActualValue());
                        }
                    } catch (Exception e) {
                        log.error("[Lineage-DQC] 规则执行异常: rule={}",
                                rule.getRuleName(), e);
                    }
                }

                // 汇总结果
                long failedCount = results.stream().filter(r -> !r.isPassed()).count();
                if (failedCount > 0) {
                    String alertMsg = String.format(
                            "血缘变更触发DQC告警: 上游表 %s.%s 变更后,下游表 %s.%s 有 %d 条规则未通过",
                            event.getDbName(), event.getTableName(),
                            node.getDbName(), node.getTableName(), failedCount);

                    notificationService.send(node.getOwner(), "[DQC告警] 血缘变更质量校验", alertMsg);
                }

                log.info("[Lineage-DQC] DQC校验完成: table={}.{}, total={}, failed={}",
                        node.getDbName(), node.getTableName(),
                        results.size(), failedCount);

            } catch (Exception e) {
                log.error("[Lineage-DQC] DQC触发异常: table={}.{}",
                        node.getDbName(), node.getTableName(), e);
            }
        }
    }

    /**
     * 根据变更类型构建针对性 DQC 规则
     */
    private List<DQCRule> buildChangeTriggeredDQCRules(SchemaChangeEvent event,
                                                       String targetDb,
                                                       String targetTable) {
        List<DQCRule> rules = new ArrayList<>();
        String fullTable = targetDb + "." + targetTable;

        switch (event.getChangeType()) {
            case "DROP_COLUMN":
                // 字段被删除: 检查下游表对应字段是否全为空
                List<String> affectedColumns = findAffectedColumns(
                        event.getDbName(), event.getTableName(), event.getColumnName(),
                        targetDb, targetTable);
                for (String col : affectedColumns) {
                    rules.add(DQCRule.builder()
                            .ruleName("lineage_null_check_" + targetTable + "_" + col)
                            .ruleType(DQCRuleType.NULL_RATE)
                            .level(DQCLevel.WARN)
                            .checkSql("SELECT COUNT(CASE WHEN " + col + " IS NULL THEN 1 END) " +
                                    "/ GREATEST(COUNT(*), 1) FROM " + fullTable)
                            .expectedValue("0.5")
                            .comparator(DQCComparator.LESS_THAN)
                            .build());
                }
                break;

            case "MODIFY_TYPE":
                // 字段类型变更: 检查数据是否能正常解析
                rules.add(DQCRule.builder()
                        .ruleName("lineage_row_count_" + targetTable)
                        .ruleType(DQCRuleType.ROW_COUNT)
                        .level(DQCLevel.BLOCK)
                        .checkSql("SELECT COUNT(*) FROM " + fullTable)
                        .expectedValue("0")
                        .comparator(DQCComparator.GREATER_THAN)
                        .build());
                break;

            case "DROP_TABLE":
                // 表被删除: 检查下游 ETL 是否还能正常产出
                rules.add(DQCRule.builder()
                        .ruleName("lineage_table_exists_" + targetTable)
                        .ruleType(DQCRuleType.ROW_COUNT)
                        .level(DQCLevel.BLOCK)
                        .checkSql("SELECT COUNT(*) FROM " + fullTable +
                                " WHERE dt = CURRENT_DATE")
                        .expectedValue("0")
                        .comparator(DQCComparator.GREATER_THAN)
                        .build());
                break;

            default:
                break;
        }

        return rules;
    }

    /**
     * 查询受影响的下游字段
     */
    private List<String> findAffectedColumns(String sourceDb, String sourceTable,
                                              String sourceColumn,
                                              String targetDb, String targetTable) {
        return jdbc.queryForList(
                "SELECT target_column FROM meta_column_lineage " +
                "WHERE source_db = ? AND source_table = ? AND source_column = ? " +
                "AND target_db = ? AND target_table = ?",
                String.class,
                sourceDb, sourceTable, sourceColumn, targetDb, targetTable);
    }

    // ================================================================
    // 6. 幂等控制
    // ================================================================

    /**
     * 血缘采集幂等控制器
     * 防止同一个 ETL SQL 被重复解析和注册
     */
    public static class LineageIdempotentController {

        private final JdbcTemplate jdbc;

        public LineageIdempotentController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /**
         * 检查 SQL 是否已被采集(基于 SQL 指纹)
         */
        public boolean isAlreadyCollected(String etlJobName, String sql) {
            String sqlHash = DigestUtils.md5Hex(sql);
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM meta_table_lineage " +
                    "WHERE etl_job_name = ? AND sql_hash = ?",
                    Integer.class, etlJobName, sqlHash);
            return count != null && count > 0;
        }

        /**
         * 安全执行血缘采集(幂等)
         */
        public void safeCollect(DataLineageTracker tracker, String etlJobName, String sql) {
            if (isAlreadyCollected(etlJobName, sql)) {
                log.info("[Lineage-Idempotent] SQL已采集,跳过: job={}, sqlHash={}",
                        etlJobName, DigestUtils.md5Hex(sql));
                return;
            }

            // 采集表级血缘
            tracker.parseAndRegisterTableLineage(etlJobName, sql);

            // 采集字段级血缘
            tracker.parseColumnLineage(etlJobName, sql);

            log.info("[Lineage-Idempotent] 血缘采集完成: job={}", etlJobName);
        }
    }
}
```

