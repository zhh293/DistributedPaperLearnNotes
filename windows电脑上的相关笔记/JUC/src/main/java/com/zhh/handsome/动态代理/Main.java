package com.zhh.handsome.动态代理;

public class Main {
    public static void main(String[] args) {
        UserService userService = new UserServiceImpl();

        TransactionManager txManager = new SimpleTransactionManager();
        TransactionPoxyFactory<UserService> proxyFactory = new TransactionPoxyFactory<>(userService,txManager);
        UserService proxyInstance = proxyFactory.createProxyInstance();
        proxyInstance.addUser();
        proxyInstance.deleteUser();
    }
}
