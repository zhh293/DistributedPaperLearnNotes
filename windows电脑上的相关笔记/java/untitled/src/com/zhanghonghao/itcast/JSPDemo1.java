package com.zhanghonghao.itcast;

public class JSPDemo1 {
    /*JSP 的概念和作用
    JSP（JavaServer Pages）是一种基于 Java 的服务器端技术，它允许在 HTML 页面中嵌入 Java 代码。JSP 本质上是 Servlet 的一种简化形式，服务器会将 JSP 文件编译成 Servlet 类，然后执行这些类来生成动态网页内容。

    JSP 的主要作用包括：

    动态网页生成：可以在 HTML 页面中嵌入 Java 代码，根据用户请求和服务器状态动态生成内容
    分离关注点：将表示逻辑（HTML）与业务逻辑（Java）分离
    简化开发：对于主要负责前端的开发者，使用 JSP 比直接编写 Servlet 更容易上手
    支持 MVC 架构：可以作为 MVC 模式中的视图层*/
}
/*
Servlet 和 JSP 处理 JSON 数据的能力
是的，仅使用 Servlet 和 JSP 完全可以处理前端传来的 JSON 数据，并将处理结果封装到对象中返回给前端。虽然没有 Spring Boot 那样的便捷框架，但通过以下方式可以实现类似功能：
处理流程
使用 Servlet 接收 HTTP 请求
从请求体中读取 JSON 数据
使用 JSON 处理库（如 Jackson、Gson）将 JSON 解析为 Java 对象
        执行业务逻辑处理
将结果对象转换回 JSON 格式
设置响应头为application/json
通过响应输出流返回 JSON 数据*/

/*
@WebServlet("/users")
public class UserController extends HttpServlet {
    private List<User> users = new ArrayList<>();
    private Gson gson = new Gson();

    @Override
    public void init() throws ServletException {
        // 初始化一些测试数据
        users.add(new User(1L, "user1", "user1@example.com"));
        users.add(new User(2L, "user2", "user2@example.com"));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 设置响应内容类型
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // 将用户列表转换为JSON并返回
        PrintWriter out = resp.getWriter();
        out.print(gson.toJson(users));
        out.flush();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 设置请求和响应内容类型
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // 读取请求中的JSON数据
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = req.getReader();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }

        // 解析JSON数据并创建User对象
        JsonObject jsonObject = gson.fromJson(sb.toString(), JsonObject.class);
        User newUser = new User();
        newUser.setUsername(jsonObject.get("username").getAsString());
        newUser.setEmail(jsonObject.get("email").getAsString());
        newUser.setId((long) (users.size() + 1));

        // 添加到用户列表
        users.add(newUser);

        // 返回成功消息和新用户对象
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "User created successfully");
        response.add("user", gson.toJsonTree(newUser));

        PrintWriter out = resp.getWriter();
        out.print(response.toString());
        out.flush();
    }
}*/

/*<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd"
version="4.0">

    <!-- 配置Gson过滤器，设置字符编码 -->
    <filter>
        <filter-name>encodingFilter</filter-name>
        <filter-class>org.apache.catalina.filters.SetCharacterEncodingFilter</filter-class>
        <init-param>
            <param-name>encoding</param-name>
            <param-value>UTF-8</param-value>
        </init-param>
    </filter>
    <filter-mapping>
        <filter-name>encodingFilter</filter-name>
        <url-pattern>*//*</url-pattern>
    </filter-mapping>

    <!-- 配置Servlet映射（如果使用注解则不需要） -->
    <!--
    <servlet>
        <servlet-name>UserController</servlet-name>
        <servlet-class>com.example.servlet.UserController</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>UserController</servlet-name>
        <url-pattern>/users</url-pattern>
    </servlet-mapping>
    -->

    <!-- 设置欢迎页面 -->
    <welcome-file-list>
        <welcome-file>index.jsp</welcome-file>
    </welcome-file-list>
</web-app>*/

//这就看出来springboot生态的完善性了吧，一个json处理器就比这些少了十分之一


