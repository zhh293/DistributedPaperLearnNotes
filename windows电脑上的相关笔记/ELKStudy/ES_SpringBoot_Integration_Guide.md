# Elasticsearch 在 Spring Boot 中的集成与使用指南

## 目录
1. [概述](#概述)
2. [环境准备](#环境准备)
3. [依赖配置](#依赖配置)
4. [基础配置](#基础配置)
5. [客户端配置](#客户端配置)
6. [API使用详解](#api使用详解)
7. [高级特性](#高级特性)
8. [最佳实践](#最佳实践)
9. [常见问题](#常见问题)

## 概述

Elasticsearch (ES) 是一个分布式的开源搜索和分析引擎，常用于全文搜索、日志分析、实时数据分析等场景。在 Spring Boot 应用中集成 Elasticsearch 可以帮助我们快速构建搜索功能。

### 核心概念回顾

- **Index (索引)**: 相当于关系型数据库中的数据库 + 表的组合，在 ES 7.8+ 中，type 概念被废弃，一个索引只包含一个类型
- **Document (文档)**: 相当于关系型数据库中的一行记录
- **Field (字段)**: 相当于关系型数据库中的列
- **Mapping (映射)**: 定义文档结构和字段类型

## 环境准备

### 软件要求

- Java 8+ (推荐 Java 17)
- Spring Boot 2.7+ 或 3.x
- Elasticsearch 7.17+ (兼容性更好) 或 8.x+
- Maven 或 Gradle

### 启动 Elasticsearch

确保本地或服务器上已启动 Elasticsearch 服务，默认端口为 9200：

```bash
# 检查 ES 是否正常运行
curl http://localhost:9200
```

## 依赖配置

### Maven 依赖

根据 Elasticsearch 版本选择合适的客户端依赖：

#### 方案一：使用 Elasticsearch Java API Client (推荐 - ES 8.x)

```xml
<dependencies>
    <!-- Spring Boot Starter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- Elasticsearch Java Client (ES 8.x) -->
    <dependency>
        <groupId>co.elastic.clients</groupId>
        <artifactId>elasticsearch-java</artifactId>
        <version>8.11.0</version>
    </dependency>
    
    <!-- Elasticsearch REST Client -->
    <dependency>
        <groupId>org.elasticsearch.client</groupId>
        <artifactId>elasticsearch-rest-client</artifactId>
        <version>8.11.0</version>
    </dependency>
    
    <!-- Jackson JSON Processor -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    
    <!-- JSON Processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
</dependencies>
```

#### 方案二：使用 Elasticsearch High Level Client (ES 7.x)

```xml
<dependencies>
    <!-- Elasticsearch High Level Client (ES 7.x) -->
    <dependency>
        <groupId>org.elasticsearch.client</groupId>
        <artifactId>elasticsearch-rest-high-level-client</artifactId>
        <version>7.17.0</version>
    </dependency>
</dependencies>
```

### Gradle 依赖

```gradle
dependencies {
    implementation 'co.elastic.clients:elasticsearch-java:8.11.0'
    implementation 'org.elasticsearch.client:elasticsearch-rest-client:8.11.0'
    implementation 'com.fasterxml.jackson.core:jackson-databind'
}
```

## 基础配置

### 1. 配置文件 (application.yml)

```yaml
elasticsearch:
  host: localhost
  port: 9200
  scheme: http
  connect-timeout: 5s
  socket-timeout: 60s
  connection-request-timeout: 5s
  max-connect-per-route: 10
  max-connect-total: 30
```

### 2. 配置属性类

```java
@ConfigurationProperties(prefix = "elasticsearch")
@Data
@Component
public class ElasticsearchProperties {
    private String host = "localhost";
    private int port = 9200;
    private String scheme = "http";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration socketTimeout = Duration.ofMinutes(1);
    private Duration connectionRequestTimeout = Duration.ofSeconds(5);
    private int maxConnectPerRoute = 10;
    private int maxConnectTotal = 30;
}
```

## 客户端配置

### 1. Elasticsearch Java Client 配置 (ES 8.x)

```java
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {
    
    @Bean
    @Primary
    public ElasticsearchClient elasticsearchClient(ElasticsearchProperties properties) {
        // 创建低级客户端
        RestClient restClient = RestClient.builder(
                new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme())
        )
        .setRequestConfigCallback(requestConfigBuilder -> 
            requestConfigBuilder
                .setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .setSocketTimeout(Math.toIntExact(properties.getSocketTimeout().toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(properties.getConnectionRequestTimeout().toMillis()))
        )
        .setHttpClientConfigCallback(httpClientBuilder -> 
            httpClientBuilder
                .setMaxConnPerRoute(properties.getMaxConnectPerRoute())
                .setMaxConnTotal(properties.getMaxConnectTotal())
        )
        .build();
        
        // 创建传输层
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
                
        // 创建高阶客户端
        return new ElasticsearchClient(transport);
    }
}
```

### 2. 旧版 High Level Client 配置 (ES 7.x)

```java
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {
    
    @Bean
    @Primary
    public RestHighLevelClient restHighLevelClient(ElasticsearchProperties properties) {
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()));
        
        builder.setRequestConfigCallback(requestConfigBuilder -> 
            requestConfigBuilder
                .setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .setSocketTimeout(Math.toIntExact(properties.getSocketTimeout().toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(properties.getConnectionRequestTimeout().toMillis())));
        
        builder.setHttpClientConfigCallback(httpClientBuilder -> 
            httpClientBuilder
                .setMaxConnPerRoute(properties.getMaxConnectPerRoute())
                .setMaxConnTotal(properties.getMaxConnectTotal()));
        
        return new RestHighLevelClient(builder);
    }
}
```

## API使用详解

### 1. 索引操作

#### 创建索引

```java
@Service
public class IndexService {
    
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    /**
     * 创建索引
     */
    public boolean createIndex(String indexName) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest.Builder()
            .index(indexName)
            .mappings(createMapping())
            .settings(createSettings())
            .build();
            
        CreateIndexResponse response = elasticsearchClient.indices().create(request);
        return response.acknowledged();
    }
    
    /**
     * 删除索引
     */
    public boolean deleteIndex(String indexName) throws IOException {
        DeleteIndexResponse response = elasticsearchClient.indices()
            .delete(new DeleteIndexRequest.Builder().index(indexName).build());
        return response.acknowledged();
    }
    
    /**
     * 检查索引是否存在
     */
    public boolean existsIndex(String indexName) throws IOException {
        return elasticsearchClient.indices()
            .exists(new ExistsRequest.Builder().index(indexName).build());
    }
    
    private TypeMapping createMapping() {
        return TypeMapping.of(mapping -> mapping
            .properties("name", Property.of(p -> p.text(t -> t.analyzer("standard"))))
            .properties("age", Property.of(p -> p.integer(i -> i)))
            .properties("sex", Property.of(p -> p.keyword(k -> k)))
            .properties("tags", Property.of(p -> p.keyword(k -> k)))
        );
    }
    
    private Settings createSettings() {
        return Settings.of(settings -> settings
            .numberOfShards("1")
            .numberOfReplicas("1")
        );
    }
}
```

#### 索引别名操作

```java
/**
 * 添加索引别名
 */
public boolean addAlias(String indexName, String aliasName) throws IOException {
    Alias alias = Alias.of(a -> a);
    PutAliasRequest request = new PutAliasRequest.Builder()
        .index(indexName)
        .name(aliasName)
        .alias(alias)
        .build();
        
    PutAliasResponse response = elasticsearchClient.indices().putAlias(request);
    return response.acknowledged();
}
```

### 2. 文档操作

#### 添加文档

```java
@Service
public class DocumentService {
    
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    /**
     * 添加单个文档
     */
    public String addDocument(String indexName, Object document, String docId) throws IOException {
        IndexResponse response = elasticsearchClient.index(IndexRequest.of(i -> i
            .index(indexName)
            .id(docId)
            .document(document)
        )).result().jsonValue();
        
        return response.toString();
    }
    
    /**
     * 批量添加文档
     */
    public BulkResponse bulkAddDocuments(String indexName, List<Object> documents) throws IOException {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        
        for (int i = 0; i < documents.size(); i++) {
            bulkBuilder.operations(op -> op.index(idx -> idx
                .index(indexName)
                .id(String.valueOf(i))
                .document(documents.get(i))
            ));
        }
        
        return elasticsearchClient.bulk(bulkBuilder.build());
    }
    
    /**
     * 更新文档
     */
    public UpdateResponse<User> updateDocument(String indexName, String docId, Object document) throws IOException {
        return elasticsearchClient.update(UpdateRequest.of(u -> u
            .index(indexName)
            .id(docId)
            .doc(document)
            .docAsUpsert(true)
        ), User.class);
    }
    
    /**
     * 删除文档
     */
    public DeleteResponse deleteDocument(String indexName, String docId) throws IOException {
        return elasticsearchClient.delete(DeleteRequest.of(d -> d
            .index(indexName)
            .id(docId)
        ));
    }
    
    /**
     * 获取文档
     */
    public GetResponse<User> getDocument(String indexName, String docId) throws IOException {
        return elasticsearchClient.get(GetRequest.of(g -> g
            .index(indexName)
            .id(docId)
        ), User.class);
    }
}
```

### 3. 搜索操作

#### 基础搜索

```java
@Service
public class SearchService {
    
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    /**
     * 精确匹配搜索
     */
    public SearchResponse<User> termSearch(String indexName, String fieldName, String value) throws IOException {
        return elasticsearchClient.search(SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .term(t -> t
                    .field(fieldName)
                    .value(v -> v.stringValue(value))
                )
            )
        ), User.class);
    }
    
    /**
     * 全文搜索
     */
    public SearchResponse<User> matchSearch(String indexName, String fieldName, String value) throws IOException {
        return elasticsearchClient.search(SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .match(m -> m
                    .field(fieldName)
                    .query(value)
                )
            )
        ), User.class);
    }
    
    /**
     * 复合查询 (Bool Query)
     */
    public SearchResponse<User> boolSearch(String indexName, String termField, String termValue, 
                                         String matchField, String matchValue) throws IOException {
        return elasticsearchClient.search(SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .bool(b -> b
                    .must(mu -> mu
                        .term(t -> t
                            .field(termField)
                            .value(v -> v.stringValue(termValue))
                        )
                    )
                    .must(mu -> mu
                        .match(m -> m
                            .field(matchField)
                            .query(matchValue)
                        )
                    )
                )
            )
        ), User.class);
    }
    
    /**
     * 分页搜索
     */
    public SearchResponse<User> paginatedSearch(String indexName, String query, int page, int size) throws IOException {
        int from = (page - 1) * size;
        
        return elasticsearchClient.search(SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .queryString(qs -> qs
                    .query(query)
                )
            )
            .from(from)
            .size(size)
        ), User.class);
    }
    
    /**
     * 高亮搜索
     */
    public SearchResponse<User> highlightSearch(String indexName, String field, String query) throws IOException {
        return elasticsearchClient.search(SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .match(m -> m
                    .field(field)
                    .query(query)
                )
            )
            .highlight(h -> h
                .fields(field, f -> f
                    .preTags("<em>")
                    .postTags("</em>")
                )
            )
        ), User.class);
    }
}
```

#### 聚合查询

```java
/**
 * 聚合查询 - 统计数量
 */
public SearchResponse<Object> countAggregation(String indexName, String field) throws IOException {
    return elasticsearchClient.search(SearchRequest.of(s -> s
        .index(indexName)
        .aggregations("count_agg", a -> a
            .terms(t -> t
                .field(field)
            )
        )
    ), Object.class);
}

/**
 * 聚合查询 - 数值统计
 */
public SearchResponse<Object> statsAggregation(String indexName, String field) throws IOException {
    return elasticsearchClient.search(SearchRequest.of(s -> s
        .index(indexName)
        .aggregations("stats_agg", a -> a
            .stats(st -> st
                .field(field)
            )
        )
    ), Object.class);
}
```

### 4. Repository 模式封装

```java
@Repository
public class ElasticsearchRepository<T> {
    
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    /**
     * 保存文档
     */
    public <T> T save(String indexName, String id, T entity) throws IOException {
        elasticsearchClient.index(i -> i
            .index(indexName)
            .id(id)
            .document(entity)
        );
        return entity;
    }
    
    /**
     * 根据ID查找
     */
    public <T> Optional<T> findById(String indexName, String id, Class<T> clazz) throws IOException {
        GetResponse<T> response = elasticsearchClient.get(g -> g
            .index(indexName)
            .id(id), clazz);
            
        return response.found() ? Optional.of(response.source()) : Optional.empty();
    }
    
    /**
     * 删除文档
     */
    public boolean deleteById(String indexName, String id) throws IOException {
        DeleteResponse response = elasticsearchClient.delete(d -> d
            .index(indexName)
            .id(id)
        );
        return response.result().name().equals("DELETED");
    }
    
    /**
     * 搜索所有文档
     */
    public <T> List<T> findAll(String indexName, Class<T> clazz) throws IOException {
        SearchResponse<T> response = elasticsearchClient.search(s -> s
            .index(indexName)
            .query(q -> q
                .matchAll(m -> m)
            ), clazz);
            
        return response.hits().hits().stream()
            .map(hit -> hit.source())
            .collect(Collectors.toList());
    }
}
```

## 高级特性

### 1. 自定义分析器

```java
/**
 * 创建带有自定义分析器的索引
 */
public boolean createIndexWithAnalyzer(String indexName) throws IOException {
    Settings settings = Settings.of(s -> s
        .analysis(a -> a
            .analyzer("custom_analyzer", analyzer -> analyzer
                .custom(custom -> custom
                    .tokenizer("standard")
                    .filter("lowercase", "stop")
                )
            )
        )
    );
    
    TypeMapping mapping = TypeMapping.of(m -> m
        .properties("content", p -> p
            .text(t -> t.analyzer("custom_analyzer"))
        )
    );
    
    CreateIndexRequest request = CreateIndexRequest.of(i -> i
        .index(indexName)
        .settings(settings)
        .mappings(mapping)
    );
    
    return elasticsearchClient.indices().create(request).acknowledged();
}
```

### 2. 滚动查询 (Scroll API)

```java
/**
 * 滚动查询大量数据
 */
public List<User> scrollSearch(String indexName, int batchSize) throws IOException {
    List<User> results = new ArrayList<>();
    String scrollId = null;
    
    try {
        // 初始化滚动查询
        SearchResponse<User> response = elasticsearchClient.search(s -> s
            .index(indexName)
            .scroll(Time.of(t -> t.time("1m")))
            .size(batchSize), User.class);
            
        scrollId = response.scrollId();
        
        // 处理第一批数据
        results.addAll(response.hits().hits().stream()
            .map(hit -> hit.source())
            .collect(Collectors.toList()));
        
        // 继续滚动获取剩余数据
        while (!response.hits().hits().isEmpty()) {
            response = elasticsearchClient.scroll(sc -> sc
                .scrollId(scrollId)
                .scroll(Time.of(t -> t.time("1m"))), User.class);
                
            if (response.hits().hits().isEmpty()) {
                break;
            }
            
            results.addAll(response.hits().hits().stream()
                .map(hit -> hit.source())
                .collect(Collectors.toList()));
                
            scrollId = response.scrollId();
        }
    } finally {
        // 清理滚动上下文
        if (scrollId != null) {
            elasticsearchClient.clearScroll(c -> c.scrollId(scrollId));
        }
    }
    
    return results;
}
```

### 3. 搜索模板

```java
/**
 * 使用搜索模板
 */
public SearchResponse<User> searchWithTemplate(String templateName, Map<String, Object> params) throws IOException {
    SearchTemplateRequest request = SearchTemplateRequest.of(st -> st
        .index("users")
        .source(templateName)
        .params(params)
    );
    
    return elasticsearchClient.searchTemplate(request, User.class);
}
```

## 最佳实践

### 1. 异常处理

```java
@Service
public class SafeElasticsearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(SafeElasticsearchService.class);
    
    @Autowired
    private ElasticsearchClient elasticsearchClient;
    
    public Optional<User> safeGetDocument(String indexName, String docId) {
        try {
            GetResponse<User> response = elasticsearchClient.get(g -> g
                .index(indexName)
                .id(docId), User.class);
                
            return Optional.ofNullable(response.found() ? response.source() : null);
        } catch (IOException e) {
            logger.error("Error getting document from index: {}", indexName, e);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Unexpected error getting document: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
}
```

### 2. 连接池配置

```java
@Configuration
public class ElasticsearchPoolConfig {
    
    @Bean
    public RestClientBuilder restClientBuilder(ElasticsearchProperties properties) {
        return RestClient.builder(
                new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()))
            .setRequestConfigCallback(requestConfigBuilder -> 
                requestConfigBuilder
                    .setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()))
                    .setSocketTimeout(Math.toIntExact(properties.getSocketTimeout().toMillis()))
                    .setConnectionRequestTimeout(Math.toIntExact(properties.getConnectionRequestTimeout().toMillis())))
            .setHttpClientConfigCallback(httpClientBuilder -> 
                httpClientBuilder
                    .setMaxConnTotal(properties.getMaxConnectTotal())
                    .setMaxConnPerRoute(properties.getMaxConnectPerRoute())
                    .setDefaultIOReactorConfig(ioReactorConfig -> ioReactorConfig
                        .setIoThreadCount(2)
                        .setSoKeepAlive(true)
                        .setSoReuseAddress(true)
                        .setTcpNoDelay(true)
                    ));
    }
}
```

### 3. 性能优化

- 使用批量操作处理大量数据
- 合理设置索引分片和副本数
- 优化查询语句，避免深度分页
- 使用过滤器而不是查询进行精确匹配
- 合理使用缓存

### 4. 监控和日志

```java
@Component
public class ElasticsearchMetrics {
    
    private static final MeterRegistry meterRegistry;
    
    @EventListener
    public void handleSearchEvent(SearchEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        sample.stop(Timer.builder("elasticsearch.search.duration")
            .tag("index", event.getIndexName())
            .register(meterRegistry));
    }
}
```

## 常见问题

### 1. 连接问题

**问题**: `Connection refused` 或 `NoNodeAvailableException`

**解决方案**:
- 检查 Elasticsearch 服务是否正在运行
- 确认连接地址和端口配置正确
- 检查防火墙设置

### 2. 映射问题

**问题**: 字段类型不匹配或无法索引

**解决方案**:
- 检查索引映射定义
- 使用 `GET /index_name/_mapping` 查看当前映射
- 重建索引并重新定义映射

### 3. 性能问题

**问题**: 查询速度慢或内存不足

**解决方案**:
- 优化查询语句，避免全表扫描
- 调整 JVM 堆大小
- 增加分片数量
- 使用过滤器替代查询

### 4. 版本兼容性

**问题**: 客户端版本与服务端版本不兼容

**解决方案**:
- 确保客户端和服务端版本兼容
- 参考官方文档的版本兼容性矩阵
- 考虑升级到相同主版本

## 总结

Elasticsearch 与 Spring Boot 的集成提供了强大的搜索和分析能力。通过合理配置客户端、使用合适的 API、遵循最佳实践，可以构建高性能的搜索应用。

关键要点：
1. 选择合适的客户端库（ES 8.x 推荐使用 elasticsearch-java）
2. 正确配置连接参数和连接池
3. 合理设计索引映射
4. 优化查询性能
5. 实施适当的错误处理和监控

这份指南涵盖了从基础配置到高级特性的全面内容，希望能帮助您更好地理解和使用 Elasticsearch。