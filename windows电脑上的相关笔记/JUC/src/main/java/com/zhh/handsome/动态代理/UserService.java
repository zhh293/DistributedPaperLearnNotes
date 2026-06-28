package com.zhh.handsome.动态代理;

public interface UserService {
    @transaction
    void addUser();
    void deleteUser();
}
