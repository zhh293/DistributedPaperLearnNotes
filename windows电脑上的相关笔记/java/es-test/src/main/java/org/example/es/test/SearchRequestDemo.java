package org.example.es.test;

import org.apache.http.HttpHost;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

public class SearchRequestDemo {
    public static void main(String[] args) throws IOException {
        RestHighLevelClient esClient = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost("localhost", 9200, "http")
                )
        );
        SearchRequest request = new SearchRequest("my_index");
        SearchSourceBuilder builder = new SearchSourceBuilder();
        //builder.query(new MatchAllQueryBuilder());
        builder.highlighter(new HighlightBuilder());
        builder.query(new TermQueryBuilder("name", "张三"));
        builder.timeout(new TimeValue(1, TimeUnit.MINUTES));
        request.source(builder);
        SearchResponse search = esClient.search(request, RequestOptions.DEFAULT);
        search.getHits().forEach(hit -> {
            System.out.println(hit.getSourceAsString());
        });
        /*BigDecimal num = new BigDecimal("0.01");
        BigDecimal num2 = new BigDecimal("0.01");
        BigDecimal add = num2.add(num);
        System.out.println(add.doubleValue());*/

        /*//想要查询的话，可以使用QueryBuilders 静态方法创建查询条件。
        //termQuery，matchAllQuery
        MatchQueryBuilder queryBuilders = QueryBuilders.matchQuery("name", "张三");//想查哪个字段，字段中需要包含什么内容等等
        MatchAllQueryBuilder matchAllQueryBuilder = QueryBuilders.matchAllQuery();
        TermQueryBuilder termQueryBuilder = QueryBuilders.termQuery("name", "张三");*/
    }
   /* 在 Elasticsearch 中执行搜索查询（如 match、term、组合条件查询等），核心是使用 SearchRequest 类，配合 SearchSourceBuilder 构建查询条件，以及 QueryBuilders 提供的各种查询类型。以下是详细的实现方法和示例：
    一、核心类与流程
    搜索查询的核心类包括：

    SearchRequest：定义搜索请求（指定索引、类型等）。
    SearchSourceBuilder：构建查询的具体内容（查询条件、分页、排序、高亮等）。
    QueryBuilders：提供各种查询类型的静态方法（如 matchQuery、termQuery、boolQuery 等）。

    基本流程：

    创建 SearchRequest 并指定要搜索的索引。
    创建 SearchSourceBuilder 并配置查询条件（通过 QueryBuilders）。
            （可选）配置分页、排序、高亮、返回字段等。
    将 SearchSourceBuilder 绑定到 SearchRequest。
    通过客户端执行请求，获取 SearchResponse 并处理结果。
    二、常用查询类型及示例
    以下是实际开发中最常用的查询类型及代码实现：
            1. 全文匹配（match query）
    用于对文本字段进行全文搜索（会进行分词处理），适合搜索用户输入的自然语言。

    java
            运行
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import java.io.IOException;

    public class MatchQueryExample {
        public static void main(String[] args) throws IOException {
            // 假设已初始化 RestHighLevelClient
            RestHighLevelClient client = new RestHighLevelClient();

            // 1. 创建搜索请求（指定索引，可多个）
            SearchRequest searchRequest = new SearchRequest("articles"); // 搜索 "articles" 索引

            // 2. 构建查询条件
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            // match查询：在 "content" 字段中搜索 "elasticsearch 教程"（会分词）
            sourceBuilder.query(QueryBuilders.matchQuery("content", "elasticsearch 教程"));

            // 3. 绑定查询条件到请求
            searchRequest.source(sourceBuilder);

            // 4. 执行查询
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            // 5. 处理结果（解析命中的文档）
            handleSearchResponse(response);

            // 关闭客户端
            client.close();
        }

        // 处理搜索响应的工具方法
        private static void handleSearchResponse(SearchResponse response) {
            long totalHits = response.getHits().getTotalHits().value;
            System.out.println("匹配到 " + totalHits + " 条文档");

            // 遍历命中的文档
            response.getHits().forEach(hit -> {
                System.out.println("文档ID: " + hit.getId());
                System.out.println("文档内容: " + hit.getSourceAsString()); // JSON格式内容
                System.out.println("匹配得分: " + hit.getScore()); // 相关性得分
            });
        }
    }
2. 精确匹配（term query）
    用于对非文本字段（如数字、日期、keyword 类型的字符串）进行精确匹配（不会分词）。

    java
            运行
// 示例：精确匹配 "status" 字段为 "published" 的文档
sourceBuilder.query(QueryBuilders.termQuery("status", "published"));

// 注意：如果字段是 text 类型，建议用 term 匹配其 keyword 子字段（避免分词影响）
sourceBuilder.query(QueryBuilders.termQuery("title.keyword", "Elasticsearch入门"));
3. 组合条件查询（bool query）
    通过 must（必须满足）、should（或）、mustNot（必须不满足）组合多个查询条件，实现复杂逻辑。

    java
            运行
// 示例：查询 "content" 包含 "java" 且 "status" 为 "published"，且 "views" > 1000 的文档
sourceBuilder.query(QueryBuilders.boolQuery()
        .must(QueryBuilders.matchQuery("content", "java")) // 必须包含 "java"
            .must(QueryBuilders.termQuery("status", "published")) // 状态必须是 published
            .must(QueryBuilders.rangeQuery("views").gt(1000)) // 浏览量 > 1000
            .should(QueryBuilders.termQuery("tags", "技术")) // 可选：标签包含 "技术"（满足会增加相关性）
            );
    三、常用辅助功能配置
    除了核心查询条件，还可以配置分页、排序、高亮等功能：
            1. 分页（from + size）
    控制返回结果的起始位置和数量（类似 MySQL 的 limit）：

    java
            运行
sourceBuilder.from(0); // 从第0条开始（默认0）
sourceBuilder.size(10); // 每次返回10条（默认10）
2. 排序（sort）
    按指定字段排序（支持多字段排序）：

    java
            运行
// 示例：先按 "publishTime" 降序（最新的在前），再按 "views" 降序
sourceBuilder.sort("publishTime", SortOrder.DESC);
sourceBuilder.sort("views", SortOrder.DESC);
3. 高亮（highlight）
    对匹配的文本字段添加高亮标记（如 <em>），方便前端展示：

    java
            运行
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;

    // 配置高亮
    HighlightBuilder highlightBuilder = new HighlightBuilder();
    // 对 "content" 字段启用高亮
    HighlightBuilder.Field contentHighlight = new HighlightBuilder.Field("content");
contentHighlight.preTags("<em>"); // 高亮前缀
contentHighlight.postTags("</em>"); // 高亮后缀
highlightBuilder.field(contentHighlight);

// 将高亮配置添加到查询
sourceBuilder.highlighter(highlightBuilder);

// 处理结果时获取高亮内容（在 handleSearchResponse 中）
response.getHits().forEach(hit -> {
        Map<String, HighlightField> highlightFields = hit.getHighlightFields();
        HighlightField contentField = highlightFields.get("content");
        if (contentField != null) {
            // 高亮后的片段（可能有多个）
            String[] highLightTexts = contentField.getFragments().stream()
                    .map(Text::string)
                    .toArray(String[]::new);
            System.out.println("高亮内容: " + Arrays.toString(highLightTexts));
        }
    });
4. 返回指定字段（fetchSource）
    只返回需要的字段，减少网络传输：

    java
            运行
// 只返回 "title"、"publishTime" 字段，排除 "content" 字段
sourceBuilder.fetchSource(new String[]{"title", "publishTime"}, new String[]{"content"});
    四、完整示例（综合功能）
    以下是一个包含 bool 查询 + 分页 + 排序 + 高亮 的完整示例：

    java
            运行
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.common.text.Text;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

    public class ComprehensiveSearchExample {
        public static void main(String[] args) throws IOException {
            RestHighLevelClient client = new RestHighLevelClient();

            // 1. 创建搜索请求
            SearchRequest searchRequest = new SearchRequest("articles");

            // 2. 构建查询条件
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

            // 2.1 组合查询条件（bool）
            sourceBuilder.query(QueryBuilders.boolQuery()
                    .must(QueryBuilders.matchQuery("content", "elasticsearch")
                            .fuzziness(Fuzziness.AUTO)) // 允许模糊匹配
                    .must(QueryBuilders.rangeQuery("publishTime").gte("2023-01-01")) // 2023年之后发布
                    .mustNot(QueryBuilders.termQuery("status", "draft")) // 排除草稿状态
            );

            // 2.2 分页：第1页（0开始），10条/页
            sourceBuilder.from(0);
            sourceBuilder.size(10);

            // 2.3 排序：按发布时间降序
            sourceBuilder.sort("publishTime", SortOrder.DESC);

            // 2.4 高亮：对content字段匹配内容高亮
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            HighlightBuilder.Field contentField = new HighlightBuilder.Field("content");
            contentField.preTags("<strong>");
            contentField.postTags("</strong>");
            highlightBuilder.field(contentField);
            sourceBuilder.highlighter(highlightBuilder);

            // 2.5 只返回指定字段
            sourceBuilder.fetchSource(new String[]{"title", "content", "publishTime"}, null);

            // 3. 绑定查询到请求
            searchRequest.source(sourceBuilder);

            // 4. 执行查询
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            // 5. 处理结果
            handleResponse(response);

            client.close();
        }

        private static void handleResponse(SearchResponse response) {
            System.out.println("总匹配数: " + response.getHits().getTotalHits().value);

            for (SearchHit hit : response.getHits()) {
                System.out.println("\n文档ID: " + hit.getId());
                System.out.println("标题: " + hit.getSourceAsMap().get("title"));
                System.out.println("发布时间: " + hit.getSourceAsMap().get("publishTime"));

                // 输出高亮内容
                Map<String, HighlightBuilder.Field> highlightFields = hit.getHighlightFields();
                HighlightBuilder.Field contentHighlight = highlightFields.get("content");
                if (contentHighlight != null) {
                    String[] fragments = Arrays.stream(contentHighlight.getFragments())
                            .map(Text::string)
                            .toArray(String[]::new);
                    System.out.println("高亮内容: " + Arrays.toString(fragments));
                }
            }
        }
    }
















    总结
    搜索查询的核心是 SearchRequest + SearchSourceBuilder + QueryBuilders。
    不同查询类型适用于不同场景：match 用于全文搜索，term 用于精确匹配，bool 用于组合条件。
    辅助功能（分页、排序、高亮等）通过 SearchSourceBuilder 配置，可根据业务需求灵活添加。
    处理响应时，通过 SearchResponse 获取命中的文档、得分、高亮内容等信息。

    如果需要更复杂的查询（如嵌套查询、地理查询等），可以参考 Elasticsearch 官方文档中 QueryBuilders 的其他方法。*/



}
