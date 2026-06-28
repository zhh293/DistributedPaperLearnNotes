# CQL（Cypher）语法、用法及细节全总结

Cypher是Neo4j原生查询语言，采用声明式语法，专为属性图模型设计，核心目标是简洁高效地处理“节点-关系-属性”的查询、增删改及分析操作。本文全面梳理CQL语法规则、使用场景、核心细节及避坑点，可直接作为手册查阅或下载使用。

# 一、CQL核心基础（必掌握）

## 1.1 核心元素语法

CQL围绕“节点、关系、标签、属性”四大核心元素构建，语法贴近自然语言，以下是基础表达格式：

### 1.1.1 节点（Node）

代表现实世界实体（如用户、商品），用小括号 `()` 表示，支持标签和属性定义。

|语法格式|说明|示例|细节注意|
|---|---|---|---|
|无标签无属性|仅表示节点，无分类和特征|`(n)`|n为节点别名，可自定义，用于后续引用|
|单标签|给节点分类，标签用冒号分隔|`(n:User)`|标签首字母建议大写（规范），不可含空格|
|多标签|一个节点可属于多个分类|`(n:User:Vip)`|标签间用冒号分隔，无顺序要求|
|带属性|属性为键值对，用大括号 `{}` 包裹|`(n:User {id:1, name:"张三", age:25})`|属性值支持String、Int、Float、Boolean、List等类型；字符串用双引号|
### 1.1.2 关系（Relationship）

代表节点间关联，用中括号 `[]` 表示，必须有向、带类型，支持属性，用箭头`->` / `<-` 表示方向。

|语法格式|说明|示例|细节注意|
|---|---|---|---|
|基础关系|带类型的有向关系|`(a:User)-[r:FOLLOWS]->(b:User)`|r为关系别名；关系类型首字母大写，一个关系仅能有一个类型|
|带属性关系|给关系添加特征（如时间、权重）|`(a)-[r:FOLLOWS {time:2026-01-26, mutual:true}]->(b)`|关系属性与节点属性类型一致，可用于筛选和排序|
|无方向关系|查询时忽略方向，底层仍为有向存储|`(a)-[r:FOLLOWS]-(b)`|仅查询可用，创建关系必须指定方向|
### 1.1.3 路径（Path）

表示节点和关系构成的链路，用变量接收，支持指定关系层数。

|语法格式|说明|示例|
|---|---|---|
|固定层数路径|指定关系层数（1层即直接关联）|`p=(a:User)-[r:FOLLOWS*1]->(b:User)`|
|可变层数路径|用 `*min..max` 表示层数范围，max可省略（无上限）|`p=(a)-[*2..3]->(b)`（2-3层关系）；`p=(a)-[*..5]-(b)`（最多5层）|
路径查询层数不宜过多（建议≤5层），否则会因遍历节点过多导致性能下降。

## 1.2 基础语句结构

CQL语句由“子句”组成，常见子句包括 `MATCH`（匹配）、`RETURN`（返回）、`CREATE`（创建）、`WHERE`（筛选）等，子句顺序有严格要求（如 `WHERE` 需在 `MATCH` 之后、`RETURN` 之前）。

# 二、核心操作语法（高频场景）

## 2.1 查询操作（MATCH + RETURN + WHERE）

最常用场景，用于匹配节点/关系并返回结果，支持筛选、排序、分页。

### 2.1.1 基础查询

```cypher


// 1. 查询所有User标签节点，返回name和age
MATCH (n:User)
RETURN n.name, n.age;

// 2. 查询张三关注的所有用户，返回关注对象姓名和关注时间
MATCH (a:User {name:"张三"})-[r:FOLLOWS]->(b:User)
RETURN b.name, r.time;

// 3. 给结果起别名
MATCH (a:User)-[r:FOLLOWS]->(b:User)
RETURN b.name AS 关注对象, r.time AS 关注时间;

```

### 2.1.2 条件筛选（WHERE）

支持比较运算符、逻辑运算符、正则匹配等，筛选节点/关系属性。

```cypher


// 1. 比较筛选（=、>、<、>=、<=、<>）
MATCH (n:User)
WHERE n.age > 25 AND n.gender = "男"
RETURN n.name;

// 2. 逻辑运算（AND、OR、NOT）
MATCH (a:User)-[r:FOLLOWS]->(b:User)
WHERE a.name = "张三" AND r.mutual = true // 互相关注
RETURN b.name;

// 3. 正则匹配（=~）
MATCH (n:User)
WHERE n.name =~ "张.*" // 匹配姓张的用户
RETURN n.name;

// 4. 列表包含（IN）
MATCH (n:User)
WHERE n.id IN [1,2,3] // id在指定列表中
RETURN n.name;

// 5. 空值判断（IS NULL / IS NOT NULL）
MATCH (n:User)
WHERE n.phone IS NOT NULL // 有手机号的用户
RETURN n.name;
```

### 2.1.3 排序与分页（ORDER BY + SKIP + LIMIT）

