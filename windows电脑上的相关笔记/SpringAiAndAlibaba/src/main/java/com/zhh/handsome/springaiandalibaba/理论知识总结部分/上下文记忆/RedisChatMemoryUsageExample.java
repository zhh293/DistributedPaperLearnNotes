package com.zhh.handsome.springaiandalibaba.理论知识总结部分.上下文记忆;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis聊天内存使用示例
 */
@Configuration
public class RedisChatMemoryUsageExample {


    
   /* @Bean
    public ChatClient chatClient(@Autowired RedisChatMemory redisChatMemory, OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor(), new MessageChatMemoryAdvisor(redisChatMemory))
                .defaultSystem("你叫唐明迪")
                .defaultUser("用户")
                .build();
    }*/
}