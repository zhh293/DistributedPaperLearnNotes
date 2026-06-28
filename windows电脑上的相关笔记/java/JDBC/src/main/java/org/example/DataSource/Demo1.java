package org.example.DataSource;

public class Demo1 {
/*    二、DataSource 与 DriverManager 的联系
    核心目标一致：两者都是 JDBC 规范中定义的组件，最终目的都是获取数据库连接（java.sql.Connection对象），用于执行 SQL 操作。
    底层依赖关系：DataSource的实现（如连接池）在底层可能会间接使用DriverManager获取原始连接（尤其是简单实现的DataSource），但会对连接进行池化管理以提升性能。
    兼容性：DriverManager是 JDBC 的基础组件，所有数据库驱动都必须兼容它；而DataSource是更高级的抽象，需依赖数据库驱动和具体实现（如连接池）。
    三、DataSource 的项目级别配置与使用 Demo
    在企业级项目中，DataSource通常结合连接池（如 HikariCP，Spring Boot 默认连接池）使用，由 Spring 框架统一管理。下面以Spring Boot + MySQL为例，展示DataSource的配置和使用。
            1. 环境准备
    依赖引入：在pom.xml（Maven）或build.gradle（Gradle）中添加必要依赖：
    xml
            <!-- Spring Boot JDBC  Starter（包含DataSource自动配置） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
            2. DataSource 配置（连接池参数）
    在application.properties或application.yml中配置数据库连接信息和连接池参数（以application.yml为例）：
    yaml
    spring:
    datasource:
            # 数据库连接信息
    url: jdbc:mysql://localhost:3306/test_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver  # MySQL 8.x 驱动类

    # Hikari连接池参数（Spring Boot默认使用Hikari）
    hikari:
    maximum-pool-size: 10  # 最大连接数（根据并发量调整）
    minimum-idle: 5        # 最小空闲连接数
    idle-timeout: 300000   # 空闲连接超时时间（5分钟，单位：毫秒）
    connection-timeout: 20000  # 连接超时时间（20秒）
    max-lifetime: 1800000   # 连接最大存活时间（30分钟）
    说明：Spring Boot 会根据配置自动创建DataSource实例（默认是HikariDataSource），无需手动编码。




    在项目中，可通过两种方式使用DataSource：
    方式 1：直接注入 DataSource 获取连接
            java
    运行
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

    @Service
    public class UserService {

        // 注入Spring自动配置的DataSource
        @Autowired
        private DataSource dataSource;

        public String getUserNameById(Long id) {
            String sql = "SELECT username FROM user WHERE id = ?";

            // 通过DataSource获取连接（推荐使用DataSourceUtils，与事务管理兼容）
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return rs.getString("username");
                }
            } catch (SQLException e) {
                throw new RuntimeException("查询失败", e);
            } finally {
                // 释放连接（实际由连接池回收，并非真正关闭）
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            return null;
        }
    }
    方式 2：通过 JdbcTemplate 简化操作（推荐）
    Spring 提供的JdbcTemplate封装了DataSource的使用，无需手动处理连接的获取和释放，更简洁安全：
    java
            运行
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

    @Service
    public class UserService {

        // 注入JdbcTemplate（内部依赖DataSource）
        @Autowired
        private JdbcTemplate jdbcTemplate;

        public String getUserNameById(Long id) {
            String sql = "SELECT username FROM user WHERE id = ?";
            // 直接使用JdbcTemplate执行查询，无需手动管理连接
            return jdbcTemplate.queryForObject(
                    sql,
                    new Object[]{id},
                    String.class  // 返回结果类型
            );
        }
    }
4. 自定义 DataSource 配置（可选）
    如果需要更灵活的配置（如多数据源），可手动定义DataSource的 Bean：
    java
            运行
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

    @Configuration
    public class DataSourceConfig {

        @Bean
        public DataSource dataSource() {
            // 配置Hikari连接池
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://localhost:3306/test_db");
            config.setUsername("root");
            config.setPassword("123456");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(5);
            // 其他参数...

            return new HikariDataSource(config);
        }
    }
    四、总结
    DriverManager是 JDBC 早期的基础组件，适合简单场景，但需手动管理连接，功能有限。
    DataSource是企业级应用的首选，结合连接池可实现连接复用、自动管理，支持高级特性（如分布式事务），配置集中且易于维护。
    在 Spring Boot 项目中，DataSource的配置通过application.yml完成，使用时可直接注入或通过JdbcTemplate简化操作，大幅提升开发效率和系统稳定性。*/


}



