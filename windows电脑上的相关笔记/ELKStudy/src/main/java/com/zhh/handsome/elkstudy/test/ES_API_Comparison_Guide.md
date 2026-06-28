# Elasticsearch API对比指南：新旧版本API转换详解

## 概述

Elasticsearch Java API经历了重大变化，从7.x版本的High Level REST Client到8.x版本的Elasticsearch Client。本文档将详细介绍这两种API的差异以及如何在它们之间进行转换。

## 1. API架构差异

### 1.1 旧版API (High Level REST Client)
- 使用[SearchSourceBuilder](file:///E:/ELKStudy/src/main/java/com/zhh/handsome/elkstudy/test/Rest风格.java#L248-L248)构建查询
- 依赖QueryBuilders创建查询条件
- 使用RestHighLevelClient作为客户端

### 1.2 新版API (Elasticsearch Client 8.x)
- 使用类型安全的DSL构建查询
- 直接使用ElasticsearchClient
- 通过lambda表达式构建查询

## 2. 核心概念转换对照表

| 旧版API | 新版API | 说明 |
|--------|--------|------|
| [SearchSourceBuilder](file:///E:/ELKStudy/src/main/java/com/zhh/handsome/elkstudy/test/Rest风格.java#L248-L248) | `SearchRequest.of()` | 查询构建器 |
| `QueryBuilders.matchQuery()` | `Query.of(q -> q.match())` | Match查询 |
| `QueryBuilders.termQuery()` | `Query.of(q -> q.term())` | Term查询 |
| `HighlightBuilder` | `Highlight.Builder` | 高亮构建器 |
| `RestHighLevelClient` | `ElasticsearchClient` | 客户端 |

## 3. 代码转换示例

### 3.1 原始新版API代码（您提到的部分）

```java
// 原始代码 - 使用新版API
Query query = Query.of(q -> q
        .match(m -> m
                .field(fieldName)
                .query(FieldValue.of(value))
        )
);

Highlight.Builder highlightBuilder = buildHighlight(highlightFields);

SearchRequest request = SearchRequest.of(s -> s
        .index(indexName)
        .query(query)
        .highlight(highlightBuilder.build())
);

SearchResponse<User> response = elasticsearchClient.search(request, User.class);
```

### 3.2 转换为旧版API代码

```java
// 转换后的代码 - 使用旧版API
SearchRequest searchRequest = new SearchRequest(indexName);
SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

// 构建match查询
sourceBuilder.query(QueryBuilders.matchQuery(fieldName, value));

// 构建高亮设置
HighlightBuilder highlightBuilder = buildHighlight(highlightFields);
sourceBuilder.highlighter(highlightBuilder);

// 绑定查询到请求
searchRequest.source(sourceBuilder);

// 执行搜索
SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
```

## 4. 详细转换步骤

### 4.1 查询条件转换

#### Match Query
- **新版**: `Query.of(q -> q.match(m -> m.field(fieldName).query(FieldValue.of(value))))`
- **旧版**: `QueryBuilders.matchQuery(fieldName, value)`

#### Term Query
- **新版**: `Query.of(q -> q.term(t -> t.field(fieldName).value(v -> v.stringValue(value))))`
- **旧版**: `QueryBuilders.termQuery(fieldName, value)`

#### Bool Query
- **新版**:
```java
Query.of(q -> q.bool(b -> b
    .must(m -> m.term(t -> t.field("field1").value(FieldValue.of("value1"))))
    .mustNot(m -> m.term(t -> t.field("field2").value(FieldValue.of("value2"))))
))
```

- **旧版**:
```java
QueryBuilders.boolQuery()
    .must(QueryBuilders.termQuery("field1", "value1"))
    .mustNot(QueryBuilders.termQuery("field2", "value2"))
```

### 4.2 高亮设置转换

#### 新版API高亮构建
```java
Highlight.Builder highlightBuilder = new Highlight.Builder();
for (String field : highlightFields) {
    highlightBuilder.fields(field, new HighlightField.Builder()
            .preTags("<mark>")
            .postTags("</mark>")
            .build());
}
```

#### 旧版API高亮构建
```java
HighlightBuilder highlightBuilder = new HighlightBuilder();
for (String field : highlightFields) {
    highlightBuilder.field(field);
}
highlightBuilder.preTags("<mark>").postTags("</mark>");
```

### 4.3 分页设置转换

#### 新版API
```java
SearchRequest.of(s -> s
    .from((page - 1) * size)
    .size(size)
)
```

#### 旧版API
```java
SearchSourceBuilder.from((page - 1) * size).size(size)
```

## 5. 实际应用转换示例

### 5.1 完整的高亮搜索方法对比

#### 新版API实现
```java
public List<HighlightSearchResult> highlightMatchSearch(String indexName, String fieldName, String value, List<String> highlightFields) throws IOException {
    // 构建查询条件
    Query query = Query.of(q -> q
            .match(m -> m
                    .field(fieldName)
                    .query(FieldValue.of(value))
            )
    );

    // 构建高亮设置
    Highlight.Builder highlightBuilder = buildHighlight(highlightFields);

    // 构建搜索请求
    SearchRequest request = SearchRequest.of(s -> s
            .index(indexName)
            .query(query)
            .highlight(highlightBuilder.build())
    );

    SearchResponse<User> response = elasticsearchClient.search(request, User.class);

    // 将结果转换为包含高亮信息的对象
    return response.hits().hits().stream()
            .map(hit -> mapToHighlightSearchResult(hit))
            .collect(Collectors.toList());
}
```

#### 旧版API实现
```java
public List<HighlightSearchResult> highlightMatchSearch(String indexName, String fieldName, String value, List<String> highlightFields) throws IOException {
    // 1. 创建搜索请求
    SearchRequest searchRequest = new SearchRequest(indexName);

    // 2. 使用SearchSourceBuilder构建查询条件
    SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

    // 构建match查询
    sourceBuilder.query(QueryBuilders.matchQuery(fieldName, value));

    // 构建高亮设置
    HighlightBuilder highlightBuilder = buildHighlight(highlightFields);
    sourceBuilder.highlighter(highlightBuilder);

    // 3. 将SearchSourceBuilder绑定到SearchRequest
    searchRequest.source(sourceBuilder);

    // 4. 执行搜索
    SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);

    // 5. 将结果转换为包含高亮信息的对象
    return convertToHighlightResults(response);
}
```

## 6. 优缺点对比

### 6.1 新版API优点
- 类型安全：编译时检查，减少运行时错误
- 代码简洁：链式调用更加直观
- 维护性好：官方推荐，持续更新

### 6.2 旧版API优点
- 社区资料丰富：大量教程和示例
- 熟悉度高：许多老项目仍在使用
- 学习成本低：API相对简单直接

### 6.3 旧版API缺点
- 不再维护：ES 8.x后已废弃
- 缺乏类型安全：容易出现运行时错误
- 性能略低：不如新版API优化

## 7. 迁移建议

### 7.1 何时使用旧版API
- 维护老项目时
- 团队对旧版API更熟悉
- 项目暂时无法升级ES版本

### 7.2 何时使用新版API
- 新项目开发
- 现有项目重构
- 需要更好的类型安全

## 8. 学习建议

如果您觉得ES语句难写，建议：

1. **先掌握基础概念**：理解query vs filter、text vs keyword等基本概念
2. **从简单查询开始**：先练习match、term等简单查询
3. **逐步增加复杂度**：添加高亮、分页、聚合等功能
4. **多动手实践**：编写实际代码并运行测试
5. **查阅官方文档**：ES官方文档提供了丰富的示例

## 9. 常见问题解答

### 9.1 如何选择API版本？
- 新项目：直接使用新版API
- 老项目：根据升级计划决定是否迁移

### 9.2 如何快速上手ES查询？
- 使用Kibana的Dev Tools进行实验
- 从简单的GET请求开始，逐步构建复杂查询
- 理解查询的JSON结构

### 9.3 如何调试ES查询？
- 使用ES的profile功能
- 检查查询的explain结果
- 使用Kibana的查询分析工具

通过以上对比和示例，您可以更好地理解两种API的差异，并根据项目需求选择合适的实现方式。