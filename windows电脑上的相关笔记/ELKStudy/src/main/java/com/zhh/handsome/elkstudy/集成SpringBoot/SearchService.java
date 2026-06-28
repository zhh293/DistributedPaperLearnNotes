//package com.zhh.handsome.elkstudy.集成SpringBoot;
//
//import co.elastic.clients.elasticsearch.ElasticsearchClient;
//import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
//import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
//import co.elastic.clients.elasticsearch._types.query_dsl.Query;
//import co.elastic.clients.elasticsearch.core.SearchRequest;
//import co.elastic.clients.elasticsearch.core.SearchResponse;
//import co.elastic.clients.elasticsearch.core.search.Hit;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.util.List;
//import java.util.stream.Collectors;
//
//@Service
//public class SearchService {
//
//    @Autowired
//    private ElasticsearchClient elasticsearchClient;
//
//    /**
//     * 精确匹配搜索
//     */
//    public List<User> termSearch(String indexName, String fieldName, String value) throws IOException {
//        Query query = Query.of(q -> q
//                .term(t -> t
//                        .field(fieldName)
//                        .value(v -> v.stringValue(value))
//                )
//        );
//
//        SearchRequest request = SearchRequest.of(s -> s
//                .index(indexName)
//                .query(query)
//        );
//
//        SearchResponse<User> response = elasticsearchClient.search(request, User.class);
//        return response.hits().hits().stream()
//                .map(Hit::source)
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 全文搜索
//     */
//    public List<User> matchSearch(String indexName, String fieldName, String value) throws IOException {
//        Query query = Query.of(q -> q
//                .match(m -> m
//                        .field(fieldName)
//                        .query(value)
//                )
//        );
//
//        SearchRequest request = SearchRequest.of(s -> s
//                .index(indexName)
//                .query(query)
//        );
//
//        SearchResponse<User> response = elasticsearchClient.search(request, User.class);
//        return response.hits().hits().stream()
//                .map(Hit::source)
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 复合查询 (Bool Query)
//     */
//    public List<User> boolSearch(String indexName, String termField, String termValue,
//                                 String matchField, String matchValue) throws IOException {
//        Query termQuery = Query.of(q -> q
//                .term(t -> t
//                        .field(termField)
//                        .value(v -> v.stringValue(termValue))
//                )
//        );
//
//        Query matchQuery = Query.of(q -> q
//                .match(m -> m
//                        .field(matchField)
//                        .query(matchValue)
//                )
//        );
//
//        Query boolQuery = Query.of(q -> q
//                .bool(b -> b
//                        .must(termQuery, matchQuery)
//                )
//        );
//
//        SearchRequest request = SearchRequest.of(s -> s
//                .index(indexName)
//                .query(boolQuery)
//        );
//
//        SearchResponse<User> response = elasticsearchClient.search(request, User.class);
//        return response.hits().hits().stream()
//                .map(Hit::source)
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 分页搜索
//     */
//    public List<User> paginatedSearch(String indexName, String queryStr, int page, int size) throws IOException {
//        int from = (page - 1) * size;
//
//        Query query = Query.of(q -> q
//                .queryString(qs -> qs
//                        .query(queryStr)
//                )
//        );
//
//        SearchRequest request = SearchRequest.of(s -> s
//                .index(indexName)
//                .query(query)
//                .from(from)
//                .size(size)
//        );
//
//        SearchResponse<User> response = elasticsearchClient.search(request, User.class);
//        return response.hits().hits().stream()
//                .map(Hit::source)
//                .collect(Collectors.toList());
//    }
//}