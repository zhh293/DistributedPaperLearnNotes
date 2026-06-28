package org.example.service.impl;

import org.example.dao.BookDao;
import org.example.dao.OrderDao;
import org.example.service.BookService;
import org.example.service.OrderService;

public class BookServiceImpl implements BookService, OrderService {
    private BookDao bookDao;
    private OrderDao orderDao;
    private String dataName;
    private int age;
    private OrderDao userOrder;
    public void setBookDao(BookDao bookDao) {
        System.out.println("bookDao 注入检测：" + (bookDao != null)); // 输出是否为 true
        this.bookDao = bookDao;
    }

    public BookDao getBookDao() {
        return bookDao;
    }
    public void setOrderDao(OrderDao orderDao) {
        this.orderDao = orderDao;
    }
    public OrderDao getOrderDao() {
        return orderDao;
    }
    public void setDataName(String dataName) {
        this.dataName = dataName;
    }
    public String getDataName() {
        return dataName;
    }
    public void setAge(int age) {
        this.age = age;
    }
    public void setUserOrder(OrderDao userOrder) {
        this.userOrder = userOrder;
    }
    public OrderDao getUserOrder() {
        return userOrder;
    }
    @Override
    public void printBooks() {
        bookDao.save();
        System.out.println("Book Service"+dataName+age);
    }

    @Override
    public void save() {
        bookDao.save();
        System.out.println("Book Service11111111111111"+dataName+age);
    }
}
