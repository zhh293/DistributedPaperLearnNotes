# Elasticsearch 高亮搜索功能 API 文档

## 功能概述

本项目实现了 Elasticsearch 的高亮搜索功能，允许在搜索结果中突出显示匹配的文本片段。这对于改善用户体验非常重要，因为它可以帮助用户快速识别搜索词在文档中的位置。

## API 接口

### 1. 全文高亮搜索

**接口地址:** `GET /es/highlight/search/match/{indexName}/{fieldName}/{value}`

**参数说明:**
- `{indexName}`: 索引名称
- `{fieldName}`: 搜索字段名称
- `{value}`: 搜索关键词
- `highlightFields`: 可选，需要高亮的字段列表，逗号分隔

**示例:**
```
GET /es/highlight/search/match/users/name/john?highlightFields=name,sex,tags
```

### 2. 精确高亮搜索

**接口地址:** `GET /es/highlight/search/term/{indexName}/{fieldName}/{value}`

**参数说明:**
- `{indexName}`: 索引名称
- `{fieldName}`: 搜索字段名称
- `{value}`: 搜索关键词
- `highlightFields`: 可选，需要高亮的字段列表，逗号分隔

**示例:**
```
GET /es/highlight/search/term/users/sex/male?highlightFields=name,sex
```

### 3. 多字段高亮搜索

**接口地址:** `GET /es/highlight/search/multi/{indexName}/{value}`

**参数说明:**
- `{indexName}`: 索引名称
- `{value}`: 搜索关键词
- `searchFields`: 必需，搜索字段列表，逗号分隔
- `highlightFields`: 可选，需要高亮的字段列表，逗号分隔

**示例:**
```
GET /es/highlight/search/multi/users/john?searchFields=name,tags&highlightFields=name,tags
```

### 4. 分页高亮搜索

**接口地址:** `GET /es/highlight/search/paginated/{indexName}`

**参数说明:**
- `{indexName}`: 索引名称
- `query`: 搜索查询语句，默认为"*"
- `searchFields`: 可选，搜索字段列表，逗号分隔
- `highlightFields`: 可选，需要高亮的字段列表，逗号分隔
- `page`: 页码，默认为1
- `size`: 每页大小，默认为10

**示例:**
```
GET /es/highlight/search/paginated/users?query=john&searchFields=name,tags&highlightFields=name,tags&page=1&size=5
```

## 响应格式

所有高亮搜索接口返回以下格式的数据：

```json
[
  {
    "user": {
      "name": "John Doe",
      "age": 30,
      "sex": "male",
      "tags": ["developer", "java"]
    },
    "highlights": {
      "name": ["<mark class='highlight'>John</mark> Doe"],
      "tags": ["<mark class='highlight'>java</mark>"]
    },
    "score": 1.234
  }
]
```

## 前端展示

项目提供了前端页面用于测试高亮搜索功能：

- 主页: `http://localhost:8080/`
- 高亮搜索页面: `http://localhost:8080/highlight-search.html`

## 技术实现细节

### 高亮配置

- 高亮预标签: `<mark class='highlight'>`
- 高亮后标签: `</mark>`
- 片段大小: 150 字符
- 返回片段数: 3 个

### 实现类

- `HighlightSearchService`: 核心搜索服务，包含各种高亮搜索方法
- `HighlightSearchController`: REST 控制器，暴露搜索接口
- `HighlightSearchResult`: 搜索结果封装类
- `User`: 用户实体类

## 使用示例

要在您的代码中使用高亮搜索功能，可以注入 `HighlightSearchService` 并调用相应的方法：

```java
@Autowired
private HighlightSearchService highlightSearchService;

// 执行高亮搜索
List<HighlightSearchResult> results = highlightSearchService
    .highlightMatchSearch("users", "name", "john", Arrays.asList("name", "tags"));
```

## 注意事项

1. 确保 Elasticsearch 服务正在运行
2. 确保指定的索引存在且包含相应的文档
3. 高亮功能对于大型文本字段特别有用
4. 可以根据需要自定义高亮标签样式