```cypher


// 按年龄降序排序，跳过前2条，取前5条
MATCH (n:User)
WHERE n.gender = "女"
RETURN n.name, n.age
ORDER BY n.age DESC
SKIP 2
LIMIT 5;

```

分页时建议结合排序使用，否则结果顺序不固定；`LIMIT` 可有效控制返回数据量，优化查询性能。

## 2.2 创建操作（CREATE / MERGE）

### 2.2.1 CREATE（强制创建）

无论节点/关系是否存在，都会创建新数据，可能导致重复。

```cypher


// 1. 创建单个节点
CREATE (n:User {id:4, name:"李四", age:26, gender:"男"});

// 2. 创建节点及关系（批量创建多个）
CREATE 
  (a:User {id:5, name:"王五"}),
  (b:Product {id:1001, name:"手机"}),
  (a)-[r:BUYS {time:2026-01-20, amount:2999}]->(b);

```

### 2.2.2 MERGE（匹配或创建）

先匹配指定节点/关系，存在则复用，不存在则创建，可避免重复数据（推荐使用）。

```cypher


// 1. 匹配或创建用户（按id唯一标识）
MERGE (n:User {id:1, name:"张三"})
ON CREATE SET n.age = 25, n.createTime = timestamp() // 创建时设置额外属性
ON MATCH SET n.updateTime = timestamp() // 匹配时更新时间戳
RETURN n;

// 2. 匹配或创建关系
MERGE (a:User {id:1})-[r:FOLLOWS]->(b:User {id:2})
ON CREATE SET r.time = 2026-01-26
RETURN r;

```

`MERGE` 需基于唯一属性（如id）匹配，否则可能误判为“不存在”而重复创建。

## 2.3 更新与删除操作（SET / REMOVE / DELETE）

### 2.3.1 更新属性（SET）

用于添加、修改节点/关系的属性。

```cypher


// 1. 修改单个属性
MATCH (n:User {name:"张三"})
SET n.age = 26;

// 2. 添加多个属性
MATCH (n:User {name:"张三"})
SET n.phone = "13800138000", n.address = "北京";

// 3. 复制属性（将a的属性复制给b）
MATCH (a:User {id:1}), (b:User {id:2})
SET b.age = a.age;

```

### 2.3.2 删除属性/标签（REMOVE）

`REMOVE` 用于删除节点的标签、节点/关系的属性（区别于`DELETE`，后者删除节点/关系本身）。

```cypher


// 1. 删除节点属性
MATCH (n:User {name:"张三"})
REMOVE n.address;

// 2. 删除节点标签
MATCH (n:User {name:"张三"})
REMOVE n:Vip; // 移除张三的Vip标签

```

### 2.3.3 删除节点/关系（DELETE）

删除节点前必须先删除其关联的所有关系（否则报错，因Neo4j不允许存在“孤点关系”）。

```cypher


// 1. 删除关系
MATCH (a:User {id:1})-[r:FOLLOWS]->(b:User {id:2})
DELETE r;

// 2. 删除节点及关联的所有关系（用 DETACH 关键字）
MATCH (n:User {id:3})
DETACH DELETE n;

```

## 2.4 索引与约束（优化与数据一致性）

索引用于优化属性查询性能，约束用于保证数据唯一性和完整性，底层基于Lucene实现。

### 2.4.1 索引操作

```cypher


// 1. 创建单属性索引（标签+属性）
CREATE INDEX user_id_idx FOR (n:User) ON (n.id);

// 2. 创建复合属性索引（多个属性组合）
CREATE INDEX user_name_phone_idx FOR (n:User) ON (n.name, n.phone);

// 3. 创建全文索引（支持模糊查询）
CREATE FULLTEXT INDEX user_name_ft_idx FOR (n:User) ON EACH [n.name];

// 4. 查询索引
SHOW INDEXES;

// 5. 删除索引
DROP INDEX user_id_idx;

```

1. 索引仅支持节点的“标签+属性”，关系不支持建索引；2. 全文索引查询需用 `lucene.query()` 函数。

### 2.4.2 约束操作

```cypher


// 1. 唯一性约束（保证标签+属性值唯一）
CREATE CONSTRAINT user_id_unique FOR (n:User) REQUIRE n.id IS UNIQUE;

// 2. 节点键约束（比唯一性约束更严格，属性值唯一且非空）
CREATE CONSTRAINT user_id_node_key FOR (n:User) REQUIRE n.id IS NODE KEY;

// 3. 关系属性约束（限制关系属性非空，Neo4j 5.x+支持）
CREATE CONSTRAINT follows_time_not_null FOR ()-[r:FOLLOWS]-() REQUIRE r.time IS NOT NULL;

// 4. 查询约束
SHOW CONSTRAINTS;

// 5. 删除约束
DROP CONSTRAINT user_id_unique;

```

约束会自动创建对应的索引，无需手动重复创建；删除约束时，对应的索引也会被删除。

