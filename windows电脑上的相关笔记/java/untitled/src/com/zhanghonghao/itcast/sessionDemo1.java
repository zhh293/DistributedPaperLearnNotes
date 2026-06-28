package com.zhanghonghao.itcast;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebServlet("/sessionDemo1")
public class sessionDemo1 extends HttpServlet {
    public void doGet(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session=request.getSession();
        session.setAttribute("username","zhanghonghao");//存储数据
        response.setHeader("Set-Cookie","JSESSIONID="+session.getId());
    }
    public void doPost(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session=request.getSession();
        session.setAttribute("username","zhanghonghao");//存储数据

    }

/*    I# Session :

    概念:服务器端会话技术，在一次会话的多次请求间共享数据，将数据保存在服务器端的对象中。Httpsession

1.

        2

    快速入门:

    Httpsession对象:

    object getAttribute(string name)

    void setAttribute(string name, object value)

    void removeAttribute(string name)*/


}
