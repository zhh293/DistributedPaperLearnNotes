package com.zhh.handsome.springaiandalibaba.理论知识总结部分.上下文记忆;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis聊天内存配置类
 */
@Configuration
public class RedisChatMemoryConfig {
    
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
    
    @Bean
    public RedisChatMemory redisChatMemory(StringRedisTemplate stringRedisTemplate) {
        // 设置过期时间为1小时
        return new RedisChatMemory(stringRedisTemplate, 60 * 60);
    }
}