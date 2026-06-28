package com.zhh.handsome.producer;

/*package com.example.rabbitmq.producer;

import com.example.rabbitmq.config.RabbitMQConfig;
import com.example.rabbitmq.dto.MessageDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;*/

/**
 * 消息生产者
 *//*
@Component
@RequiredArgsConstructor
public class MessageProducer {

    // RabbitTemplate是Spring AMQP提供的发送消息的工具类
    private final RabbitTemplate rabbitTemplate;

    *//**
     * 发送Direct类型消息
     *//*
    public void sendDirectMessage(String content, boolean isRoutingKey1) {
        MessageDTO message = new MessageDTO();
        message.setId(UUID.randomUUID().toString());
        message.setContent(content);
        message.setSender("system");
        message.setSendTime(LocalDateTime.now());
        message.setType("direct");

        // 根据参数选择不同的路由键
        String routingKey = isRoutingKey1 ?
                RabbitMQConfig.DIRECT_ROUTING_KEY_1 :
                RabbitMQConfig.DIRECT_ROUTING_KEY_2;

        // 发送消息，参数：交换机、路由键、消息内容、消息ID(用于确认机制)
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.DIRECT_EXCHANGE,
                routingKey,
                message,
                new CorrelationData(message.getId())
        );
    }

    *//**
     * 发送Topic类型消息
     *//*
    public void sendTopicMessage(String content, String routingKeySuffix) {
        MessageDTO message = new MessageDTO();
        message.setId(UUID.randomUUID().toString());
        message.setContent(content);
        message.setSender("system");
        message.setSendTime(LocalDateTime.now());
        message.setType("topic");

        // 完整路由键 = 前缀 + 后缀
        String fullRoutingKey = "topic.routing.key." + routingKeySuffix;

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.TOPIC_EXCHANGE,
                fullRoutingKey,
                message,
                new CorrelationData(message.getId())
        );
    }

    *//**
     * 发送Fanout类型消息
     *//*
    public void sendFanoutMessage(String content) {
        MessageDTO message = new MessageDTO();
        message.setId(UUID.randomUUID().toString());
        message.setContent(content);
        message.setSender("system");
        message.setSendTime(LocalDateTime.now());
        message.setType("fanout");

        // Fanout交换机忽略路由键，这里可以传任意值或空
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.FANOUT_EXCHANGE,
                "", // 路由键无效
                message,
                new CorrelationData(message.getId())
        );
    }

    *//**
     * 发送会进入死信队列的消息
     *//*
    public void sendDeadLetterMessage(String content) {
        MessageDTO message = new MessageDTO();
        message.setId(UUID.randomUUID().toString());
        message.setContent(content);
        message.setSender("system");
        message.setSendTime(LocalDateTime.now());
        message.setType("dead_letter");

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.DIRECT_EXCHANGE,
                "normal.routing.key",
                message,
                new CorrelationData(message.getId())
        );
    }
}*/
