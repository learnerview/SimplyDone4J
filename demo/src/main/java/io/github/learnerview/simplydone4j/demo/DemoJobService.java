package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class DemoJobService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int STATUS_LOAD_LIMIT = 200;

    private final JobQueryRepository jobQueryRepository;
    private final JobMapper jobMapper;
    private final DeadLetterService deadLetterService;

    public DemoJobService(JobQueryRepository jobQueryRepository, JobMapper jobMapper,
                          DeadLetterService deadLetterService) {
        this.jobQueryRepository = jobQueryRepository;
        this.jobMapper = jobMapper;
        this.deadLetterService = deadLetterService;
    }

    public JobPage jobs(String status, String priority, String query, int limit) {
        int pageSize = clamp(limit);
        String needle = normalize(query);
        JobPriority priorityFilter = parsePriority(priority);
        List<JobResponse> matches = load(status).stream()
                .map(jobMapper::toResponse)
                .filter(job -> priorityFilter == null
                        || priorityFilter.name().equalsIgnoreCase(job.getPriority()))
                .filter(job -> matches(job, needle))
                .sorted(Comparator.comparing(JobResponse::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new JobPage(matches.stream().limit(pageSize).toList(), matches.size(),
                pageSize, Instant.now());
    }

    public DeadLetterPage deadLetters(String query, int limit) {
        int pageSize = clamp(limit);
        String needle = normalize(query);
        List<JobResponse> matches = deadLetterService.list(pageSize).stream()
                .filter(job -> matches(job, needle))
                .sorted(Comparator.comparing(JobResponse::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new DeadLetterPage(matches, deadLetterService.count(), pageSize, Instant.now());
    }

    public JobResponse replay(String jobId) {
        return deadLetterService.replay(jobId);
    }

    public void discard(String jobId) {
        deadLetterService.discard(jobId);
    }

    private List<JobEntity> load(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            List<JobEntity> jobs = new ArrayList<>();
            for (JobStatus value : JobStatus.values()) {
                jobs.addAll(jobQueryRepository.findByStatus(value, STATUS_LOAD_LIMIT));
            }
            return jobs;
        }
        return jobQueryRepository.findByStatus(parseStatus(status), STATUS_LOAD_LIMIT);
    }

    private boolean matches(JobResponse job, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(job.getId(), needle)
                || contains(job.getJobType(), needle)
                || contains(job.getProducer(), needle)
                || contains(job.getIdempotencyKey(), needle)
                || contains(job.getStatus(), needle)
                || contains(job.getPriority(), needle)
                || contains(job.getResult(), needle)
                || contains(String.valueOf(job.getPayload()), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    private static JobStatus parseStatus(String status) {
        try {
            return JobStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown job status: " + status);
        }
    }

    private static JobPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank() || "ALL".equalsIgnoreCase(priority)) {
            return null;
        }
        try {
            return JobPriority.valueOf(priority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown priority: " + priority);
        }
    }

    public record JobPage(List<JobResponse> jobs, int matched, int limit, Instant generatedAt) {
    }

    public record DeadLetterPage(List<JobResponse> jobs, long total, int limit,
                                 Instant generatedAt) {
    }
}
