package org.example.Config;

import jakarta.annotation.PreDestroy;
import org.example.service.impl.BookServiceimpl;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

@Configuration
@ComponentScan("org.example.*")
@PropertySource("classpath:jdbc.properties")//扫描配置文件，加油
public class SpringConfig {
    //定义一个方法获取要获得的对象，方法返回值就是对象，方法名就是对象名
   /* public DataSource  getDataSource(){
        //创建连接池
        ComboPooledDataSource dataSource = new ComboPooledDataSource();
        //设置连接池属性
        try {
            dataSource.setDriverClass("com.mysql.jdbc.Driver");
        } catch (PropertyVetoException e) {
            e.printStackTrace();
        }
        dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/spring_db");
        dataSource.setUser("root");
      }
    */
/*  定义一个方法获解要管理的对象

    @Bean，表示当前方法的返网筑是一个Bean

    @Bean

    public DataSource dataSource(){

        DruidDataSource ds:new DruidDataSource();

        ds.setDriverClassName("com.mysql.jdbc.Driver);

                ds.setUrl("jdbc:mysql://localhost:3306/spring_db");

        ds,setUsername("root");

        ds.setPassword("root");

        return ds;
        但是这种方法耦合度太高，而且有点挤，所以还有另外一种方法*/
   /* 将独立的配置类加入核心配置

    方式一:导入式

    public class JdbcConfig{

        @Bean

        public Datasource datasource(){

            DruidDataSource ds=newDruidDatasource();

/1相关配置

            return ds;

            使用@Import注解手动加入配置类到核心配置，此注解只能添加一次，多个数据请用数组格式

            @Configuration

            @Import(JdbcConfig.class)

            public class springConfig {*/











/*    @Configuration注解用于设定当前类为配置类

    @Componentscan注解用于设定扫描路径，此注解只能添加一次，多个数据请用数组格式

    @ComponentScan({com.itheima.service","com.itheima.dao"})*/
   /* @Bean
    public BookServiceimpl getBookService(){
        return new BookServiceimpl();
     }*/
/*bean作用范围

        bean生命周期*/
//@Scope("prototype") 控制ioc对象是否单例
//@PostConstruct，在bean对象的初始化方法上面加入，表示这是对象初始化时要做的处理方法,构造方法执行完之后执行
//    @PreDestroy 表示对象销毁前要执行的方法
}
