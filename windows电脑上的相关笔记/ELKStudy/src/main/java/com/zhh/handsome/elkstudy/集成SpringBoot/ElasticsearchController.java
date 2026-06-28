//package com.zhh.handsome.elkstudy.集成SpringBoot;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import java.io.IOException;
//import java.util.List;
//
//@RestController
//@RequestMapping("/es")
//public class ElasticsearchController {
//
//    @Autowired
//    private IndexService indexService;
//
//    @Autowired
//    private DocumentService documentService;
//
//    @Autowired
//    private SearchService searchService;
//
//    // 索引操作
//    @PostMapping("/index/{indexName}")
//    public String createIndex(@PathVariable String indexName) {
//        try {
//            boolean result = indexService.createIndex(indexName);
//            return result ? "Index created successfully" : "Failed to create index";
//        } catch (IOException e) {
//            return "Error: " + e.getMessage();
//        }
//    }
//
//    @DeleteMapping("/index/{indexName}")
//    public String deleteIndex(@PathVariable String indexName) {
//        try {
//            boolean result = indexService.deleteIndex(indexName);
//            return result ? "Index deleted successfully" : "Failed to delete index";
//        } catch (IOException e) {
//            return "Error: " + e.getMessage();
//        }
//    }
//
//    @GetMapping("/index/{indexName}/exists")
//    public Boolean existsIndex(@PathVariable String indexName) {
//        try {
//            return indexService.existsIndex(indexName);
//        } catch (IOException e) {
//            return false;
//        }
//    }
//
//    // 文档操作
//    @PostMapping("/document/{indexName}/{docId}")
//    public String addDocument(@PathVariable String indexName,
//                              @PathVariable String docId,
//                              @RequestBody User user) {
//        try {
//            String result = documentService.addDocument(indexName, user, docId);
//            return "Document added with result: " + result;
//        } catch (IOException e) {
//            return "Error: " + e.getMessage();
//        }
//    }
//
//    @GetMapping("/document/{indexName}/{docId}")
//    public User getDocument(@PathVariable String indexName, @PathVariable String docId) {
//        try {
//            return documentService.getDocument(indexName, docId);
//        } catch (IOException e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
//
//    @PutMapping("/document/{indexName}/{docId}")
//    public String updateDocument(@PathVariable String indexName,
//                                 @PathVariable String docId,
//                                 @RequestBody User user) {
//        try {
//            String result = documentService.updateDocument(indexName, docId, user);
//            return "Document updated with result: " + result;
//        } catch (IOException e) {
//            return "Error: " + e.getMessage();
//        }
//    }
//
//    @DeleteMapping("/document/{indexName}/{docId}")
//    public String deleteDocument(@PathVariable String indexName, @PathVariable String docId) {
//        try {
//            String result = documentService.deleteDocument(indexName, docId);
//            return "Document deleted with result: " + result;
//        } catch (IOException e) {
//            return "Error: " + e.getMessage();
//        }
//    }
//
//    // 搜索操作
//    @GetMapping("/search/term/{indexName}/{fieldName}/{value}")
//    public List<User> termSearch(@PathVariable String indexName,
//                                 @PathVariable String fieldName,
//                                 @PathVariable String value) {
//        try {
//            return searchService.termSearch(indexName, fieldName, value);
//        } catch (IOException e) {
//            e.printStackTrace();
//            return List.of();
//        }
//    }
//
//    @GetMapping("/search/match/{indexName}/{fieldName}/{value}")
//    public List<User> matchSearch(@PathVariable String indexName,
//                                  @PathVariable String fieldName,
//                                  @PathVariable String value) {
//        try {
//            return searchService.matchSearch(indexName, fieldName, value);
//        } catch (IOException e) {
//            e.printStackTrace();
//            return List.of();
//        }
//    }
//
//    @GetMapping("/search/paginated/{indexName}")
//    public List<User> paginatedSearch(@PathVariable String indexName,
//                                      @RequestParam(defaultValue = "*") String query,
//                                      @RequestParam(defaultValue = "1") int page,
//                                      @RequestParam(defaultValue = "10") int size) {
//        try {
//            return searchService.paginatedSearch(indexName, query, page, size);
//        } catch (IOException e) {
//            e.printStackTrace();
//            return List.of();
//        }
//    }
//}