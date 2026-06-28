# Elasticsearch基础架构详解

## 1. Elasticsearch基本组成

### 1.1 核心组件
Elasticsearch是一个分布式的搜索和分析引擎，主要由以下几个核心组件构成：

- **Node（节点）**：单个Elasticsearch实例，可以是集群中的一个节点
- **Cluster（集群）**：一个或多个节点的集合，共同持有数据并提供联合索引和搜索功能
- **Index（索引）**：文档的集合，类似于关系型数据库中的"数据库"
- **Shard（分片）**：索引的水平分割，用于数据分布和并行处理
- **Replica（副本）**：分片的副本，用于故障恢复和负载均衡

### 1.2 集群架构
```
[Cluster: my-cluster]
├── Node 1 (Master eligible)
│   ├── Shard 0 (Primary)
│   └── Shard 2 (Replica)
├── Node 2
│   ├── Shard 1 (Primary)
│   └── Shard 0 (Replica)
└── Node 3
    ├── Shard 2 (Primary)
    └── Shard 1 (Replica)
```

## 2. 索引结构

### 2.1 索引概述
索引是Elasticsearch中存储相关文档的地方，具有以下特点：
- 索引是逻辑命名空间，用于组织文档
- 每个索引可以包含多个类型（在ES 7.x之前）
- 索引名称必须小写
- 索引可以配置映射（mapping）和设置（settings）

### 2.2 索引设置（Index Settings）
```json
{
  "settings": {
    "number_of_shards": 5,           // 主分片数量
    "number_of_replicas": 1,         // 副本分片数量
    "refresh_interval": "1s",        // 刷新间隔
    "analysis": {                    // 分析器配置
      "analyzer": {
        "my_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "stop"]
        }
      }
    }
  }
}
```

### 2.3 索引映射（Index Mapping）
映射定义了索引中每个字段的数据类型和配置：

```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",               // 字段类型
        "analyzer": "standard",       // 分析器
        "boost": 2.0                  // 权重
      },
      "status": {
        "type": "keyword",            // 精确匹配类型
        "doc_values": true,           // 是否启用doc values
        "ignore_above": 256           // 忽略超过256字符的值
      }
    }
  }
}
```

## 3. 文档结构

### 3.1 文档概述
文档是Elasticsearch中存储的基本单位，具有以下特征：
- 以JSON格式存储
- 每个文档都有唯一的ID
- 文档存储在特定的索引中
- 文档包含多个字段

### 3.2 文档格式
```json
{
  "_index": "blog_posts",           // 文档所在的索引
  "_type": "_doc",                  // 文档类型（ES 7.x后固定为_doc）
  "_id": "1",                       // 文档唯一标识符
  "_version": 1,                    // 文档版本号
  "_score": 1.0,                    // 相关性得分（搜索结果中）
  "_source": {                      // 原始文档内容
    "title": "Elasticsearch Basics",
    "content": "Introduction to Elasticsearch...",
    "author": "John Doe",
    "published_date": "2023-01-01",
    "tags": ["elasticsearch", "search", "tutorial"]
  }
}
```

### 3.3 文档元数据字段
Elasticsearch自动为每个文档添加以下元数据字段：

- **_index**：文档所属的索引名称
- **_id**：文档的唯一标识符
- **_type**：文档类型（ES 7.x后固定为_doc）
- **_version**：文档的版本号，用于并发控制
- **_seq_no**：文档的序列号，用于乐观并发控制
- **_primary_term**：主分片的任期，与_seq_no一起用于并发控制
- **_score**：文档的相关性得分（搜索时）

## 4. 字段结构和属性

### 4.1 核心数据类型

#### 4.1.1 字符串类型
- **text**：用于全文搜索，会被分析器处理
  ```json
  {
    "title": {
      "type": "text",
      "analyzer": "standard",        // 分析器
      "search_analyzer": "standard", // 搜索时使用的分析器
      "boost": 1.2                   // 字段权重
    }
  }
  ```

- **keyword**：用于精确匹配、排序和聚合
  ```json
  {
    "status": {
      "type": "keyword",
      "doc_values": true,            // 启用doc values用于排序和聚合
      "ignore_above": 256,           // 忽略超过指定长度的值
      "null_value": "N/A"            // 空值替换
    }
  }
  ```

#### 4.1.2 数值类型
- **integer**, **long**, **float**, **double**：不同精度的数值类型
  ```json
  {
    "view_count": {
      "type": "integer",
      "coerce": true,                // 是否强制类型转换
      "null_value": 0                // 空值替换
    }
  }
  ```

#### 4.1.3 日期类型
```json
{
  "created_at": {
    "type": "date",
    "format": "strict_date_optional_time||epoch_millis",  // 日期格式
    "null_value": "2020-01-01"      // 空值替换
  }
}
```

