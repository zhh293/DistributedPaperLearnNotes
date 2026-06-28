package com.zhh.handsome.动态代理;

import java.lang.reflect.Proxy;

public  class TransactionPoxyFactory<T>  {
    private T target;
    private TransactionManager transactionManager;
    private TransactionPoxyFactory() {}
    public TransactionPoxyFactory(T target, TransactionManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }
    public T createProxyInstance() {
        Object object = Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                target.getClass().getInterfaces(),
                new TransactionInvocationHandler(target, transactionManager)
        );
        return (T) object;
    }
}
