package org.example.springai1.最新的聊天API;

import org.example.springai1.Tools.CourseTool;
import org.example.springai1.Tools.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/**
 * Redis聊天内存使用示例
 */
@Configuration
public class RedisChatMemoryUsageExample {

    @Autowired
    private CourseTool courseTool;
    @Autowired
    private WeatherTool weatherTool;
    
    @Bean
    public ChatClient chatClient(@Autowired RedisChatMemory redisChatMemory, OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor(), new MessageChatMemoryAdvisor(redisChatMemory))
                .defaultSystem("你叫唐明迪")
                .defaultTools(courseTool, weatherTool)
                .defaultUser("用户")
                .build();
    }
}