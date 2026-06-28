package org.example.拦截器;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class ProjectInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
    }

}
//"*"就表示了所有的文件，但是“*”并不包括子目录下的文件；  "**"匹配包含任意级子目录中所有的文件\
/*
HttpServletRequest request：
参数含义：代表客户端发送的 HTTP 请求对象，包含了请求的所有信息，像请求 URL、请求头（Headers）、请求参数、Cookie 等内容 。
作用：在 preHandle 里，可通过它获取请求相关数据，比如判断请求的来源、获取用户提交的参数做校验、根据请求头信息进行权限初步筛选等 。例如，从 request.getHeader("token") 获取令牌，用于验证用户身份 。
HttpServletResponse response：
参数含义：代表要返回给客户端的 HTTP 响应对象，可用来设置响应的状态码、响应头、向客户端写数据等 。
作用：若在 preHandle 中判定请求不合法（如权限不足），可通过 response 设置 403 等状态码，或者直接写入错误提示信息返回给客户端，提前终止不合理请求的后续处理 。比如 response.sendError(HttpServletResponse.SC_FORBIDDEN, "权限不足") 。
Object handler：
参数含义：表示处理请求的处理器对象，在 Spring MVC 中，可能是 Controller 里对应的处理方法相关的 HandlerMethod 等类型（也可能是其他类型处理器，依具体框架和配置而定 ），它包含了处理器的详细信息，像所在类、方法、参数等元数据 。
作用：借助 handler 能获取处理器的具体信息，用于做更细致的拦截判断。比如，根据 handler 判断当前请求要访问的是哪个 Controller 的哪个方法，进而针对特定方法做拦截逻辑调整，像某些敏感方法需要额外的权限校验等 。
可以通过 instanceof 等判断其具体类型，再反射获取方法、类的注解等信息辅助处理 */




/*
1. 注册顺序决定基础执行流
拦截器的执行顺序默认与 “注册顺序” 一致（如 Spring MVC 中通过 addInterceptor 注册的先后），可理解为：先注册的拦截器，在流程中更早参与 “前置处理”，更晚参与 “后置处理” 。
        2. preHandle 方法：顺序执行
规则：按拦截器注册顺序依次执行（先注册的先执行 preHandle）。
关键影响：若某个拦截器的 preHandle 返回 false，则后续拦截器的 preHandle 不再执行，且当前及之后拦截器的 postHandle、afterCompletion 也不会触发（除非已执行过 preHandle 且返回 true 的拦截器，需执行 afterCompletion 清理 ）。
        3. postHandle 方法：逆序执行
规则：按拦截器注册顺序的 “逆序” 执行（先注册的后执行 postHandle）。
前提条件：所有拦截器的 preHandle 都返回 true 才会执行；若有任意 preHandle 返回 false，则所有拦截器的 postHandle 都不执行。
        4. afterCompletion 方法：逆序执行（仅 preHandle 成功时触发）
规则：按拦截器注册顺序的 “逆序” 执行（先注册的后执行 afterCompletion）。
触发条件：当前拦截器的 preHandle 返回 true（无论后续拦截器是否阻塞，只要自己 preHandle 成功，就需在请求结束时执行 afterCompletion 做资源清理 ）。*/
