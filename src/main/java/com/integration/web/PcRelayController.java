package com.integration.web;

import com.integration.ai.AiService;
import com.integration.domain.User;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 모멘텀에서 한 질문을 사용자의 PC(Claude Code)로 보내는 중계.
 *
 * PC에서는 scripts/pc-agent.mjs 가 같은 계정으로 로그인해 몇 초마다 PUT /agents/{id} 를 보냅니다(생존 신호 + 최근 세션 목록).
 * 그 응답으로 대기 중인 질문을 하나 받아 `claude -p --resume <세션>` 을 읽기 전용으로 실행하고, 답을 /jobs/{id}/result 로 돌려줍니다.
 * 서버는 PC에 아무것도 직접 실행하지 않고, 질문과 답만 메모리에 잠깐 들고 있습니다(재시작하면 비워짐).
 *
 *  - PUT  /api/ai/pc/agents/{agentId}   : PC 생존 신호(+세션 목록) → 대기 중인 질문 하나(없으면 null)
 *  - GET  /api/ai/pc/agents             : 지금 연결된 PC 목록
 *  - POST /api/ai/pc/jobs               : 질문 보내기 { agentId, sessionId?, cwd, question }
 *  - GET  /api/ai/pc/jobs               : 최근 질문들(상태·답)
 *  - POST /api/ai/pc/jobs/{id}/result   : PC가 답 돌려주기
 */
@RestController
@RequestMapping("/api/ai/pc")
public class PcRelayController {

    private static final Duration ONLINE = Duration.ofSeconds(30);
    private static final Duration STALE_RUNNING = Duration.ofMinutes(30);
    private static final int MAX_QUESTION = 8000;
    private static final int MAX_SESSIONS = 40;
    private static final int MAX_JOBS = 30;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public record SessionInfo(String sessionId, String cwd, String title, Instant updatedAt) {}
    public record AgentHello(String name, List<SessionInfo> sessions) {}
    public record Agent(String id, String name, Instant lastSeen, List<SessionInfo> sessions) {}
    public record JobRequest(String agentId, String sessionId, String cwd, String question) {}
    public record JobResult(String answer, String error, String sessionId) {}
    public record Job(long id, String agentId, String sessionId, String cwd, String question,
                      String status, String answer, String error, Instant createdAt, Instant finishedAt) {
        Job with(String st, String sid, String ans, String err, Instant fin) {
            return new Job(id, agentId, sid, cwd, question, st, ans, err, createdAt, fin);
        }
    }

    private final AiService ai;
    private final CurrentUserService currentUser;
    private final Map<Long, Map<String, Agent>> agents = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Job>> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public PcRelayController(AiService ai, CurrentUserService currentUser) {
        this.ai = ai;
        this.currentUser = currentUser;
    }

    @PutMapping("/agents/{agentId}")
    public Job hello(@PathVariable String agentId, @RequestBody AgentHello hello,
                     @RequestParam(defaultValue = "false") boolean busy) {
        Long uid = requireAllowed().getId();
        checkId(agentId, "agentId");
        List<SessionInfo> ss = hello.sessions() == null ? List.of()
                : hello.sessions().stream().filter(s -> s.sessionId() != null && ID.matcher(s.sessionId()).matches())
                    .limit(MAX_SESSIONS).toList();
        String name = hello.name() == null || hello.name().isBlank() ? agentId : clip(hello.name().strip(), 60);
        agents.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).put(agentId, new Agent(agentId, name, Instant.now(), ss));

