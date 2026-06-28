package org.example.springbootwebquick;

public class bean的加载控制 {
}
/*
（二）加载顺序控制
依赖驱动（自动处理）
Spring 容器会先加载被依赖的 Bean（通过 @Autowired、构造器注入等）。例如：
java
@Service
public class UserService {
    @Autowired // 先加载UserRepository，再实例化UserService
    private UserRepository repo;
}*/
/*
显式控制：@DependsOn
强制指定 Bean 依赖，确保被依赖的 Bean 先加载。
示例：
java
@Bean
@DependsOn("redisTemplate") // 确保redisTemplate先加载
public CacheManager cacheManager() { ... }


顺序注解与接口（集合注入顺序）
@Order/@Priority 与 Ordered/PriorityOrdered：
控制同一接口下 Bean 的注入顺序（值越小优先级越高，@Priority 高于 @Order）。
示例：
java
@Component @Order(1) // 顺序1
public class BeanA implements MyInterface { ... }

@Component @Order(2) // 顺序2
public class BeanB implements MyInterface { ... }

@Service
public class MyService {
    @Autowired
    private List<MyInterface> beans; // BeanA先于BeanB
}

@ConditionalOnBean
作用：容器中存在指定 Bean 时，才创建当前 Bean。
示例：依赖数据源（DataSource）时创建事务管理器：
java
@Bean
@ConditionalOnBean(DataSource.class)
public PlatformTransactionManager transactionManager() { ... }

场景：依赖其他 Bean 的组件（如事务管理器、JdbcTemplate）。
@ConditionalOnMissingBean
作用：容器中不存在指定 Bean 时，创建当前 Bean（提供默认实现，允许用户自定义覆盖）。
示例：无自定义RestTemplate时，创建默认实例：
java
@Bean
@ConditionalOnMissingBean(RestTemplate.class)
public RestTemplate restTemplate() { ... }


场景：Spring Boot 自动配置（如DispatcherServlet、JacksonObjectMapper的默认配置）。*/

/*三、基于配置属性的注解
@ConditionalOnProperty
作用：配置文件中属性满足条件（存在、值匹配）时，创建当前 Bean。
示例：开启数据源配置（spring.datasource.enabled=true）：
java
@Bean
@ConditionalOnProperty(name = "spring.datasource.enabled", havingValue = "true")
public DataSource dataSource() { ... }


属性：name（属性名）、havingValue（期望值）、matchIfMissing（属性不存在时是否生效，默认false）。*/



/*SpringBoot 与传统 Spring/SpringMVC 的关系
你提出了一个很好的问题。在 SpringBoot 中，确实不需要显式地配置 @ComponentScan、Spring 管理业务层或 SpringMVC 管理控制层，这正是 SpringBoot 的 "约定优于配置" 理念的体现。
SpringBoot 的自动配置机制
SpringBoot 通过自动配置 (AutoConfiguration) 机制简化了传统 Spring/SpringMVC 的配置过程：

自动组件扫描：
SpringBoot 应用启动时，默认会扫描主应用类所在包及其子包下的所有组件，无需显式配置 @ComponentScan。
自动配置 SpringMVC：
SpringBoot 通过 WebMvcAutoConfiguration 类自动配置 SpringMVC，包括视图解析器、静态资源处理器等。
依赖注入简化：
只需在需要注入的地方使用 @Autowired、@Resource 或构造函数注入即可。*/



/*MVC的工作流程:

用户通过视图层发送请求到服务器，在服务器中请求被Controller接收，Controller调用相应的Model层处理请

求，处理完毕将结果返回到Controller，Controller再根据请求处理的结果找到相应的View视图，渲染数据后最终

        响应给浏览器*/
/*说白了，spring框架实现了ioc容器，aop，事务等能够简化业务开发的思想和工具
* 但是只有spring是无法实现网络层面的交互的，因为你用spring写出的逻辑终究只是在自己电脑上，数据也只能自己输入，输出也只能传给自己显示
* 所以说springmvc打通了服务器（java代码构建的）和客户端之间的联系和通道，并借助spring这个强大的处理业务的工具，很好地实现了后端开发
* spring框架是工具，是非常重要的思想，是java后端开发极其重要的东西。springmvc负责网络之间的请求处理，以及规定了业务处理的逻辑和结构
* 提供了开发的规范，为开发提供了一整套完备的解决方案。
* 打通了主机，面向了互连网。。。。。牛逼*/

