package com.zhh.handsome.SpringbootMongodb;

public class Demo {


  /*  以下是 Spring Boot 集成 MongoDB 后，从简单到复杂的核心操作语法总结，涵盖基础配置、CRUD、查询、更新、删除、进阶操作等，主要围绕MongoTemplate和Repository两种方式展开：
    一、基础准备（前提）
            1. 依赖与配置
            xml
<!-- pom.xml 依赖 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
    yaml
# application.yml 配置
    spring:
    data:
    mongodb:
    uri: mongodb://localhost:27017/testdb # 无密码
            # 带认证：mongodb://user:pass@localhost:27017/testdb?authSource=admin
            2. 实体类映射（核心）
    java
            运行
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

    @Document(collection = "user") // 绑定集合名（默认类名小写）
    public class User {
        @Id // 映射MongoDB的_id（主键）
        private String id; // 推荐用String，自动兼容ObjectId

        @Field("username") // 映射集合字段名（默认与属性名一致）
        private String name;

        private Integer age;
        private List<String> hobbies; // 数组类型
        private Address address; // 嵌套对象（需定义Address类）

        // getter、setter、构造器
    }
    二、简单 CRUD 操作
1. 插入（新增）
            （1）MongoTemplate 方式
    java
            运行
    @Autowired
    private MongoTemplate mongoTemplate;

    // 插入单条
    User user = new User();
user.setName("张三");
user.setAge(20);
    User savedUser = mongoTemplate.insert(user); // 返回含id的对象

    // 批量插入
    List<User> users = Arrays.asList(user1, user2);
mongoTemplate.insertAll(users);
（2）Repository 方式
    java
            运行
    // 定义接口
    public interface UserRepository extends MongoRepository<User, String> {
        // 继承自带的save方法（新增/更新）
    }

    // 使用
    @Autowired
    private UserRepository userRepo;

    User saved = userRepo.save(user); // 新增（id为null时）或更新（id存在时）
2. 查询（单条 / 列表）
            （1）MongoTemplate 方式（基础查询）
    java
            运行
    // 查单个：根据id
    User user = mongoTemplate.findById("123", User.class);

    // 查单个：条件查询（name = "张三"）
    Query query = Query.query(Criteria.where("name").is("张三"));
    User zhangsan = mongoTemplate.findOne(query, User.class);

    // 查列表：所有数据
    List<User> all = mongoTemplate.findAll(User.class);

    // 查列表：条件查询（age > 18）
    Query query = Query.query(Criteria.where("age").gt(18));
    List<User> adults = mongoTemplate.find(query, User.class);
（2）Repository 方式（方法名规则）
    java
            运行
    public interface UserRepository extends MongoRepository<User, String> {
        // 按name查询（等于）
        User findByName(String name);

        // 按age查询（小于）
        List<User> findByAgeLessThan(Integer age);

        // 多条件：name包含xx且age在xx到xx之间
        List<User> findByNameContainingAndAgeBetween(String namePart, Integer min, Integer max);

        // 排序：按age降序
        List<User> findByHobbiesContainingOrderByAgeDesc(String hobby);
    }

    // 使用
    User zhangsan = userRepo.findByName("张三");
    List<User> teens = userRepo.findByAgeLessThan(18);
3. 更新
（1）MongoTemplate 方式
    java
            运行
    // 更新单条：name为"张三"的用户，age改为21
    Query query = Query.query(Criteria.where("name").is("张三"));
    Update update = new Update().set("age", 21); // $set操作
mongoTemplate.updateFirst(query, update, User.class);

    // 更新多条：所有age < 18的用户，添加"学生"标签
    Query query = Query.query(Criteria.where("age").lt(18));
    Update update = new Update().addToSet("tags", "学生"); // $addToSet操作
mongoTemplate.updateMulti(query, update, User.class);

// 不存在则插入（upsert）
mongoTemplate.upsert(query, update, User.class);
（2）Repository 方式（@Modifying 注解）
    java
            运行
    public interface UserRepository extends MongoRepository<User, String> {
        @Modifying
        @Query("{'name': ?0}") // 查询条件：name = ?0
        void updateAgeByName(String name, @Update("{'$set': {'age': ?1}}") Integer age);
    }

// 使用（需在Service层加@Transactional）
userRepo.updateAgeByName("张三", 21);
4. 删除
（1）MongoTemplate 方式
    java
            运行
// 根据id删除
mongoTemplate.remove("123", User.class);

    // 条件删除：age < 18的用户
    Query query = Query.query(Criteria.where("age").lt(18));
mongoTemplate.remove(query, User.class);
（2）Repository 方式
    java
            运行
    public interface UserRepository extends MongoRepository<User, String> {
        // 按name删除
        void deleteByName(String name);

        // 按age删除（小于）
        long deleteByAgeLessThan(Integer age); // 返回删除条数
    }

// 使用
userRepo.deleteByName("张三");
    long deleted = userRepo.deleteByAgeLessThan(18);
    三、中等复杂度操作
1. 复杂查询（多条件、分页、排序）
            （1）MongoTemplate 方式
    java
            运行
    // 多条件：age > 18 且 hobbies包含"篮球" 且 address.city = "北京"
    Criteria criteria = new Criteria();
criteria.and("age").gt(18);
criteria.and("hobbies").in("篮球");
criteria.and("address.city").is("北京");

    Query query = new Query(criteria);

// 分页：第2页（0开始），每页10条
query.skip(10).limit(10);

// 排序：按age降序，name升序
query.with(Sort.by(
        Sort.Order.desc("age"),
        Sort.Order.asc("name")
        ));

    List<User> result = mongoTemplate.find(query, User.class);
（2）Repository 方式（分页 + 排序）
    java
            运行
    public interface UserRepository extends MongoRepository<User, String> {
        // 分页查询：age > ?0
        Page<User> findByAgeGreaterThan(Integer age, Pageable pageable);
    }

    // 使用
    Pageable pageable = PageRequest.of(
            1, // 第2页（0基索引）
            10, // 每页10条
            Sort.by("age").descending() // 按age降序
    );
    Page<User> page = userRepo.findByAgeGreaterThan(18, pageable);
// 分页结果：page.getContent()（数据）、page.getTotalElements()（总条数）
2. 自定义查询（@Query 注解）
    用于方法名规则无法满足的场景：
    java
            运行
    public interface UserRepository extends MongoRepository<User, String> {
        // 原生条件：age > ?0 且 hobbies包含?1
        @Query("{'age': {'$gt': ?0}, 'hobbies': {'$in': [?1]}}")
        List<User> findAdultWithHobby(Integer age, String hobby);

        // 只返回指定字段（_id默认返回，用0排除）
        @Query(value = "{'age': {'$gt': 18}}", fields = "{'name': 1, 'age': 1, '_id': 0}")
        List<User> findAdultNamesAndAges();

        // 模糊查询（正则）
        @Query("{'name': {'$regex': ?0, '$options': 'i'}}") // i表示忽略大小写
        List<User> findByNameLikeIgnoreCase(String namePart);
    }
    四、高级操作
1. 索引管理（注解方式）
    java
            运行
    @Document(collection = "user")
// 复合索引：name升序 + age降序
    @CompoundIndex(def = "{'name': 1, 'age': -1}", unique = false)
    public class User {
        @Id
        private String id;

        @Indexed(unique = true) // 唯一索引（避免重复name）
        private String name;

        @Indexed // 单字段索引（加速age查询）
        private Integer age;
    }
2. 聚合操作（统计、分组等）
    用MongoTemplate实现复杂统计：
    java
            运行
    // 示例：按城市分组，统计每个城市的用户数量
    public List<CityCount> groupByCity() {
        // 构建聚合管道
        Aggregation aggregation = Aggregation.newAggregation(
                // 1. 匹配条件：age > 18
                Aggregation.match(Criteria.where("age").gt(18)),
                // 2. 按address.city分组，统计数量
                Aggregation.group("address.city").count().as("userCount"),
                // 3. 投影结果（重命名字段）
                Aggregation.project("userCount")
                        .and("_id").as("city") // 将分组字段_id重命名为city
                        .andExclude("_id") // 排除默认的_id
        );

        // 执行聚合（输入集合名：user，输出类型：CityCount）
        AggregationResults<CityCount> results = mongoTemplate.aggregate(
                aggregation, "user", CityCount.class
        );
        return results.getMappedResults();
    }

    // 结果实体类
    @Data
    class CityCount {
        private String city;
        private Long userCount;
    }
3. 事务支持
            java
    运行
// 1. 配置事务管理器（MongoDB 4.0+支持）
    @Configuration
    public class MongoConfig {
        @Bean
        public MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
            return new MongoTransactionManager(factory);
        }
    }

    // 2. 在Service中使用
    @Service
    public class UserService {
        @Autowired
        private UserRepository userRepo;

        @Transactional // 开启事务
        public void transferData() {
            // 操作1：新增用户
            userRepo.save(new User("张三", 20));
            // 操作2：删除用户（若中间出错，所有操作回滚）
            userRepo.deleteByName("李四");
        }
    }
    总结
    简单操作：优先用Repository接口（方法名规则），零代码实现 CRUD；
    中等操作：复杂条件用MongoTemplate的Criteria，或Repository的@Query；
    高级操作：聚合用Aggregation，事务需配置管理器 +@Transactional。
    这种封装大幅简化了原生 MongoDB 的Document拼接操作，更符合 Java 开发者的面向对象习惯。*/




}
