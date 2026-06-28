package org.example.ClassDemo1.动态代理.Transactional;

public interface TransactionManager {
    void begin();
    void commit();
    void rollback();
}
