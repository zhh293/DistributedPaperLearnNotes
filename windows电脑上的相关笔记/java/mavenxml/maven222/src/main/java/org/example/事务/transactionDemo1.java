package org.example.事务;

public class transactionDemo1 {}
   /* 在Spring框架中， @Transactional  是处理数据库事务的核心注解，就像给方法套了一个“事务保护罩”，保证里面的操作要么全部成功提交，要么全部失败回滚。下面用大白话和生活例子详细讲解它的作用、用法和相关注解。

    一、@Transactional 是什么？能干嘛？

    类比场景：
    比如你给朋友转账1000元，需要分两步：

            1. 从你的账户扣1000元；

            2. 给朋友的账户加1000元。
    如果第二步突然出错（比如朋友账户冻结），这时候需要把第一步也撤销（回滚），否则你的钱扣了但朋友没收到，就亏大了。
             @Transactional  就是用来保证这两步要么都成功，要么都失败，避免“半吊子”操作。

    二、@Transactional 怎么用？常用属性有哪些？

            1. 基本用法

    步骤：

            1. 在Spring Boot项目中，默认已经开启事务支持（底层自动配置了  DataSourceTransactionManager ）。

            2. 在需要事务的方法上直接加  @Transactional  注解（通常加在Service层方法上）。

    示例代码：

    java

    @Service
    public class AccountService {

        @Autowired
        private AccountRepository repo;

        // 给方法加事务注解
        @Transactional
        public void transferMoney(Long fromId, Long toId, double amount) {
            // 扣钱
            repo.updateBalance(fromId, -amount);
            // 模拟网络异常（比如对方账户不存在）
            if (toId == 999) {
                throw new RuntimeException("目标账户不存在");
            }
            // 加钱
            repo.updateBalance(toId, +amount);
        }
    }
 

    执行逻辑：

            - 如果  transferMoney  方法执行中抛出异常（比如  RuntimeException ），两步操作都会回滚，你的账户和朋友账户都不会变。

            - 如果没抛异常，两步操作都会提交到数据库，钱正常转走。

            2. 常用属性详解（用中文大白话解释）

    表格

    属性名 作用 例子/默认值
rollbackFor 指定哪些异常需要回滚（默认只回滚 RuntimeException 和 Error） rollbackFor = Exception.class：所有异常都回滚
noRollbackFor 指定哪些异常不回滚（即使抛了这些异常，事务也会提交） noRollbackFor = BusinessException.class：业务异常不回滚
propagation 事务传播行为（多个带事务的方法互相调用时，如何管理事务边界） 默认 REQUIRED（见下文传播行为详解）
 isolation事务隔离级别（解决多个事务同时操作数据时的脏读、幻读等问题） 默认 DEFAULT（依赖数据库，比如MySQL默认可重复读）
 timeout事务超时时间（超过时间未完成则自动回滚） timeout = 30：30秒超时
 readOnly 是否为只读事务（优化查询性能，禁止写操作） readOnly = true：适合纯查询方法

    三、@Transactional 的“兄弟注解”：@EnableTransactionManagement

    作用：

            - 告诉Spring框架“我要开启事务管理”，通常在Spring Boot项目中自动生效（通过 @SpringBootApplication 间接引入），但如果是传统Spring项目，需要手动在配置类上加这个注解。

    示例代码：

    java

    @Configuration
    @EnableTransactionManagement // 开启事务管理
    public class AppConfig {
        // 其他配置...
    }


    为什么需要它？

            - Spring通过AOP（动态代理）实现事务管理 @EnableTransactionManagement 会注册一个 TransactionAspectSupport 切面，负责在方法执行前后开启/提交/回滚事务。

    四、事务传播行为（propagation）详解：多个事务方法互相调用怎么办？

    假设你有两个方法，都加了 @Transactional，当方法A调用方法B时，事务怎么管理？
            propagation 属性就是用来定义这种场景下的事务规则，共有7种模式，常用的有5种：

            1. REQUIRED（默认值）

    场景：如果当前有事务，方法B加入这个事务；如果没有，就新建一个事务。
    类比：就像“搭顺风车”，有车（事务）就上车，没车就自己打车（新建事务）。

    示例：

    java

    @Transactional(propagation = Propagation.REQUIRED) // 默认
    public void methodA() {
        methodB(); // methodB会加入methodA的事务
        // 若methodB抛异常，methodA和methodB的操作一起回滚
    }

    @Transactional
    public void methodB() {
        // ...
    }
 

        2. REQUIRES_NEW

    场景：不管当前有没有事务，方法B都新建一个事务，原来的事务挂起。
    类比：相当于“自己单独打车”，不和调用者共用户一辆车（事务）。

    示例：

    java

    @Transactional
    public void methodA() {
        methodB(); // methodB新建事务，和methodA的事务分开
        int i = 1/0; // methodA抛异常，只回滚methodA的操作，methodB的操作已提交
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void methodB() {
        // ...
    }
 

        3. SUPPORTS

    场景：如果当前有事务，方法B加入；如果没有，就以非事务方式执行。
    类比：“有车就上车，没车就走路”（非事务执行）。

            4. NOT_SUPPORTED

    场景：方法B以非事务方式执行，挂起当前事务（如果有的话）。
    类比：“拒绝坐车，自己走路”，不管调用者有没有事务，自己都不用事务。

            5. MANDATORY

    场景：必须存在当前事务，否则抛异常。
    类比：“必须坐车，没车就罢工”，如果调用者没开事务，方法B直接报错。

    五、常见误区和坑：为什么我的@Transactional不生效？

    即使加了  @Transactional ，也可能遇到事务不回滚的情况，常见原因有：

            1. 异常类型不对

- 默认只回滚运行时异常（RuntimeException）和Error，如果抛的是受检异常（Checked Exception，如IOException），不会自动回滚。
    解决：加  rollbackFor = Exception.class  强制回滚所有异常：
    java

    @Transactional(rollbackFor = Exception.class)
 

        2. 方法是 private 或 static 

            - Spring的事务代理基于接口或类代理， private 方法无法被代理，导致注解失效。
    解决：把方法改为  public 。

            3. 类内部调用

- 在同一个类中，方法A（无事务）调用方法B（有 @Transactional ），事务不会生效，因为没有通过代理对象调用。
    解决：注入自己（通过 @Autowired 注入当前类），用代理对象调用：
    java

    @Service
    public class UserService {
        @Autowired
        private UserService self; // 注入自己（代理对象）

        public void methodA() {
            self.methodB(); // 通过代理调用，事务生效
        }

        @Transactional
        public void methodB() {
            // ...
        }
    }
 

        4. 没有被Spring容器管理

- 如果类没有被 @Component 、 @Service 等注解标记，未纳入Spring容器， @Transactional  会失效。
    解决：确保类是Spring Bean（加注解或在扫描路径内）。

    六、拓展：编程式事务 vs 声明式事务

    除了 @Transactional （声明式事务），Spring还支持编程式事务，用 TransactionTemplate 手动控制事务：

    声明式事务（推荐）

    优点：简单，代码无侵入性，通过注解配置。
    缺点：灵活性较低，适合简单场景。

    编程式事务

    场景：需要更细粒度控制事务（如根据不同条件决定是否回滚）。
    示例代码：

    java

    @Service
    public class OrderService {
        private final TransactionTemplate transactionTemplate;

        @Autowired
        public OrderService(TransactionTemplate transactionTemplate) {
            this.transactionTemplate = transactionTemplate;
        }

        public void processOrder() {
            transactionTemplate.execute(status -> { // 手动开启事务
                try {
                    // 业务逻辑1
                /
}*/
