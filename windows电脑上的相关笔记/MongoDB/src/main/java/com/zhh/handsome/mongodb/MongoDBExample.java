package com.zhh.handsome.mongodb;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Arrays;
import java.util.List;

/**
 * MongoDB CRUD操作示例
 */
public class MongoDBExample {
    private static MongoCollection<Document> userCollection;

    public static void main(String[] args) {
        MongoClient mongoClient = null;
        try {
            // 1. 获取数据库连接
            mongoClient = MongoDBConfig.getMongoClient();
            MongoDatabase database = MongoDBConfig.getDatabase();
            System.out.println("成功连接到MongoDB数据库: " + database.getName());

            // 2. 获取或创建集合(表)
            userCollection = database.getCollection("users");
            System.out.println("成功获取集合: " + userCollection.getNamespace().getCollectionName());

            // 3. 执行CRUD操作
            // 清空集合（仅示例用）
            userCollection.deleteMany(new Document());

            // 创建操作
            createDocuments();

            // 读取操作
            readDocuments();

            // 更新操作
            updateDocuments();

            // 删除操作
            deleteDocuments();

        } catch (Exception e) {
            System.err.println("MongoDB操作出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 4. 关闭连接
            if (mongoClient != null) {
                mongoClient.close();
                System.out.println("MongoDB连接已关闭");
            }
        }
    }

    /**
     * 创建文档（插入数据）
     */
    private static void createDocuments() {
        System.out.println("\n===== 执行创建操作 =====");

        try {
            // 插入单个文档
            User user1 = new User("张三", 25, "zhangsan@example.com",
                    new String[]{"篮球", "游戏"}, false);
            Document doc1 = new Document("name", user1.getName())
                    .append("age", user1.getAge())
                    .append("email", user1.getEmail())
                    .append("hobbies", user1.getHobbies())
                    .append("isStudent", user1.isStudent());

            InsertOneResult result1 = userCollection.insertOne(doc1);
            System.out.println("插入单个文档成功，ID: " + result1.getInsertedId());

            // 插入多个文档
            User user2 = new User("李四", 30, "lisi@example.com",
                    new String[]{"读书", "跑步"}, true);
            User user3 = new User("王五", 28, "wangwu@example.com",
                    new String[]{"游泳", "健身"}, false);

            User user4=new User("赵六",48,"sjkfhkjh@qq.com",new String[]{"胆管","klobe"},false);

            Document doc2 = new Document("name", user2.getName())
                    .append("age", user2.getAge())
                    .append("email", user2.getEmail())
                    .append("hobbies", user2.getHobbies())
                    .append("isStudent", user2.isStudent());

            Document doc3 = new Document("name", user3.getName())
                    .append("age", user3.getAge())
                    .append("email", user3.getEmail())
                    .append("hobbies", user3.getHobbies())
                    .append("isStudent", user3.isStudent());

            Document doc4=new Document("name",user4.getName())
                    .append("age",user4.getAge())
                    .append("email",user4.getEmail())
                    .append("isStudent",user4.isStudent())
                    .append("hobbies",user4.getHobbies());

            List<Document> documents = Arrays.asList(doc2, doc3,doc4);
            InsertManyResult result2 = userCollection.insertMany(documents);
            System.out.println("插入多个文档成功，插入数量: " + result2.getInsertedIds().size());

        } catch (Exception e) {
            System.err.println("插入文档出错: " + e.getMessage());
        }
    }

    /**
     * 读取文档（查询数据）
     */
    private static void readDocuments() {
        System.out.println("\n===== 执行读取操作 =====");

        try {
            // 查询所有文档
            System.out.println("所有用户:");
            FindIterable<Document> allUsers = userCollection.find();
            for (Document doc : allUsers) {
                printDocument(doc);
            }

            // 按条件查询：年龄大于26的用户
            System.out.println("\n年龄大于26的用户:");
            FindIterable<Document> adultUsers = userCollection.find(Filters.gt("age", 26));
            for (Document doc : adultUsers) {
                printDocument(doc);
            }

            // 按条件查询：爱好包含"跑步"的用户
            System.out.println("\n爱好包含跑步的用户:");
            FindIterable<Document> runningLovers = userCollection.find(Filters.eq("hobbies", "跑步"));
            for (Document doc : runningLovers) {
                printDocument(doc);
            }

            // 查询单个文档
            System.out.println("\n查询名字为张三的用户:");
            Document zhangsan = userCollection.find(Filters.eq("name", "张三")).first();
            if (zhangsan != null) {
                printDocument(zhangsan);
            }

        } catch (Exception e) {
            System.err.println("查询文档出错: " + e.getMessage());
        }
    }

    /**
     * 更新文档
     */
    private static void updateDocuments() {
        System.out.println("\n===== 执行更新操作 =====");

        try {
            // 更新单个文档：将张三的年龄改为26
            UpdateResult result1 = userCollection.updateOne(
                    Filters.eq("name", "张三"),
                    Updates.set("age", 26)
            );
            System.out.println("更新单个文档 - 匹配数量: " + result1.getMatchedCount() +
                    ", 修改数量: " + result1.getModifiedCount());

            // 更新多个文档：将所有学生的邮箱后缀改为student.com
            UpdateResult result2 = userCollection.updateMany(
                    Filters.eq("isStudent", true),
                    Updates.set("email",
                            new Document("$regexReplace",
                                    new Document("input", "$email")
                                            .append("regex", "@.*")
                                            .append("replacement", "@student.com")))
            );
            System.out.println("更新多个文档 - 匹配数量: " + result2.getMatchedCount() +
                    ", 修改数量: " + result2.getModifiedCount());

            // 向数组添加元素：给王五添加一个爱好"旅行"
            UpdateResult result3 = userCollection.updateOne(
                    Filters.eq("name", "王五"),
                    Updates.push("hobbies", "旅行")
            );
            System.out.println("向数组添加元素 - 匹配数量: " + result3.getMatchedCount() +
                    ", 修改数量: " + result3.getModifiedCount());

            // 查看更新后的王五信息
            System.out.println("更新后的王五信息:");
            Document updatedWangwu = userCollection.find(Filters.eq("name", "王五")).first();
            if (updatedWangwu != null) {
                printDocument(updatedWangwu);
            }

        } catch (Exception e) {
            System.err.println("更新文档出错: " + e.getMessage());
        }
    }

    /**
     * 删除文档
     */
    private static void deleteDocuments() {
        System.out.println("\n===== 执行删除操作 =====");

        try {
            // 删除单个文档：删除名字为李四的用户
            DeleteResult result1 = userCollection.deleteOne(Filters.eq("name", "李四"));
            System.out.println("删除单个文档 - 删除数量: " + result1.getDeletedCount());

            // 删除多个文档：删除年龄小于27的用户
            DeleteResult result2 = userCollection.deleteMany(Filters.lt("age", 27));
            System.out.println("删除多个文档 - 删除数量: " + result2.getDeletedCount());

            // 查看删除后剩余的用户
            System.out.println("删除后剩余的用户:");
            FindIterable<Document> remainingUsers = userCollection.find();
            for (Document doc : remainingUsers) {
                printDocument(doc);
            }

        } catch (Exception e) {
            System.err.println("删除文档出错: " + e.getMessage());
        }
    }

    /**
     * 打印文档内容
     */
    private static void printDocument(Document doc) {
        System.out.println("ID: " + doc.getObjectId("_id") +
                ", 姓名: " + doc.getString("name") +
                ", 年龄: " + doc.getInteger("age") +
                ", 邮箱: " + doc.getString("email") +
                ", 爱好: " + doc.get("hobbies") +
                ", 是否学生: " + doc.getBoolean("isStudent"));
    }
}