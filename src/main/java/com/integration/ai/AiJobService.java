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
import java.util.function.Consumer;

/**
 * Claude 질문을 백그라운드에서 돌립니다. 대화 내용은 ConversationService(DB)에 남고,
 * 여기서는 진행 중인 질문과 "가장 최근 질문"만 메모리에 둡니다.
 *
 * 왜 백그라운드인가: 프로젝트를 읽으며 답하면 1~3분이 걸리기도 하는데, API 앞단의
 * Cloudflare Tunnel이 약 100초에서 연결을 끊습니다. 짧게 여러 번 묻는 방식이면 시간 제한이
 * 없고, 안경에 "읽기: OrderService.java" 같은 진행 상황도 보여 줄 수 있습니다.
 *
 * "가장 최근 질문"(/latest): PC에서 물어도 안경(폰의 Even 앱)이 따라가 같이 보여 줍니다.
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
        final Long conversationId;
        final String question;
        final String project;
        final int imageCount;
        final Instant createdAt = Instant.now();
        volatile Status status = Status.running;
        final List<String> progress = new ArrayList<>();
        volatile AiService.Answer answer;
        volatile String error;

        Job(String id, Long userId, Long conversationId, String question, String project, int imageCount) {
            this.id = id;
            this.userId = userId;
            this.conversationId = conversationId;
            this.question = question;
            this.project = project;
            this.imageCount = imageCount;
        }

        synchronized void addProgress(String p) {
            progress.add(p.length() > 120 ? p.substring(0, 117) + "..." : p);
            if (progress.size() > MAX_PROGRESS) progress.remove(0);
        }
    }

    /** turns: 이 대화의 질문·답 쌍 수(진행 중인 질문은 끝나야 셉니다). */
    public record JobView(String id, Status status, Long conversationId, String question, String project, int imageCount,
                          Instant createdAt, List<String> progress,
                          String answer, String detail, List<String> steps, String error, int turns) {}

    /** 질문에 답하는 일. 대화 기록과 진행 상황 콜백을 받아 답을 만듭니다(백그라운드 스레드). */
    @FunctionalInterface
    public interface Work {
        AiService.Answer run(List<AiService.Turn> history, Consumer<String> progress);
    }

    private final ConversationService conversations;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Long, String> latest = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-job");
        t.setDaemon(true);
        return t;
    });

    public AiJobService(ConversationService conversations) {
        this.conversations = conversations;
    }

    /**
     * @param conversationId 이어 갈 대화(호출 전에 주인 확인을 마친 것). null이면 저장하지 않습니다.
     * @param legacyHistory  예전 앱(0.1.5)이 직접 보내는 대화 기록. 있으면 이것을 씁니다.
     */
    public String start(Long userId, Long conversationId, String question, String project, int imageCount,
                        List<AiService.Turn> legacyHistory, Work work) {
        cleanup();
        long running = jobs.values().stream().filter(j -> j.userId.equals(userId) && j.status == Status.running).count();
        if (running >= MAX_RUNNING_PER_USER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "앞 질문이 끝나면 다시 물어봐 주세요.");
        }
        List<AiService.Turn> history = legacyHistory != null ? legacyHistory
                : conversationId != null ? conversations.history(conversationId) : List.of();

        Job job = new Job(UUID.randomUUID().toString(), userId, conversationId, question, project, imageCount);
        jobs.put(job.id, job);
        latest.put(userId, job.id);
        pool.submit(() -> {
            try {
                AiService.Answer a = work.run(history, job::addProgress);
                if (conversationId != null) conversations.appendTurn(userId, conversationId, question, imageCount, a);
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

    /** 안경의 "새 대화" — 최근 질문 표시를 비웁니다(저장된 대화는 그대로). */
    public void reset(Long userId) {
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
        int turns = job.conversationId == null ? 0 : conversations.turnCount(job.conversationId);
        return new JobView(job.id, job.status, job.conversationId, job.question, job.project, job.imageCount,
                job.createdAt, progress,
                a == null ? null : a.summary(), a == null ? null : a.detail(), a == null ? List.of() : a.steps(),
                job.error, turns);
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(KEEP);
        jobs.values().removeIf(j -> j.status != Status.running && j.createdAt.isBefore(cutoff));
        latest.values().removeIf(id -> !jobs.containsKey(id));
    }
}
