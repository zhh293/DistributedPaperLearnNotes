package org.example.ClassDemo1.动态代理.Transactional;

public class UserServiceImpl implements UserService {
    @Override
    public void save(String name) {
        System.out.println("保存姓名" + name);
    }

    @Override
    public void delete(String name) {
        System.out.println("删除姓名" + name);
    }
}