        if (busy) return null; // 실행 중엔 생존 신호만
        Deque<Job> q = jobsOf(uid);
        synchronized (q) {
            for (Job j : q) {
                if (agentId.equals(j.agentId()) && "queued".equals(j.status())) {
                    replace(q, j, j.with("running", j.sessionId(), null, null, null));
                    return j;
                }
            }
        }
        return null;
    }

    @GetMapping("/agents")
    public List<Agent> agents() {
        Long uid = requireAllowed().getId();
        Instant cut = Instant.now().minus(ONLINE);
        return agents.getOrDefault(uid, Map.of()).values().stream()
                .filter(a -> a.lastSeen().isAfter(cut))
                .sorted(Comparator.comparing(Agent::name)).toList();
    }

    @PostMapping("/jobs")
    public Job ask(@RequestBody JobRequest req) {
        Long uid = requireAllowed().getId();
        checkId(req.agentId(), "agentId");
        Agent a = agents.getOrDefault(uid, Map.of()).get(req.agentId());
        if (a == null || a.lastSeen().isBefore(Instant.now().minus(ONLINE)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "그 PC가 지금 연결돼 있지 않아요. PC에서 pc-agent를 켜 주세요.");
        String q = req.question() == null ? "" : req.question().strip();
        if (q.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력하세요.");
        if (q.length() > MAX_QUESTION) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 너무 길어요.");
        // PC가 알려 준 세션·폴더만 쓸 수 있게(엉뚱한 폴더에서 실행되지 않도록)
        String sid = req.sessionId() == null || req.sessionId().isBlank() ? null : req.sessionId();
        String cwd;
        if (sid != null) {
            checkId(sid, "sessionId");
            cwd = a.sessions().stream().filter(s -> sid.equals(s.sessionId())).map(SessionInfo::cwd).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "그 PC에 이 세션이 없어요."));
        } else {
            cwd = req.cwd();
            if (cwd == null || a.sessions().stream().noneMatch(s -> cwd.equals(s.cwd())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "그 PC에서 쓰던 프로젝트 폴더만 고를 수 있어요.");
        }
        Job j = new Job(seq.incrementAndGet(), a.id(), sid, cwd, q, "queued", null, null, Instant.now(), null);
        Deque<Job> list = jobsOf(uid);
        synchronized (list) {
            list.addFirst(j);
            while (list.size() > MAX_JOBS) list.removeLast();
        }
        return j;
    }

    @GetMapping("/jobs")
    public List<Job> jobs() {
        Long uid = requireAllowed().getId();
        Deque<Job> list = jobsOf(uid);
        synchronized (list) {
            Instant stale = Instant.now().minus(STALE_RUNNING);
            for (Job j : List.copyOf(list)) {
                if ("running".equals(j.status()) && j.createdAt().isBefore(stale))
                    replace(list, j, j.with("error", j.sessionId(), null, "PC에서 답이 오지 않았어요(시간 초과).", Instant.now()));
            }
            return List.copyOf(list);
        }
    }

    @PostMapping("/jobs/{id}/result")
    public Job result(@PathVariable long id, @RequestBody JobResult r) {
        Long uid = requireAllowed().getId();
        Deque<Job> list = jobsOf(uid);
        synchronized (list) {
            Job j = list.stream().filter(x -> x.id() == id).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그 질문을 찾을 수 없어요."));
            String sid = r.sessionId() != null && ID.matcher(r.sessionId()).matches() ? r.sessionId() : j.sessionId();
            boolean failed = r.error() != null && !r.error().isBlank();
            Job done = j.with(failed ? "error" : "done", sid, clip(r.answer(), 200_000), failed ? clip(r.error(), 2000) : null, Instant.now());
            replace(list, j, done);
            return done;
        }
    }

    // ---------- 공통 ----------

    private Deque<Job> jobsOf(Long uid) {
        return jobs.computeIfAbsent(uid, k -> new ArrayDeque<>());
    }

    private static void replace(Deque<Job> list, Job old, Job neu) {
        List<Job> copy = new ArrayList<>(list);
        int i = copy.indexOf(old);
        if (i >= 0) copy.set(i, neu);
        list.clear();
        list.addAll(copy);
    }

    private static void checkId(String v, String what) {
        if (v == null || !ID.matcher(v).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " 형식이 올바르지 않아요.");
    }

    private static String clip(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }

    private User requireAllowed() {
        User user = currentUser.require();
        if (!ai.isEnabledFor(user.getEmail()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 계정은 Claude 기능을 쓸 수 없어요.");
        return user;
    }
}
