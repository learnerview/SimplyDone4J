package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties.Executor;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RateLimiterServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.SchedulerEngine;
import io.github.learnerview.simplydone4j.service.impl.WorkerMaintenanceServiceImpl;
import jakarta.validation.Validator;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.URI;
import java.time.Duration;

@AutoConfiguration
@EnableConfigurationProperties(SimplyDoneProperties.class)
@EnableScheduling
public final class SimplyDoneAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.url:redis://localhost:6379}") String redisUrl) {
        String url = redisUrl;
        if (url == null || url.isBlank()) {
            url = "redis://localhost:6379";
        }
        String envUrl = System.getenv("REDIS_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            url = envUrl;
        }
        boolean useSsl = url.startsWith("rediss://");
        String normalized = useSsl ? url.replaceFirst("rediss://", "redis://") : url;
        URI uri = URI.create(normalized);

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(uri.getHost());
        serverConfig.setPort(uri.getPort() > 0 ? uri.getPort() : 6379);

        if (uri.getUserInfo() != null) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            if (userInfo.length == 2) {
                serverConfig.setUsername(userInfo[0]);
                serverConfig.setPassword(userInfo[1]);
            } else if (userInfo.length == 1) {
                serverConfig.setPassword(userInfo[0]);
            }
        }

        return new LettuceConnectionFactory(
                serverConfig,
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolTaskExecutor jobTaskExecutor(SimplyDoneProperties props) {
        Executor exec = props.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("sd4j-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueRepository queueRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisQueueRepository(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobRepository jobRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisJobRepository(redis, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutionLogRepository jobExecutionLogRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisJobExecutionLogRepository(redis, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry() {
        return new HandlerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public JobEventPublisher jobEventPublisher(ApplicationEventPublisher eventPublisher) {
        return new JobEventPublisher(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobMapper jobMapper(ObjectMapper objectMapper) {
        return new JobMapper(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterService rateLimiterService(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RateLimiterServiceImpl(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryService retryService(JobRepository jobRepo, JobExecutionLogRepository logRepo,
                                      SimplyDoneProperties props, JobEventPublisher eventPublisher) {
        return new RetryServiceImpl(jobRepo, logRepo, props, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobSubmissionService jobSubmissionService(JobRepository jobRepo, QueueRepository queueRepo,
                                                      RateLimiterService rateLimiter, SimplyDoneProperties props,
                                                      JobMapper jobMapper, JobEventPublisher eventPublisher,
                                                      StringRedisTemplate redis, ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(() ->
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        return new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter, props, jobMapper, eventPublisher,
                redis, validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutorService jobExecutorService(JobRepository jobRepo, RetryService retryService,
                                                  HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                                  ThreadPoolTaskExecutor jobTaskExecutor, SimplyDoneProperties props) {
        return new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                jobTaskExecutor, props.getExecutor().getDefaultTimeoutSeconds());
    }

    @Bean
    @Profile("worker")
    @ConditionalOnMissingBean
    public SchedulerService schedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                                             JobExecutorService jobExecutor, SimplyDoneProperties props) {
        return new SchedulerEngine(queueRepo, jobRepo, jobExecutor, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public MonitoringService monitoringService(JobRepository jobRepo, QueueRepository queueRepo,
                                                JobExecutionLogRepository logRepo) {
        return new MonitoringService(jobRepo, queueRepo, logRepo);
    }

    @Bean
    @Profile("worker")
    @ConditionalOnMissingBean
    public WorkerMaintenanceService workerMaintenanceService(JobRepository jobRepo, QueueRepository queueRepo,
                                                              RetryService retryService, SimplyDoneProperties props) {
        return new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, props);
    }
}
