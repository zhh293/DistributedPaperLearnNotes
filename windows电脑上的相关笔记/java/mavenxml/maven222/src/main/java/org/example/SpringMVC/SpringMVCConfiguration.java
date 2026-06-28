package org.example.SpringMVC;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("org.example.SpringMVC")
public class SpringMVCConfiguration {
/*    1. DispatcherServlet
    这是 Spring MVC 的核心前端控制器，负责接收所有 HTTP 请求并将它们分发给相应的处理器进行处理。它实现了 Servlet 接口，是整个请求处理流程的入口点。

    工作流程：

    接收客户端请求
    根据请求信息找到合适的处理器（Handler）
    将请求转发给处理器执行
    接收处理器返回的结果（ModelAndView）
    根据结果选择合适的视图解析器进行渲染
2. HandlerMapping
    负责将请求映射到对应的处理器（Handler）。Spring MVC 提供了多种实现，如 BeanNameUrlHandlerMapping、SimpleUrlHandlerMapping 和 RequestMappingHandlerMapping（支持 @RequestMapping 注解）。

    核心接口：

    java
    public interface HandlerMapping {
        HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception;
    }
3. HandlerAdapter
    由于处理器（Handler）的类型可能各不相同，HandlerAdapter 负责将 DispatcherServlet 接收到的请求适配到具体的处理器实现。

    常用实现：

    HttpRequestHandlerAdapter：处理 HttpRequestHandler 类型的处理器
    SimpleControllerHandlerAdapter：处理 Controller 接口实现类
    RequestMappingHandlerAdapter：处理使用 @RequestMapping 注解的控制器方法


4. HandlerExceptionResolver
    负责处理处理器执行过程中抛出的异常。它允许应用程序定义统一的异常处理机制。

    常用实现：

    SimpleMappingExceptionResolver：将异常映射到视图
    DefaultHandlerExceptionResolver：处理 Spring MVC 框架内部的异常
    ExceptionHandlerExceptionResolver：处理使用 @ExceptionHandler 注解的异常处理方法*/


   /* 5. 协作流程示例
    假设客户端请求 /user/123：

    HandlerMapping 工作：
    RequestMappingHandlerMapping 匹配到 @GetMapping("/user/{id}") 的方法。
    返回包含该方法所在 Controller 的 HandlerExecutionChain。

    HandlerAdapter 工作：
    RequestMappingHandlerAdapter 解析请求参数（如 id=123）。
    通过反射调用 Controller 中的方法，获取返回值（如 User 对象）。



    总结
    HandlerMapping：负责 “找对人”（找到处理请求的组件）。
    HandlerAdapter：负责 “用对方式”（以正确的方式调用该组件）。*/


    /*虽然不需要手动实现核心接口，但在以下场景可能需要自定义配置：
            （1）自定义 HandlerMapping
    场景：需要实现特殊的 URL 匹配规则（如动态路由）。
    做法：继承 RequestMappingHandlerMapping 并重写方法，或实现 HandlerMapping 接口。
    示例：自定义路由前缀或版本控制。
            （2）自定义 HandlerAdapter
    场景：需要支持非标准的处理器类型（如自定义注解）。
    做法：实现 HandlerAdapter 接口，或继承 RequestMappingHandlerAdapter。
    示例：处理特定格式的请求参数或响应结果。
            （3）添加拦截器
    场景：需要在请求处理前后添加公共逻辑（如日志、权限校验）。
    做法：实现 HandlerInterceptor 接口，并注册到 WebMvcConfigurer。
    示例：登录状态检查、请求耗时统计。
    @Configuration
    public class WebConfig implements WebMvcConfigurer {
    // 注册拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login");
    }

    // 配置静态资源
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }
    通过springboot来进行静态资源的配置，而不是通过前端配置。就是说只有springboot启动起来静态资源才能被访问。前端是无法作用到静态资源的。
Spring Boot 默认静态资源目录
Spring Boot 默认会从以下位置加载静态资源：
classpath:/static/
classpath:/public/
classpath:/resources/
classpath:/META-INF/resources/
但 Swagger UI 的 HTML 文件和 WebJars 资源需要特定的映射路径才能被正确访问。
Swagger UI 集成
Swagger UI 的 JAR 包中，doc.html 存放在 META-INF/resources/ 目录下，需要通过自定义映射才能访问。




    // 自定义消息转换器
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new MappingJackson2HttpMessageConverter());
    }
}


    */




   /* 详细步骤说明
    DispatcherServlet 接收请求：
    所有请求首先到达 DispatcherServlet，它是 Spring MVC 的核心前端控制器
    HandlerMapping 定位处理方法：
    HandlerMapping 组件根据请求 URL、HTTP 方法等条件，查找匹配的 Controller 方法
    例如，一个 URL 可能映射到 @RestController 中的 @PostMapping 方法
    消息转换器处理请求体：
    HttpMessageConverter 负责将 HTTP 请求体转换为 Java 对象
    例如：JSON 请求体 → Java 对象
    常见的转换器包括 MappingJackson2HttpMessageConverter (处理 JSON) 和 Jaxb2RootElementHttpMessageConverter (处理 XML)
    HandlerAdapter 调用处理方法：
    HandlerAdapter 负责调用 Controller 中的具体方法
    它会将转换后的 Java 对象绑定到方法参数上
    例如：@RequestBody User user参数会接收转换后的 User 对象
    返回结果处理：
    处理方法返回的结果 (如 @ResponseBody 注解的方法返回的对象)
    会被 HttpMessageConverter 转换回 HTTP 响应体 (如 JSON/XML)
    你描述的过程非常准确。Spring MVC 框架确实通过消息转换器将请求体转换为 Java 对象，
    然后通过 HandlerMapping 定位处理方法，
    最后由 HandlerAdapter 将 Java 对象传入方法参数中。
    这种设计使得开发者可以专注于业务逻辑，
    而不必关心 HTTP 请求和响应的底层处理细节。





*/






}
