package com.integration.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolChoiceAuto;
import com.anthropic.models.beta.messages.BetaToolChoiceNone;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * G2 안경의 "Claude에게 묻기" — 질문을 Claude API로 보내고 답을 텍스트로 돌려줍니다.
 *
 * 브라우저/안경 앱이 Claude API를 직접 부르지 않는 이유: API 키를 프론트(.ehpk 포함)에
 * 넣으면 누구나 꺼내 쓸 수 있습니다. 인증은 우리 JWT로 하고, 키는 서버 밖으로 나가지 않습니다.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 이전 대화는 최근 몇 턴만 보냅니다 (질문·답 한 쌍 = 2턴). */
    static final int MAX_HISTORY_TURNS = 10;
    /** 한 번에 받는 사진 수. 코드가 길면 여러 장으로 나눠 찍습니다. */
    static final int MAX_IMAGES = 6;
    /** 사진 한 장 상한(디코딩 후). Claude API 한도는 5MB이고, 앱은 보통 0.5MB 안쪽으로 줄여 보냅니다. */
    private static final int MAX_IMAGE_BYTES = 4_500_000;
    /** 답변 중 "안경용 요약"과 "폰용 전체 답"을 나누는 표시. */
    static final String DETAIL_MARK = "@@상세@@";
    /** 답변 끝의 "작업 단계" 목록 표시. 이 목록을 할 일로 저장합니다. */
    static final String STEPS_MARK = "@@할일@@";
    static final int MAX_STEPS = 8;
    /** todo.text 컬럼(varchar 255)과 안경 한 줄 폭을 고려한 단계 한 줄 상한. */
    static final int MAX_STEP_CHARS = 80;

    /** 프로젝트 설명 파일 상한. 이보다 크면 잘라서 넣고 경고를 남깁니다. */
    private static final int MAX_CONTEXT_CHARS = 400_000;

    /** 안경 화면(576×288, 초록 단색, 한 쪽에 몇 줄)에 맞춘 답변 규칙. */
    private static final String RULES = """
            너는 Even Realities G2 스마트 안경에 답을 띄워 주는 도우미다.
            사용자는 개발자이고 주로 Java, Python, 데이터베이스(SQL), 데이터 사이언스를 묻는다.

            답변 규칙:
            - 한국어로, 핵심부터 짧게 답한다. 보통 300자 안쪽, 길어도 800자를 넘기지 않는다.
            - 마크다운을 쓰지 않는다(#, **, 표, 코드 블록 ``` 금지). 안경은 일반 텍스트만 보여 준다.
            - 목록은 줄바꿈과 "- "로만 쓴다.
            - 코드는 꼭 필요할 때만 몇 줄로, 들여쓰기 2칸으로 쓴다.
            - 특수 기호(✓ ▶ → ★ 등)는 안경 글꼴에 없어서 사라지므로 ASCII 기호만 쓴다(->, [x]).
            - 파일·클래스·메서드 이름은 정확히 쓴다. 모르면 추측하지 말고 모른다고 말한다.

            사진이 오면:
            - 사용자가 폐쇄망 PC 모니터를 폰으로 찍은 요구사항 문서나 코드다. 글자를 꼼꼼히 읽는다.
            - 흐리거나 잘려서 못 읽은 부분은 지어내지 말고 "OO 부분이 안 보여요, 다시 찍어 주세요"라고 말한다.
            - 요구사항을 만족하려면 어느 파일·클래스·메서드의 어디를 어떻게 바꾸는지 답한다.
            - 사진에 없는 코드가 더 필요하면 어떤 파일/메서드를 더 찍어 달라고 말한다.

            답이 길거나 코드 수정이 들어가면 두 부분으로 나눈다:
            1) 먼저 안경용 요약: 무엇을 어디서 바꾸는지 5줄 안쪽.
            2) 그다음 줄에 @@상세@@ 한 줄만 쓰고, 그 아래에 폰 화면용 전체 답을 쓴다.
               전체 답에는 바꿀 코드를 빠짐없이 쓴다(수정 전/후, 또는 바뀐 메서드 전체).
               이 부분은 길어도 되고 800자 제한이 없다. 마크다운 금지는 그대로다.
            짧은 질문이면 @@상세@@ 없이 요약만 쓴다.

            코드 수정이나 작업이 필요한 답이면 맨 끝에 작업 단계를 붙인다(사용자가 할 일 목록으로 저장한다):
            @@할일@@ 한 줄만 쓰고, 그 아래에 실제로 손댈 순서대로 한 줄에 하나씩 "- "로 시작해 쓴다.
            - 최대 8개, 한 줄 40자 안쪽, 파일/메서드 이름을 넣는다. 예: - OrderService.save()에 수량 검증 추가
            - 테스트 작성이 필요하면 마지막 단계로 넣는다.
            단순 설명 질문이면 @@할일@@을 쓰지 않는다.
            """;

    /** 프로젝트를 읽으며 답할 때, 도구를 몇 번까지 쓰게 할지(대화 왕복 수). */
    static final int MAX_TOOL_TURNS = 25;

    private static final String PROJECT_RULES = """

            지금 사용자의 프로젝트 "%s" 폴더를 읽을 수 있다. 도구: list_files(폴더 구조), read_file(파일 읽기), search(검색).
            - 프로젝트에 관한 질문이면 추측하지 말고 먼저 search / list_files로 찾고 read_file로 실제 코드를 읽은 뒤 답한다.
            - 답에는 실제로 읽은 파일 경로와 줄 번호를 쓴다(예: src/g2/screens.js:120).
            - 도구는 꼭 필요한 만큼만 쓴다. 관련 파일을 몇 개 읽었으면 답한다.
            - 이 도구로는 읽기만 된다. 수정은 사용자가 직접 하므로, 바꿀 코드를 @@상세@@ 아래에 정확히 쓴다.
            """;

    /** 프로젝트를 고르지 않고 물을 때. "zip 올렸는데 왜 못 읽어?" 같은 혼동을 안내로 풉니다. */
    private static final String NO_PROJECT_RULES = """

            지금은 프로젝트가 선택되지 않아 서버에 올린 코드(GitHub 저장소·zip)를 읽을 수 없다.
            사용자가 코드·zip·프로젝트 파일을 보고 답해 달라고 하면, 추측해서 답하지 말고 이렇게 안내한다:
            "폰이나 PC 화면의 '프로젝트' 목록에서 그 프로젝트를 고른 뒤 다시 물어봐 주세요.
             아직 안 올렸다면 '관리'에서 zip 업로드나 GitHub 추가를 먼저 해 주세요."
            """;

    private static final List<BetaTool> PROJECT_TOOLS = List.of(
            tool("list_files", "프로젝트 폴더 구조를 본다. 폴더는 끝에 / 가 붙는다. node_modules·build 같은 폴더는 빠진다.",
                    Map.of("path", Map.of("type", "string", "description", "프로젝트 루트 기준 폴더 경로. 루트는 ."),
                            "depth", Map.of("type", "integer", "description", "몇 단계까지 볼지 1~4 (기본 2)")),
                    List.of("path")),
            tool("read_file", "파일을 줄 번호와 함께 읽는다. 한 번에 최대 400줄.",
                    Map.of("path", Map.of("type", "string", "description", "프로젝트 루트 기준 파일 경로"),
                            "start_line", Map.of("type", "integer", "description", "시작 줄(1부터, 기본 1)"),
                            "end_line", Map.of("type", "integer", "description", "끝 줄(생략하면 start_line부터 400줄)")),
                    List.of("path")),
            tool("search", "프로젝트 파일 내용을 검색한다. 결과는 경로:줄: 내용 형식. 기본은 대소문자를 무시한 문자열 검색.",
                    Map.of("query", Map.of("type", "string", "description", "찾을 문자열(또는 regex=true일 때 정규식)"),
                            "path", Map.of("type", "string", "description", "검색할 폴더(기본 . = 전체)"),
                            "regex", Map.of("type", "boolean", "description", "정규식으로 검색할지(기본 false)")),
                    List.of("query")));

    private static BetaTool tool(String name, String description, Map<String, Object> props, List<String> required) {
        BetaTool.InputSchema.Properties.Builder pb = BetaTool.InputSchema.Properties.builder();
        props.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(BetaTool.InputSchema.builder().properties(pb.build()).required(required).build())
                .build();
    }

    private final AnthropicClient client;   // 키가 없으면 null — 기능 꺼짐 (서버 주인 전용)
    private final String baseUrl;            // 테스트용 가짜 서버 주소(보통 빈 값)
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final String effortName;   // Claude Code --effort 에 그대로 넘김
    private final String systemPrompt;
    private final int dailyLimit;
    private final int globalDailyLimit;
    private final Set<String> allowedEmails;
    private final ZoneId zone;
    /** 서버 전체 오늘 사용 횟수 (모든 사용자 합). */
    private final AtomicReference<DailyCount> globalUsage = new AtomicReference<>(new DailyCount(LocalDate.MIN, 0));

    /** userId → 오늘 사용 횟수. 서버 한 대 기준의 간단한 비용 안전장치입니다. */
    private final Map<Long, DailyCount> usage = new ConcurrentHashMap<>();

    private record DailyCount(LocalDate day, int count) {}

    public record Turn(String role, String text) {}

    /** 사진: mediaType(image/jpeg 등) + base64 데이터. */
    public record Image(String mediaType, String data) {}

    /** summary: 안경에 띄울 짧은 답. detail: 폰에 띄울 전체 답(없으면 null). */
    /** engine: 누가 답했는지 — "subscription"(구독, Claude Code) 또는 "api"(API 키). */
    public record Answer(String summary, String detail, List<String> steps, String engine) {
        public Answer(String summary, String detail, List<String> steps) { this(summary, detail, steps, "api"); }
        Answer(String summary, String detail) { this(summary, detail, List.of()); }

        public Answer withEngine(String e) { return new Answer(summary, detail, steps, e); }

        /** 이어서 묻기용으로 대화 기록에 남길 원문. */
        public String full() {
            String s = detail == null ? summary : summary + "\n" + DETAIL_MARK + "\n" + detail;
            if (!steps.isEmpty()) s += "\n" + STEPS_MARK + "\n- " + String.join("\n- ", steps);
            return s;
        }
    }

    // 생성자가 두 개라 스프링이 쓸 쪽을 표시합니다.
    @org.springframework.beans.factory.annotation.Autowired
    public AiService(
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:claude-opus-5-5}") String model,
            @Value("${app.ai.effort:medium}") String effort,
            @Value("${app.ai.context-file:}") String contextFile,
            @Value("${app.ai.daily-limit:100}") int dailyLimit,
            @Value("${app.ai.global-daily-limit:300}") int globalDailyLimit,
            @Value("${app.ai.allowed-emails:}") String allowedEmails,
            @Value("${app.push.zone:Asia/Seoul}") String zone) {
        this(apiKey, model, effort, contextFile, dailyLimit, globalDailyLimit, allowedEmails, zone, "");
    }

    /** baseUrl: 테스트에서 가짜 Claude 서버를 가리킬 때만 씁니다. 비우면 api.anthropic.com. */
    AiService(String apiKey, String model, String effort, String contextFile, int dailyLimit,
              int globalDailyLimit, String allowedEmails, String zone, String baseUrl) {
        AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // 사진 여러 장 + 긴 코드 답은 1분을 넘기기도 합니다.
                .timeout(Duration.ofSeconds(180));
        if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl).maxRetries(0);
        this.client = apiKey.isBlank() ? null : cb.build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.effort = BetaOutputConfig.Effort.of(effort);
        this.effortName = effort;
        this.dailyLimit = dailyLimit;
        this.globalDailyLimit = globalDailyLimit;
        this.allowedEmails = parseEmails(allowedEmails);
        this.zone = ZoneId.of(zone);
        this.systemPrompt = RULES + loadContext(contextFile);
        if (client == null) log.info("ANTHROPIC_API_KEY 미설정 — /api/ai/ask 비활성화");
        else if (this.allowedEmails.isEmpty()) log.warn("AI_ALLOWED_EMAILS 미설정 — 키는 있지만 아무도 /api/ai/ask 를 쓸 수 없습니다");
    }

    static Set<String> parseEmails(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(e -> e.strip().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 서버 주인(허용 목록) — 서버 키·구독을 쓰는 계정. */
    public boolean isOwner(String email) {
        boolean anyEngine = client != null || (claudeCode != null && claudeCode.installed());
        return anyEngine && email != null && allowedEmails.contains(email.strip().toLowerCase(Locale.ROOT));
    }

    /** 이 사용자가 Claude 기능을 쓸 수 있는지: 서버 주인이거나, 자기 API 키를 등록한 사용자. 앱이 메뉴를 숨기는 데 씁니다. */
    public boolean isEnabledFor(String email) {
        return isOwner(email) || (userKeys != null && userKeys.hasKey(email));
    }

    /** 다른 사용자가 등록한 자기 API 키(BYOK). 없으면(테스트 등) 주인만 씁니다. */
    private UserApiKeyService userKeys;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUserKeys(UserApiKeyService userKeys) {
        this.userKeys = userKeys;
    }

    /** 사용자 키별 클라이언트(키 해시 → 클라이언트). 많아지면 비웁니다. */
    private final Map<String, AnthropicClient> userClients = new ConcurrentHashMap<>();

    private AnthropicClient clientFor(String key) {
        String h;
        try {
            h = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (userClients.size() > 50) userClients.clear();
        return userClients.computeIfAbsent(h, x -> {
            AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(180));
            if (baseUrl != null && !baseUrl.isBlank()) cb.baseUrl(baseUrl).maxRetries(0);
            return cb.build();
        });
    }

    /** 구독(Claude Code)으로 먼저 답하기. 없으면(테스트 등) API만 씁니다. */
    private ClaudeCodeEngine claudeCode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setClaudeCode(ClaudeCodeEngine claudeCode) {
        this.claudeCode = claudeCode;
    }

    /** 화면 표시용: 구독 상태와 API 키 유무. */
    public record EngineState(ClaudeCodeEngine.State subscription, boolean api) {}

    public EngineState engineState() {
        return new EngineState(claudeCode == null ? null : claudeCode.state(), client != null);
    }

    private static String loadContext(String contextFile) {
        if (contextFile == null || contextFile.isBlank()) return "";
        try {
            String text = Files.readString(Path.of(contextFile));
            if (text.length() > MAX_CONTEXT_CHARS) {
                log.warn("AI 컨텍스트 파일이 {}자라 앞 {}자만 씁니다: {}", text.length(), MAX_CONTEXT_CHARS, contextFile);
                text = text.substring(0, MAX_CONTEXT_CHARS);
            }
            log.info("AI 컨텍스트 파일 로드: {} ({}자)", contextFile, text.length());
            return "\n아래는 사용자의 프로젝트 자료다. 프로젝트 질문에는 이 자료를 근거로 답한다.\n"
                    + "<project>\n" + text + "\n</project>\n";
        } catch (IOException e) {
            // 파일이 없어도 일반 질문은 답할 수 있게 기동은 계속합니다.
            log.warn("AI 컨텍스트 파일을 읽지 못했습니다: {} ({})", contextFile, e.getMessage());
            return "";
        }
    }

    public Answer ask(Long userId, String email, String question, List<Turn> history, List<Image> images) {
        return ask(userId, email, question, history, images, null, p -> {});
    }

    /**
     * projectDir가 있으면 Claude가 그 폴더를 도구로 읽으며 답합니다(여러 번 왕복).
     * progress에는 "읽기: src/App.jsx" 같은 진행 상황이 실시간으로 들어옵니다.
     */
    public Answer ask(Long userId, String email, String question, List<Turn> history, List<Image> images,
                      ProjectRef project, Consumer<String> progress) {
        List<BetaContentBlockParam> imageBlocks = toImageBlocks(images);

        // ── 다른 사용자: 자기 키로만. 서버 주인의 키·구독(client, claudeCode)은 절대 쓰지 않습니다. ──
        if (!isOwner(email)) {
            String key = userKeys == null ? null : userKeys.keyFor(userId).orElse(null);
            if (key == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Claude를 쓰려면 ⋯ → Claude API 키에서 내 Anthropic API 키를 등록해 주세요.");
            }
            if (project != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트(코드 읽기) 기능은 아직 서버 주인 계정만 쓸 수 있어요.");
            }
            takeUserQuota(userId);
            return askApi(clientFor(key), true, question, history, imageBlocks, null, progress).withEngine("byok");
        }

        if (client == null && (claudeCode == null || !claudeCode.installed())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 기능이 꺼져 있어요.");
        }

        // 1) 구독(Claude Code)으로 먼저. 한도에 걸리거나 쓸 수 없으면 2) API 키로.
        if (claudeCode != null && claudeCode.ready()) {
            try {
                return claudeCode.ask(model, effortName, systemPrompt, question, history, images,
                        project == null ? null : project.name(), project == null ? null : project.dir(), progress);
            } catch (ClaudeCodeEngine.Unavailable e) {
                if (client == null) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            e.limit ? "구독 한도에 걸렸고 API 키가 없어요. 한도가 풀린 뒤 다시 물어봐 주세요." : "Claude Code로 답하지 못했어요: " + e.getMessage());
                }
                progress.accept(e.limit ? "구독 한도 → API로 답해요" : "구독을 못 써서 API로 답해요");
            }
        } else if (client == null) {
            ClaudeCodeEngine.State st = claudeCode == null ? null : claudeCode.state();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    st != null && st.pausedUntil() != null
                            ? "구독을 쉬는 중이고(" + st.reason() + ") API 키가 없어요. 잠시 후 다시 물어봐 주세요."
                            : "AI 기능이 꺼져 있어요.");
        }
        // API 비용 안전장치(하루 횟수)는 API로 답할 때만 셉니다.
        takeQuota(userId);
        return askApi(client, false, question, history, imageBlocks, project, progress);
    }

    /** API 키로 답하기(주인 키 또는 사용자 키). byok: 사용자 키면 오류 문구를 그 사람에게 맞게. */
    private Answer askApi(AnthropicClient c, boolean byok, String question, List<Turn> history,
                          List<BetaContentBlockParam> imageBlocks, ProjectRef project, Consumer<String> progress) {
        List<BetaTextBlockParam> system = new ArrayList<>();
        // 규칙 + 프로젝트 자료는 매번 같으므로 캐시합니다 (두 번째 질문부터 싸고 빠름).
        system.add(BetaTextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build());
        ProjectTools tools = null;
        if (project == null) {
            system.add(BetaTextBlockParam.builder().text(NO_PROJECT_RULES).build());
        } else {
            system.add(BetaTextBlockParam.builder().text(PROJECT_RULES.formatted(project.name())).build());
            try {
                tools = new ProjectTools(project.dir());
            } catch (java.io.IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트 폴더를 열 수 없어요.");
            }
        }

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                // 생각(thinking) 토큰도 여기에 포함되므로 답 길이보다 넉넉하게 잡습니다.
                .maxTokens(16000L)
                .systemOfBetaTextBlockParams(system)
                // 도구 결과가 쌓이는 대화 전체를 자동 캐시합니다(왕복할수록 앞부분은 캐시에서 읽음).
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                // 안전 분류기가 거절하면 서버가 알아서 다른 모델로 다시 답하게 합니다.
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));

        for (Turn t : sanitize(history)) {
            if ("user".equals(t.role())) params.addUserMessage(t.text());
            else params.addAssistantMessage(t.text());
        }
        if (imageBlocks.isEmpty()) {
            params.addUserMessage(question);
        } else {
            // 이미지를 질문 앞에 둡니다(문서 → 질문 순서가 답이 더 정확합니다).
            List<BetaContentBlockParam> blocks = new ArrayList<>(imageBlocks);
            blocks.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(question).build()));
            params.addUserMessageOfContentBlockParams(blocks);
        }

        if (tools != null) {
            PROJECT_TOOLS.forEach(params::addTool);
            params.toolChoice(BetaToolChoiceAuto.builder().build());
        }

        BetaMessage res = call(c, byok, params);
        for (int turn = 1; tools != null && BetaStopReason.TOOL_USE.equals(res.stopReason().orElse(null)); turn++) {
            params.addMessage(res.toParam());
            List<BetaContentBlockParam> results = new ArrayList<>();
            for (var block : res.content()) {
                BetaToolUseBlock use = block.toolUse().orElse(null);
                if (use == null) continue;
                results.add(runTool(tools, use, progress));
            }
            params.addUserMessageOfContentBlockParams(results);
            if (turn >= MAX_TOOL_TURNS) {
                // 더 읽지 말고 지금까지 읽은 것으로 답하게 합니다.
                progress.accept("정리하는 중");
                params.toolChoice(BetaToolChoiceNone.builder().build());
            }
            res = call(c, byok, params);
        }
        return toAnswer(res);
    }

    private BetaMessage call(AnthropicClient c, boolean byok, MessageCreateParams.Builder params) {
        try {
            return c.beta().messages().create(params.build());
        } catch (RateLimitException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 물어봐 주세요.");
        } catch (AnthropicServiceException e) {
            if (byok && (e.statusCode() == 401 || e.statusCode() == 403)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "등록한 API 키가 거절됐어요. ⋯ → Claude API 키에서 다시 등록해 주세요.");
            }
            if (byok && e.statusCode() == 400 && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("credit")) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "API 키 계정의 크레딧이 부족해요. Anthropic Console에서 충전해 주세요.");
            }
            log.error("Claude API 오류 status={}", e.statusCode(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude 응답을 받지 못했어요.");
        } catch (AnthropicIoException e) {
            log.error("Claude API 연결 실패", e);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Claude에 연결할 수 없어요.");
        }
    }

    public record ProjectRef(String name, Path dir) {}

    private static BetaContentBlockParam runTool(ProjectTools tools, BetaToolUseBlock use, Consumer<String> progress) {
        Map<?, ?> in;
        try {
            in = use.input(Map.class);
        } catch (RuntimeException e) {
            in = Map.of();
        }
        String path = str(in.get("path"), ".");
        String out;
        boolean error = false;
        try {
            out = switch (use.name()) {
                case "list_files" -> {
                    progress.accept("목록: " + path);
                    yield tools.listFiles(path, num(in.get("depth"), 2));
                }
                case "read_file" -> {
                    progress.accept("읽기: " + path);
                    yield tools.readFile(path, num(in.get("start_line"), 1), num(in.get("end_line"), 0));
                }
                case "search" -> {
                    String q = str(in.get("query"), "");
                    progress.accept("검색: " + q);
                    yield tools.search(q, path, Boolean.TRUE.equals(in.get("regex")));
                }
                default -> throw new ProjectTools.ToolException("모르는 도구: " + use.name());
            };
        } catch (ProjectTools.ToolException e) {
            out = e.getMessage();
            error = true;
        }
        return BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                .toolUseId(use.id())
                .content(out)
                .isError(error)
                .build());
    }

    private static String str(Object v, String dflt) {
        return v instanceof String s && !s.isBlank() ? s : dflt;
    }

    private static int num(Object v, int dflt) {
        return v instanceof Number n ? n.intValue() : dflt;
    }

    private Answer toAnswer(BetaMessage res) {
        BetaStopReason stop = res.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            return new Answer("이 질문에는 답할 수 없어요.", null);
        }
        String answer = res.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .strip();
        if (BetaStopReason.MAX_TOKENS.equals(stop)) answer += "\n...(답이 길어서 잘렸어요)";
        if (answer.isEmpty()) return new Answer("답을 만들지 못했어요. 다시 물어봐 주세요.", null);
        return split(answer);
    }

    /** "요약 @@상세@@ 전체 @@할일@@ 단계" 형식을 나눕니다. 표시가 없으면 전부 요약입니다. */
    static Answer split(String answer) {
        List<String> steps = List.of();
        int s = answer.lastIndexOf(STEPS_MARK);
        if (s >= 0) {
            steps = parseSteps(answer.substring(s + STEPS_MARK.length()));
            answer = answer.substring(0, s).strip();
        }
        int i = answer.indexOf(DETAIL_MARK);
        if (i < 0) return new Answer(answer.isEmpty() ? "작업 단계를 할 일로 저장할 수 있어요." : answer, null, steps);
        String summary = answer.substring(0, i).strip();
        String detail = answer.substring(i + DETAIL_MARK.length()).strip();
        if (summary.isEmpty()) summary = "전체 답은 폰 화면에서 보세요.";
        return new Answer(summary, detail.isEmpty() ? null : detail, steps);
    }

    static List<String> parseSteps(String block) {
        return block.lines()
                .map(String::strip)
                // "- ", "1. ", "1) " 같은 머리표를 떼어 냅니다.
                .map(l -> l.replaceFirst("^(?:[-*]|\\d+[.)])\\s*", "").strip())
                .filter(l -> !l.isEmpty())
                .map(l -> l.length() > MAX_STEP_CHARS ? l.substring(0, MAX_STEP_CHARS - 3) + "..." : l)
                .limit(MAX_STEPS)
                .toList();
    }

    private static List<BetaContentBlockParam> toImageBlocks(List<Image> images) {
        if (images == null || images.isEmpty()) return List.of();
        if (images.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 한 번에 " + MAX_IMAGES + "장까지 보낼 수 있어요.");
        }
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        for (Image img : images) {
            BetaBase64ImageSource.MediaType type = switch (img.mediaType() == null ? "" : img.mediaType()) {
                case "image/jpeg" -> BetaBase64ImageSource.MediaType.IMAGE_JPEG;
                case "image/png" -> BetaBase64ImageSource.MediaType.IMAGE_PNG;
                case "image/webp" -> BetaBase64ImageSource.MediaType.IMAGE_WEBP;
                case "image/gif" -> BetaBase64ImageSource.MediaType.IMAGE_GIF;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 사진 형식이에요(jpeg/png/webp).");
            };
            String data = img.data() == null ? "" : img.data();
            int bytes;
            try {
                bytes = Base64.getDecoder().decode(data).length;
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 데이터가 올바르지 않아요.");
            }
            if (bytes == 0 || bytes > MAX_IMAGE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 한 장은 4.5MB 이하여야 해요.");
            }
            blocks.add(BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                    .source(BetaBase64ImageSource.builder().mediaType(type).data(data).build())
                    .build()));
        }
        return blocks;
    }

    /** user로 시작해 user/assistant가 번갈아 나오고 assistant로 끝나는 최근 턴만 남깁니다. */
    static List<Turn> sanitize(List<Turn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<Turn> clean = history.stream()
                .filter(t -> t != null && t.text() != null && !t.text().isBlank())
                .filter(t -> "user".equals(t.role()) || "assistant".equals(t.role()))
                .toList();
        int from = Math.max(0, clean.size() - MAX_HISTORY_TURNS);
        List<Turn> recent = clean.subList(from, clean.size());
        int start = 0;
        while (start < recent.size() && !"user".equals(recent.get(start).role())) start++;
        recent = recent.subList(start, recent.size());
        for (int i = 0; i < recent.size(); i++) {
            String expected = i % 2 == 0 ? "user" : "assistant";
            if (!expected.equals(recent.get(i).role())) return List.of();
        }
        if (recent.size() % 2 == 1) recent = recent.subList(0, recent.size() - 1);
        return recent;
    }

    // ---------- 빠른 한 번 호출 (통역·답 추천) ----------
    // 문장 하나를 옮기거나 대답 한 줄을 추천하는 짧은 일이라 빠르고 싼 모델(기본 Haiku)을 씁니다.
    // 주인: 서버 API 키 → 없으면 구독(Claude Code). 다른 사용자: 자기 키로만(주인 쪽으로 넘어가지 않음).
    private String quickModel = "claude-haiku-5-5";
    private int quickDailyLimit = 3000;
    private final Map<Long, DailyCount> quickUsage = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQuickModel(@Value("${app.ai.quick-model:claude-haiku-5-5}") String m) { if (m != null && !m.isBlank()) this.quickModel = m; }

    public String quick(Long userId, String email, String system, String prompt, int maxTokens) {
        LocalDate today = LocalDate.now(zone);
        DailyCount n = quickUsage.compute(userId, (id, c) ->
                c == null || !c.day().equals(today) ? new DailyCount(today, 1) : new DailyCount(today, c.count() + 1));
        if (n.count() > quickDailyLimit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘 통역 횟수를 다 썼어요.");

        AnthropicClient c;
        boolean owner = isOwner(email);
        // 내 키가 등록돼 있으면 주인이어도 그 키로(통역은 빠른 응답이 중요해서 CLI보다 API가 나아요)
        String myKey = userKeys == null ? null : userKeys.keyFor(userId).orElse(null);
        boolean byok = !owner || myKey != null;
        if (myKey != null) {
            c = clientFor(myKey);
        } else if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "통역을 쓰려면 ⋯ → Claude API 키에서 내 API 키를 등록해 주세요.");
        } else if (client != null) {
            c = client;
        } else if (claudeCode != null && claudeCode.ready()) {
            try {
                Answer a = claudeCode.ask(quickModel, "low", system, prompt, List.of(), List.of(), null, null, p -> {});
                return a.detail() == null ? a.summary() : a.summary() + "\n" + a.detail();
            } catch (ClaudeCodeEngine.Unavailable e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 통역을 할 수 없어요: " + e.getMessage());
            }
        } else {
            ClaudeCodeEngine.State st = claudeCode == null ? null : claudeCode.state();
            String why = st == null || !st.installed() ? "서버에서 Claude Code를 찾지 못했어요"
                    : st.pausedUntil() != null ? "구독을 잠시 쉬는 중이에요(" + st.reason() + ")" : "구독을 쓸 수 없어요";
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    why + ". ⋯ → Claude API 키에 내 키를 등록하면 그 키로 통역해요.");
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(quickModel)
                .maxTokens((long) maxTokens)
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(system)
                        .cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
                .addUserMessage(prompt);
        BetaMessage res = call(c, byok, params);
        return res.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
                .collect(Collectors.joining("\n")).strip();
    }

    /** 사용자 키로 답할 때: 그 사람 하루 횟수만 셉니다(서버 전체 한도·주인 한도와 무관). */
    private void takeUserQuota(Long userId) {
        LocalDate today = LocalDate.now(zone);
        DailyCount next = usage.compute(userId, (id, c) ->
                c == null || !c.day().equals(today) ? new DailyCount(today, 1) : new DailyCount(today, c.count() + 1));
        if (next.count() > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘 질문 횟수(" + dailyLimit + "회)를 다 썼어요.");
        }
    }

    private void takeQuota(Long userId) {
        LocalDate today = LocalDate.now(zone);
        DailyCount global = globalUsage.updateAndGet(c ->
                c.day().equals(today) ? new DailyCount(today, c.count() + 1) : new DailyCount(today, 1));
        if (global.count() > globalDailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘 Claude 사용량을 다 썼어요. 내일 다시 써 주세요.");
        }
        DailyCount next = usage.compute(userId, (id, c) ->
                c == null || !c.day().equals(today) ? new DailyCount(today, 1) : new DailyCount(today, c.count() + 1));
        if (next.count() > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "오늘 질문 횟수(" + dailyLimit + "회)를 다 썼어요.");
        }
    }
}
