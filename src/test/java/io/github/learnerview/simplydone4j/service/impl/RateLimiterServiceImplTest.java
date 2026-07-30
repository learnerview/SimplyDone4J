package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceImplTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;

    SimplyDoneProperties props;
    RateLimiterServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        props.getRateLimit().setRequestsPerMinute(5);
        props.getRateLimit().setWindowSeconds(10);
        service = new RateLimiterServiceImpl(redis, props);
    }

    @Nested
    class LuaScriptAvailable {
        @BeforeEach
        void setUp() {
            service.initScript();
        }

        @Test
        void shouldAllowRequestWithinLimit() {
            when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(List.of(1L, System.currentTimeMillis()));

            assertDoesNotThrow(() -> service.checkRateLimit("producer-1"));
        }

        @Test
        void shouldThrowWhenRateLimitExceeded() {
            when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(List.of(0L, System.currentTimeMillis() - 5000L));

            assertThrows(RateLimitExceededException.class,
                    () -> service.checkRateLimit("producer-1"));
        }

        @Test
        void shouldHandleNullResultFromLua() {
            when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(null);

            assertDoesNotThrow(() -> service.checkRateLimit("producer-1"));
        }

        @Test
        void shouldHandleEmptyResultFromLua() {
            when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> service.checkRateLimit("producer-1"));
        }
    }

    @Nested
    class RedisUnavailable {
        @BeforeEach
        void setUp() {
            lenient().when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));
        }

        @Test
        void shouldFallbackWhenRedisFails() {
            assertDoesNotThrow(() -> service.checkRateLimit("test-producer"));
        }
    }
}
