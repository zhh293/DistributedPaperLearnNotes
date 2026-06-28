package org.example;

import org.example.Config.SpringConfig;
import org.example.dao.BookDao;
import org.example.service.BookService;
import org.example.service.impl.BookServiceimpl;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        /*ApplicationContext  context = new ClassPathXmlApplicationContext("applicationContext.xml");
        BookDao bookDao =(BookDao) context.getBean("BookDao");
        bookDao.save();
        BookService bookService = (BookService) context.getBean(BookServiceimpl.class);
        bookService.printBooks();*/
//        ApplicationContext context=new AnnotationConfigApplicationContext(SpringConfig.class);
     /*   AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(SpringConfig.class);
        context.getBean(BookService.class).printBooks();
        BookDao bookDao =(BookDao) context.getBean("BookDao");
        bookDao.save();
        context.close();*/
        AnnotationConfigApplicationContext  context=new AnnotationConfigApplicationContext(SpringConfig.class);
//        context.getBean(BookService.class).printBooks();
        BookDao bookDao =(BookDao) context.getBean("BookDao");
        bookDao.update();
        context.close();
    }
}
/*Spring提供@Component注解的三个行生注解

@Controller:用于表现层bean定义

@Service:用于业务层bean定义

@Repository:用于数据层bean定义*/
/*第三方的bean管理容器：
    1.Spring
    2.MyBatis
    3.Struts
    4.Hibernate
    5.JPA
    6.其他框架*/

