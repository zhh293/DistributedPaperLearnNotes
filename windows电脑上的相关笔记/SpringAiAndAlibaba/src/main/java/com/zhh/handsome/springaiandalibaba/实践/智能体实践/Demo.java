package com.zhh.handsome.springaiandalibaba.实践.智能体实践;


import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/demo1")

public class Demo {
    @Autowired
    private ReactAgent reactAgent;
    @GetMapping("/test")
    public String test(String  message){
        try {
            AssistantMessage call = reactAgent.call(message);
            List<AssistantMessage.ToolCall> toolCalls = call.getToolCalls();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                String toolId = toolCall.id();
                String toolType = toolCall.type();
                String functionCall = toolCall.name();
                String arguments = toolCall.arguments();
                System.out.println("toolId:"+toolId);
                System.out.println("toolType:"+toolType);
                System.out.println("functionCall:"+functionCall);
                System.out.println("arguments:"+arguments);
            }
            return call.getText();
        }catch (Exception e) {
            e.printStackTrace();
        }
        return "hello world";
    }

}
