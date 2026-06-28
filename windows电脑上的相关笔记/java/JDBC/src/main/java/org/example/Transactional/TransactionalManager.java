package org.example.Transactional;

public interface TransactionalManager {
    // 开启事务
    void beginTransaction();

    // 提交事务
    void commit();

    // 回滚事务
    void rollback();
}
