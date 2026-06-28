package org.example.ClassDemo1.动态代理.Transactional;

import java.lang.reflect.Proxy;

public class TransactionProxyFactory {
    private final Object target;
    private final TransactionManager transactionManager;
    public TransactionProxyFactory(Object target, TransactionManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }
    public Object createProxy() {
        return Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                target.getClass().getInterfaces(),
                new TransactionInnovationHandler(transactionManager, transactionManager)
        );
    }
}
