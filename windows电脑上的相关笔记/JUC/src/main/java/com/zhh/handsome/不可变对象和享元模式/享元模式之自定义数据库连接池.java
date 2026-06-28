/*
package com.zhh.handsome.不可变对象和享元模式;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 连接池配置类：存储数据库连接信息和池参数
class PoolConfig {
    // 数据库连接配置
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClass = "com.mysql.cj.jdbc.Driver"; // MySQL 8.0+ 驱动

    // 池参数
    private int minIdle = 5;    // 最小空闲连接数（初始化时创建）
    private int maxTotal = 10;  // 最大连接数（池里最多有多少个连接）
    private long maxWait = 3000; // 获取连接的最大等待时间（毫秒）

    // 构造器 + getter/setter（简化，用lombok可省略）
    public PoolConfig(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    // getter/setter 省略...
}

// 步骤 2：实现代理 Connection（关键！）
// 原生 Connection 的 close() 方法会直接关闭底层连接，我们需要通过「动态代理」重写这个方法，改成「归还连接到池」：




class PooledConnection implements InvocationHandler {
    private final Connection target; // 原生数据库连接（被代理对象）
    private final BasicConnectionPool pool; // 所属连接池（用于归还）

    // 构造器：传入原生连接和连接池
    public PooledConnection(Connection target, BasicConnectionPool pool) {
        this.target = target;
        this.pool = pool;
    }

    // 创建代理对象（对外提供的Connection是代理对象）
    public static Connection wrap(Connection target, BasicConnectionPool pool) {
        return (Connection) Proxy.newProxyInstance(
                PooledConnection.class.getClassLoader(),
                new Class[]{Connection.class}, // 代理接口
                new PooledConnection(target, pool) // InvocationHandler
        );
    }

    // 代理逻辑：拦截方法调用
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 拦截 close() 方法：不关闭连接，而是归还到池
        if ("close".equals(method.getName())) {
            pool.returnConnection((Connection) proxy); // 归还代理连接
            return null; // close() 无返回值
        }
        // 其他方法（如createStatement、executeQuery）直接调用原生连接的方法
        return method.invoke(target, args);
    }

    // 暴露原生连接（用于连接有效性校验）
    public Connection getTarget() {
        return target;
    }
}



public class BasicConnectionPool {
    private final PoolConfig config;
    private final List<Connection> idleConnections = new ArrayList<>(); // 空闲连接池
    private int activeCount = 0; // 活跃连接数（正在被使用的连接）

    // 构造器：初始化连接池（加载驱动 + 创建最小空闲连接）
    public BasicConnectionPool(PoolConfig config) {
        this.config = config;
        try {
            // 1. 加载数据库驱动
            Class.forName(config.getDriverClass());
            // 2. 初始化最小空闲连接
            for (int i = 0; i < config.getMinIdle(); i++) {
                idleConnections.add(createNewConnection());
            }
            System.out.println("连接池初始化完成，空闲连接数：" + idleConnections.size());
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("连接池初始化失败", e);
        }
    }

    // 核心方法1：获取连接
    public Connection getConnection() throws SQLException {
        // 1. 先从空闲池里拿连接（复用）
        if (!idleConnections.isEmpty()) {
            Connection proxyConn = idleConnections.remove(0);
            activeCount++;
            System.out.println("复用空闲连接，当前活跃连接数：" + activeCount + "，空闲连接数：" + idleConnections.size());
            return proxyConn;
        }

        // 2. 空闲池无连接，且未达最大连接数：创建新连接
        if (activeCount < config.getMaxTotal()) {
            Connection proxyConn = createNewConnection();
            activeCount++;
            System.out.println("创建新连接，当前活跃连接数：" + activeCount + "，空闲连接数：" + idleConnections.size());
            return proxyConn;
        }

        // 3. 达最大连接数：抛出异常（进阶版会加超时等待）
        throw new SQLException("连接池已达最大连接数：" + config.getMaxTotal() + "，无可用连接");
    }

    // 核心方法2：归还连接
    public void returnConnection(Connection proxyConn) {
        // 1. 活跃连接数减1
        activeCount--;
        // 2. 将代理连接放回空闲池（供下次复用）
        idleConnections.add(proxyConn);
        System.out.println("连接归还成功，当前活跃连接数：" + activeCount + "，空闲连接数：" + idleConnections.size());
    }

    // 辅助方法：创建新的原生连接 + 包装成代理连接
    private Connection createNewConnection() throws SQLException {
        // 1. 创建原生JDBC连接
        Connection rawConn = DriverManager.getConnection(
                config.getJdbcUrl(),
                config.getUsername(),
                config.getPassword()
        );
        // 2. 包装成代理连接（重写close()）
        return PooledConnection.wrap(rawConn, this);
    }

    // 核心方法3：关闭连接池（销毁所有连接）
    public void close() {
        System.out.println("开始关闭连接池，销毁所有连接...");
        // 销毁空闲池里的所有连接
        for (Connection conn : idleConnections) {
            try {
                // 这里要关闭「原生连接」，而非代理连接的close()（归还）
                PooledConnection handler = (PooledConnection) Proxy.getInvocationHandler(conn);
                handler.getTarget().close(); // 关闭原生连接
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        idleConnections.clear();
        activeCount = 0;
        System.out.println("连接池关闭完成");
    }

    //  getter 用于监控
    public int getActiveCount() {
        return activeCount;
    }

    public int getIdleCount() {
        return idleConnections.size();
    }
}





public class TestBasicPool {
    public static void main(String[] args) {
        // 1. 配置数据库连接信息（替换成你的数据库地址）
        PoolConfig config = new PoolConfig(
                "jdbc:mysql://localhost:3306/test_db?useSSL=false&serverTimezone=UTC",
                "root",
                "123456"
        );
        config.setMinIdle(2); // 最小空闲连接数2
        config.setMaxTotal(3); // 最大连接数3

        // 2. 创建连接池
        BasicConnectionPool pool = new BasicConnectionPool(config);

        // 3. 测试连接复用（单线程）
        try {
            // 第一次获取：复用空闲连接（初始化的2个之一）
            Connection conn1 = pool.getConnection();
            queryData(conn1, "第一次查询");
            conn1.close(); // 实际是归还连接，而非关闭

            // 第二次获取：复用刚才归还的连接
            Connection conn2 = pool.getConnection();
            queryData(conn2, "第二次查询");
            conn2.close();

            // 第三次获取：复用空闲连接
            Connection conn3 = pool.getConnection();
            queryData(conn3, "第三次查询");
            conn3.close();

            // 测试超过最大连接数（创建3个活跃连接）
            Connection connA = pool.getConnection();
            Connection connB = pool.getConnection();
            Connection connC = pool.getConnection();
            System.out.println("已创建3个活跃连接，当前活跃数：" + pool.getActiveCount());

            // 第四次获取：达最大连接数，抛出异常
            try {
                Connection connD = pool.getConnection();
            } catch (SQLException e) {
                System.out.println("预期异常：" + e.getMessage());
            }

            // 归还一个连接后，再获取
            connA.close();
            Connection connD = pool.getConnection();
            queryData(connD, "第四次查询（归还后获取）");
            connD.close();

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            // 4. 关闭连接池
            pool.close();
        }
    }

    // 辅助方法：执行查询（验证连接可用）
    private static void queryData(Connection conn, String label) throws SQLException {
        String sql = "SELECT 1 AS test_result";
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                System.out.println(label + "：连接可用，查询结果：" + rs.getInt("test_result"));
            }
        }
    }
}








*/
