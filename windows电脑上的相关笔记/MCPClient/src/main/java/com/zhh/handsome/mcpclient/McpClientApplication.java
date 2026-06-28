package com.zhh.handsome.mcpclient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RequestMapping
@RestController
public class McpClientApplication {


    @Autowired
    private ChatClient chatClient;

    @GetMapping("/")
    public String index() {
        return chatClient.prompt()
                .user("现在几点，然后对应着美国现在几点")
                .call()
                .content();
    }
    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }

}
