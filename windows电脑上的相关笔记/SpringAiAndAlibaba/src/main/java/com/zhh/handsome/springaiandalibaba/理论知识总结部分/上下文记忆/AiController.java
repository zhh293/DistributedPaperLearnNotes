/*
package com.zhh.handsome.springaiandalibaba.理论知识总结部分.上下文记忆;

import org.example.springai1.Repository.ChatHistoryReposity;
import org.example.springai1.util.MessageVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.example.springai1.ENUM.enumOperation.CHAT;


样例演示

@RestController
@RequestMapping("/ai")
public class AiController {

     @Qualifier("ollamaChatModel1")
     @Autowired
     private  ChatClient chatClient;

     @Autowired
     private  ChatHistoryReposity chatHistoryReposity;

     @Autowired
     @Qualifier("redisChatMemory")
     private  ChatMemory chatMemory;


    @PostMapping(value = "/chat",produces = "text/html;charset=UTF-8")
    public Flux<String> chat(@RequestParam String prompt,String chatId) {
         chatHistoryReposity.save(String.valueOf(CHAT),chatId);
         return chatClient.prompt()
                .user(prompt)
                .advisors(a->{
                    a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId);
                })
                .stream()
                .content();
    }
    @GetMapping("/history/{type}/{chatId}")
    public List<MessageVO> getHistory(@PathVariable String type,@PathVariable String chatId) {
        List<Message> messages = chatMemory.get(chatId, Integer.MAX_VALUE);
        if(messages==null){
            return List.of();
        }
       return messages.stream().map(message -> new MessageVO((message))).toList();
    }

    @GetMapping("/history/{type}")
    public List<String> getChatIds(@PathVariable String type) {
        return chatHistoryReposity.getChatIds(type);
    }
}
*/




























/*
@RequiredArgsConstructor 注解全解析（Lombok 核心注解）
一、是什么？
@RequiredArgsConstructor 是 Lombok 库提供的注解，用于 自动生成构造函数，仅包含两类字段：

被 final 修饰的字段（不可变字段）；
被 @NonNull 注解标记的字段（非空强制字段）。

如果类中没有这两类字段，会生成 无参构造函数。
二、核心作用
1. 简化代码，消灭样板工程
手动编写构造函数繁琐（尤其依赖多的类），注解自动生成构造器，减少重复代码。
示例对比：

java
// 手动写构造器（繁琐）
@Service
public class UserService {
    private final UserRepository userRepo;
    private final MailService mailService;

    public UserService(UserRepository userRepo, MailService mailService) {
        this.userRepo = userRepo;
        this.mailService = mailService;
    }
}



















// 使用 @RequiredArgsConstructor（简洁）
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepo; // final 字段 → 自动纳入构造器
    private final MailService mailService;
}
2. 完美适配 Spring 依赖注入
Spring 推荐 构造器注入（比 @Autowired 字段注入更优），@RequiredArgsConstructor 自动生成构造器，让依赖注入更丝滑：

不可变性：final 字段构造后无法修改，避免运行时意外赋值；
非空保证：构造时必须传入依赖，减少空指针异常（NPE）；
便于测试：单元测试可直接通过构造器传入 Mock 对象，无需反射。
        3. 强化代码语义
通过 final 标记 “必需依赖”，配合注解自动生成构造器，清晰表达类的依赖关系（哪些字段是核心、必须初始化）。*//*




*/
/*
五、与 Spring 结合的最佳实践
在 Spring 的 @Service、@Controller 中，优先用构造器注入 + @RequiredArgsConstructor：

java
@RestController
@RequiredArgsConstructor // 自动生成构造器
@RequestMapping("/users")
public class UserController {
    private final UserService userService; // final → 构造器注入
    private final AuthService authService;   // final → 构造器注入

    // Spring 自动通过构造器注入依赖，无需 @Autowired！
}


为什么比 @Autowired 好？

@Autowired 是 字段注入，依赖可被后期修改（非 final），且无法保证非空；
构造器注入通过 final 强制依赖不可变，且 IDE 能直接提示 “缺少依赖”（编译期检查）。*//*


*/