/*springMVC最突出的作用就是实现了web功能，可以与前端进行无阻碍地交互？
你的理解基本正确，但可以更深入地解释 Spring MVC 的核心作用。Spring MVC 确实是为 Web 开发设计的框架，但它的 “无阻碍交互” 能力不仅仅是与前端通信，而是通过分层架构和标准化模式解决了 Web 开发中的一系列复杂问题。
一、Spring MVC 的核心价值：分层与解耦
Spring MVC 的最突出作用是将 Web 应用程序按职责划分为清晰的层次，主要包括：

控制器层（Controller）
接收前端请求（如 HTTP GET/POST），调用服务层处理业务逻辑，返回视图或 JSON 数据。
通过 @Controller、@RestController、@RequestMapping 等注解简化请求映射。
模型层（Model）
封装业务数据和状态，通常是 POJO（Plain Old Java Object）或 DTO（Data Transfer Object）。
视图层（View）
负责数据展示，支持 JSP、Thymeleaf、Freemarker 等模板引擎，也可直接返回 JSON/XML 数据（RESTful 风格）。

这种分层架构使开发者能够专注于单一职责，例如：前端工程师维护视图模板，后端工程师专注于控制器和业务逻辑。
RESTful API 支持
@RestController + @ResponseBody 注解简化 JSON 响应，无需额外配置。
示例：
java
@RestController
@RequestMapping("/api/users")
public class UserController {
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.findById(id);
    }
}


参数绑定与校验
自动将 HTTP 参数绑定到 Java 对象（如表单数据、URL 参数），支持 @RequestParam、@RequestBody 等注解。
集成 Bean Validation（如 @Valid + BindingResult）实现请求参数校验。
视图解析与模板引擎
支持多种视图技术（如 Thymeleaf、JSP），通过 ViewResolver 自动映射逻辑视图名到物理视图。
异步请求处理
通过 @Async、CompletableFuture 或 DeferredResult 支持异步响应，提升并发性能。
三、与前端框架的无缝集成
Spring MVC 可以与主流前端技术栈（如 Vue、React、Angular）配合：

前后端分离模式：Spring MVC 作为后端 API 服务，返回 JSON 数据，前端框架负责视图渲染。
单体应用模式：Spring MVC 结合 Thymeleaf 等模板引擎，实现服务器端渲染（SSR）*/



//那么springmvc是如何实现web开发的呢，基于什么技术做到的呢，其实有很多，这里只说servlet
/*Spring MVC 框架底层是基于 Servlet 技术实现的。Spring MVC 本质上是对 Servlet API 的高级封装，它并没有脱离 Java EE/Servlet 规范，而是在其基础上提供了更简洁、更灵活的 Web 开发方式。
Spring MVC 与 Servlet 的关系
Spring MVC 的核心是 DispatcherServlet，它是一个标准的 Servlet（继承自 HttpServlet），负责处理所有 HTTP 请求并协调 MVC 组件之间的交互。当客户端发送请求时，请求首先到达 DispatcherServlet，然后由它根据配置将请求分发给相应的控制器（Controller）处理。
主要实现机制
核心 Servlet：DispatcherServlet 作为前端控制器，接收所有请求并进行统一处理。
Servlet 配置：在传统的 Spring MVC 应用中，需要在 web.xml 中配置 DispatcherServlet：

xml
        <servlet>
    <servlet-name>dispatcher</servlet-name>
    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
    <init-param>
        <param-name>contextConfigLocation</param-name>
        <param-value>/WEB-INF/dispatcher-servlet.xml</param-value>
    </init-param>
    <load-on-startup>1</load-on-startup>
</servlet>

<servlet-mapping>
    <servlet-name>dispatcher</servlet-name>
    <url-pattern>/</url-pattern>
</servlet-mapping>



Servlet 生命周期：DispatcherServlet 的初始化和销毁过程遵循 Servlet 规范，通过 init() 和 destroy() 方法完成。
请求处理：DispatcherServlet 内部使用 HandlerMapping、HandlerAdapter 等组件处理请求，但最终仍依赖 Servlet API 与客户端交互。
现代配置方式
在现代 Spring Boot 应用中，虽然不再需要手动编写 web.xml，但 DispatcherServlet 仍然是核心组件，它由 Spring Boot 自动配置并注册到嵌入式 Servlet 容器（如 Tomcat、Jetty）中。
总结
Spring MVC 框架并没有绕过 Servlet 技术，而是在 Servlet 基础上构建了更强大的 Web 开发模型。通过使用 Spring MVC，开发者可以更专注于业务逻辑，而不必直接处理 Servlet API 的复杂性。*/
