package org.example.Transactional;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class TransactionInvocationHandler implements InvocationHandler {
    private final Object target; // 目标对象
    private final TransactionalManager transactionManager; // 事务管理器

    public TransactionInvocationHandler(Object target, TransactionalManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 检查目标方法是否标注了@MyTransactional注解
        if (method.isAnnotationPresent(MyTransactional.class)) {
            return executeWithTransaction(method, args);
        } else {
            // 没有标注注解的方法，直接调用
            return method.invoke(target, args);
        }
    }

    // 带事务执行方法
    private Object executeWithTransaction(Method method, Object[] args) throws Throwable {
        try {
            // 1. 开启事务
            transactionManager.beginTransaction();

            // 2. 执行目标方法
            Object result = method.invoke(target, args);

            // 3. 方法执行成功，提交事务
            transactionManager.commit();

            return result;
        } catch (Exception e) {
            // 4. 方法执行异常，回滚事务
            transactionManager.rollback();

            // 抛出异常，让上层处理
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
