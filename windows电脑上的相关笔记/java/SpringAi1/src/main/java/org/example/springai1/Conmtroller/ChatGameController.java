package org.example.springai1.Conmtroller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@Slf4j

public class ChatGameController {
    @Qualifier("gameChatClient")
    @Autowired
    private  ChatClient chatClient;

//    private final ChatClient chatClient;
    @GetMapping(value = "/game",produces = "text/html;charset=UTF-8")
    public Flux<String> game(@RequestParam String prompt, String chatId){
        return chatClient.prompt()
                .user(prompt)
                .advisors(a->{
                    a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId);
                })
                .stream()
                .content();
    }
}



/*

这个警告的核心原因是：Lombok 生成构造函数时，默认不会把字段上的 @Qualifier 注解复制到构造函数的参数上，导致 Spring 无法通过 @Qualifier 识别具体要注入的 Bean。以下是详细分析和解决方案：
一、问题本质
场景：
你在字段上用了 @Qualifier("ollamaChatModel1") 和 @Autowired，同时用 @RequiredArgsConstructor 让 Lombok 生成构造函数。
矛盾：
Lombok 生成的构造函数 参数上没有 @Qualifier 注解，但 Spring 依赖注入时，需要通过构造函数参数的 @Qualifier 来匹配同名 Bean（如 @Bean("ollamaChatModel1")）。
二、解决方案（两种核心思路）
方案 1：让 Lombok 把 @Qualifier 复制到构造函数
利用 @RequiredArgsConstructor 的 onConstructor_ 属性（JDK 8+ 专用，下划线是为了避免和 JDK 注解参数冲突），显式指定要复制的注解：

java
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;

@RequiredArgsConstructor(onConstructor_ = { @Autowired, @Qualifier("ollamaChatModel1") })
public class YourService {
    @Qualifier("ollamaChatModel1") // 字段上的注解会被 Lombok 忽略，需通过 onConstructor_ 传递
    private final ChatClient ollamaChatModel1;
}*/
