# Elasticsearch 文档操作API详解

## 1. 文档基本操作

### 1.1 创建文档 (Index API)

创建文档有两种方式：

**方式1：指定文档ID**
```java
IndexRequest indexRequest = new IndexRequest("index_name");
indexRequest.id("document_id");
indexRequest.source(JSON.toJSONString(user), XContentType.JSON);
IndexResponse response = esClient.index(indexRequest, RequestOptions.DEFAULT);
```

**方式2：自动生成文档ID**
```java
IndexRequest indexRequest = new IndexRequest("index_name");
indexRequest.source(JSON.toJSONString(user), XContentType.JSON);
IndexResponse response = esClient.index(indexRequest, RequestOptions.DEFAULT);
```

### 1.2 获取文档 (Get API)

```java
GetRequest getRequest = new GetRequest("index_name", "document_id");
GetResponse response = esClient.get(getRequest, RequestOptions.DEFAULT);
if(response.isExists()) {
    String sourceAsString = response.getSourceAsString(); // 获取JSON字符串
    Map<String, Object> sourceAsMap = response.getSourceAsMap(); // 获取Map格式
}
```

### 1.3 更新文档 (Update API)

```java
UpdateRequest updateRequest = new UpdateRequest("index_name", "document_id");
UpdateRequest doc = updateRequest.doc(JSON.toJSONString(user), XContentType.JSON);
UpdateResponse response = esClient.update(updateRequest, RequestOptions.DEFAULT);
```

### 1.4 删除文档 (Delete API)

```java
DeleteRequest deleteRequest = new DeleteRequest("index_name", "document_id");
DeleteResponse response = esClient.delete(deleteRequest, RequestOptions.DEFAULT);
```

## 2. 批量操作 (Bulk API)

批量操作是ES最重要的性能优化手段之一，可以在单个请求中执行多个索引/删除/更新操作。

```java
BulkRequest bulkRequest = new BulkRequest();
bulkRequest.timeout("10s");

// 添加多个操作
ArrayList<User> users = new ArrayList<>();
users.add(User.builder().name("张三").age(18).sex("男").build());
users.add(User.builder().name("李四").age(20).sex("女").build());

for(int i = 0; i < users.size(); i++) {
    IndexRequest indexRequest = new IndexRequest("index_name");
    indexRequest.id("" + i);
    indexRequest.source(JSON.toJSONString(users.get(i)), XContentType.JSON);
    bulkRequest.add(indexRequest);
}

BulkResponse bulkResponse = esClient.bulk(bulkRequest, RequestOptions.DEFAULT);
```

## 3. 搜索操作 (Search API)

### 3.1 基础查询

```java
SearchRequest searchRequest = new SearchRequest("index_name");
SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

// match查询 - 全文匹配
sourceBuilder.query(QueryBuilders.matchQuery("field_name", "search_value"));

// term查询 - 精确匹配
sourceBuilder.query(QueryBuilders.termQuery("field_name", "exact_value"));

// wildcard查询 - 通配符查询
sourceBuilder.query(QueryBuilders.wildcardQuery("field_name", "*pattern*"));

searchRequest.source(sourceBuilder);
SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);
```

### 3.2 复合查询 (Bool Query)

```java
sourceBuilder.query(QueryBuilders.boolQuery()
    .must(QueryBuilders.matchQuery("content", "java")) // 必须满足
    .must(QueryBuilders.termQuery("status", "published")) // 必须满足
    .mustNot(QueryBuilders.termQuery("tags", "draft")) // 必须不满足
    .should(QueryBuilders.termQuery("category", "tech")) // 满足更好，不满足也可
    .minimumShouldMatch(1) // should子句中至少满足的数量
);
```

### 3.3 范围查询

```java
// 数值范围查询
sourceBuilder.query(QueryBuilders.rangeQuery("age")
    .gte(18) // >= 18
    .lte(65)); // <= 65

// 日期范围查询
sourceBuilder.query(QueryBuilders.rangeQuery("create_date")
    .gte("2023-01-01")
    .lt("2024-01-01")
    .format("yyyy-MM-dd"));
```

### 3.4 模糊查询

```java
// 模糊查询 - 支持拼写错误容忍
sourceBuilder.query(QueryBuilders.fuzzyQuery("title", "elasticsearch")
    .fuzziness(Fuzziness.AUTO));

// 前缀查询
sourceBuilder.query(QueryBuilders.prefixQuery("name", "zhang"));

// 通配符查询
sourceBuilder.query(QueryBuilders.wildcardQuery("email", "*@gmail.com"));
```

