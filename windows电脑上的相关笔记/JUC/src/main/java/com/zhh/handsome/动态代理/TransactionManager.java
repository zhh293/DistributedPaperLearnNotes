package com.zhh.handsome.动态代理;

public interface TransactionManager {
    void beginTransaction();
    void commit();
    void rollback();
}
