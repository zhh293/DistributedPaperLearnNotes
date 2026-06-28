package servlet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import java.io.IOException;
@SpringBootApplication
@ServletComponentScan
@WebServlet("/servletOpen")
public class ServletOpen  implements Servlet {

   /* 文字描述总结
    用户通过浏览器访问 http://localhost:8080/day13_tomcat/demo1，
    // Tomcat 根据 web.xml 中的配置找到并加载 cn.itcast.web.servlet.ServletDemo1 类，
    创建其实例并调用 service 方法来处理请求，
    最终在控制台输出 "Hello Servlet"。*/


   /* 步骤 1：服务器接收到客户端的请求后，首先解析请求的 URL 路径，确定要访问的具体 Servlet 资源。
    步骤 2：在 web.xml 配置文件中查找与该 URL 路径匹配的 <url-pattern> 标签内容。
    步骤 3：如果找到匹配的 <url-pattern>，则进一步找到对应的 <servlet-class> 标签，获取 Servlet 的全类名（即完整的类路径）。
    步骤 4：Tomcat 将该 Servlet 类的字节码文件（.class 文件）加载到内存中，并创建该 Servlet 类的实例对象。
    步骤 5：最后，Tomcat 调用该 Servlet 实例对象的相关方法（如 service() 方法）来处理客户端的请求。*/

    /*也就说业务逻辑什么的其实都是在service方法里面完成的，只不过之后springboot通过注解简化了好多步骤*/

    //我这里使用的是springboot，所以这里没有web.xml，而是通过注解的方式来配置servlet，并且启动springboot，
    // 因为他内嵌了Tomcat，我就不想自己去创建Tomcat了，哈哈哈。
public static void main(String[] args){
    SpringApplication.run(ServletOpen.class, args);
}
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

    }

    @Override
    public ServletConfig getServletConfig() {
        return null;
    }

    @Override
    public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
              System
                      .out.println("servletOpen");
    }

    @Override
    public String getServletInfo() {
        return "";
    }

    @Override
    public void destroy() {

    }
    //创建javaee项目
    //定义一个类，继承HttpServlet，重写doGet和doPost方法
    //在web.xml中配置servlet，并启动
   /* @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response){
        try{
            Cookie[] cookies = request.getCookies();
            cookies  = cookies == null ? new Cookie[0] : cookies;
            for(Cookie cookie : cookies){
                if(cookie.getName().equals("username")){
                    response.getWriter().write("Hello " + cookie.getValue());
                    return;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }*/
}
/*
Spring Boot 通过注解简化了这些步骤
Spring Boot 在底层仍然使用了 Servlet 规范，但通过 注解驱动 的方式大大简化了开发流程：
对比项
原始 Servlet 方式
Spring Boot 注解方式
定义 Servlet
实现 Servlet 接口，重写方法
使用 @WebServlet 或直接使用 @RestController / @Controller
映射 URL
在 web.xml 中配置 <servlet-mapping>
使用 @RequestMapping("/xxx") 或 @GetMapping, @PostMapping 等
        生命周期管理
手动控制 init/destroy
Spring 自动管理 Bean 生命周期
部署依赖
需要部署到外部 Tomcat
内嵌 Tomcat，可独立运行 jar 包*/
