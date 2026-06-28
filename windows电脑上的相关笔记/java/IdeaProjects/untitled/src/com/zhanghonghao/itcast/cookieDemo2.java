package com.zhanghonghao.itcast;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import java.io.IOException;
import java.util.Enumeration;

@WebServlet("/cookieDemo2")
public class cookieDemo2 extends HttpServlet {
    protected void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response) throws javax.servlet.ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        for(Cookie cookie:cookies){
            String name = cookie.getName();
            String value = cookie.getValue();
            System.out.println(name+":"+value);
            Enumeration <String> names = request.getHeaderNames();
            response.setContentType("text/html");
            while(names.hasMoreElements()){
                String name1 = names.nextElement();
                response.getWriter().write("<p>"+name1+":"+request.getHeader(name1)+"</p>");
            }
            String contextPath = request.getContextPath();
            response.getWriter().write("<p>contextPath:"+contextPath+"</p>");
            response.getWriter().write("<p>serverName:"+name+"</p>");
        }
    }
    protected void doGet(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response) throws javax.servlet.ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        for(Cookie cookie:cookies){
            String name = cookie.getName();
            String value = cookie.getValue();
            System.out.println(name+":"+value);
            Enumeration <String> names = request.getHeaderNames();
            response.setContentType("text/html");
            while(names.hasMoreElements()){
                String name1 = names.nextElement();
                response.getWriter().write("<p>"+name1+":"+request.getHeader(name1)+"</p>");
            }
            String contextPath = request.getContextPath();
            response.getWriter().write("<p>contextPath:"+contextPath+"</p>");
            response.getWriter().write("<p>serverName:"+name+"</p>");
        }
    }
}
