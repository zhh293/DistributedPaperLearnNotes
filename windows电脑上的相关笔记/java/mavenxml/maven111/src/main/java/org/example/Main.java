package org.example;

import org.example.dao.BookDao;
import org.example.service.BookService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("application.xml");
        /*BookDao bookDao = (BookDao) context.getBean("bookDao");
        BookDao bookDao1 = (BookDao) context.getBean("bookDao");
        BookService bookService = (BookService) context.getBean("service");
        bookService.printBooks();
        System.out.println(bookDao1);
        System.out.println(bookDao);
        bookDao.save();*/
        //BookDao bookDao2=(BookDao)context.getBean("orderDao");//bookDao2.save();
        /*BookDao bookDao = (BookDao) context.getBean("userDao");
        BookDao bookDao2 = (BookDao) context.getBean("userDao");
        System.out.println(bookDao);
        System.out.println(bookDao2);
        bookDao.save();*/
        //动态创建，好耶好耶好耶！！！！！！！！！！！！！！！！
       /* context.registerShutdownHook();
        BookDao bookDao =(BookDao) context.getBean("bookDao");
        BookDao bookDao2 =(BookDao) context.getBean("userDao");*/
       // BookService bookService = (BookService) context.getBean("bookService");
        //bookService.printBooks();
        BookService service3 = (BookService) context.getBean("service3");
        service3.printBooks();

    }
}