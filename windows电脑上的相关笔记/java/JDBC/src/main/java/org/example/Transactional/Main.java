package org.example.Transactional;

public class Main {
    public static void main(String[] args) {
        // 1. 创建目标对象
        UserService userService = new UserServiceImpl();

        // 2. 创建事务管理器
       TransactionalManager transactionManager = new SimpleTransactionManager();

        // 3. 创建代理工厂并生成代理对象
        TransactionProxyFactory proxyFactory = new TransactionProxyFactory(userService, transactionManager);
        UserService userServiceProxy = (UserService) proxyFactory.createProxy();

        // 4. 测试带事务的方法
        System.out.println("=== 测试正常执行的事务方法 ===");
        userServiceProxy.createUser("张三");

        // 5. 测试不带事务的方法
        System.out.println("\n=== 测试不带事务的方法 ===");
        userServiceProxy.queryUser("张三");

        // 6. 测试事务回滚（取消注释下面的代码和createUser中的异常代码）
        // System.out.println("\n=== 测试事务回滚 ===");
        // try {
        //     userServiceProxy.createUser("error");
        // } catch (Exception e) {
        //     System.out.println("捕获到异常：" + e.getMessage());
        // }
    }
}

