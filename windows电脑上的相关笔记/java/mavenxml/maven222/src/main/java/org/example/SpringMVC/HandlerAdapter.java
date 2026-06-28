package org.example.SpringMVC;

public class HandlerAdapter {
  /*  HandlerAdapter 是 Spring MVC 框架中的一个核心组件，它的作用类似于 "翻译官"，负责将 DispatcherServlet 和各种不同类型的处理器 (Handler) 连接起来，使它们能够无缝协作。下面我将详细讲解它的工作原理。
    为什么需要 HandlerAdapter？
    在 Spring MVC 中，处理器可以有多种形式：

    实现 Controller 接口的类
    带 @RequestMapping 注解的方法
    实现 HttpRequestHandler 接口的类
    其他自定义处理器...

    这些处理器的接口和调用方式各不相同，但 DispatcherServlet 希望以统一的方式调用它们。这就需要 HandlerAdapter 来适配不同类型的处理器。
    HandlerAdapter 的核心接口
    HandlerAdapter 是一个接口，定义了三个核心方法：

    java
    public interface HandlerAdapter {
        // 判断该适配器是否支持给定的处理器
        boolean supports(Object handler);

        // 处理请求并返回ModelAndView
        ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception;

        // 获取最后修改时间
        long getLastModified(HttpServletRequest request, Object handler);
    }
    常见的 HandlerAdapter 实现类
    Spring MVC 提供了多种 HandlerAdapter 实现：

    RequestMappingHandlerAdapter
    支持 @RequestMapping 注解的处理器方法
    处理基于注解的控制器 (如 @Controller 注解的类)
    提供参数绑定、数据验证等功能
            SimpleControllerHandlerAdapter
    支持实现 Controller 接口的处理器
    适用于早期的 Spring MVC 应用
    HttpRequestHandlerAdapter
    支持实现 HttpRequestHandler 接口的处理器
    用于直接处理 HTTP 请求的场景
    HandlerAdapter 的工作流程

    当 DispatcherServlet 接收到请求时：

    通过 HandlerMapping 找到处理该请求的 Handler
    遍历所有注册的 HandlerAdapter，找到能支持该 Handler 的适配器
    调用适配器的 handle 方法，传入 request、response 和 Handler
    适配器以适当的方式调用 Handler，并返回 ModelAndView
    DispatcherServlet 继续处理 ModelAndView，完成后续流程*/

    /*总结
    HandlerAdapter 的核心作用是：

    统一处理器的调用方式
            适配不同类型的处理器
    处理参数绑定和返回值转换
    隔离 DispatcherServlet 和具体处理器的实现细节*/



/*1. 处理器的定义
    处理器是一个处理请求的对象，它可以是一个类的实例（如 Controller）或一个方法（如被 @RequestMapping 注解的方法）。DispatcherServlet 通过 HandlerMapping 找到对应的处理器后，会将请求委托给处理器处理。
            2. 处理器的常见类型
2.1 Controller 接口实现类
2.2 @Controller 注解的类
    现代 Spring MVC 中，处理器通常是被 @Controller 注解的类，配合 @RequestMapping 注解的方法：
            2.4 @RestController 注解的类
    专用于 RESTful API，方法默认返回 JSON/XML：
    处理器的工作方式
    请求映射：DispatcherServlet 通过 HandlerMapping 找到与请求 URI 匹配的处理器。
    参数绑定：HandlerAdapter 将请求参数（如 URL 参数、表单数据、JSON 等）绑定到处理器方法的参数上。
    方法调用：HandlerAdapter 调用处理器的方法，传入绑定后的参数。
    响应处理：处理器方法返回结果（如视图名、ModelAndView、JSON 等），由 HandlerAdapter 处理后返回给客户端。*/






/*
    DispatcherServlet 在启动时会调用 initStrategies() 方法初始化各种组件，包括 HandlerAdapter：

    java
    // DispatcherServlet.java
    protected void initStrategies(ApplicationContext context) {
        // ...其他初始化代码...
        initHandlerAdapters(context);
        // ...其他初始化代码...
    }

    private void initHandlerAdapters(ApplicationContext context) {
        this.handlerAdapters = null;

        // 优先使用用户自定义的 HandlerAdapter
        if (this.detectAllHandlerAdapters) {
            // 从容器中查找所有 HandlerAdapter 类型的 bean
            Map<String, HandlerAdapter> matchingBeans = BeanFactoryUtils.beansOfTypeIncludingAncestors(
                    context, HandlerAdapter.class, true, false);
            if (!matchingBeans.isEmpty()) {
                this.handlerAdapters = new ArrayList<>(matchingBeans.values());
                // 排序（通过 Ordered 接口）
                AnnotationAwareOrderComparator.sort(this.handlerAdapters);
            }
        } else {
            try {
                // 使用默认的 HandlerAdapter
                HandlerAdapter ha = context.getBean(HANDLER_ADAPTER_BEAN_NAME, HandlerAdapter.class);
                this.handlerAdapters = Collections.singletonList(ha);
            } catch (NoSuchBeanDefinitionException ex) {
                // 忽略，继续使用默认配置
            }
        }

        // 如果没有找到任何 HandlerAdapter，则注册默认的
        if (this.handlerAdapters == null) {
            this.handlerAdapters = getDefaultStrategies(context, HandlerAdapter.class);
            if (logger.isTraceEnabled()) {
                logger.trace("No HandlerAdapters declared for servlet '" + getServletName() +
                        "': using default strategies from DispatcherServlet.properties");
            }
        }
    }
4. 默认的 HandlerAdapter 配置
    Spring MVC 提供了三个默认的 HandlerAdapter，定义在 DispatcherServlet.properties 文件中：

    properties
    org.springframework.web.servlet.HandlerAdapter= \
    org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter, \
    org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter, \
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter


    这三个适配器分别支持：

    HttpRequestHandlerAdapter：处理实现 HttpRequestHandler 接口的处理器。
    SimpleControllerHandlerAdapter：处理实现 Controller 接口的处理器。
    RequestMappingHandlerAdapter：处理使用 @RequestMapping 注解的方法（现代 Spring MVC 的主要方式）。*/

}
