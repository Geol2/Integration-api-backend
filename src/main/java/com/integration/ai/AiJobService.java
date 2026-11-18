package com.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude 질문을 백그라운드에서 돌리고, 앱은 jobId로 진행 상황을 물어봅니다.
 *
 * 왜 동기 응답이 아닌가: 프로젝트를 읽으며 답하면 1~3분이 걸리기도 하는데, API 앞단의
 * Cloudflare Tunnel이 약 100초에서 연결을 끊습니다. 짧게 여러 번 묻는 방식이면 시간 제한이
 * 없고, 안경에 "읽기: OrderService.java" 같은 진행 상황도 보여 줄 수 있습니다.
 */
@Service
public class AiJobService {

    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);
    private static final Duration KEEP = Duration.ofMinutes(30);
    private static final int MAX_PROGRESS = 50;
    /** 한 사용자가 동시에 돌릴 수 있는 질문 수. */
    private static final int MAX_RUNNING_PER_USER = 2;

    public enum Status { running, done, error }

    public static final class Job {
        final String id;
        final Long userId;
        final Instant createdAt = Instant.now();
        volatile Status status = Status.running;
        final List<String> progress = new ArrayList<>();
        volatile AiService.Answer answer;
        volatile String error;

        Job(String id, Long userId) { this.id = id; this.userId = userId; }

        synchronized void addProgress(String p) {
            progress.add(p.length() > 120 ? p.substring(0, 117) + "..." : p);
            if (progress.size() > MAX_PROGRESS) progress.remove(0);
        }
    }

    public record JobView(String id, Status status, List<String> progress,
                          String answer, String detail, List<String> steps, String error) {}

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-job");
        t.setDaemon(true);
        return t;
    });

    /** work는 progress 콜백을 받아 답을 만듭니다(백그라운드 스레드에서 실행). */
    public String start(Long userId, java.util.function.Function<java.util.function.Consumer<String>, AiService.Answer> work) {
        cleanup();
        long running = jobs.values().stream().filter(j -> j.userId.equals(userId) && j.status == Status.running).count();
        if (running >= MAX_RUNNING_PER_USER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "앞 질문이 끝나면 다시 물어봐 주세요.");
        }
        Job job = new Job(UUID.randomUUID().toString(), userId);
        jobs.put(job.id, job);
        pool.submit(() -> {
            try {
                job.answer = work.apply(job::addProgress);
                job.status = Status.done;
            } catch (ResponseStatusException e) {
                job.error = e.getReason() != null ? e.getReason() : "답을 받지 못했어요.";
                job.status = Status.error;
            } catch (RuntimeException e) {
                log.error("AI 작업 실패", e);
                job.error = "답을 받지 못했어요. 잠시 후 다시 물어봐 주세요.";
                job.status = Status.error;
            }
        });
        return job.id;
    }

    public JobView get(Long userId, String id) {
        Job job = jobs.get(id);
        // 남의 작업은 없는 것처럼 보입니다.
        if (job == null || !job.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작업이 없어요.");
        List<String> progress;
        synchronized (job) {
            progress = List.copyOf(job.progress);
        }
        AiService.Answer a = job.answer;
        return new JobView(job.id, job.status, progress,
                a == null ? null : a.summary(), a == null ? null : a.detail(), a == null ? List.of() : a.steps(),
                job.error);
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(KEEP);
        jobs.values().removeIf(j -> j.status != Status.running && j.createdAt.isBefore(cutoff));
    }

}
