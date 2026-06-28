package org.example.dao.impl;

import org.example.dao.BookDao;
import org.example.dao.OrderDao;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.awt.print.Book;

public class BookDaoImpl implements BookDao, OrderDao {
    //ApplicationContext applicationContext=new ClassPathXmlApplicationContext("application.xml");
    //private BookDao bookDao=(BookDao)applicationContext.getBean("bookDao");
    public BookDaoImpl() {
       // System.out.println("BookDaoImpl is running");
    }
    @Override
    public void save() {
        System.out.println("save Book and Order");
    }
    //bean创建的时候对应的操作
    public void init() {
        System.out.println("BookDaoImpl init");
    }
    //bean销毁前对应的操作
    public void destroy() {
        System.out.println("BookDaoImpl destroy");
    }
}
