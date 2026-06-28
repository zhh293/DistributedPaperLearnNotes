package org.example.ClassDemo1.动态代理.Transactional;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class TransactionInnovationHandler implements InvocationHandler {
    private Object target;
    private TransactionManager transactionManager;
    public TransactionInnovationHandler(Object target, TransactionManager transactionManager) {
        this.target = target;
        this.transactionManager = transactionManager;
    }
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if(method.isAnnotationPresent(MyTransaction.class)){
            return executeTransaction(method,args);
        }
        return method.invoke(target, args);
    }

    private Object executeTransaction(Method method, Object[] args) throws Throwable {
        try{
            transactionManager.begin();

            Object result = method.invoke(target, args);

            transactionManager.commit();

            return result;

        }catch (Exception e){
            transactionManager.rollback();
            throw e;
        }
    }
}
