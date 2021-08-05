package com.hqy.cache.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.Serializable;

/**
 * ################ Redis 基础配置 ##############
 * # Redis数据库索引（默认为0）
 * spring.redis.database=0
 * # Redis服务器地址
 * spring.redis.host=127.0.0.1
 * # Redis服务器连接端口
 * spring.redis.port=6379
 * # Redis服务器连接密码（默认为空）
 * spring.redis.password=zwqh
 * # 链接超时时间 单位 ms（毫秒）
 * spring.redis.timeout=3000
 * ################ Redis 线程池设置 ##############
 * # 连接池最大连接数（使用负值表示没有限制） 默认 8
 * spring.redis.lettuce.pool.max-active=8
 * # 连接池最大阻塞等待时间（使用负值表示没有限制） 默认 -1
 * spring.redis.lettuce.pool.max-wait=-1
 * # 连接池中的最大空闲连接 默认 8
 * spring.redis.lettuce.pool.max-idle=8
 * # 连接池中的最小空闲连接 默认 0
 * spring.redis.lettuce.pool.min-idle=0
 * <p>
 * 基于Lettuce的RedisTemplate
 * @author qy
 * @project: hqy-parent-all
 * @create 2021-07-22 16:28
 */
@Configuration
@SuppressWarnings("all")
public class LettuceRedisConfig {

    @Bean("LettuceRedisTemplate")
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        //配置jackson的序列化器
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        jackson2JsonRedisSerializer.setObjectMapper(objectMapper);
        //String 序列化器
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key采用String的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // Hash key序列化
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化
        template.setHashValueSerializer(jackson2JsonRedisSerializer);
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jackson2JsonRedisSerializer);
//        template.afterPropertiesSet();
        // 配置连接工厂
        template.setConnectionFactory(factory);
        return template;
    }

}
