package org.example.mcpserver;




import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }


    @Bean
    public ToolCallbackProvider getToolCallbackProvider(WeatherTool weatherService) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }
   /*@Bean
    public ChatClient ChatClient(OpenAiChatModel openAiChatModel, WeatherTool weatherService) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("你是一个天气预报助手，可以帮助用户查询未来几天的天气情况。")
                .defaultTools(weatherService)
                .build();
   }*/
}
