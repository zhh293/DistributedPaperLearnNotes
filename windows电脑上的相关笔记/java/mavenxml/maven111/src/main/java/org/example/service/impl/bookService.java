package org.example.service.impl;

import org.example.dao.BookDao;
import org.example.service.BookService;

public class bookService implements BookService {
    private BookDao bookDao;
    public void setBookDao(BookDao bookDao) {
        this.bookDao = bookDao;
    }
    @Override
    public void printBooks() {
        bookDao.save();
        System.out.println("Book Service14532436475");
    }

    public BookDao getBookDao() {
        return bookDao;
    }
}
/*1. Spring 确实支持反射直接注入字段
Spring 可以通过反射直接注入私有字段，无需构造器或 setter 方法：
java
@Service
public class BookService {
    @Autowired
    private BookDao bookDao; // 无需构造器或setter
}

这种方式称为字段注入，它利用反射绕过访问修饰符（如 private）直接设置字段值。Spring 通过 AutowiredAnnotationBeanPostProcessor 实现这一功能。
2. 为什么还需要构造器 /setter 注入？
虽然字段注入更简洁，但构造器和 setter 注入在某些场景下更具优势：
（1）强制依赖与不可变性
构造器注入确保依赖在对象创建时就被设置，避免后续为空：
java
@Service
public class BookService {
    private final BookDao bookDao; // final字段必须通过构造器初始化

    public BookService(BookDao bookDao) {
        this.bookDao = bookDao;
    }
}


优点：依赖不可变（final）、对象创建后状态完整、避免 NullPointerException。
（2）依赖注入的设计原则
构造器注入符合 ** 依赖倒置原则（DIP）和控制反转（IoC）** 的核心思想：
对象不负责创建自己的依赖，而是通过外部（Spring 容器）注入。
构造器清晰表达类的依赖关系，提高代码可读性和可维护性。
3. 与构造器注入的对比
（1）构造器注入的不可变性
构造器注入天然支持 final 字段，确保依赖不可变：
java
@Service
public class BookService {
    private final BookDao bookDao;

    public BookService(BookDao bookDao) { // @Autowired可省略
        this.bookDao = bookDao;
    }
}
优势：
依赖在对象创建时即被设置，不可变（final）。
无需担心多线程环境下的字段修改问题。
（2）字段注入的潜在风险
字段注入若不使用 final，可能被类内部方法或反射修改：
java
@Service
public class BookService {
    @Autowired
    private BookDao bookDao;

    public void setBookDao(BookDao bookDao) { // 手动提供setter，允许外部修改
        this.bookDao = bookDao;
    }
}


风险：
非 final 字段可被修改，破坏依赖的不可变性。
多线程环境下可能引发线程安全问题。
维度	XML 强制 setter / 构造器	注解允许字段注入
历史兼容性	遵循 JavaBean 规范，适配早期 Java 生态	适应现代开发对简洁性的需求
显式 vs 隐式	依赖关系在 XML 中显式可见	依赖关系通过注解隐式表达
设计原则	符合封装性，强制依赖通过公共接口暴露	允许灵活注入，但可能破坏封装
测试友好性	易于手动实例化测试	依赖 Spring 容器或反射工具
性能	反射调用公共方法（略快）	直接反射字段（略慢）*/