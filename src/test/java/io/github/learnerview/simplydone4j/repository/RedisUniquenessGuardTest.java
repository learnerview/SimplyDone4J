package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisUniquenessGuardTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    RedisUniquenessGuard guard;

    @BeforeEach
    void setUp() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setKeyPrefix("sd4j:test");
        guard = new RedisUniquenessGuard(redis, props);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void shouldDeriveTheLockKeyFromTheConfiguredPrefix() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        guard.tryAcquire("account-42", "job-1", Duration.ofSeconds(60));

        verify(values).setIfAbsent(eq("sd4j:test:unique:account-42"), eq("job-1"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void shouldReportAcquisitionWhenSetIfAbsentSucceeds() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertTrue(guard.tryAcquire("account-42", "job-1", Duration.ofSeconds(60)));
    }

    @Test
    void shouldReportContentionWhenTheKeyIsAlreadyHeld() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(guard.tryAcquire("account-42", "job-1", Duration.ofSeconds(60)));
    }

    @Test
    void shouldTreatUnkeyedWorkAsUncontended() {
        assertTrue(guard.tryAcquire(null, "job-1", Duration.ofSeconds(60)));
        assertTrue(guard.tryAcquire("", "job-1", Duration.ofSeconds(60)));
        assertTrue(guard.tryAcquire("   ", "job-1", Duration.ofSeconds(60)));

        verify(redis, never()).opsForValue();
    }

    @Test
    void shouldReleaseOnlyWhenTheScriptDeletedTheKey() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString())).thenReturn(1L);

        assertTrue(guard.release("account-42", "job-1"));
    }

    @Test
    void shouldNotEvictAKeyHeldByAnotherOwner() {
        // The script's compare-and-delete is what returns 0 here. Reporting success
        // would be a lie, and releasing unconditionally would break the guarantee.
        when(redis.execute(any(RedisScript.class), any(List.class), anyString())).thenReturn(0L);

        assertFalse(guard.release("account-42", "job-1"));
    }

    @Test
    void shouldNotTreatANullScriptResultAsARelease() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString())).thenReturn(null);

        assertFalse(guard.release("account-42", "job-1"));
    }

    @Test
    void shouldPassTheOwnerAsTheReleaseArgument() {
        when(redis.execute(any(RedisScript.class), any(List.class), anyString())).thenReturn(1L);

        guard.release("account-42", "job-7");

        verify(redis).execute(any(RedisScript.class), eq(List.of("sd4j:test:unique:account-42")),
                eq("job-7"));
    }

    @Test
    void shouldSkipRedisEntirelyWhenReleasingAnUnkeyedJob() {
        assertTrue(guard.release(null, "job-1"));
        assertTrue(guard.release("  ", "job-1"));

        verify(redis, never()).execute(any(RedisScript.class), any(List.class), anyString());
    }

    @Test
    void shouldAlwaysReportItselfEnabled() {
        // The DISABLED constant is what the auto-config returns when the feature is off,
        // so a Redis-backed instance is always the enabled one.
        assertTrue(guard.isEnabled());
    }
}
