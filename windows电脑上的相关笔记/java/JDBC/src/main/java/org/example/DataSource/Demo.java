package org.example.DataSource;

public class Demo {
/*    在 Java JDBC（Java Database Connectivity）中，DataSource和DriverManager都是用于获取数据库连接的核心组件，但它们在设计理念、功能特性和适用场景上有显著区别。
    下面详细解析两者的区别、联系，以及DataSource在项目级别的配置与使用示例。
    一、DataSource 与 DriverManager 的区别
    维度	DriverManager	DataSource
    出现时间	JDBC 1.0 规范（早期版本）	JDBC 2.0 规范（后期引入，更现代）
    连接管理方式	手动管理：每次获取连接需显式指定 URL、用户名、密码，且无内置连接池	自动管理：通常与连接池结合，由容器（如 Spring）统一管理连接生命周期
    功能特性	仅提供最基础的连接获取功能，不支持连接池、分布式事务等高级特性	支持连接池（如 HikariCP、C3P0）、分布式事务（JTA）、连接复用等企业级特性
    配置方式	连接参数（URL、用户名、密码）硬编码在代码中，修改需改代码	配置集中管理（如配置文件），修改无需改代码，便于维护
    适用场景	简单小程序、学习场景，对性能和可维护性要求低	企业级应用、生产环境，对性能（连接复用）、可扩展性、可维护性要求高
    资源释放	需手动关闭连接（connection.close()），易因遗漏导致连接泄露	连接池自动回收空闲连接，减少人为失误导致的资源泄露*/










}
