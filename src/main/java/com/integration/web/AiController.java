package com.integration.web;

import com.integration.ai.AiJobService;
import com.integration.ai.AiService;
import com.integration.ai.ConversationService;
import com.integration.ai.ProjectStore;
import com.integration.ai.SttService;
import com.integration.domain.User;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * G2 안경 "Claude에게 묻기". 로그인 + 허용 계정(AI_ALLOWED_EMAILS)만 쓸 수 있습니다.
 *
 *  - POST /api/ai/ask           : 바로 답(짧은 질문용, 100초 넘으면 Cloudflare가 끊음)
 *  - POST /api/ai/jobs          : 백그라운드로 시작 → GET /api/ai/jobs/{id} 로 진행 상황·답 확인
 *  - GET  /api/ai/jobs/latest   : 어느 화면에서 물었든 가장 최근 질문 (안경 앱이 따라감)
 *  - POST /api/ai/conversation/reset : 안경의 최근 질문 표시 비우기
 *  - /api/ai/conversations       : 프로젝트별 여러 대화(목록·새 대화·내역·이름 바꾸기·삭제), DB에 저장
 *  - /api/ai/projects           : Claude가 읽을 프로젝트(GitHub 클론, zip 업로드) 관리
 *  - POST /api/ai/transcribe    : 안경 마이크 음성(PCM) → 텍스트 (서버의 whisper)
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final int MAX_QUESTION_CHARS = 4000;
    private static final String PHOTO_DEFAULT_QUESTION =
            "사진 속 요구사항과 코드를 보고, 요구사항대로 동작하려면 어디를 어떻게 수정해야 하는지 알려줘.";

    private final AiService ai;
    private final AiJobService jobs;
    private final ProjectStore projects;
    private final SttService stt;
    private final ConversationService conversations;
    private final CurrentUserService currentUser;

    public AiController(AiService ai, AiJobService jobs, ProjectStore projects, SttService stt,
                        ConversationService conversations, CurrentUserService currentUser) {
        this.ai = ai;
        this.jobs = jobs;
        this.projects = projects;
        this.stt = stt;
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    /**
     * voice: 서버에 음성 인식(whisper)이 설치돼 있어 마이크로 물을 수 있는지.
     * engine: 구독(Claude Code) 상태와 API 키 유무 — 화면에 "구독으로 답하는 중 / 한도라 API" 표시용.
     */
    public record StatusResponse(boolean enabled, boolean voice, AiService.EngineState engine) {}
    public record TranscribeResponse(String text) {}

    /** 앱이 "Claude에게 묻기" 메뉴를 보여줄지 정합니다. 허용된 계정이 아니면 메뉴 자체가 숨겨집니다. */
    @GetMapping("/status")
    public StatusResponse status() {
        boolean enabled = ai.isEnabledFor(currentUser.require().getEmail());
        return new StatusResponse(enabled, enabled && stt.isAvailable(), enabled ? ai.engineState() : null);
    }

    /** audio: PCM 16bit little-endian 모노. rate: 샘플레이트(기본 16000). */
    @PostMapping("/transcribe")
    public TranscribeResponse transcribe(@RequestParam("audio") MultipartFile audio,
                                         @RequestParam(value = "rate", defaultValue = "16000") int rate) throws java.io.IOException {
        requireAllowed();
        return new TranscribeResponse(stt.transcribe(audio.getBytes(), rate));
    }

    /**
     * conversationId: 이어 갈 대화. 없으면 project로 새 대화를 만듭니다(대화의 프로젝트가 우선).
     * project: 읽게 할 프로젝트 이름(없으면 일반 질문).
     * history: 예전 앱(0.1.5)만 보냅니다. 있으면 저장하지 않고 그 기록을 씁니다.
     */
    public record AskRequest(String question, List<AiService.Turn> history, List<AiService.Image> images,
                             String project, Long conversationId) {}
    /** answer: 안경용 요약, detail: 폰용 전체 답(코드 등, 없으면 null), steps: 할 일로 저장할 작업 단계. */
    public record AskResponse(String answer, String detail, List<String> steps) {}
    public record JobStarted(String jobId, Long conversationId) {}

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req) {
        User user = requireAllowed();
        String q = question(req);
        AiService.Answer a = ai.ask(user.getId(), user.getEmail(), q, req.history(), req.images(), projectRef(req.project()), p -> {});
        return new AskResponse(a.summary(), a.detail(), a.steps());
    }

    @PostMapping("/jobs")
    public JobStarted startJob(@RequestBody AskRequest req) {
        User user = requireAllowed();
        String q = question(req);
        Long id = user.getId();
        String email = user.getEmail();
        boolean legacy = req.history() != null;
        Long conversationId = null;
        String projectName = req.project();
        if (!legacy) {
            ConversationService.ConversationView conv = req.conversationId() != null
                    ? conversations.get(id, req.conversationId())
                    : conversations.create(id, req.project());
            conversationId = conv.id();
            projectName = conv.project();
        }
        // 프로젝트 확인은 여기서(요청 스레드) 해서, 없는 프로젝트면 바로 에러로 알립니다.
        AiService.ProjectRef project = projectRef(projectName);
        int imageCount = req.images() == null ? 0 : req.images().size();
        String jobId = jobs.start(id, conversationId, q, projectName, imageCount, req.history(), (history, progress) ->
                ai.ask(id, email, q, history, req.images(), project, progress));
        return new JobStarted(jobId, conversationId);
    }

    /** 가장 최근 질문. 없으면 빈 응답(null). */
    @GetMapping("/jobs/latest")
    public AiJobService.JobView latestJob() {
        return jobs.latest(requireAllowed().getId());
    }

    @PostMapping("/conversation/reset")
    public void resetConversation() {
        jobs.reset(requireAllowed().getId());
    }

    @GetMapping("/jobs/{id}")
    public AiJobService.JobView job(@PathVariable String id) {
        return jobs.get(requireAllowed().getId(), id);
    }

    // ---------- 대화 ----------

    public record CreateConversationRequest(String project) {}
    public record RenameConversationRequest(String title) {}

    @GetMapping("/conversations")
    public List<ConversationService.ConversationView> listConversations() {
        return conversations.list(requireAllowed().getId());
    }

    @PostMapping("/conversations")
    public ConversationService.ConversationView createConversation(@RequestBody CreateConversationRequest req) {
        return conversations.create(requireAllowed().getId(), req.project());
    }

    /** PC Claude Code 세션 가져오기(동기화 스크립트용). 같은 externalId면 새로 생긴 질문만 붙습니다. */
    public record ImportConversationRequest(String externalId, String title, List<ConversationService.ImportedTurn> turns) {}

    @PostMapping("/conversations/import")
    public ConversationService.ImportResult importConversation(@RequestBody ImportConversationRequest req) {
        if (req.turns() != null && req.turns().size() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "한 번에 2000개까지 보낼 수 있어요.");
        return conversations.importTurns(requireAllowed().getId(), req.externalId(), req.title(), req.turns());
    }

    @GetMapping("/conversations/{id}")
    public ConversationService.Detail conversation(@PathVariable Long id) {
        return conversations.detail(requireAllowed().getId(), id);
    }

    @PatchMapping("/conversations/{id}")
    public ConversationService.ConversationView renameConversation(@PathVariable Long id, @RequestBody RenameConversationRequest req) {
        return conversations.rename(requireAllowed().getId(), id, req.title());
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(@PathVariable Long id) {
        conversations.delete(requireAllowed().getId(), id);
    }

    // ---------- 프로젝트 ----------

    public record AddGitRequest(String url, String name) {}

    @GetMapping("/projects")
    public List<ProjectStore.Project> listProjects() {
        requireAllowed();
        return projects.list();
    }

    /** privateRepos: 서버에 GitHub 토큰이 있어 비공개 저장소도 받을 수 있는지. */
    public record ProjectSettings(boolean privateRepos) {}

    @GetMapping("/projects/settings")
    public ProjectSettings projectSettings() {
        requireAllowed();
        return new ProjectSettings(projects.privateReposEnabled());
    }

    @PostMapping("/projects/git")
    public ProjectStore.Project addGit(@RequestBody AddGitRequest req) {
        requireAllowed();
        return projects.addGit(req.url(), req.name());
    }

    @PostMapping("/projects/{name}/sync")
    public ProjectStore.Project sync(@PathVariable String name) {
        requireAllowed();
        return projects.sync(name);
    }

    @PostMapping("/projects/upload")
    public ProjectStore.Project upload(@RequestParam("name") String name, @RequestParam("file") MultipartFile file) {
        requireAllowed();
        return projects.upload(name, file);
    }

    @DeleteMapping("/projects/{name}")
    public void deleteProject(@PathVariable String name) {
        requireAllowed();
        projects.delete(name);
    }

    // ---------- 공통 ----------

    private User requireAllowed() {
        User user = currentUser.require();
        if (!ai.isEnabledFor(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 계정은 Claude 기능을 쓸 수 없어요.");
        }
        return user;
    }

    private static String question(AskRequest req) {
        String q = req.question() == null ? "" : req.question().strip();
        boolean hasImages = req.images() != null && !req.images().isEmpty();
        if (q.isEmpty() && hasImages) q = PHOTO_DEFAULT_QUESTION;
        if (q.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력하세요.");
        if (q.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 너무 길어요(최대 " + MAX_QUESTION_CHARS + "자).");
        }
        return q;
    }

    private AiService.ProjectRef projectRef(String name) {
        if (name == null || name.isBlank()) return null;
        return new AiService.ProjectRef(name, projects.resolveReady(name));
    }
}
