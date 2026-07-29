package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobSubmissionServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock io.github.learnerview.simplydone4j.service.RateLimiterService rateLimiter;
    @Mock JobMapper jobMapper;
    @Mock JobEventPublisher eventPublisher;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobSubmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter, props, jobMapper, eventPublisher,
                redis, mock(Validator.class));
    }

    @Test
    void shouldSubmitNewJob() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.queueSize(any())).thenReturn(0L);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertNotNull(response.getJobId());
        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo).save(any(JobEntity.class));
        verify(queueRepo).enqueue(anyString(), any(), anyLong());
    }

    @Test
    void shouldReturnExistingJobOnIdempotentSubmit() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("job-1");

        JobEntity existing = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(existing));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertEquals("job-1", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldThrowWhenQueueFull() {
        props.getQueue().setMaxDepth(1);
        when(queueRepo.queueSize(any())).thenReturn(2L);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(QueueFullException.class, () -> service.submit("producer-1", req));
    }
}
