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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Claude 질문을 백그라운드에서 돌리고, 대화를 사용자별로 서버에 둡니다.
 *
 * 왜 백그라운드인가: 프로젝트를 읽으며 답하면 1~3분이 걸리기도 하는데, API 앞단의
 * Cloudflare Tunnel이 약 100초에서 연결을 끊습니다. 짧게 여러 번 묻는 방식이면 시간 제한이
 * 없고, 안경에 "읽기: OrderService.java" 같은 진행 상황도 보여 줄 수 있습니다.
 *
 * 왜 대화를 서버에 두는가: PC 브라우저에서 물어도 안경(폰의 Even 앱)에 같이 뜨고, 어느
 * 화면에서 이어서 물어도 같은 대화로 이어지게 하려고요. 안경 앱은 /latest 를 계속 보다가
 * 새 질문이 생기면 따라갑니다. 메모리에만 두므로 서버를 재시작하면 대화가 새로 시작됩니다.
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
        final String question;
        final String project;
        final int imageCount;
        final Instant createdAt = Instant.now();
        volatile Status status = Status.running;
        final List<String> progress = new ArrayList<>();
        volatile AiService.Answer answer;
        volatile String error;

        Job(String id, Long userId, String question, String project, int imageCount) {
            this.id = id;
            this.userId = userId;
            this.question = question;
            this.project = project;
            this.imageCount = imageCount;
        }

        synchronized void addProgress(String p) {
            progress.add(p.length() > 120 ? p.substring(0, 117) + "..." : p);
            if (progress.size() > MAX_PROGRESS) progress.remove(0);
        }
    }

    /** turns: 이 답까지 포함한 대화 왕복 수(질문·답 한 쌍 = 1). */
    public record JobView(String id, Status status, String question, String project, int imageCount,
                          Instant createdAt, List<String> progress,
                          String answer, String detail, List<String> steps, String error, int turns) {}

    /** 사용자별 대화. 프로젝트가 바뀌면 새 대화로 시작합니다. */
    private static final class Conversation {
        String project;
        final List<AiService.Turn> turns = new ArrayList<>();
        Instant updatedAt = Instant.now();
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, String> latest = new ConcurrentHashMap<>();
    private final Map<Long, Conversation> conversations = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-job");
        t.setDaemon(true);
        return t;
    });

    /** 질문에 답하는 일. 대화 기록과 진행 상황 콜백을 받아 답을 만듭니다(백그라운드 스레드). */
    @FunctionalInterface
    public interface Work {
        AiService.Answer run(List<AiService.Turn> history, Consumer<String> progress);
    }

    /**
     * @param clientHistory 예전 앱(0.1.5)이 직접 보내는 대화 기록. null이면 서버 대화를 씁니다.
     */
    public String start(Long userId, String question, String project, int imageCount,
                        List<AiService.Turn> clientHistory, Work work) {
        cleanup();
        long running = jobs.values().stream().filter(j -> j.userId.equals(userId) && j.status == Status.running).count();
        if (running >= MAX_RUNNING_PER_USER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "앞 질문이 끝나면 다시 물어봐 주세요.");
        }
        List<AiService.Turn> history;
        if (clientHistory != null) {
            history = clientHistory;
        } else {
            Conversation c = conversations.computeIfAbsent(userId, id -> new Conversation());
            synchronized (c) {
                if (!Objects.equals(c.project, project)) {
                    c.turns.clear();
                    c.project = project;
                }
                history = List.copyOf(c.turns);
            }
        }

        Job job = new Job(UUID.randomUUID().toString(), userId, question, project, imageCount);
        jobs.put(job.id, job);
        latest.put(userId, job.id);
        pool.submit(() -> {
            try {
                AiService.Answer a = work.run(history, job::addProgress);
                if (clientHistory == null) remember(userId, project, question, imageCount, a);
                job.answer = a;
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

    private void remember(Long userId, String project, String question, int imageCount, AiService.Answer a) {
        Conversation c = conversations.computeIfAbsent(userId, id -> new Conversation());
        synchronized (c) {
            // 답을 기다리는 사이 다른 화면에서 프로젝트를 바꿨으면 이 답은 새 대화에 섞지 않습니다.
            if (!Objects.equals(c.project, project)) return;
            // 사진은 다시 보내지 않습니다(비용). "사진을 봤다"는 사실만 남깁니다.
            String asked = imageCount > 0 ? question + "\n[사진 " + imageCount + "장 첨부]" : question;
            c.turns.add(new AiService.Turn("user", asked));
            c.turns.add(new AiService.Turn("assistant", a.full()));
            c.updatedAt = Instant.now();
        }
    }

    /** 새 대화 — 어느 화면에서 눌러도 모든 화면의 대화가 함께 비워집니다. */
    public void reset(Long userId) {
        conversations.remove(userId);
        latest.remove(userId);
    }

    public JobView get(Long userId, String id) {
        Job job = jobs.get(id);
        // 남의 작업은 없는 것처럼 보입니다.
        if (job == null || !job.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작업이 없어요.");
        return view(job);
    }

    /** 이 사용자의 가장 최근 질문(어느 화면에서 했든). 없으면 null. */
    public JobView latest(Long userId) {
        String id = latest.get(userId);
        Job job = id == null ? null : jobs.get(id);
        return job == null ? null : view(job);
    }

    private JobView view(Job job) {
        List<String> progress;
        synchronized (job) {
            progress = List.copyOf(job.progress);
        }
        AiService.Answer a = job.answer;
        int turns;
        Conversation c = conversations.get(job.userId);
        if (c == null) {
            turns = 0;
        } else {
            synchronized (c) {
                turns = c.turns.size() / 2;
            }
        }
        return new JobView(job.id, job.status, job.question, job.project, job.imageCount, job.createdAt, progress,
                a == null ? null : a.summary(), a == null ? null : a.detail(), a == null ? List.of() : a.steps(),
                job.error, turns);
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(KEEP);
        jobs.values().removeIf(j -> j.status != Status.running && j.createdAt.isBefore(cutoff));
        latest.values().removeIf(id -> !jobs.containsKey(id));
        // 하루 넘게 쉰 대화는 새로 시작합니다(오래된 맥락이 답을 흐리지 않게).
        Instant stale = Instant.now().minus(Duration.ofHours(24));
        conversations.values().removeIf(c -> c.updatedAt.isBefore(stale));
    }
}
