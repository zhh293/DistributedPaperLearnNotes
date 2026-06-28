package org.example.ClassDemo1.动态代理.Transactional;



public interface UserService {
    @MyTransaction
    void save(String name);

    void delete(String name);

}
