package com.integration.ai;

import com.integration.domain.AiConversation;
import com.integration.domain.AiMessage;
import com.integration.repository.AiConversationRepository;
import com.integration.repository.AiMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Claude 대화 저장소. 프로젝트마다 대화를 여러 개 두고(클로드 데스크톱처럼), 서버를
 * 재시작해도 남습니다. 어느 화면(폰·PC·안경)에서 이어 물어도 같은 대화로 이어집니다.
 */
@Service
public class ConversationService {

    static final String NEW_TITLE = "새 대화";
    /** Claude에게 다시 보내는 앞 대화 수(질문·답 쌍). 길수록 맥락은 좋지만 비용이 늡니다. */
    static final int HISTORY_TURNS = 8;
    private static final int MAX_LIST = 200;

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;

    public ConversationService(AiConversationRepository conversations, AiMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /** externalId: PC Claude Code 등 밖에서 가져온 대화의 세션 ID(모멘텀에서 시작한 대화는 null). */
    public record ConversationView(Long id, String project, String title, Instant createdAt, Instant updatedAt, String externalId) {
        static ConversationView of(AiConversation c) {
            return new ConversationView(c.getId(), c.getProject(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(), c.getExternalId());
        }
    }

    /** engine: 누가 답했는지 — subscription(구독) / api / null(예전 기록). */
    public record TurnView(Long id, String question, int imageCount, String answer, String detail,
                           List<String> steps, String engine, Instant createdAt) {
        static TurnView of(AiMessage m) {
            return new TurnView(m.getId(), m.getQuestion(), m.getImageCount(), m.getAnswer(), m.getDetail(),
                    splitSteps(m.getSteps()), m.getEngine(), m.getCreatedAt());
        }
    }

    public record Detail(ConversationView conversation, List<TurnView> turns) {}

    @Transactional
    public ConversationView create(Long userId, String project) {
        if (project != null && !project.isBlank()) ProjectStore.checkName(project);
        AiConversation c = new AiConversation();
        c.setUserId(userId);
        c.setProject(project == null || project.isBlank() ? null : project);
        c.setTitle(NEW_TITLE);
        return ConversationView.of(conversations.save(c));
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list(Long userId) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, MAX_LIST))
                .stream().map(ConversationView::of).toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(Long userId, Long id) {
        return ConversationView.of(require(userId, id));
    }

    @Transactional(readOnly = true)
    public Detail detail(Long userId, Long id) {
        AiConversation c = require(userId, id);
        return new Detail(ConversationView.of(c),
                messages.findByConversationIdOrderByIdAsc(c.getId()).stream().map(TurnView::of).toList());
    }

    @Transactional
    public ConversationView rename(Long userId, Long id, String title) {
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목을 입력하세요.");
        AiConversation c = require(userId, id);
        c.setTitle(t.length() > 100 ? t.substring(0, 100) : t);
        return ConversationView.of(c);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        AiConversation c = require(userId, id);
        messages.deleteByConversationId(c.getId());
        conversations.delete(c);
    }

    /** Claude에게 다시 보낼 앞 대화(최근 HISTORY_TURNS쌍). 사진은 "[사진 N장 첨부]"로만 남습니다. */
    @Transactional(readOnly = true)
    public List<AiService.Turn> history(Long conversationId) {
        List<AiMessage> all = messages.findByConversationIdOrderByIdAsc(conversationId);
        List<AiService.Turn> out = new ArrayList<>();
        for (AiMessage m : all.subList(Math.max(0, all.size() - HISTORY_TURNS), all.size())) {
            String asked = m.getImageCount() > 0 ? m.getQuestion() + "\n[사진 " + m.getImageCount() + "장 첨부]" : m.getQuestion();
            out.add(new AiService.Turn("user", asked));
            out.add(new AiService.Turn("assistant",
                    new AiService.Answer(m.getAnswer(), m.getDetail(), splitSteps(m.getSteps())).full()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public int turnCount(Long conversationId) {
        return (int) messages.countByConversationId(conversationId);
    }

    /** 답을 대화에 붙이고, 첫 질문이면 제목으로 씁니다. 대화가 그사이 지워졌으면 아무것도 안 합니다. */
    @Transactional
    public void appendTurn(Long userId, Long conversationId, String question, int imageCount, AiService.Answer a) {
        AiConversation c = conversations.findByIdAndUserId(conversationId, userId).orElse(null);
        if (c == null) return;
        AiMessage m = new AiMessage();
        m.setConversationId(c.getId());
        m.setUserId(userId);
        m.setQuestion(question);
        m.setImageCount(imageCount);
        m.setAnswer(a.summary());
        m.setDetail(a.detail());
        m.setSteps(a.steps().isEmpty() ? null : String.join("\n", a.steps()));
        m.setEngine(a.engine());
        messages.save(m);
        c.setUpdatedAt(Instant.now());
        if (NEW_TITLE.equals(c.getTitle())) c.setTitle(titleFrom(question, imageCount));
    }

    // ---------- 밖에서 가져오기 (PC Claude Code 세션 동기화) ----------

    /** 가져온 질문·답 한 쌍. at: 질문한 때(없으면 지금). */
    public record ImportedTurn(String question, String answer, Instant at) {}
    public record ImportResult(ConversationView conversation, int added) {}

    static final String IMPORT_ENGINE = "claude-code";
    private static final int SUMMARY_CHARS = 280;
    private static final int MAX_TEXT = 200_000;

    /**
     * externalId(세션 ID)로 대화를 찾거나 만들고, 이미 들어 있는 질문 수 뒤의 것만 붙입니다.
     * 그래서 같은 세션을 여러 번 보내도 중복되지 않고, 이어진 부분만 늘어납니다.
     */
    @Transactional
    public ImportResult importTurns(Long userId, String externalId, String title, List<ImportedTurn> turns) {
        String ext = externalId == null ? "" : externalId.strip();
        if (ext.isEmpty() || ext.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalId가 필요합니다(80자 이하).");
        AiConversation c = conversations.findFirstByUserIdAndExternalId(userId, ext).orElseGet(() -> {
            AiConversation n = new AiConversation();
            n.setUserId(userId);
            n.setExternalId(ext);
            n.setTitle(NEW_TITLE);
            return conversations.save(n);
        });
        int have = (int) messages.countByConversationId(c.getId());
        int added = 0;
        Instant last = null;
        List<ImportedTurn> list = turns == null ? List.of() : turns;
        for (int i = have; i < list.size(); i++) {
            ImportedTurn t = list.get(i);
            String q = clip(t.question());
            String a = clip(t.answer());
            AiMessage m = new AiMessage();
            m.setConversationId(c.getId());
            m.setUserId(userId);
            m.setQuestion(q.isEmpty() ? "(빈 질문)" : q);
            m.setAnswer(summary(a));
            m.setDetail(a.length() > SUMMARY_CHARS ? a : null);
            m.setEngine(IMPORT_ENGINE);
            if (t.at() != null) m.setCreatedAt(t.at());
            messages.save(m);
            last = t.at() != null ? t.at() : Instant.now();
            added++;
        }
        // 제목은 처음 가져올 때만 정합니다. 사용자가 이름을 바꾼 뒤 다시 동기화해도 덮어쓰지 않도록.
        String tt = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        boolean fresh = have == 0 || NEW_TITLE.equals(c.getTitle());
        if (!fresh) { /* 기존 제목 유지 */ }
        else if (!tt.isEmpty()) c.setTitle(tt.length() > 100 ? tt.substring(0, 100) : tt);
        else if (NEW_TITLE.equals(c.getTitle()) && !list.isEmpty()) c.setTitle(titleFrom(list.get(0).question() == null ? "" : list.get(0).question(), 0));
        if (last != null) c.setUpdatedAt(last);
        return new ImportResult(ConversationView.of(c), added);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "\n…(잘림)" : t;
    }

    /** 안경·목록용 짧은 답: 첫 문단에서 SUMMARY_CHARS자까지. */
    static String summary(String a) {
        if (a.isEmpty()) return "(답 없음)";
        String first = a.split("\\n\\s*\\n", 2)[0].strip();
        return first.length() > SUMMARY_CHARS ? first.substring(0, SUMMARY_CHARS) + "…" : first;
    }

    static String titleFrom(String question, int imageCount) {
        String q = question.replaceAll("\\s+", " ").strip();
        if (q.isEmpty()) q = imageCount > 0 ? "사진 " + imageCount + "장" : NEW_TITLE;
        return q.length() > 40 ? q.substring(0, 40) + "..." : q;
    }

    private AiConversation require(Long userId, Long id) {
        // 남의 대화는 없는 것처럼 보입니다.
        return conversations.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화가 없어요."));
    }

    private static List<String> splitSteps(String steps) {
        return steps == null || steps.isBlank() ? List.of() : Arrays.stream(steps.split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