# 三、高级语法（路径分析与函数）

## 3.1 路径分析函数

Neo4j原生支持路径分析，适用于社交网络、路径规划等场景。

```cypher


// 1. 最短路径（默认按关系数量，也可按权重）
MATCH p=shortestPath((a:User {name:"张三"})-[*..5]-(b:User {name:"赵六"}))
RETURN p;

// 2. 所有最短路径
MATCH p=allShortestPaths((a:User {name:"张三"})-[*..5]-(b:User {name:"赵六"}))
RETURN p;

// 3. 带权重的最短路径（按关系属性排序，如距离、费用）
MATCH p=(a:City {name:"北京"})-[r:ROAD*]-(b:City {name:"上海"})
WITH p, reduce(total=0, rel IN relationships(p) | total + rel.distance) AS totalDistance
RETURN p, totalDistance
ORDER BY totalDistance ASC
LIMIT 1;

```

## 3.2 常用函数

### 3.2.1 聚合函数

```cypher


// COUNT：统计数量
MATCH (n:User)
RETURN COUNT(n) AS 用户总数;

// SUM/AVG/MIN/MAX：求和/平均值/最小值/最大值
MATCH (a:User {name:"张三"})-[r:BUYS]->(b:Product)
RETURN SUM(r.amount) AS 总消费, AVG(r.amount) AS 平均消费;

// COLLECT：将结果转为列表
MATCH (a:User)-[r:FOLLOWS]->(b:User)
RETURN a.name, COLLECT(b.name) AS 关注列表;

```

### 3.2.2 字符串函数

```cypher


MATCH (n:User)
RETURN 
  n.name,
  UPPER(n.name) AS 大写姓名, // 转大写
  LOWER(n.name) AS 小写姓名, // 转小写
  SUBSTRING(n.name, 0, 1) AS 姓氏, // 截取字符串（起始索引，长度）
  LENGTH(n.name) AS 姓名长度; // 字符串长度

```

### 3.2.3 日期与时间函数

```cypher


// timestamp()：获取当前时间戳（毫秒）
CREATE (n:User {name:"孙七", createTime:timestamp()});

// date()/datetime()：获取当前日期/日期时间
MATCH (n:User)
SET n.updateTime = datetime();

```

# 四、事务与批量操作

## 4.1 事务控制

Neo4j支持ACID事务，默认自动提交，也可手动控制事务（适用于多步操作原子性要求）。

```cypher


// 手动事务（客户端API中使用，如Java Driver）
BEGIN TRANSACTION; // 开启事务
MATCH (a:User {id:1})-[r:FOLLOWS]->(b:User {id:2})
DELETE r;
CREATE (a)-[r:FOLLOWS {time:2026-01-26}]->(b);
COMMIT; // 提交事务
// ROLLBACK; // 回滚事务（出错时）

```

## 4.2 批量操作（UNWIND）

用 `UNWIND` 将列表数据展开，实现批量创建、更新，适用于大量数据操作。

```cypher


// 批量创建用户
WITH [
  {id:6, name:"周八", age:24},
  {id:7, name:"吴九", age:27},
  {id:8, name:"郑十", age:23}
] AS userList
UNWIND userList AS user
MERGE (n:User {id:user.id})
ON CREATE SET n.name = user.name, n.age = user.age;

```

批量操作建议控制批次大小（如每批次1000条），避免一次性加载过多数据导致内存溢出。

# 五、语法细节与避坑指南

## 5.1 语法规范

- 关键字不区分大小写（如 `MATCH` / `match` 均可），但标签、关系类型、属性名区分大小写；

- 字符串必须用双引号，单引号不支持；

- 语句结尾用分号分隔，多个子句按“MATCH→WHERE→SET→RETURN”顺序排列。

## 5.2 常见坑点

1. 关系创建必须指定方向，查询时可忽略方向；

2. 删除节点时未删除关联关系，导致报错（需用 `DETACH DELETE`）；

3. 路径查询无层数限制，导致遍历过多节点，性能骤降（建议限制层数≤5）；

4. 用 `CREATE` 重复创建数据（应优先用 `MERGE` 结合唯一属性匹配）；

5. 未建索引直接按属性查询大量数据，导致全量扫描（高频查询属性需建索引）。

## 5.3 性能优化建议

- 高频查询的“标签+属性”组合建索引，避免全量扫描；

- 路径查询限制层数，减少遍历节点数；

- 批量操作分批次执行，控制每批次数据量；

- 避免在 `WHERE` 子句中对属性做函数运算（会导致索引失效）。

# 六、总结

CQL语法核心围绕“属性图模型”设计，简洁直观，重点掌握`MATCH`、`CREATE`、`MERGE`、`WHERE` 等基础子句，结合索引、约束优化性能和数据一致性，再通过路径分析函数和批量操作应对复杂场景。实际使用中需注意语法规范和避坑点，结合业务场景选择合适的语句，兼顾性能和可读性。
> （注：文档部分内容可能由 AI 生成）