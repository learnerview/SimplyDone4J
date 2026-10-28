package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pins the atomic save: the record and the indexes move together, and a write that
 * cannot finish leaves nothing behind.
 *
 * <p>Needs a real Redis. The subject is what the server does with a script that touches
 * thirty keys, and a mock cannot show that.</p>
 */
class RedisJobAtomicSaveRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimplyDoneProperties props;
    private RedisJobRepository repository;

    @BeforeAll
    static void connectToRedis() {
        boolean reachable;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(HOST, PORT));
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            connectionFactory = factory;
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "Needs a real Redis at " + HOST + ":" + PORT
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show "
                        + "what the server does with a script that touches thirty keys.");
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        props = new SimplyDoneProperties();
        repository = new RedisJobRepository(redis, props);
    }


    private JobEntity job(String id, JobStatus status, JobPriority priority) {
        return JobEntity.builder()
                .id(id)
                .jobType("test")
                .producer("producer-1")
                .status(status)
                .priority(priority)
                .nextRunAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /** Every index key the job could be a member of. */
    private List<String> indexKeys() {
        List<String> keys = new java.util.ArrayList<>();
        for (JobStatus s : JobStatus.values()) {
            keys.add("simplydone4j:idx:status:" + s.name().toLowerCase());
        }
        for (JobStatus s : JobStatus.values()) {
            for (JobPriority p : JobPriority.values()) {
                keys.add("simplydone4j:idx:status:priority:" + s.name().toLowerCase() + ':' + p.name().toLowerCase());
            }
        }
        return keys;
    }

    /**
     * The members of an index key. A key that is not a sorted set cannot hold the job,
     * so a wrong type counts as "no members" rather than as a failure.
     */
    private Set<String> membersOf(String key) {
        try {
            Set<String> members = redis.opsForZSet().range(key, 0, -1);
            return members == null ? Set.of() : members;
        } catch (org.springframework.data.redis.RedisSystemException e) {
            return Set.of();
        }
    }

    private void assertIndexedExactlyIn(String jobId, JobStatus status, JobPriority priority) {
        for (JobStatus s : JobStatus.values()) {
            Set<String> members = membersOf("simplydone4j:idx:status:" + s.name().toLowerCase());
            boolean expected = s == status;
            assertEquals(expected, members.contains(jobId),
                    "job " + jobId + " should " + (expected ? "" : "not ") + "be in idx:status:" + s.name().toLowerCase());
        }
        for (JobStatus s : JobStatus.values()) {
            for (JobPriority p : JobPriority.values()) {
                Set<String> members = membersOf(
                        "simplydone4j:idx:status:priority:" + s.name().toLowerCase() + ':' + p.name().toLowerCase());
                // A finished job is counted by status; it is never selected from a
                // priority bucket, so it is not in one.
                boolean expected = s == status && p == priority && !isTerminal(status);
                assertEquals(expected, members.contains(jobId),
                        "job " + jobId + " should " + (expected ? "" : "not ") + "be in idx:status:priority:"
                                + s.name().toLowerCase() + ':' + p.name().toLowerCase());
            }
        }
    }

    private boolean isTerminal(JobStatus status) {
        return status == JobStatus.SUCCESS || status == JobStatus.FAILED
                || status == JobStatus.DLQ || status == JobStatus.CANCELLED;
    }

    @Test
    @DisplayName("the record and the indexes agree after every status")
    void shouldKeepTheRecordAndTheIndexesConsistentForEveryStatus() {
        for (JobStatus status : JobStatus.values()) {
            String id = "job-" + status.name().toLowerCase();
            repository.save(job(id, status, JobPriority.NORMAL));

            JobEntity stored = repository.findById(id).orElseThrow();
            assertEquals(status, stored.getStatus(), "the record must hold the status that was written");
            assertIndexedExactlyIn(id, status, JobPriority.NORMAL);
        }
    }

    @Test
    @DisplayName("a status change moves the job out of the old index")
    void shouldMoveTheJobBetweenIndexesWhenTheStatusChanges() {
        String id = "job-moves";
        repository.save(job(id, JobStatus.QUEUED, JobPriority.NORMAL));
        assertIndexedExactlyIn(id, JobStatus.QUEUED, JobPriority.NORMAL);

        repository.save(job(id, JobStatus.RUNNING, JobPriority.NORMAL));
        assertIndexedExactlyIn(id, JobStatus.RUNNING, JobPriority.NORMAL);

        repository.save(job(id, JobStatus.SUCCESS, JobPriority.NORMAL));
        assertIndexedExactlyIn(id, JobStatus.SUCCESS, JobPriority.NORMAL);
    }

    @Test
    @DisplayName("a priority change leaves no member in the old priority's bucket")
    void shouldLeaveNoMemberInThePreviousPriorityBucket() {
        String id = "job-reprioritised";
        repository.save(job(id, JobStatus.QUEUED, JobPriority.HIGH));
        assertIndexedExactlyIn(id, JobStatus.QUEUED, JobPriority.HIGH);

        repository.save(job(id, JobStatus.QUEUED, JobPriority.NORMAL));
        assertIndexedExactlyIn(id, JobStatus.QUEUED, JobPriority.NORMAL);

        Set<String> high = redis.opsForZSet().range("simplydone4j:idx:status:priority:queued:high", 0, -1);
        assertFalse(high != null && high.contains(id),
                "the job must not linger in the priority bucket it left");
    }

    @Test
    @DisplayName("a write that cannot finish leaves the record and the indexes untouched")
    void shouldLeaveEverythingUntouchedWhenTheWriteCannotFinish() {
        String id = "job-broken";
        repository.save(job(id, JobStatus.QUEUED, JobPriority.NORMAL));

        // Break one of the indexes the write has to touch: a plain string where the
        // write expects a sorted set. This is the fault the atomic write has to survive.
        redis.opsForValue().set("simplydone4j:idx:status:running", "not-a-sorted-set");

        JobEntity updated = job(id, JobStatus.RUNNING, JobPriority.NORMAL);
        assertThrows(IllegalStateException.class, () -> repository.save(updated));

        JobEntity stored = repository.findById(id).orElseThrow();
        assertEquals(JobStatus.QUEUED, stored.getStatus(),
                "the record must not be half-written: the write either happens completely or not at all");
        assertIndexedExactlyIn(id, JobStatus.QUEUED, JobPriority.NORMAL);
    }

    @Test
    @DisplayName("the fenced write refuses a job whose lease belongs to somebody else")
    void shouldRefuseAFencedWriteWhenTheLeaseDoesNotMatch() {
        String id = "job-fenced";
        JobEntity queued = job(id, JobStatus.QUEUED, JobPriority.NORMAL);
        queued.setLeaseToken("token-a");
        repository.save(queued);

        JobEntity claimed = job(id, JobStatus.RUNNING, JobPriority.NORMAL);
        claimed.setLeaseToken("token-b");

        assertFalse(repository.saveIfLeaseHeld(claimed, "token-b"),
                "the write must be refused when the persisted lease token is not the expected one");
        assertEquals(JobStatus.QUEUED, repository.findById(id).orElseThrow().getStatus());

        assertTrue(repository.saveIfLeaseHeld(claimed, "token-a"),
                "the write must go through when the token matches");
        assertEquals(JobStatus.RUNNING, repository.findById(id).orElseThrow().getStatus());
        assertIndexedExactlyIn(id, JobStatus.RUNNING, JobPriority.NORMAL);
    }

    @Test
    @DisplayName("the whole write is one round trip")
    void shouldWriteTheJobInOneRoundTrip() {
        StringRedisTemplate spy = Mockito.spy(redis);
        RedisJobRepository counting = new RedisJobRepository(spy, props);

        counting.save(job("job-round-trip", JobStatus.QUEUED, JobPriority.NORMAL));

        verify(spy, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(spy, Mockito.never()).opsForHash();
        verify(spy, Mockito.never()).opsForZSet();
        verify(spy, Mockito.never()).expire(any(String.class), any(java.time.Duration.class));
    }
}
