<%--
  Created by IntelliJ IDEA.
  User: 92819
  Date: 2025/5/22
  Time: 13:51
  To change this template use File | Settings | File Templates.
--%>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
  <head>
    <title>我是张鸿昊</title>
  </head>
  <body>
  <h1>Hello from the client!</h1>
  <form action="/submit" metho
        d="post">
    <label for="message">Enter a message:</label>
    <input type="text" id="message" name="message">
    <button type="submit">Submit</button>
  </form>
  <a href="/cookieDemo">post请求cookiedemo</a>
  </body>
</html>
<%--jsp：入门学习
可以把它理解为一个特殊的页面，其中即可以直接定义html标签，也可以定义java代码




--%>