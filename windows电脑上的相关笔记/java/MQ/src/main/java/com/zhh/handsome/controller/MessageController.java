package com.zhh.handsome.controller;

/*
package com.example.rabbitmq.controller;

import com.example.rabbitmq.producer.MessageProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

*/
/**
 * 测试接口，用于发送各种类型的消息
 *//*

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageProducer messageProducer;

    */
/**
     * 发送Direct类型消息
     *//*

    @GetMapping("/direct")
    public String sendDirectMessage(@RequestParam String content,
                                    @RequestParam(defaultValue = "true") boolean isRoutingKey1) {
        messageProducer.sendDirectMessage(content, isRoutingKey1);
        return "Direct消息发送成功";
    }

    */
/**
     * 发送Topic类型消息
     *//*

    @GetMapping("/topic")
    public String sendTopicMessage(@RequestParam String content,
                                   @RequestParam String routingKeySuffix) {
        messageProducer.sendTopicMessage(content, routingKeySuffix);
        return "Topic消息发送成功";
    }

    */
/**
     * 发送Fanout类型消息
     *//*

    @GetMapping("/fanout")
    public String sendFanoutMessage(@RequestParam String content) {
        messageProducer.sendFanoutMessage(content);
        return "Fanout消息发送成功";
    }

    */
/**
     * 发送会进入死信队列的消息
     *//*

    @GetMapping("/dead-letter")
    public String sendDeadLetterMessage(@RequestParam String content) {
        messageProducer.sendDeadLetterMessage(content);
        return "死信消息发送成功，10秒后会进入死信队列";
    }
}
*/
