package com.zhh.handsome.springaiandalibaba.实践.聊天客户端API;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.zhh.handsome.springaiandalibaba.实践.智能体实践.NeedTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(OpenAiChatModel openAiChatModel,
            Optional<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider) {
        ChatClient.Builder builder = ChatClient.builder(openAiChatModel)
                .defaultSystem("You are a helpful assistant.");
        syncMcpToolCallbackProvider.ifPresent(provider -> builder.defaultTools(provider.getToolCallbacks()));
        return builder.build();
    }

    @Bean
    public ChatClient chatClient2(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("You are a helpful assistant.")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultOptions(new OpenAiChatOptions())
                .build();
    }

   /* @Bean
    protected VectorStore vectorStore(OpenAiEmbeddingModel openAiEmbeddingModel) {
        return RedisVectorStore.builder()
                .embeddingModel(openAiEmbeddingModel)
                .build();
    }*/
    @Bean
    public VectorStore vectorStore(OpenAiEmbeddingModel openAiEmbeddingModel){
        return SimpleVectorStore.builder(openAiEmbeddingModel)
                .build();
    }

    @Bean
    public ChatClient chatClient3(OpenAiChatModel openAiChatModel, VectorStore vectorStore) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("You are a helpful assistant.")
                .defaultAdvisors(new SimpleLoggerAdvisor(), new MessageChatMemoryAdvisor(new InMemoryChatMemory()),
                        new QuestionAnswerAdvisor(vectorStore))
                .defaultOptions(new OpenAiChatOptions())
                .build();
    }

    @Bean
    public ReactAgent reactAgent(OpenAiChatModel openAiChatModel, NeedTools needTools) {
        ToolCallback[] from = ToolCallbacks.from(needTools);
        List<ToolCallback> toolCallbacks = List.of(from);
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setTemperature(0.7);
        options.setMaxTokens(1024);
        return ReactAgent.builder()
                .model(openAiChatModel)
                .systemPrompt("You are a helpful assistant.")
                .name("assistant")
                .chatOptions(options)
                .tools(toolCallbacks)
                .saver(new MemorySaver())
                .build();
    }



}
