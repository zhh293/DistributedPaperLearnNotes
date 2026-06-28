package com.zhh.handsome.elkstudy.前后端实现高亮;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HighlightSearchService {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private RestHighLevelClient restHighLevelClient;

    /**
     * 带高亮的全文搜索
     *
     * @param indexName 索引名称
     * @param fieldName 要搜索的字段名
     * @param value     搜索关键词
     * @param highlightFields 需要高亮的字段列表
     * @return 包含高亮信息的搜索结果
     * @throws IOException
     */
    public List<HighlightSearchResult> highlightMatchSearch(String indexName, String fieldName, String value, List<String> highlightFields) throws IOException {
//        // 1. 创建搜索请求
//        org.elasticsearch.action.search.SearchRequest searchRequest = new org.elasticsearch.action.search.SearchRequest(indexName);
//
//        // 2. 使用SearchSourceBuilder构建查询条件
//        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
//
//        // 构建match查询
//        sourceBuilder.query(QueryBuilders.matchQuery(fieldName, value));
//
//        // 构建高亮设置
//        HighlightBuilder highlightBuilder = new HighlightBuilder();
//        highlightBuilder.fields(highlightFields.stream());
//        HighlightBuilder highlighter1 = sourceBuilder.highlighter(highlightBuilder);
//
//        // 3. 将SearchSourceBuilder绑定到SearchRequest
//        searchRequest.source(sourceBuilder);
//
//        // 4. 执行搜索
//        org.elasticsearch.action.search.SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
//
//        // 5. 将结果转换为包含高亮信息的对象
//        return convertToHighlightResults(response);
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

    /**
     * 带高亮的精确搜索
     *
     * @param indexName 索引名称
     * @param fieldName 要搜索的字段名
     * @param value     搜索关键词
     * @param highlightFields 需要高亮的字段列表
     * @return 包含高亮信息的搜索结果
     * @throws IOException
     */
    public List<HighlightSearchResult> highlightTermSearch(String indexName, String fieldName, String value, List<String> highlightFields) throws IOException {
        // 构建查询条件
        Query query = Query.of(q -> q
                .term(t -> t
                        .field(fieldName)
                        .value(v -> v.stringValue(value))
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

    /**
     * 带高亮的多字段搜索
     *
     * @param indexName 索引名称
     * @param searchFields 要搜索的字段列表
     * @param value     搜索关键词
     * @param highlightFields 需要高亮的字段列表
     * @return 包含高亮信息的搜索结果
     * @throws IOException
     */
    public List<HighlightSearchResult> highlightMultiMatchSearch(String indexName, List<String> searchFields, String value, List<String> highlightFields) throws IOException {
        // 构建多字段查询条件
        Query query = Query.of(q -> q
                .multiMatch(m -> m
                        .fields(searchFields)
                        .query(value)
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

    /**
     * 带高亮的分页搜索
     *
     * @param indexName 索引名称
     * @param queryStr 搜索关键词
     * @param searchFields 要搜索的字段列表
     * @param highlightFields 需要高亮的字段列表
     * @param page 页码
     * @param size 页面大小
     * @return 包含高亮信息的搜索结果
     * @throws IOException
     */
    public List<HighlightSearchResult> highlightPaginatedSearch(String indexName, String queryStr, List<String> searchFields, List<String> highlightFields, int page, int size) throws IOException {
        // 计算偏移量
        int from = (page - 1) * size;

        // 构建查询条件
        Query query;
        if (searchFields.isEmpty()) {
            // 如果没有指定搜索字段，则使用全文搜索
            query = Query.of(q -> q
                    .queryString(qs -> qs
                            .query(queryStr)
                    )
            );
        } else {
            // 使用多字段匹配查询
            query = Query.of(q -> q
                    .multiMatch(m -> m
                            .fields(searchFields)
                            .query(queryStr)
                    )
            );
        }

        // 构建高亮设置
        Highlight.Builder highlightBuilder = buildHighlight(highlightFields);

        // 构建搜索请求
        SearchRequest request = SearchRequest.of(s -> s
                .index(indexName)
                .query(query)
                .highlight(highlightBuilder.build())
                .from(from)
                .size(size)
        );

        SearchResponse<User> response = elasticsearchClient.search(request, User.class);

        // 将结果转换为包含高亮信息的对象
        return response.hits().hits().stream()
                .map(hit -> mapToHighlightSearchResult(hit))
                .collect(Collectors.toList());
    }

    /**
     * 构建高亮设置
     */
    private Highlight.Builder buildHighlight(List<String> highlightFields) {
        Highlight.Builder highlightBuilder = new Highlight.Builder();

        // 为每个需要高亮的字段设置高亮参数
        for (String field : highlightFields) {
            highlightBuilder.fields(field, new HighlightField.Builder()
                    .preTags("<mark class='highlight'>")
                    .postTags("</mark>")
                    .fragmentSize(150) // 片段大小
                    .numberOfFragments(3) // 返回的高亮片段数量
                    .build());
        }

        // 设置默认的高亮参数
        highlightBuilder.preTags("<mark class='highlight'>")
                .postTags("</mark>")
                .fragmentSize(150)
                .numberOfFragments(3);

        return highlightBuilder;
    }

    /**
     * 将搜索命中结果映射为高亮搜索结果对象
     */
    private HighlightSearchResult mapToHighlightSearchResult(Hit<User> hit) {
        User user = hit.source();
        Map<String, Object> highlights = new HashMap<>();

        if (hit.highlight() != null) {
            hit.highlight().forEach((field, fragments) -> {
                highlights.put(field, fragments);
            });
        }

        return HighlightSearchResult.builder()
                .user(user)
                .highlights(highlights)
                .score(hit.score())
                .build();
    }
}