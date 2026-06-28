package com.zhh.handsome.elkstudy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// 扫描所有子包中的组件
public class ElkStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElkStudyApplication.class, args);
    }

}





/*是的，你的理解是正确的。

在 Elasticsearch 7.8 版本之后，`type`（类型）的概念已经被移除，**一个索引（index）直接对应多个文档（document）**，不再支持在一个索引中定义多个类型。这相当于：

        - **MySQL 中的 Database + Table** → **Elasticsearch 中的 Index**
        - **MySQL 中的 Row** → **Elasticsearch 中的 Document**

        ### ✅ 具体变化说明

| MySQL 结构 | Elasticsearch 7.8+ 对应 |
        |------------|------------------------|
        | `Database` | `Index`（索引） |
        | `Table` | ❌ 已废弃，不再使用 `type` |
        | `Row` | `Document`（文档） |
        | `Column` | `Field`（字段） |

        > 📌 举个例子：
        > 在旧版本 ES 中，你可能会有：
        > ```json
> PUT /user_index/user/1
        > ```

        > 其中 `user_index` 是索引，`user` 是 type。
        > 但从 7.8 开始，只能写成：
        > ```json
> PUT /user_index/_doc/1
        > ```

        > 即：`_doc` 是默认且唯一的 type。

        ### 🔍 总结

- **ES 的 index 等价于 MySQL 的 database + table 的组合**
        - 每个 index 存储一组结构相似的 document
- 不再支持多 type，简化了数据模型设计

这种设计使得 Elasticsearch 更加轻量、灵活，更适合现代搜索和分析场景。







是的，你的理解基本正确。

在 Elasticsearch 7.8 版本之后，`type` 概念被移除，一个索引（`index`）不再支持多个类型（`type`），因此：

- **一个索引等价于一个数据库中的单张表**
- 所有文档（`document`）都存储在这个唯一的“表”中
- 不再能在一个索引中区分不同类型的实体（如用户、订单等）

### ✅ 简要总结

| 概念 | 对应关系 |
|------|----------|
| `index` | 类似 MySQL 中的 `table` |
| `document` | 类似 MySQL 中的 `row` |
| `field` | 类似 MySQL 中的 `column` |

> ⚠️ 注意：虽然结构上类似于“一个数据库只有一张表”，但 Elasticsearch 的设计目标是**面向文档的搜索与分析**，而非传统的关系型数据管理。它通过灵活的 schema 和强大的全文检索能力来弥补这一差异。

所以可以理解为：
👉 **Elasticsearch 的一个索引 = 一张逻辑上的表，但更灵活、更适合非结构化或半结构化数据的搜索场景**。




