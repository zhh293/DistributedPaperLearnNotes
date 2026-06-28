package org.example.BEAN;

public class BeanAnnotationDemo {
/*    @Bean 注解最基本的用法是在
    @Configuration 注解的类中声明一个方法，
    该方法返回一个对象实例，
    这个实例会被注册为 Spring 容器中的 Bean
    @Configuration
    public class AppConfig {

        @Bean
        public MyService myService() {
            return new MyServiceImpl();
        }
    }*/


    /*自定义 Bean 名称
    可以通过 @Bean 注解的 name 或 value 属性指定 Bean 的名称：
    @Configuration
    public class AppConfig {

        @Bean(name = "myCustomService")
        public MyService myService() {
            return new MyServiceImpl();
        }

        // 或者使用value属性
        @Bean(value = "anotherService")
        public MyService anotherService() {
            return new MyServiceImpl();
        }

        // 多个名称
        @Bean({"service1", "service2", "service3"})
        public MyService multipleNamedService() {
            return new MyServiceImpl();
        }
    }*/

/*    @Bean
    public DataSource dataSource() {
        // 配置数据源
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        // Spring会自动注入dataSource Bean，这一点也太牛逼了吧，我操了
        return new JdbcTemplate(dataSource);
    }*/




/*    @Configuration
    public class AppConfig {

        @Bean
        @Primary // 当有多个同类型Bean时，优先使用这个
        public MyService primaryService() {
            return new MyServiceImpl();
        }

        @Bean
        @Qualifier("specialService") // 使用限定符标识Bean
        public MyService specialService() {
            return new SpecialServiceImpl();
        }
    }*/
}
