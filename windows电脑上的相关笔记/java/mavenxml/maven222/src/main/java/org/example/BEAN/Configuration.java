package org.example.BEAN;

public class Configuration {
   /* @Configuration 注解详解
    @Configuration 是 Spring 框架中的一个核心注解，它用于标记一个类作为 Spring 应用程序的 Java 配置类。这个注解是 Spring 3.0 引入的，作为 XML 配置的替代方案，允许开发者使用纯 Java 代码来配置 Spring 应用。
    基本作用
    @Configuration 注解的主要作用是：

    标记一个类作为 Spring 配置类
    允许在类中定义 Bean
    支持 Bean 之间的依赖注入
    支持方法间的调用（CGLIB 代理增强）*/
   /*与 @Component 的区别
    @Configuration 注解实际上是 @Component 的一个特殊形式，它们的主要区别在于：

    @Configuration 类会被 CGLIB 代理，而 @Component 类不会
    @Configuration 类中的 @Bean 方法可以调用其他 @Bean 方法，确保返回的是同一个 Bean 实例
    @Configuration 类主要用于定义应用程序的整体配置，而 @Component 用于标记普通的组件类*/


    /*在上面的例子中，jdbcTemplate()方法调用了dataSource()方法。由于 AppConfig 类被 CGLIB 代理，
    这里的dataSource()调用实际上会返回 Spring 容器中已经存在的 DataSource Bean 实例，而不是创建一个新的实例。*/
}
/*
导入其他配置类
可以使用 @Import 注解导入其他配置类：*/

/*
导入资源文件
可以使用 @PropertySource 注解导入属性文件：


配置组件扫描
可以使用 @ComponentScan 注解配置组件扫描：
这样，Spring 会自动扫描com.example.service包下的所有 @Component、@Service、@Repository 和 @Controller 注解的类，并将它们注册为 Bean。*/
