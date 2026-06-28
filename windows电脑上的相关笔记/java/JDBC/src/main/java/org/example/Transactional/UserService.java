package org.example.Transactional;

// 业务接口示例
public interface UserService {
    // 带事务的方法
    @MyTransactional
    void createUser(String username);

    // 不带事务的方法
    void queryUser(String username);
}