## 4. 高级搜索功能

### 4.1 分页 (From & Size)

```java
sourceBuilder.from(0); // 从第0条开始
sourceBuilder.size(10); // 每页10条
```

### 4.2 排序

```java
// 单字段排序
sourceBuilder.sort("create_time", SortOrder.DESC);

// 多字段排序
sourceBuilder.sort("create_time", SortOrder.DESC);
sourceBuilder.sort("priority", SortOrder.ASC);
sourceBuilder.sort(SortBuilders.scoreSort()); // 按相关性得分排序
```

### 4.3 高亮 (Highlight)

```java
HighlightBuilder highlightBuilder = new HighlightBuilder();
HighlightBuilder.Field highlightTitle = new HighlightBuilder.Field("title");
highlightTitle.preTags("<em>");
highlightTitle.postTags("</em>");
highlightBuilder.field(highlightTitle);

sourceBuilder.highlighter(highlightBuilder);
```

### 4.4 聚合查询 (Aggregations)

```java
// 按字段分组统计
AggregationBuilder aggregation = AggregationBuilders
    .terms("by_category")
    .field("category");

// 求和聚合
AggregationBuilder sumAgg = AggregationBuilders
    .sum("total_sales")
    .field("sales_amount");

// 平均值聚合
AggregationBuilder avgAgg = AggregationBuilders
    .avg("avg_price")
    .field("price");

// 日期范围聚合
AggregationBuilder dateHistAgg = AggregationBuilders
    .dateHistogram("daily_count")
    .field("create_time")
    .calendarInterval(DateHistogramInterval.DAY);

sourceBuilder.aggregation(aggregation);
```

### 4.5 过滤器 (Filter Context)

```java
// filter上下文不计算相关性得分，性能更好
BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
boolQuery.filter(QueryBuilders.rangeQuery("age").gte(18).lte(65));
boolQuery.filter(QueryBuilders.termQuery("status", "active"));
sourceBuilder.query(boolQuery);
```

### 4.6 搜索模板 (Script Query)

```java
// 脚本查询 - 复杂条件判断
Map<String, Object> params = new HashMap<>();
params.put("factor", 1.2);
sourceBuilder.query(QueryBuilders.scriptQuery(
    new Script(ScriptType.INLINE, "painless",
        "doc['price'].value * params.factor > params.threshold",
        params)));
```

## 5. 批量更新 (Update By Query)

```java
UpdateByQueryRequest updateByQueryRequest = new UpdateByQueryRequest("index_name");
updateByQueryRequest.setConflicts("proceed"); // 处理版本冲突
updateByQueryRequest.setQuery(QueryBuilders.termQuery("status", "pending"));

// 设置更新脚本
Map<String, Object> params = new HashMap<>();
params.put("newStatus", "processed");
updateByQueryRequest.setScript(new Script(ScriptType.INLINE, "painless",
    "ctx._source.status = params.newStatus", params));

BulkByScrollResponse response = esClient.updateByQuery(updateByQueryRequest, RequestOptions.DEFAULT);
```

## 6. 批量删除 (Delete By Query)

```java
DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest("index_name");
deleteByQueryRequest.setQuery(QueryBuilders.rangeQuery("create_time").lt("2023-01-01"));
deleteByQueryRequest.setConflicts("proceed");

BulkByScrollResponse response = esClient.deleteByQuery(deleteByQueryRequest, RequestOptions.DEFAULT);
```

## 7. 滚动查询 (Scroll API)

用于深度分页，避免深分页性能问题：

```java
SearchRequest searchRequest = new SearchRequest("index_name");
SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
searchSourceBuilder.query(QueryBuilders.matchAllQuery());
searchSourceBuilder.size(1000); // 每次获取1000条
searchRequest.source(searchSourceBuilder);
searchRequest.scroll(TimeValue.timeValueMinutes(1)); // 滚动窗口1分钟

// 执行首次搜索
SearchResponse searchResponse = esClient.search(searchRequest, RequestOptions.DEFAULT);
String scrollId = searchResponse.getScrollId();
SearchHit[] hits = searchResponse.getHits().getHits();

while(hits != null && hits.length > 0) {
    // 处理当前批次的数据
    for(SearchHit hit : hits) {
        // 处理文档
    }
    
    // 继续滚动获取下一批数据
    SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
    scrollRequest.scroll(TimeValue.timeValueMinutes(1));
    searchResponse = esClient.scroll(scrollRequest, RequestOptions.DEFAULT);
    scrollId = searchResponse.getScrollId();
    hits = searchResponse.getHits().getHits();
}

// 清理滚动上下文
ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
clearScrollRequest.addScrollId(scrollId);
esClient.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
```

