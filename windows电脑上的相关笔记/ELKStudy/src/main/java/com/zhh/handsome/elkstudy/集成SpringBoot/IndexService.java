//package com.zhh.handsome.elkstudy.集成SpringBoot;
//
//import co.elastic.clients.elasticsearch.ElasticsearchClient;
//import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
//import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
//import co.elastic.clients.elasticsearch.indices.ExistsRequest;
//import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
//import co.elastic.clients.elasticsearch.indices.TypeMapping;
//import co.elastic.clients.json.JsonData;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.Map;
//
//@Service
//public class IndexService {
//
//    @Autowired
//    private ElasticsearchClient elasticsearchClient;
//
//    /**
//     * 创建索引
//     */
//    public boolean createIndex(String indexName) throws IOException {
//        CreateIndexRequest request = CreateIndexRequest.of(i -> i
//                .index(indexName)
//                .mappings(typeMapping())
//        );
//
//        var response = elasticsearchClient.indices().create(request);
//        return response.acknowledged();
//    }
//
//    /**
//     * 删除索引
//     */
//    public boolean deleteIndex(String indexName) throws IOException {
//        DeleteIndexRequest request = DeleteIndexRequest.of(i -> i.index(indexName));
//        var response = elasticsearchClient.indices().delete(request);
//        return response.acknowledged();
//    }
//
//    /**
//     * 检查索引是否存在
//     */
//    public boolean existsIndex(String indexName) throws IOException {
//        ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));
//        return elasticsearchClient.indices().exists(request);
//    }
//
//    /**
//     * 定义索引映射
//     */
//    private TypeMapping typeMapping() {
//        return TypeMapping.of(tm -> tm
//                .properties("name", pb -> pb.text(t -> t.analyzer("standard")))
//                .properties("age", pb -> pb.integer(i -> i))
//                .properties("sex", pb -> pb.keyword(k -> k))
//                .properties("tags", pb -> pb.keyword(k -> k))
//        );
//    }
//}