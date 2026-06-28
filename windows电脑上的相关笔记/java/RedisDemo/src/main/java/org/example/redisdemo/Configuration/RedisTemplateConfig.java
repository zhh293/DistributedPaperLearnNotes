package org.example.redisdemo.Configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
public class RedisTemplateConfig {
    //淘汰的版本
    /*@Bean
    public org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        //c创建对象
        org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate = new org.springframework.data.redis.core.RedisTemplate<>();
        //设置连接工厂
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置key的序列化方式
        redisTemplate.setKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        //设置value的序列化方式
        redisTemplate.setValueSerializer(new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer());
        //返回
        return redisTemplate;
    }*/

}
