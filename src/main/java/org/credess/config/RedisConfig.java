package org.credess.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuration class for Spring Data Redis.
 * Sets up the RedisTemplate with proper serializers for keys and values.
 */
@Configuration
public class RedisConfig {

    /**
     * Creates and configures the RedisTemplate bean.
     *
     * @param connectionFactory The Redis connection factory provided by Spring Boot.
     * @return Configured RedisTemplate instance.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Serialize keys as standard strings
        template.setKeySerializer(new StringRedisSerializer());

        // Serialize values as JSON using Jackson
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        // Configure hash key and value serializers
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }
}