/*<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>用户管理</title>
    <style>
        .container { width: 80%; margin: 0 auto; padding: 20px; }
        .form-group { margin-bottom: 15px; }
label { display: inline-block; width: 80px; }
input { padding: 6px; width: 300px; }
button { padding: 8px 16px; background: #4CAF50; color: white; border: none; cursor: pointer; }
button:hover { background: #45a049; }
        .user-table { width: 100%; border-collapse: collapse; margin-top: 30px; }
        .user-table th, .user-table td { border: 1px solid #ddd; padding: 12px; text-align: left; }
        .user-table th { background: #f2f2f2; }
        .message { margin: 10px 0; padding: 10px; border-radius: 4px; }
        .success { background: #dff0d8; color: #3c763d; }
        .error { background: #f2dede; color: #a94442; }
    </style>
</head>
<body>
<div class="container">
    <h2>用户管理系统</h2>

    <!-- 添加用户表单 -->
    <div>
        <h3>添加新用户</h3>
        <div class="form-group">
            <label for="username">用户名：</label>
            <input type="text" id="username" placeholder="请输入用户名">
        </div>
        <div class="form-group">
            <label for="email">邮箱：</label>
            <input type="email" id="email" placeholder="请输入邮箱">
        </div>
        <button onclick="addUser()">添加用户</button>
        <div id="message" class="message" style="display: none;"></div>
    </div>

    <!-- 用户列表展示 -->
    <div style="margin-top: 30px;">
        <h3>用户列表</h3>
        <table class="user-table">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>用户名</th>
                    <th>邮箱</th>
                </tr>
            </thead>
            <tbody id="userList">
                <!-- 这里将通过AJAX动态填充数据 -->
            </tbody>
        </table>
    </div>
</div>

<script>
// 页面加载时自动加载用户列表
window.onload = loadUserList;

// 加载用户列表（调用后端GET /users）
function loadUserList() {
    fetch("/users") // 后端接口地址
            .then(response => {
    if (!response.ok) {
        throw new Error("获取用户列表失败");
    }
    return response.json(); // 解析JSON响应
            })
            .then(users => {
                const userListElement = document.getElementById("userList");
    userListElement.innerHTML = ""; // 清空现有列表

    // 动态生成表格行
    users.forEach(user => {
                    const row = document.createElement("tr");
    row.innerHTML = `
                        <td>${user.id}</td>
            <td>${user.username}</td>
            <td>${user.email}</td>
                    `;
    userListElement.appendChild(row);
                });
            })
            .catch(error => {
            showMessage("错误：" + error.message, "error");
            });
}

// 添加用户（调用后端POST /users）
function addUser() {
        const username = document.getElementById("username").value.trim();
        const email = document.getElementById("email").value.trim();

    // 简单验证
    if (!username || !email) {
        showMessage("用户名和邮箱不能为空", "error");
        return;
    }

    // 构造请求数据
        const userData = {
            username: username,
            email: email
        };

    fetch("/users", {
            method: "POST",
            headers: {
        "Content-Type": "application/json" // 声明发送JSON数据
    },
    body: JSON.stringify(userData) // 转换为JSON字符串
        })
        .then(response => {
    if (!response.ok) {
        throw new Error("添加用户失败");
    }
    return response.json();
        })
        .then(result => {
    if (result.success) {
        showMessage("添加成功：" + result.message, "success");
        // 清空表单
        document.getElementById("username").value = "";
        document.getElementById("email").value = "";
        // 重新加载用户列表
        loadUserList();
    } else {
        showMessage("添加失败：" + result.message, "error");
    }
        })
        .catch(error => {
            showMessage("错误：" + error.message, "error");
        });
}

// 显示提示消息
function showMessage(text, type) {
        const messageElement = document.getElementById("message");
    messageElement.textContent = text;
    messageElement.className = "message " + type; // 设置样式（成功/错误）
    messageElement.style.display = "block";

    // 3秒后自动隐藏消息
    setTimeout(() => {
            messageElement.style.display = "none";
        }, 3000);
}
</script>
</body>
</html>*/
