//package com.zhh.handsome.elkstudy.集成SpringBoot;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Component;
//
//import java.io.IOException;
//import java.util.List;
//
//@Component
//public class ElasticsearchDemo implements CommandLineRunner {
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
//    @Override
//    public void run(String... args) throws Exception {
//        System.out.println("开始演示 Elasticsearch 集成...");
//
//        // 1. 创建索引
//        String indexName = "demo_users";
//        System.out.println("创建索引: " + indexName);
//        boolean created = indexService.createIndex(indexName);
//        System.out.println("索引创建结果: " + created);
//
//        // 2. 添加一些示例数据
//        System.out.println("添加示例数据...");
//        addUser(indexName, "1", "张三", 25, "男", new String[]{"工程师", "Java"});
//        addUser(indexName, "2", "李四", 30, "女", new String[]{"设计师", "UI"});
//        addUser(indexName, "3", "王五", 28, "男", new String[]{"产品经理", "敏捷"});
//
//        // 3. 搜索演示
//        System.out.println("精确匹配搜索 (性别=男):");
//        List<User> maleUsers = searchService.termSearch(indexName, "sex", "男");
//        maleUsers.forEach(user -> System.out.println("  " + user));
//
//        System.out.println("全文搜索 (姓名=张三):");
//        List<User> zhangsanUsers = searchService.matchSearch(indexName, "name", "张三");
//        zhangsanUsers.forEach(user -> System.out.println("  " + user));
//
//        System.out.println("分页搜索 (第一页, 每页2条):");
//        List<User> paginatedUsers = searchService.paginatedSearch(indexName, "*", 1, 2);
//        paginatedUsers.forEach(user -> System.out.println("  " + user));
//
//        // 4. 更新文档
//        System.out.println("更新用户信息...");
//        User updatedUser = User.builder()
//                .name("张三丰")
//                .age(100)
//                .sex("男")
//                .tags(new String[]{"武当创始人", "太极拳"})
//                .build();
//        String updateResult = documentService.updateDocument(indexName, "1", updatedUser);
//        System.out.println("更新结果: " + updateResult);
//
//        // 5. 获取更新后的文档
//        System.out.println("获取更新后的用户:");
//        User retrievedUser = documentService.getDocument(indexName, "1");
//        System.out.println("更新后的用户: " + retrievedUser);
//
//        System.out.println("Elasticsearch 集成演示完成!");
//    }
//
//    private void addUser(String indexName, String id, String name, Integer age, String sex, String[] tags) throws IOException {
//        User user = User.builder()
//                .name(name)
//                .age(age)
//                .sex(sex)
//                .tags(tags)
//                .build();
//
//        String result = documentService.addDocument(indexName, user, id);
//        System.out.println("  添加用户 " + name + " 结果: " + result);
//    }
//}