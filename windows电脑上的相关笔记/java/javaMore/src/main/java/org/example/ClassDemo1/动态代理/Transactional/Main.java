package org.example.ClassDemo1.动态代理.Transactional;

public class Main {
    public static void main(String[] args) {
        UserService userService = new UserServiceImpl();
        TransactionManager transactionManager=new SimpleTransactionManager();

        TransactionProxyFactory transactionProxyFactory=new TransactionProxyFactory(userService,transactionManager);

        UserService proxy = (UserService) transactionProxyFactory.createProxy();

        proxy.save("张鸿昊");

        proxy.delete("张鸿垣");

    }
}


//我说白了，反射，多线程，动态代理才是java的精髓，太牛逼了

//呜呜呜呜