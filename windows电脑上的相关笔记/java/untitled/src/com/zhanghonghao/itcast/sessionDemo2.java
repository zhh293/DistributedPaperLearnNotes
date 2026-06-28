package com.zhanghonghao.itcast;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@WebServlet("/sessionDemo2")
public class sessionDemo2 extends HttpServlet {


     public void doGet(HttpServletRequest request, HttpServletResponse response){
         //1.得到session
         HttpSession session = request.getSession();
         //2.从session中获取数据
         Object username = session.getAttribute("username");
         System.out.println(username);
     }
     public void doPost(HttpServletRequest request, HttpServletResponse response){
         //1.得到session
         HttpSession session = request.getSession();
         //2.从session中获取数据
         Object username = session.getAttribute("username");
         System.out.println(username);
     }
}
