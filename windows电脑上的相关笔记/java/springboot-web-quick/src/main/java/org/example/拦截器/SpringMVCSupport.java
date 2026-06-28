package org.example.拦截器;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
@Configuration
public class SpringMVCSupport extends WebMvcConfigurationSupport {
    @Autowired
    private ProjectInterceptor projectInterceptor;


    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectInterceptor).
                addPathPatterns("/**")
                .excludePathPatterns("/");
    }

    @Override
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
//"*"就表示了所有的文件，但是“*”并不包括子目录下的文件；  "**"匹配包含任意级子目录中所有的文件\
/*
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 获取当前会话
        HttpSession session = request.getSession(false);

        // 检查用户是否已登录
        if (session == null || session.getAttribute("user") == null) {
            // 用户未登录，重定向到登录页面
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }

        // 用户已登录，允许请求继续
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) throws Exception {
        // 请求处理后执行，可修改ModelAndView
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) throws Exception {
        // 请求完成后执行，可进行资源清理
    }
}
*/

/*
：使用标准接口 WebMvcConfigurer 简化开发 (注意：侵入式较强)

java
@Configuration
@ComponentScan("com.itheima.controller")
@EnableWebMvc
public class SpringMvcConfig implements WebMvcConfigurer{

    @Autowired
    private ProjectInterceptor projectInterceptor;

    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectInterceptor).addPathPatterns("/books", "/books/**");
    }
}*/
