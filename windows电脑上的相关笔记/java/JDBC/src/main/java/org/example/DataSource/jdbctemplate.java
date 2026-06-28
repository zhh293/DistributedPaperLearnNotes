package org.example.DataSource;

public class jdbctemplate {


   /* JdbcTemplate 是 Spring 框架提供的核心 JDBC 工具类，它封装了 JDBC 操作的底层细节（如连接获取 / 释放、异常处理等），通过简洁的 API 简化了数据库操作。以下是 JdbcTemplate 中最常用的核心 API 分类详解，包含方法用途、参数说明和使用示例。
    一、执行增删改操作（DML）：update() 系列方法
    update() 用于执行 INSERT/UPDATE/DELETE 等 DML 语句，返回受影响的行数（int 类型）。
            1. 基础重载方法
            java
    运行
    // 1. 无参数的SQL
    int update(String sql);

    // 2. 带参数的SQL（参数按顺序传递）
    int update(String sql, Object... args);

    // 3. 带参数且指定参数类型的SQL（更精确控制参数类型）
    int update(String sql, Object[] args, int[] argTypes);
    参数说明：
    sql：SQL 语句（如 INSERT INTO user(name, age) VALUES(?, ?)）。
    args：SQL 中 ? 对应的参数值（按顺序匹配）。
    argTypes：参数的 SQL 类型（如 Types.VARCHAR、Types.INTEGER，参考 java.sql.Types）。
            2. 使用示例
            java
    运行
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 1. 插入数据
    public void addUser(String name, int age) {
        String sql = "INSERT INTO user(name, age) VALUES(?, ?)";
        int rows = jdbcTemplate.update(sql, name, age); // 传递参数
        System.out.println("插入了 " + rows + " 行数据");
    }

    // 2. 更新数据
    public void updateUserAge(Long id, int newAge) {
        String sql = "UPDATE user SET age = ? WHERE id = ?";
        // 指定参数类型（第一个参数是INT，第二个是BIGINT）
        int rows = jdbcTemplate.update(sql, new Object[]{newAge, id},
                new int[]{Types.INTEGER, Types.BIGINT});
        System.out.println("更新了 " + rows + " 行数据");
    }

    // 3. 删除数据
    public void deleteUser(Long id) {
        String sql = "DELETE FROM user WHERE id = ?";
        int rows = jdbcTemplate.update(sql, id);
        System.out.println("删除了 " + rows + " 行数据");
    }
    二、查询单个值（如计数、求和）：queryForObject()（返回基本类型）
    queryForObject() 用于查询 单个值（如 COUNT(*)、SUM(age) 等），返回结果为基本类型（Integer、Long、String 等）。
            1. 方法定义
            java
    运行
    <T> T queryForObject(String sql, Class<T> requiredType); // 无参数
    <T> T queryForObject(String sql, Object[] args, Class<T> requiredType); // 带参数
    参数说明：
    requiredType：返回值的类型（如 Integer.class、String.class）。
    注意：如果查询结果为空，会抛出 EmptyResultDataAccessException，需提前处理。
            2. 使用示例
            java
    运行
    // 1. 查询用户总数
    public int getUserCount() {
        String sql = "SELECT COUNT(*) FROM user";
        // 返回Integer类型（COUNT(*)结果）
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    // 2. 查询某个用户的姓名
    public String getUserNameById(Long id) {
        String sql = "SELECT name FROM user WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new Object[]{id}, String.class);
        } catch (EmptyResultDataAccessException e) {
            // 处理结果为空的情况
            return null;
        }
    }

    // 3. 查询年龄总和
    public Long getTotalAge() {
        String sql = "SELECT SUM(age) FROM user";
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
    三、查询单行数据（映射为实体类）：queryForObject()（带 RowMapper）
    当查询结果为 单行数据 时，使用 queryForObject(sql, rowMapper, args) 将结果映射为自定义实体类（如 User）。
            1. 核心依赖：RowMapper
    RowMapper 是一个函数式接口，用于定义 结果集（ResultSet）到实体类的映射规则，需实现 mapRow(ResultSet rs, int rowNum) 方法。
    java
            运行
    // 定义实体类
    public class User {
        private Long id;
        private String name;
        private int age;
        // 构造器、getter、setter省略
    }

    // 定义RowMapper（映射规则）
    RowMapper<User> userRowMapper = (rs, rowNum) -> {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setName(rs.getString("name"));
        user.setAge(rs.getInt("age"));
        return user;
    };
2. 方法定义
            java
    运行
    <T> T queryForObject(String sql, RowMapper<T> rowMapper); // 无参数
    <T> T queryForObject(String sql, Object[] args, RowMapper<T> rowMapper); // 带参数
3. 使用示例
            java
    运行
    // 查询单个用户（按ID）
    public User getUserById(Long id) {
        String sql = "SELECT id, name, age FROM user WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new Object[]{id}, userRowMapper);
        } catch (EmptyResultDataAccessException e) {
            return null; // 无结果时返回null
        }
    }
    四、查询多行数据（映射为列表）：query() 方法
    query() 用于查询 多行数据，返回结果为 List<T>（T 为实体类），核心依赖仍是 RowMapper。
            1. 方法定义
            java
    运行
    <T> List<T> query(String sql, RowMapper<T> rowMapper); // 无参数
    <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper); // 带参数
2. 使用示例
            java
    运行
    // 1. 查询所有用户
    public List<User> getAllUsers() {
        String sql = "SELECT id, name, age FROM user";
        return jdbcTemplate.query(sql, userRowMapper);
    }

    // 2. 按条件查询（如年龄大于18的用户）
    public List<User> getUsersByAge(int minAge) {
        String sql = "SELECT id, name, age FROM user WHERE age > ?";
        return jdbcTemplate.query(sql, new Object[]{minAge}, userRowMapper);
    }
    五、批量操作：batchUpdate() 方法
    batchUpdate() 用于 批量执行 INSERT/UPDATE/DELETE，适合一次性插入 / 修改多条数据，提升性能。
            1. 方法定义
            java
    运行
    // 1. 基于参数数组的批量操作
    int[] batchUpdate(String sql, List<Object[]> batchArgs);

    // 2. 更灵活的批量操作（通过BatchPreparedStatementSetter设置参数）
    int[] batchUpdate(String sql, BatchPreparedStatementSetter pss);
    返回值：int[] 数组，每个元素对应批量操作中每行受影响的行数。
            2. 使用示例
            java
    运行
    // 1. 批量插入用户（基于参数列表）
    public void batchAddUsers(List<User> users) {
        String sql = "INSERT INTO user(name, age) VALUES(?, ?)";
        // 转换为参数列表（每个元素是一行数据的参数）
        List<Object[]> batchArgs = new ArrayList<>();
        for (User user : users) {
            batchArgs.add(new Object[]{user.getName(), user.getAge()});
        }
        // 执行批量插入
        int[] rows = jdbcTemplate.batchUpdate(sql, batchArgs);
        System.out.println("批量插入了 " + rows.length + " 条数据");
    }

    // 2. 批量更新用户年龄（基于BatchPreparedStatementSetter）
    public void batchUpdateAges(List<Long> userIds, int newAge) {
        String sql = "UPDATE user SET age = ? WHERE id = ?";
        int[] rows = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                // 为第i行设置参数（i是批量操作的索引）
                ps.setInt(1, newAge); // 第一个?：新年龄
                ps.setLong(2, userIds.get(i)); // 第二个?：用户ID
            }

            @Override
            public int getBatchSize() {
                // 返回批量操作的总条数
                return userIds.size();
            }
        });
    }
    六、执行 DDL 语句：execute() 方法
    execute() 用于执行 数据定义语言（DDL），如创建表、删除表等，无返回值（或返回 boolean 表示是否成功）。
            1. 方法定义
            java
    运行
    void execute(String sql); // 执行SQL，无返回值
    boolean execute(String sql); // 执行SQL，返回是否有结果集（用于判断查询类DDL）
2. 使用示例
            java
    运行
    // 创建表
    public void createUserTable() {
        String sql = "CREATE TABLE IF NOT EXISTS user (" +
                "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                "name VARCHAR(50) NOT NULL," +
                "age INT)";
        jdbcTemplate.execute(sql);
        System.out.println("表创建成功");
    }

    // 删除表
    public void dropUserTable() {
        String sql = "DROP TABLE IF EXISTS user";
        jdbcTemplate.execute(sql);
    }
    七、调用存储过程：call() 方法
    call() 用于调用数据库存储过程，需配合 CallableStatementCreator 和 RowMapper（如有返回结果）。
            1. 方法定义
            java
    运行
    <T> T call(CallableStatementCreator csc, List<SqlParameter> declaredParameters);
2. 使用示例（调用返回用户列表的存储过程）
    java
            运行
    // 假设存储过程：PROC_GET_USERS_BY_AGE(min_age INT, OUT user_list RESULTSET)
    public List<User> callGetUsersByAge(int minAge) {
        // 1. 定义存储过程调用SQL
        String sql = "{call PROC_GET_USERS_BY_AGE(?, ?)}";

        // 2. 声明参数（输入参数：min_age；输出参数：结果集）
        List<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter(Types.INTEGER)); // 输入参数：min_age
        parameters.add(new SqlReturnResultSet("user_list", userRowMapper)); // 输出结果集

        // 3. 创建CallableStatementCreator，设置输入参数
        CallableStatementCreator csc = con -> {
            CallableStatement cs = con.prepareCall(sql);
            cs.setInt(1, minAge); // 设置输入参数
            return cs;
        };

        // 4. 执行存储过程，获取结果
        Map<String, Object> result = jdbcTemplate.call(csc, parameters);
        // 从结果中提取输出的用户列表
        return (List<User>) result.get("user_list");
    }
    八、常用辅助工具：BeanPropertyRowMapper
    BeanPropertyRowMapper 是 Spring 提供的默认 RowMapper 实现，可 自动将结果集映射到实体类（基于属性名与列名匹配，支持驼峰命名），无需手动编写映射逻辑。
    java
            运行
    // 使用BeanPropertyRowMapper简化映射（前提：实体类属性名与表列名一致，或符合驼峰规则）
    RowMapper<User> userRowMapper = new BeanPropertyRowMapper<>(User.class);

    // 直接使用
    public List<User> getAllUsers() {
        String sql = "SELECT id, name, age FROM user";
        return jdbcTemplate.query(sql, userRowMapper);
    }
    总结
    JdbcTemplate 的核心价值在于 消除样板代码（如连接管理、异常处理），同时提供灵活的 API 覆盖各类数据库操作。关键 API 按场景可归纳为：
    操作类型	核心方法	典型场景
    增删改（DML）	update()	插入、更新、删除单条数据
    批量增删改	batchUpdate()	批量插入 / 更新多条数据
    查询单个值	queryForObject(sql, Class)	计数、求和等聚合查询
    查询单行数据	queryForObject(sql, RowMapper)	根据 ID 查询单个实体
    查询多行数据	query(sql, RowMapper)	查询列表、条件筛选
    执行 DDL	execute()	创建表、删除表等
    调用存储过程	call()	复杂业务逻辑的存储过程调用*/





}
