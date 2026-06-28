package com.zhh.handsome.mcpclient.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class Configuration {
    @Bean
    public ChatClient chatClient(OpenAiChatModel openAiChatModel, SyncMcpToolCallbackProvider syncMcpToolCallbackProvider){
        return ChatClient.builder(openAiChatModel)
                .defaultToolCallbacks(syncMcpToolCallbackProvider.getToolCallbacks())
                .build();
    }
}
