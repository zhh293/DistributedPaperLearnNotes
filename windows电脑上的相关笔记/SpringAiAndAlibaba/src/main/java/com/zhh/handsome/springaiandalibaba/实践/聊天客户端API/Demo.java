/*
package com.zhh.handsome.springaiandalibaba.实践.聊天客户端API;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo")
public class Demo {

    @Autowired
    private ChatClient chatClient;

    @GetMapping
    public String demo(){
        return chatClient.prompt()
                .user("hello")
                .call()
                .content();
    }

    @GetMapping("/demo2")
    public Flux<String> demo2(){
        Flux<String> hello = chatClient.prompt()
                .user("hello")
                .stream()
                .content();
        new Thread(){
            @Override
            public void run(){
                hello.subscribe(System.out::println);
            }
        }.start();
        return hello;
    }
}
*/
