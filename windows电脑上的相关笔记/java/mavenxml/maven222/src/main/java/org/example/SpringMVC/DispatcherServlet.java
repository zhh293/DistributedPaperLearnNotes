package org.example.SpringMVC;

public class DispatcherServlet {

}
/*
DispatcherServlet 源码运行原理详解
DispatcherServlet 是 Spring MVC 框架的核心前端控制器，负责接收所有 HTTP 请求并协调整个请求处理流程。下面我将用通俗易懂的方式讲解它的运行原理。
核心架构设计
DispatcherServlet 的设计采用了 "责任链模式" 和 "策略模式"：

责任链：请求依次经过多个处理环节
策略：每个环节都有多种策略实现，通过配置选择
        初始化过程
当 Web 应用启动时，DispatcherServlet 会进行初始化：

继承自 HttpServlet，重写 init () 方法
加载配置文件，获取应用上下文
初始化各种组件：
HandlerMapping：将 URL 映射到处理器
HandlerAdapter：调用处理器的适配器
ViewResolver：视图解析器
其他组件...

java
// DispatcherServlet初始化核心方法简化版
protected void initStrategies(ApplicationContext context) {
    // 初始化各种策略组件
    initMultipartResolver(context);      // 文件上传解析器
    initLocaleResolver(context);         // 本地化解析器
    initThemeResolver(context);          // 主题解析器
    initHandlerMappings(context);        // 处理器映射器
    initHandlerAdapters(context);        // 处理器适配器
    initHandlerExceptionResolvers(context); // 异常解析器
    initRequestToViewNameTranslator(context); // 视图名转换器
    initViewResolvers(context);          // 视图解析器
    initFlashMapManager(context);        // FlashMap管理器
}
请求处理流程
当客户端发送 HTTP 请求时，DispatcherServlet 的处理流程如下：

接收请求：所有请求都由 DispatcherServlet 接收
映射处理器：通过 HandlerMapping 找到处理该请求的 Controller
适配调用：通过 HandlerAdapter 调用 Controller 中的方法
处理请求：Controller 处理请求，返回 ModelAndView 对象
视图解析：ViewResolver 根据视图名找到具体的 View
视图渲染：View 将 Model 中的数据渲染到页面
响应返回：将渲染后的结果返回给客户端

        java
// doDispatch方法简化版，核心请求处理流程
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;

    try {
        ModelAndView mv = null;

        // 1. 找到处理请求的处理器链（包含拦截器和处理器）
        mappedHandler = getHandler(processedRequest);
        if (mappedHandler == null) {
            noHandlerFound(processedRequest, response);
            return;
        }

        // 2. 获取处理器适配器
        HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

        // 3. 执行前置拦截器
        if (!mappedHandler.applyPreHandle(processedRequest, response)) {
            return;
        }

        // 4. 调用处理器方法，返回ModelAndView
        mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

        // 5. 处理默认视图名
        applyDefaultViewName(processedRequest, mv);

        // 6. 执行后置拦截器
        mappedHandler.applyPostHandle(processedRequest, response, mv);

        // 7. 渲染视图
        processDispatchResult(processedRequest, response, mappedHandler, mv, null);
    }
    catch (Exception ex) {
        // 异常处理
        processDispatchResult(processedRequest, response, mappedHandler, null, ex);
    }
    finally {
        // 清理资源
        if (mappedHandler != null) {
            mappedHandler.triggerAfterCompletion(processedRequest, response, null);
        }
    }
}
关键组件详解
1. HandlerMapping（处理器映射器）
作用：将 URL 映射到处理器（Controller）
实现类：
BeanNameUrlHandlerMapping：按 Bean 名称映射
RequestMappingHandlerMapping：按 @RequestMapping 注解映射
SimpleUrlHandlerMapping：简单 URL 映射
2. HandlerAdapter（处理器适配器）
作用：统一调用不同类型的处理器
实现类：
RequestMappingHandlerAdapter：处理 @RequestMapping 注解的方法
SimpleControllerHandlerAdapter：处理实现 Controller 接口的类
HttpRequestHandlerAdapter：处理实现 HttpRequestHandler 接口的类
3. ViewResolver（视图解析器）
作用：将视图名解析为具体的 View 对象
实现类：
InternalResourceViewResolver：解析 JSP 视图
ThymeleafViewResolver：解析 Thymeleaf 视图
FreeMarkerViewResolver：解析 FreeMarker 视图
4. HandlerInterceptor（处理器拦截器）
作用：在请求处理前后执行额外逻辑
三个关键方法：
preHandle：处理器执行前调用
postHandle：处理器执行后、视图渲染前调用
afterCompletion：请求完成后调用
        工作流程总结
初始化阶段：
加载 Spring 配置，初始化各种组件
注册 URL 到处理器的映射关系
请求处理阶段：
接收请求
        找到对应的处理器和拦截器链
执行前置拦截器
        通过适配器调用处理器方法
执行后置拦截器
        解析视图并渲染
返回响应
        执行完成后拦截器
设计优势：
高度解耦：各组件职责清晰
扩展性强：可自定义各种策略
统一流程：标准化的请求处理流程
与 Struts2 对比*/
