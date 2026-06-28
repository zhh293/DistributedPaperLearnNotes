package org.example.mcpserver;


import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
/*@Configuration
public class Beab {
    @Bean
    public List<ToolCallback> getToolCallbacks(){
        return List.of(ToolCallbacks.from(CourseService.class));
    }
}*/
/*这个方法的主要目的是把CourseService类里被@Tool注解标注的方法（像getAllCourses和getCourseById）注册为工具回调，这样在特定的场景下就能调用这些工具方法了。
        3. 为什么这两种方式都需要
@Service的必要性：它让CourseService成为一个普通的 Spring bean，这样你就可以在应用的其他组件里使用它，比如在 Controller 层注入并调用它的方法。
@Bean方法的必要性：它把CourseService里的工具方法注册到工具系统中，这样工具系统就能识别并调用这些方法。这在开发一些需要动态调用工具的系统时非常有用，例如集成了 AI 助手的应用。
        4. 简单理解
你可以把@Service注解创建的 bean 看作是应用的 “业务组件”，它主要用于处理业务逻辑。
而@Bean方法创建的ToolCallback列表则是一种 “工具注册机制”，它的作用是把业务组件里的方法暴露给工具系统。

这两种方式相互配合，既保证了业务逻辑的正常处理，又实现了工具方法的动态调用。*/
