/*import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

*//**
 * DataSource使用示例：展示连接复用、事务管理等特性
 *//*
public class DataSourceDemo {
    public static void main(String[] args) {
        // 测试1：展示连接复用（从连接池获取，而非每次新建）
        testConnectionReuse();

        // 测试2：展示事务管理
        testTransaction();

        // 程序结束时关闭连接池
        DataSourceUtil.closeDataSource();
    }

    *//**
     * 测试连接复用：多次获取连接，观察哈希值（相同则表示复用）
     *//*
    private static void testConnectionReuse() {
        System.out.println("\n=== 测试连接复用 ===");
        try (
                Connection conn1 = DataSourceUtil.getConnection();
                Connection conn2 = DataSourceUtil.getConnection()
        ) {
            // 打印连接哈希值（若连接池未耗尽，可能复用同一连接）
            System.out.println("连接1的哈希值：" + conn1.hashCode());
            System.out.println("连接2的哈希值：" + conn2.hashCode());
            System.out.println("连接1是否关闭：" + conn1.isClosed());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    *//**
     * 测试事务管理：插入两条数据，要么都成功，要么都失败
     *//*
    private static void testTransaction() {
        System.out.println("\n=== 测试事务管理 ===");
        Connection conn = null;
        try {
            // 1. 获取连接（从连接池）
            conn = DataSourceUtil.getConnection();
            // 2. 关闭自动提交（开启手动事务）
            conn.setAutoCommit(false);

            // 3. 执行SQL（插入两条数据）
            String sql1 = "INSERT INTO user (name, age) VALUES (?, ?)";
            try (PreparedStatement ps1 = conn.prepareStatement(sql1)) {
                ps1.setString(1, "张三");
                ps1.setInt(2, 20);
                ps1.executeUpdate();
            }

            // 模拟异常（取消注释可测试事务回滚）
            // int error = 1 / 0;

            String sql2 = "INSERT INTO user (name, age) VALUES (?, ?)";
            try (PreparedStatement ps2 = conn.prepareStatement(sql2)) {
                ps2.setString(1, "李四");
                ps2.setInt(2, 22);
                ps2.executeUpdate();
            }

            // 4. 无异常则提交事务
            conn.commit();
            System.out.println("事务提交成功：两条数据均插入");

        } catch (SQLException e) {
            // 5. 有异常则回滚事务
            if (conn != null) {
                try {
                    conn.rollback();
                    System.out.println("事务回滚：两条数据均未插入");
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            e.printStackTrace();
        } finally {
            // 6. 关闭连接（实际是归还到连接池）
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}*/

