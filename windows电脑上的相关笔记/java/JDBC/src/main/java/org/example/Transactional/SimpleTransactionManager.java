package org.example.Transactional;

import org.example.Config;

import java.sql.Connection;
import java.sql.DriverManager;

public class SimpleTransactionManager implements TransactionalManager{

    @Override
    public void beginTransaction() {
        /*Connection connection = DriverManager.getConnection(Config.getUrl());
        connection.setAutoCommit(false);
        connection.*/
        System.out.println("【事务管理】开启数据库事务");
    }

    @Override
    public void commit() {
        System.out.println("【事务管理】提交数据库事务");
    }

    @Override
    public void rollback() {
        System.out.println("【事务管理】回滚数据库事务");
    }
}
