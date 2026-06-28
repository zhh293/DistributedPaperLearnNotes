package com.zhh.handsome.config;

/*
@Configuration
@Slf4j
public class RabbitMQConfirmConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    */
/**
     * 初始化时设置回调
     *//*

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    */
/**
     * 生产者确认回调
     * 确认消息是否到达交换机
     *//*

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        String messageId = correlationData != null ? correlationData.getId() : "未知ID";

        if (ack) {
            log.info("消息[{}]已成功到达交换机", messageId);
        } else {
            log.error("消息[{}]未到达交换机，原因: {}", messageId, cause);
            // 可以在这里实现消息重发逻辑
        }
    }

    */
/**
     * 消息返回回调
     * 当消息到达交换机但无法路由到队列时触发
     *//*

    @Override
    public void returnedMessage(Message message, int replyCode, String replyText,
                                String exchange, String routingKey) {
        log.error("消息路由失败 - 交换机: {}, 路由键: {}, 响应码: {}, 响应信息: {}, 消息内容: {}",
                exchange, routingKey, replyCode, replyText, new String(message.getBody()));
        // 可以在这里处理路由失败的消息
    }
}
*/
