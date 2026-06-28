package com.zhh.handsome.动态代理;

public class UserServiceImpl implements UserService {
    @Override
    @transaction
    public void addUser() {
//        int i=1/0;
        System.out.println("添加用户");
    }
    @Override
    public void deleteUser() {
        System.out.println("删除用户");
    }
}
