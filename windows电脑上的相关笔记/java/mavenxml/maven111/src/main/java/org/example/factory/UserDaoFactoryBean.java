package org.example.factory;

import org.example.dao.BookDao;
import org.example.dao.impl.BookDaoImpl;
import org.springframework.beans.factory.FactoryBean;

public class UserDaoFactoryBean implements FactoryBean <BookDao>{
    //代替原始实例工厂中创建对象的方法
    @Override
    public BookDao getObject() throws Exception {
        System.out.println("BookDaoFactoryBean getObject");
        return new BookDaoImpl();
    }
   //
    @Override
    public Class<?> getObjectType() {
        return BookDao.class;
    }
    @Override
    public boolean isSingleton() {
        return true;
    }
}
