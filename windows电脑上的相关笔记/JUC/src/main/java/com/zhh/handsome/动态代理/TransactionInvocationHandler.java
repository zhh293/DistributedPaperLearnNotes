package com.zhh.handsome.动态代理;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class TransactionInvocationHandler implements InvocationHandler {
    private final Object target;
    private final TransactionManager transactionManager;
    public TransactionInvocationHandler(Object target, TransactionManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isAnnotationPresent(transaction.class)) {
            try {
                transactionManager.beginTransaction();
                Object result = method.invoke(target, args);
//                int i=  1/0;
                transactionManager.commit();
                return result;
            } catch (Exception e) {
                transactionManager.rollback();
                throw new RuntimeException(e);
            }
        } else {
            System.out.println("没有事务注解");
            System.out.println(target.getClass().getName());
            return method.invoke(target, args);
        }
    }
}
