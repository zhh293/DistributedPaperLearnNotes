//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
   /* 以下是 JDBC（Java Database Connectivity）中核心的类、接口及其常用方法整理，按功能模块分类，便于做笔记参考：
    一、核心包与基础概念
    核心包：java.sql（基础功能）、javax.sql（高级功能，如数据源）
    核心思想：通过接口定义数据库操作标准，数据库厂商提供实现（驱动 Jar 包）
    二、驱动与连接管理（获取数据库连接）
            1. DriverManager 类（java.sql）
    作用：管理数据库驱动，创建与数据库的连接。
    常用方法：
    static Connection getConnection(String url, String user, String password)
→ 根据 URL、用户名、密码获取数据库连接（核心方法）。
    示例 URL：jdbc:mysql://localhost:3306/dbname（MySQL）、jdbc:oracle:thin:@localhost:1521:orcl（Oracle）
    static void registerDriver(Driver driver)
→ 注册驱动（通常无需手动调用，驱动 Jar 包会自动注册）。
    static void deregisterDriver(Driver driver)
→ 注销驱动。
            2. Driver 接口（java.sql）
    作用：数据库驱动的核心接口，由数据库厂商实现（如 MySQL 的com.mysql.cj.jdbc.Driver）。
    常用方法：
    boolean acceptsURL(String url)
→ 判断驱动是否支持指定的 URL。
    Connection connect(String url, Properties info)
→ 根据 URL 和连接信息（用户名、密码等）创建连接。
            3. DataSource 接口（javax.sql）
    作用：替代DriverManager的高级连接管理方式，支持连接池、分布式事务等（推荐使用）。
    常用方法：
    Connection getConnection()
→ 获取数据库连接。
    Connection getConnection(String username, String password)
→ 带用户名密码的连接获取。
    三、连接对象（Connection 接口，java.sql）
    作用：代表与数据库的连接，用于创建语句对象、管理事务。
    常用方法：
            （1）创建语句对象
    Statement createStatement()
→ 创建普通语句对象（执行静态 SQL）。
    PreparedStatement prepareStatement(String sql)
→ 创建预编译语句对象（执行带参数的 SQL，防止 SQL 注入）。
    CallableStatement prepareCall(String sql)
→ 创建调用存储过程的语句对象。
            （2）事务管理
    void setAutoCommit(boolean autoCommit)
→ 设置是否自动提交事务（默认true，手动事务需设为false）。
    void commit()
→ 提交事务。
    void rollback()
→ 回滚事务（发生异常时调用）。
    void rollback(Savepoint savepoint)
→ 回滚到指定保存点。
    Savepoint setSavepoint()
→ 创建事务保存点。
            （3）其他常用方法
    void close()
→ 关闭连接（必须在finally中调用，释放资源）。
    boolean isClosed()
→ 判断连接是否已关闭。
    DatabaseMetaData getMetaData()
→ 获取数据库元数据（如数据库版本、支持的功能等）。
    四、语句对象（执行 SQL）
            1. Statement 接口（java.sql）
    作用：执行静态 SQL 语句（无参数）。
    常用方法：
    ResultSet executeQuery(String sql)
→ 执行查询语句（SELECT），返回结果集ResultSet。
    int executeUpdate(String sql)
→ 执行更新语句（INSERT/UPDATE/DELETE），返回受影响的行数。
    boolean execute(String sql)
→ 执行任意 SQL（可能返回结果集或受影响行数），返回true表示有结果集。
    void close()
→ 关闭语句对象（释放资源）。
            2. PreparedStatement 接口（java.sql，继承Statement）
    作用：执行预编译 SQL（带?参数），性能更好，防止 SQL 注入。
    新增常用方法（相比Statement）：
    void setXxx(int parameterIndex, Xxx value)
→ 为 SQL 中的?设置参数（Xxx为数据类型，如setInt、setString、setDate）。
    示例：setString(1, "张三") → 为第 1 个?设值为字符串 "张三"。
    ResultSet executeQuery()
→ 执行预编译的查询（无需再传 SQL）。
    int executeUpdate()
→ 执行预编译的更新（无需再传 SQL）。
            3. CallableStatement 接口（java.sql，继承PreparedStatement）
    作用：调用数据库存储过程。
    新增常用方法：
    void registerOutParameter(int parameterIndex, int sqlType)
→ 注册存储过程的输出参数（sqlType为Types类中的常量，如Types.INTEGER）。
    Xxx getXxx(int parameterIndex)
→ 获取输出参数的值（如getInt、getString）。
    void setXxx(int parameterIndex, Xxx value)
→ 设置输入参数（同PreparedStatement）。
    五、结果集（ResultSet 接口，java.sql）
    作用：存储查询语句（SELECT）的结果，通过游标遍历数据。
    常用方法：
            （1）游标移动
    boolean next()
→ 将游标移动到下一行，返回true表示有数据（核心遍历方法）。
    boolean previous()
→ 游标移动到上一行（需设置可滚动结果集）。
    void beforeFirst()
→ 游标移动到第一行之前。
    void afterLast()
→ 游标移动到最后一行之后。
            （2）获取列值
    Xxx getXxx(int columnIndex)
→ 根据列索引（从 1 开始）获取值（Xxx为数据类型，如getInt(1)、getString(2)）。
    Xxx getXxx(String columnLabel)
→ 根据列名获取值（如getString("name")、getDate("birth")）。
            （3）结果集属性与关闭
    void close()
→ 关闭结果集（释放资源）。
    boolean isClosed()
→ 判断结果集是否已关闭。
    ResultSetMetaData getMetaData()
→ 获取结果集元数据（如列名、数据类型）。
    六、元数据（获取数据库 / 结果集信息）
            1. DatabaseMetaData 接口（java.sql）
    作用：获取数据库的元信息（如数据库名称、版本、支持的功能）。
    常用方法：
    String getDatabaseProductName()
→ 获取数据库产品名称（如 "MySQL"、"Oracle"）。
    String getDatabaseProductVersion()
→ 获取数据库版本。
    ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
→ 获取数据库中的表信息（如查询所有表名）。
            2. ResultSetMetaData 接口（java.sql）
    作用：获取结果集的元信息（如列数、列名、数据类型）。
    常用方法：
    int getColumnCount()
→ 获取结果集的列数。
    String getColumnName(int column)
→ 根据列索引获取列名。
    int getColumnType(int column)
→ 根据列索引获取列的数据类型（对应Types类的常量）。
    七、异常处理（SQLException 类，java.sql）
    作用：处理 JDBC 操作中的异常（如连接失败、SQL 语法错误）。
    常用方法：
    String getMessage()
→ 获取异常信息。
    int getErrorCode()
→ 获取数据库厂商的错误码。
    SQLException getNextException()
→ 获取链式异常中的下一个异常（JDBC 可能抛出多个异常）。
    八、工具类（Types 类，java.sql）
    作用：定义 SQL 数据类型的常量（用于CallableStatement注册输出参数等场景）。
    常用常量：
    Types.INTEGER、Types.VARCHAR、Types.DATE、Types.DOUBLE、Types.BOOLEAN等。
    总结：JDBC 操作核心流程
    加载驱动（通常自动完成）→ 2. 通过DriverManager或DataSource获取Connection → 3. 创建Statement/PreparedStatement → 4. 执行 SQL → 5. 处理ResultSet → 6. 关闭资源（ResultSet→Statement→Connection，顺序不可反）。*/
}