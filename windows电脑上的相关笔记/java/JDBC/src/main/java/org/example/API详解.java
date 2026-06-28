package org.example;

public class API详解 {
    public static void main(String[] args) {
        //DriverManager

       /* 1. DriverManager（驱动管理类）
        作用：
        管理数据库驱动，负责加载驱动和获取数据库连接。
        核心方法：
        方法签名	说明
        static Connection getConnection(String url, String user, String password)	根据 URL、用户名、密码获取数据库连接（最常用）。
        static void registerDriver(Driver driver)	手动注册驱动（JDBC 4.0+ 后可省略，驱动会自动加载）。
        示例：
        java
                运行
        String url = "jdbc:mysql://localhost:3306/testdb?useSSL=false&serverTimezone=UTC";
        String user = "root";
        String pwd = "123456";

// 获取连接（自动加载驱动，前提：驱动 jar 包在类路径中）
        try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
            // ... 后续操作
        } catch (SQLException e) {
            e.printStackTrace();
        }


        注意事项：
        URL 格式：不同数据库 URL 不同（如 MySQL：jdbc:mysql://host:port/dbname?参数；Oracle：jdbc:oracle:thin:@host:port:sid）。
        驱动自动加载：JDBC 4.0+ 支持 SPI 机制，只要驱动 jar 包在类路径中，会自动加载（无需 Class.forName("com.mysql.jdbc.Driver")）。*/

      /* . Connection（数据库连接）
        作用：
        代表应用与数据库的连接，是操作数据库的基础（创建 SQL 执行对象、事务控制等）。
        核心方法：
        方法签名	说明
        Statement createStatement()	创建 Statement 对象（执行静态 SQL）。
        PreparedStatement prepareStatement(String sql)	创建 PreparedStatement 对象（执行预编译 SQL）。
        void setAutoCommit(boolean autoCommit)	设置事务自动提交（默认 true；手动事务需设为 false）。
        void commit() / void rollback()	提交 / 回滚事务（需配合 setAutoCommit(false) 使用）。
        void close()	关闭连接（释放资源，推荐用 try-with-resources 自动关闭）。
        示例（事务控制）：
        java
                运行
        try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
            conn.setAutoCommit(false); // 开启手动事务

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE account SET balance = balance - 100 WHERE id = 1");
                stmt.executeUpdate("UPDATE account SET balance = balance + 100 WHERE id = 2");
                conn.commit(); // 提交事务
            } catch (SQLException e) {
                conn.rollback(); // 异常时回滚
                e.printStackTrace();
            }
        }*/


        /*. Statement（静态 SQL 执行对象）
        作用：
        执行 静态 SQL 语句（SQL 语句直接拼接，无参数化）。
        核心方法：
        方法签名	说明
        ResultSet executeQuery(String sql)	执行查询 SQL（如 SELECT），返回 ResultSet 结果集。
        int executeUpdate(String sql)	执行增删改 SQL（如 INSERT/UPDATE/DELETE），返回影响行数。
        boolean execute(String sql)	执行任意 SQL（如 CREATE TABLE），返回 true 表示有结果集。
        示例（存在 SQL 注入风险！）：
        java
                运行
        String username = "admin'; DROP TABLE users; --"; // 恶意输入
        String sql = "SELECT * FROM users WHERE username = '" + username + "'";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            // ... 若执行，数据库表可能被删除！
        }


        注意事项：
        SQL 注入漏洞：用户输入直接拼接 SQL，可能导致恶意攻击（如删表、越权查询）。
        效率低：每次 SQL 都需重新解析，无法复用，仅适合静态 SQL 场景。*/



       /* ResultSet（结果集）
        作用：
        存储 查询结果（如 SELECT 语句返回的数据），支持遍历、获取列值。
        核心方法：
        方法签名	说明
        boolean next()	移动到下一行，返回 false 表示无更多数据。
        Xxx getXxx(int columnIndex)	根据列索引（从 1 开始）获取值（如 getString(1)、getInt(2)）。
        Xxx getXxx(String columnName)	根据列名获取值（如 getString("username")）。
        void close()	关闭结果集（推荐 try-with-resources 自动关闭）。
        示例：
        java
                运行
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM users")) {

            while (rs.next()) { // 遍历结果集
                int id = rs.getInt("id");      // 列名方式
                String name = rs.getString(2); // 列索引方式（第 2 列）
                System.out.println(id + ": " + name);
            }
        }

        注意事项：
        列索引 vs 列名：列名更直观，但需注意数据库大小写敏感（如 MySQL 列名不敏感，Oracle 敏感）。
        游标初始位置：next() 前，游标在第一行之前，需先调用 next() 进入第一行。*/


        /*PreparedStatement（预编译 SQL 执行对象）
        作用：
        执行 预编译 SQL（用 ? 占位符参数化，解决 SQL 注入，提升效率）。
        核心方法：
        方法签名	说明
        void setXxx(int paramIndex, Xxx value)	设置占位符参数（paramIndex 从 1 开始，如 setString(1, "admin")）。
        ResultSet executeQuery()	执行预编译查询（无参，因为 SQL 已预定义）。
        int executeUpdate()	执行预编译增删改（无参）。
        示例（安全防注入）：
        java
                运行
        String sql = "SELECT * FROM users WHERE username = ? AND password = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username); // 设置第 1 个占位符
            pstmt.setString(2, password); // 设置第 2 个占位符

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // 登录成功
                }
            }
        }
        优势对比（与 Statement 相比）：
        特性	Statement	PreparedStatement
        SQL 拼接	直接拼接（风险高）	占位符 ?（安全）
        预编译	每次重新解析	预编译后复用（效率高）
        SQL 注入	易受攻击	自动转义参数，彻底防御
        参数灵活性	静态 SQL，无法动态传参	动态设置参数，适合复杂查询
        注意事项：
        占位符限制：? 只能替代值（如 WHERE id = ?），不能替代表名、列名、SQL 关键字（如 SELECT * FROM ? 非法）。
        预编译缓存：数据库会缓存预编译 SQL 的执行计划，多次执行相同 SQL 时性能更优。*/




    }
}
