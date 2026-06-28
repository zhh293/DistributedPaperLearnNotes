# Elasticsearch Spring Boot 集成示例

这是一个完整的 Elasticsearch 与 Spring Boot 集成的示例项目，展示了如何在 Spring Boot 应用中使用 Elasticsearch 进行搜索和数据存储。

## 项目结构

- `ElasticsearchConfig.java`: Elasticsearch 客户端配置
- `User.java`: 数据模型
- `IndexService.java`: 索引管理服务
- `DocumentService.java`: 文档操作服务
- `SearchService.java`: 搜索服务
- `ElasticsearchController.java`: REST API 控制器

## 功能特性

### 1. 索引管理
- 创建索引
- 删除索引
- 检查索引是否存在

### 2. 文档操作
- 添加文档
- 获取文档
- 更新文档
- 删除文档
- 批量操作

### 3. 搜索功能
- 精确匹配搜索 (Term Query)
- 全文搜索 (Match Query)
- 复合查询 (Bool Query)
- 分页搜索

## API 接口说明

### 索引操作
- `POST /es/index/{indexName}` - 创建索引
- `DELETE /es/index/{indexName}` - 删除索引
- `GET /es/index/{indexName}/exists` - 检查索引是否存在

### 文档操作
- `POST /es/document/{indexName}/{docId}` - 添加文档
- `GET /es/document/{indexName}/{docId}` - 获取文档
- `PUT /es/document/{indexName}/{docId}` - 更新文档
- `DELETE /es/document/{indexName}/{docId}` - 删除文档

### 搜索操作
- `GET /es/search/term/{indexName}/{fieldName}/{value}` - 精确匹配搜索
- `GET /es/search/match/{indexName}/{fieldName}/{value}` - 全文搜索
- `GET /es/search/paginated/{indexName}?query={query}&page={page}&size={size}` - 分页搜索

## 使用示例

### 1. 创建索引
```bash
curl -X POST "http://localhost:8080/es/index/users"
```

### 2. 添加文档
```bash
curl -X POST "http://localhost:8080/es/document/users/1" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "张三",
    "age": 25,
    "sex": "男",
    "tags": ["工程师", "Java"]
  }'
```

### 3. 搜索文档
```bash
curl "http://localhost:8080/es/search/match/users/name/张三"
```

## 配置说明

在 `application.properties` 中配置 Elasticsearch 连接信息：

```properties
# Elasticsearch Configuration
elasticsearch.host=localhost
elasticsearch.port=9200
elasticsearch.scheme=http
```

## 依赖说明

项目使用了 Elasticsearch Java Client (8.x)，这是官方推荐的新版客户端，相比旧版的 High Level Client 具有更好的性能和更简洁的 API。

## 最佳实践

1. 合理配置连接池参数
2. 使用批量操作处理大量数据
3. 合理设计索引映射
4. 实现适当的错误处理
5. 监控查询性能