package org.example.service.impl;

import org.example.dao.BookDao;
import org.example.dao.impl.BookDaoimpl;
import org.example.service.BookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Service
public class BookServiceimpl implements BookService {
     //按类型装配，具有局限性，如果有多个实现类就会报错
    //按名称装配
//    @Qualifier("BookDao")，而且这个注解必须陪着autowired注解，否则也会报错
        /*@Autowired
        private BookDao bookDao;*/
    @Autowired
    private BookDao bookDao;
//    之前的配置文件自动注入，set方法是不可以去掉的，但是这里 为什么可以


    public void printBooks()
    {
        System.out.println("BookServiceimpl.printBooks()");
        bookDao.save();
    }
   /* 使用@Autowired注解开启自动装配模式(按类型)

    @Service

    public class BookserviceImpl implements BookService {

        @Autowired

        private BookDao bookDao;

        public void setBookDao(BookDao bookDao){

            this.b&okDao = bookDao;

            public void save(){

                System.out.println("book service save ...”);

                        bookDao.save();

                注意:自动装配基于反射设计创建对象并暴力反射对应属性为私有属性初始化数据，因此无需提供setter方法

                注意:

                自动装配建议使用无参构造方法创建对象(默认)，如果不提供对应构造方法，请提供唯一的构造方法*/


            }
