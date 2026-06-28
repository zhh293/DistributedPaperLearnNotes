package org.example.Transactional;

// 业务接口实现类
public class UserServiceImpl implements UserService {

    @Override
    public void createUser(String username) {
        System.out.println("执行数据库操作：插入用户 " + username);

        // 模拟一个异常，测试事务回滚
        // if ("error".equals(username)) {
        //     throw new RuntimeException("模拟数据库操作失败");
        // }
    }

    @Override
    public void queryUser(String username) {
        System.out.println("执行数据库操作：查询用户 " + username);
    }
}
