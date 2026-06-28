package com.zhanghonghao.itcast;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/cookieDemo")
public class cookieDemo extends HttpServlet {
    //会话技术：会话技术就是让服务器记住客户端，让客户端记住服务器。
    //一次会话包含多次请求和响应：浏览器第一次给服务器资源请求，服务器响应浏览器，浏览器第二次给服务器资源请求，服务器响应浏览器。
    //再一次会话的范围内获取数据
    //cookie快速入门，客户端会话技术；session：服务器会话技术
    //cookie：服务器给客户端返回一个cookie，客户端下次再访问服务器时，带上这个cookie，服务器根据这个cookie来判断客户端。
    //session：服务器给客户端返回一个session，客户端下次再访问服务器时，带上这个session，服务器根据这个session来判断客户端。
    //使用步骤
    //1.创建Cookie对象
    //Cookie cookie=new Cookie("username","zhanghonghao");
    //2.设置Cookie的属性
    //cookie.setMaxAge(60*60*24*7);
    //cookie.setPath("/");
    //3.发送Cookie
    //response.addCookie(cookie);
    //4.获取Cookie,拿到数据
    //Cookie[] cookies=request.getCookies();
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String method = request.getMethod();
        System.out.println(method);
    }
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Cookie  cookie=new Cookie("username1111","zhanghonghao");
        Cookie  cookie1=new Cookie("username2222","zhanghonghao");
        System.out.println("cookie");
        response.setContentType("text/html");
        response.setCharacterEncoding("utf-8");
        response.sendRedirect("/day16/cookieDemo.html");
        response.getWriter().write("<p>serverName:"+cookie.getName()+"这个网页能够顺利打开"+"</p>");
        response.addCookie(cookie);
        response.addCookie(cookie1);

    }
}
/*
客户端先向服务器的 CookieDemo1 发送请求。服务器处理该请求时，
创建一个 Cookie（比如 msg=hello），
并通过响应头的 set - cookie 字段将其发送给客户端，
客户端接收并保存这个 Cookie。之后，客户端向服务器的 CookieDemo2 发送请求，
此时客户端会自动把之前保存的 Cookie（msg=hello）放入请求头中一并发送给服务器。
服务器的 CookieDemo2 接收到请求后，从请求里提取出这些 Cookie，进而可以对 Cookie 中的数据进行处理（比如读取、使用等）。
这样就完成了客户端与服务器之间通过 Cookie 进行数据传递的过程，实现了在不同 Servlet 间利用 Cookie 来保持客户端相关状态信息的交互。*/
//cookie的细节
/*
* 一次可不可以发送多个cookie,是可以的，可以创建多个cookie对象，然后调用response.addCookie(cookie)方法发送给浏览器，浏览器一个一个接手即可
* 浏览器关闭后，cookie就会消失
*cookie在浏览器中保存多长时间?
1.默认情况下，当浏览器关闭后，
Cookie数据被销毁
持久化存储:
setMaxAge(int seconds)
1.正数:将cookie数据写到硬盘的文件中。持久化存储。cookie存活时间。
2.负数:默认值
3.零:删除!
*
* cookie能不能存中文?

在tomcat 8 之前 cookie中不能直接存储中文数据。

在tomcat 8 之后，cookie支持中文数据。


*
*
* */