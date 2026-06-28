package com.zhh.handsome.springaiandalibaba.实践.MCP;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
public class Demo11 {

    @Autowired
    private ChatClient chatClient;

    @GetMapping
    public String getTime(){
        return chatClient.prompt()
                .user("现在几点")
                .call()
                .content();
    }
}
