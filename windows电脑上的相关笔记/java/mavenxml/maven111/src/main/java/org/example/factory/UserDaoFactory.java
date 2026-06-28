package org.example.factory;

import org.example.dao.OrderDao;
import org.example.dao.impl.BookDaoImpl;

public class UserDaoFactory {
    public OrderDao createOrderDao() {
        return new BookDaoImpl();
    }
}
