package org.example.springai1;

import org.example.springai1.Tools.WeatherTool;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;


@MapperScan("org.example.springai1.mapper")
@SpringBootApplication
public class SpringAi1Application {

    public static void main(String[] args) {
        SpringApplication.run(SpringAi1Application.class, args);
    }

    //这个适合写MCP服务器，但是如果是本地项目使用ai，那就不合适了，直接在配置类中添加上自己写的工具类，ai就能用了。。。。。。。。。
    @Bean
    public ToolCallbackProvider businessTools(WeatherTool tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools) // 注册包含@Tool注解的bean
                .build();
    }
}
