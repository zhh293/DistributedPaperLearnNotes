package org.example.ClassDemo1.动态代理.Transactional;

public class SimpleTransactionManager implements TransactionManager {

    @Override
    public void begin() {
        System.out.println("begin");
    }

    @Override
    public void commit() {
       System.out.println("commit");
    }

    @Override
    public void rollback() {
        System.out.println("rollback");
    }
}
