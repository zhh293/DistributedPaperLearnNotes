/*import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

*//**
 * DataSource工具类：初始化连接池，提供获取连接的方法
 *//*
public class DataSourceUtil {
    // 全局唯一的DataSource实例（连接池）
    private static HikariDataSource dataSource;

    static {
        try {
            // 1. 加载配置文件
            Properties props = new Properties();
            InputStream in = DataSourceUtil.class.getClassLoader().getResourceAsStream("db.properties");
            props.load(in);

            // 2. 初始化HikariCP配置
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(props.getProperty("jdbc.url"));
            config.setUsername(props.getProperty("jdbc.username"));
            config.setPassword(props.getProperty("jdbc.password"));
            // 连接池参数（从配置文件读取）
            config.setMaximumPoolSize(Integer.parseInt(props.getProperty("hikari.maximumPoolSize")));
            config.setMinimumIdle(Integer.parseInt(props.getProperty("hikari.minimumIdle")));
            config.setConnectionTimeout(Long.parseLong(props.getProperty("hikari.connectionTimeout")));
            config.setMaxLifetime(Long.parseLong(props.getProperty("hikari.maxLifetime")));

            // 3. 创建DataSource实例（连接池）
            dataSource = new HikariDataSource(config);
            System.out.println("DataSource初始化成功！连接池已创建");

        } catch (IOException | NumberFormatException e) {
            throw new RuntimeException("DataSource初始化失败", e);
        }
    }

    *//**
     * 获取数据库连接（从连接池获取，非新建）
     *//*
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    *//**
     * 关闭连接池（程序退出时调用）
     *//*
    public static void closeDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("DataSource已关闭");
        }
    }
}






连接池复用（核心优势）
HikariDataSource 内部维护了一个连接池，getConnection() 并非每次创建新连接，而是从池里获取空闲连接。
测试 testConnectionReuse() 中，两次获取的连接哈希值可能相同（若池中有空闲连接），证明连接被复用，减少了连接创建的性能损耗。

*/

