//package com.zhh.handsome.elkstudy.集成SpringBoot;
//
//import co.elastic.clients.elasticsearch.ElasticsearchClient;
//import co.elastic.clients.elasticsearch._types.Result;
//import co.elastic.clients.elasticsearch.core.*;
//import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
//import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.List;
//
//@Service
//public class DocumentService {
//
//    @Autowired
//    private ElasticsearchClient elasticsearchClient;
//
//    /**
//     * 添加文档
//     */
//    public String addDocument(String indexName, User user, String docId) throws IOException {
//        IndexRequest<User> request = IndexRequest.of(i -> i
//                .index(indexName)
//                .id(docId)
//                .document(user)
//        );
//
//        IndexResponse response = elasticsearchClient.index(request);
//        return response.result().name();
//    }
//
//    /**
//     * 批量添加文档
//     */
//    public BulkResponse bulkAddDocuments(String indexName, List<User> users) throws IOException {
//        List<BulkOperation> operations = new ArrayList<>();
//
//        for (int i = 0; i < users.size(); i++) {
//            User user = users.get(i);
//            IndexOperation<User> op = IndexOperation.of(io -> io
//                    .index(indexName)
//                    .id(String.valueOf(i + 1))
//                    .document(user)
//            );
//            operations.add(BulkOperation.of(b -> b.index(op)));
//        }
//
//        BulkRequest request = BulkRequest.of(b -> b
//                .operations(operations)
//        );
//
//        return elasticsearchClient.bulk(request);
//    }
//
//    /**
//     * 获取文档
//     */
//    public User getDocument(String indexName, String docId) throws IOException {
//        GetRequest request = GetRequest.of(g -> g
//                .index(indexName)
//                .id(docId)
//        );
//
//        GetResponse<User> response = elasticsearchClient.get(request, User.class);
//        return response.found() ? response.source() : null;
//    }
//
//    /**
//     * 更新文档
//     */
//    public String updateDocument(String indexName, String docId, User user) throws IOException {
//        UpdateRequest<User, User> request = UpdateRequest.of(u -> u
//                .index(indexName)
//                .id(docId)
//                .doc(user)
//                .docAsUpsert(true)
//        );
//
//        UpdateResponse<User> response = elasticsearchClient.update(request, User.class);
//        return response.result().name();
//    }
//
//    /**
//     * 删除文档
//     */
//    public String deleteDocument(String indexName, String docId) throws IOException {
//        DeleteRequest request = DeleteRequest.of(d -> d
//                .index(indexName)
//                .id(docId)
//        );
//
//        DeleteResponse response = elasticsearchClient.delete(request);
//        return response.result().name();
//    }
//}