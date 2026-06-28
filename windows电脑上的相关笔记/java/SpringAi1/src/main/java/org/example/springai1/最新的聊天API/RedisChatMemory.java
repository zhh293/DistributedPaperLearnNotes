package org.example.springai1.最新的聊天API;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的聊天内存实现
 * 
 * 该实现使用Redis的List数据结构存储聊天消息序列
 * Key格式: chat:memory:{conversationId}
 * 
 * 特性:
 * 1. 支持自动过期机制
 * 2. 使用JSON序列化消息对象
 * 3. 线程安全的操作
 * 4. 可配置的过期时间
 */
public class RedisChatMemory implements ChatMemory {
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds; // 过期时间（秒）
    
    /**
     * 构造函数
     * @param redisTemplate Redis模板
     */
    public RedisChatMemory(StringRedisTemplate redisTemplate) {
        this(redisTemplate, 24 * 60 * 60); // 默认24小时
    }
    
    /**
     * 构造函数
     * @param redisTemplate Redis模板
     * @param ttlSeconds 过期时间（秒）
     */
    public RedisChatMemory(StringRedisTemplate redisTemplate, long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.ttlSeconds = ttlSeconds;
    }
    
    /**
     * 获取指定会话的所有消息
     * @param conversationId 会话ID
     * @param lastN 消息数量限制，小于等于0表示不限制
     * @return 消息列表
     *//*
    @Override
    public List<Message> get(Object conversationId, int lastN) {
        return get(conversationId.toString(), lastN);
    }
    
    *//**
     * 向指定会话添加消息
     * @param conversationId 会话ID
     * @param messages 要添加的消息列表
     *//*
    @Override
    public void add(Object conversationId, List<Message> messages) {
        add(conversationId.toString(), messages);
    }
    
    *//**
     * 清除指定会话的所有消息
     * @param conversationId 会话ID
     *//*
    @Override
    public void clear(Object conversationId) {
        clear(conversationId.toString());
    }
    */
    /**
     * 构建Redis键
     * @param conversationId 会话ID
     * @return Redis键
     */
    private String buildKey(Object conversationId) {
        return "chat:memory:" + conversationId.toString();
    }
    
    /**
     * 获取过期时间（秒）
     * @return 过期时间
     */
    public long getTtlSeconds() {
        return ttlSeconds;
    }

    @Override
    public void add(String conversationId, Message message) {
        if (message == null) {
            return;
        }
        
        String key = buildKey(conversationId);
        
        try {
            String messageJson = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, messageJson);
            // 设置过期时间
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message: " + message, e);
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        String key = buildKey(conversationId);
        
        // 批量序列化消息
        List<String> messageJsonList = new ArrayList<>();
        for (Message message : messages) {
            try {
                String messageJson = objectMapper.writeValueAsString(message);
                messageJsonList.add(messageJson);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize message: " + message, e);
            }
        }
        
        // 批量添加到Redis
        redisTemplate.opsForList().rightPushAll(key, messageJsonList.toArray(new String[0]));
        
        // 设置过期时间
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = buildKey(conversationId);
        List<String> messageJsonList = redisTemplate.opsForList().range(key, 0, -1);
        
        if (messageJsonList == null || messageJsonList.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<Message> messages = new ArrayList<>();
        for (String messageJson : messageJsonList) {
            try {
                Message message = objectMapper.readValue(messageJson, Message.class);
                messages.add(message);
            } catch (JsonProcessingException e) {
                // 记录警告日志并跳过无效消息
                System.err.println("Failed to deserialize message: " + messageJson + ", error: " + e.getMessage());
                // 不抛出异常，继续处理其他消息
            }
        }
        
        // 如果lastN大于0，返回最后N条消息
        if (lastN > 0 && messages.size() > lastN) {
            return messages.subList(messages.size() - lastN, messages.size());
        }
        
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        String key = buildKey(conversationId);
        redisTemplate.delete(key);
    }
}