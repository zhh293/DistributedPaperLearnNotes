package org.example.springai1.Configuration;

import org.apache.el.parser.SimpleCharStream;
import org.example.springai1.Constant.SystemConstant;
import org.example.springai1.Tools.CourseTool;
import org.example.springai1.Tools.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CoommonConfiguration {
    @Bean
    public ChatMemory chatMemoryRepository() {
        return new InMemoryChatMemory();
    }

    @Bean("ollamaChatModel1")
    public ChatClient chatClient(OllamaChatModel ollamaChatModel,@Qualifier("chatMemoryRepository") ChatMemory InMemoryChatMemory) {
        return ChatClient.builder(ollamaChatModel)
                .defaultSystem("你叫唐明迪")
                .defaultAdvisors(new SimpleLoggerAdvisor(),
                new MessageChatMemoryAdvisor(InMemoryChatMemory))
                .build();
    }

    @Bean("gameChatClient")
    public ChatClient gameChatClient(OpenAiChatModel openAiChatModel, @Qualifier("chatMemoryRepository") ChatMemory InMemoryChatMemory){
       return ChatClient.builder(openAiChatModel)
                .defaultSystem(SystemConstant.prompt)
                .defaultAdvisors(new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(InMemoryChatMemory))
                .build();
    }
    @Bean("serviceChatClient")
    public ChatClient serviceChatClient(OpenAiChatModel openAiChatModel,@Qualifier("chatMemoryRepository") ChatMemory InMemoryChatMemory, CourseTool courseTool, WeatherTool weatherTool){
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(SystemConstant.ServicePrompt)
                .defaultAdvisors(new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(InMemoryChatMemory),new SafeGuardAdvisor(List.of("张鸿昊")))
                .defaultTools(courseTool)
                .build();
    }
    @Bean
    VectorStore vectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel){
        SimpleVectorStore build = SimpleVectorStore.builder(embeddingModel).build();
        List<Document> documents=List.of(
                new Document("产品说明:名称:java开发语言"
                + "内容:java开发语言是一种流行的编程语言"
                + "应用场景:java开发语言可以用于开发各种软件、应用程序、游戏、数据库、网络服务、移动应用等"
                +"特性有"+"1.高并发性"+"2.安全性"+"3.可扩展性"+"4.可维护性"+"5.可移植性"+"6.可测试性"+"7.可复用性"+"8.可扩展性"+"9.可维护性")
        );
        build.add(documents);
        return build;
    }
}