## 8. 搜索模板 (Search Templates)

```java
// 定义搜索模板
String template = "{\n" +
    "  \"query\": {\n" +
    "    \"match\": {\n" +
    "      \"{{field}}\": \"{{value}}\"\n" +
    "    }\n" +
    "  }\n" +
    "}";

PutTemplateRequest putTemplateRequest = new PutTemplateRequest("simple_template", template);
esClient.putTemplate(putTemplateRequest, RequestOptions.DEFAULT);

// 使用模板
Map<String, Object> params = new HashMap<>();
params.put("field", "title");
params.put("value", "elasticsearch");

RenderSearchTemplateRequest renderRequest = new RenderSearchTemplateRequest(template);
renderRequest.setParams(params);
RenderSearchTemplateResponse response = esClient.renderSearchTemplate(renderRequest, RequestOptions.DEFAULT);
```

## 9. 地理空间查询

```java
// 地理点查询
sourceBuilder.query(QueryBuilders.geoDistanceQuery("location")
    .point(40.7128, -74.0060) // 纽约坐标
    .distance("5km")); // 5公里范围内

// 地理边界框查询
sourceBuilder.query(QueryBuilders.geoBoundingBoxQuery("location")
    .setCorners(40.73, -74.1, 40.01, -71.12));

// 地理多边形查询
List<GeoPoint> points = Arrays.asList(
    new GeoPoint(40.73, -74.1),
    new GeoPoint(40.01, -71.12),
    new GeoPoint(41.89, -71.35)
);
sourceBuilder.query(QueryBuilders.geoPolygonQuery("location", points));
```

## 10. 多搜索 API (Multi Search)

```java
MultiSearchRequest request = new MultiSearchRequest();

// 添加第一个搜索请求
SearchRequest sr1 = new SearchRequest("index1");
SearchSourceBuilder ssb1 = new SearchSourceBuilder();
ssb1.query(QueryBuilders.matchQuery("field1", "value1"));
sr1.source(ssb1);
request.add(sr1);

// 添加第二个搜索请求
SearchRequest sr2 = new SearchRequest("index2");
SearchSourceBuilder ssb2 = new SearchSourceBuilder();
ssb2.query(QueryBuilders.termQuery("field2", "value2"));
sr2.source(ssb2);
request.add(sr2);

MultiSearchResponse response = esClient.msearch(request, RequestOptions.DEFAULT);
```

## 11. 索引别名操作

```java
IndicesAliasesRequest request = new IndicesAliasesRequest();
IndicesAliasesRequest.AliasActions aliasAction = new IndicesAliasesRequest.AliasActions(
    IndicesAliasesRequest.AliasActions.Type.ADD)
    .index("index_name")
    .alias("alias_name");

request.addAliasAction(aliasAction);
AcknowledgedResponse response = esClient.indices().updateAliases(request, RequestOptions.DEFAULT);
```

## 12. 性能优化技巧

### 12.1 查询优化
- 使用filter context而非query context进行过滤
- 合理设置分片数量
- 使用preference参数优化缓存命中

### 12.2 写入优化
- 批量写入优于单条写入
- 调整refresh_interval参数
- 使用bulk API进行大量数据导入

### 12.3 内存优化
- 合理设置fielddata缓存
- 使用doc_values优化排序和聚合
- 避免加载不必要的_source字段

## 13. 错误处理与最佳实践

### 13.1 异常处理
```java
try {
    SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);
    // 处理响应
} catch (ElasticsearchException e) {
    if(e.isServerSideError()) {
        // 服务端错误
    } else {
        // 客户端错误
    }
}
```

### 13.2 连接池配置
```java
RestClientBuilder builder = RestClient.builder(
    new HttpHost("localhost", 9200))
    .setRequestConfigCallback(requestConfigBuilder -> 
        requestConfigBuilder
            .setConnectTimeout(5000)
            .setSocketTimeout(60000)
            .setConnectionRequestTimeout(5000))
    .setHttpClientConfigCallback(httpClientBuilder -> 
        httpClientBuilder
            .setMaxConnTotal(100)
            .setMaxConnPerRoute(100));
```
