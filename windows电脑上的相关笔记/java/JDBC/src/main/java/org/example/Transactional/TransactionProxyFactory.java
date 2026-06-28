package org.example.Transactional;

import java.lang.reflect.Proxy;

public class TransactionProxyFactory {
    private final Object target;
    private final TransactionalManager transactionManager;
    public TransactionProxyFactory(Object target, TransactionalManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }
    // 创建代理对象
    public Object createProxy() {
        // 使用JDK动态代理
        return Proxy.newProxyInstance(
                target.getClass().getClassLoader(), // 类加载器
                target.getClass().getInterfaces(),  // 目标对象实现的接口
                new TransactionInvocationHandler(target, transactionManager) // 事务调用处理器
        );
    }
}
