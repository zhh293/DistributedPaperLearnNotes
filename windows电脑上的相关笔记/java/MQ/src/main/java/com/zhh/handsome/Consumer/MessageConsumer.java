package com.zhh.handsome.Consumer;

/*@Component
@Slf4j
public class MessageConsumer {

    *//**
     * 消费Direct队列1的消息
     *//*
    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_1)
    public void consumeDirectQueue1(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Direct队列1】收到消息: {}", message);

            // 处理业务逻辑...
            // TODO: 实际业务处理代码

            // 手动确认消息已消费
            // 参数1: 消息标识，参数2: 是否批量确认
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Direct队列1消息出错", e);

            // 处理失败，拒绝消息并重新入队
            // 参数3: 是否重新入队
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费Direct队列2的消息
     *//*
    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_2)
    public void consumeDirectQueue2(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Direct队列2】收到消息: {}", message);

            // 处理业务逻辑...

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Direct队列2消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费Topic队列1的消息（用户相关）
     *//*
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_1)
    public void consumeTopicQueue1(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Topic队列1(用户相关)】收到消息: {}", message);

            // 处理用户相关业务...

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Topic队列1消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费Topic队列2的消息（订单相关）
     *//*
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_2)
    public void consumeTopicQueue2(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Topic队列2(订单相关)】收到消息: {}", message);

            // 处理订单相关业务...

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Topic队列2消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费Fanout队列1的消息
     *//*
    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_1)
    public void consumeFanoutQueue1(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Fanout队列1】收到消息: {}", message);

            // 处理业务逻辑...

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Fanout队列1消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费Fanout队列2的消息
     *//*
    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_2)
    public void consumeFanoutQueue2(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【Fanout队列2】收到消息: {}", message);

            // 处理业务逻辑...

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理Fanout队列2消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    *//**
     * 消费死信队列的消息
     *//*
    @RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE)
    public void consumeDeadLetterQueue(MessageDTO message, Channel channel, Message amqpMessage) throws IOException {
        try {
            log.info("【死信队列】收到消息: {}", message);

            // 处理死信消息，通常是一些需要特殊处理的失败消息

            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理死信队列消息出错", e);
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, false);
        }
    }
}*/