#### 4.1.4 布尔类型
```json
{
  "is_published": {
    "type": "boolean",
    "null_value": false             // 空值替换
  }
}
```

#### 4.1.5 二进制类型
```json
{
  "image_data": {
    "type": "binary",
    "doc_values": false             // binary类型默认不启用doc_values
  }
}
```

### 4.2 复杂数据类型

#### 4.2.1 对象类型（Object）
```json
{
  "user": {
    "type": "object",
    "properties": {
      "first_name": { "type": "text" },
      "last_name": { "type": "text" },
      "age": { "type": "integer" }
    }
  }
}
```

#### 4.2.2 嵌套类型（Nested）
```json
{
  "comments": {
    "type": "nested",
    "properties": {
      "author": { "type": "text" },
      "content": { "type": "text" },
      "date": { "type": "date" }
    }
  }
}
```

#### 4.2.3 数组类型
Elasticsearch中字段默认支持数组形式，无需特殊声明：
```json
{
  "tags": ["elasticsearch", "search", "tutorial"]  // 自动作为字符串数组处理
}
```

### 4.3 地理空间类型

#### 4.3.1 地理点类型（geo_point）
```json
{
  "location": {
    "type": "geo_point"
  }
}
```

#### 4.3.2 地理形状类型（geo_shape）
```json
{
  "area": {
    "type": "geo_shape"
  }
}
```

### 4.4 字段属性详解

#### 4.4.1 通用字段属性
- **enabled**：是否启用字段索引（默认true）
- **doc_values**：是否为字段启用列式存储，用于排序和聚合（默认true）
- **norms**：是否存储字段长度归一化因子（影响评分）
- **store**：是否存储原始字段值（默认false，仅存储在_source中）
- **null_value**：字段为空时的替换值
- **copy_to**：将多个字段值复制到一个组合字段

#### 4.4.2 文本字段特有属性
- **analyzer**：索引时使用的分析器
- **search_analyzer**：搜索时使用的分析器
- **search_quote_analyzer**：搜索短语时使用的分析器
- **boost**：字段在查询时的权重
- **eager_global_ordinals**：是否启用全局序数优化
- **fielddata**：是否为text字段启用fielddata（用于排序和聚合）

#### 4.4.3 Keyword字段特有属性
- **ignore_above**：忽略超过指定长度的值
- **split_queries_on_whitespace**：是否在空格处拆分查询
- **normalizer**：标准化器，用于规范化keyword字段

## 5. 分片机制

### 5.1 分片概述
- **Primary Shard（主分片）**：数据的主要存储分片
- **Replica Shard（副本分片）**：主分片的副本，用于容错和扩展

### 5.2 分片分配
- 每个文档通过哈希算法确定存储在哪个主分片
- 副本分片分布在不同的节点上以提高可用性
- 分片数量在索引创建时确定，之后不能更改

### 5.3 分片路由
```json
# 路由公式：shard = hash(routing) % number_of_primary_shards
{
  "routing": "user123"              // 自定义路由值
}
```

## 6. 倒排索引结构

### 6.1 倒排索引组成
- **Term Dictionary（词典）**：所有唯一词汇的有序列表
- **Postings List（倒排列表）**：包含文档ID、词频、位置等信息
- **Doc Values**：列式存储，用于排序和聚合

### 6.2 索引过程
1. 文档被分析器处理，分解为词汇
2. 词汇和文档信息被添加到倒排索引
3. 定期刷新到磁盘形成新的segment
4. 合并segments以优化性能

## 7. 集群状态管理

### 7.1 集群状态信息
- 集群中所有索引及其映射
- 集群中所有节点的信息
- 所有分片的分配情况
- 集群级设置

### 7.2 Master节点职责
- 创建或删除索引
- 分配分片到节点
- 维护集群状态

## 8. 数据可靠性保障

### 8.1 写一致性
- **quorum**：majority of active shards (default)
- **one**：at least one shard
- **all**：all active shards

### 8.2 刷新机制
- **refresh_interval**：控制索引可见性刷新频率
- **translog**：事务日志，确保数据不丢失

### 8.3 段合并
- 定期合并小的segments为大的segments
- 提高搜索性能
- 回收磁盘空间

## 9. 性能优化要点

### 9.1 索引优化
- 合理设置分片数量
- 使用合适的映射类型
- 配置适当的分析器

### 9.2 查询优化
- 使用filter上下文而非query上下文进行过滤
- 合理使用doc_values和fielddata
- 避免深度分页

### 9.3 存储优化
- 使用合理的字段属性设置
- 启用压缩选项
- 合理设置副本数量

这份架构文档涵盖了Elasticsearch的基础架构和核心概念，有助于理解ES的内部工作机制，为后续学习和使用提供坚实的基础。