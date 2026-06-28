package com.zhh.handsome.动态代理;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;

public class SimpleTransactionManager implements TransactionManager {
    /*static Connection connection;
    static {
        try {
            connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/test", "root", "root");

            connection.setAutoCommit(false);

        }catch (Exception e) {
            e.printStackTrace();
        }
    }*/
    @Override
    public void beginTransaction() {
        //开启事务
        try {

            Connection connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/test", "root", "root");
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            //connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            //connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            //connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        }catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("开启事务");

    }

    @Override
    public void commit() {
        System.out.println("提交事务");

    }

    @Override
    public void rollback() {
        System.out.println("回滚事务");
    }
}
