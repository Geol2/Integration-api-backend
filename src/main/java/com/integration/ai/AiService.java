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

    private final AnthropicClient client;   // 키가 없으면 null — 기능 꺼짐
    private final String model;
    private final BetaOutputConfig.Effort effort;
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
    public record Answer(String summary, String detail, List<String> steps) {
        Answer(String summary, String detail) { this(summary, detail, List.of()); }

        /** 이어서 묻기용으로 대화 기록에 남길 원문. */
        public String full() {
            String s = detail == null ? summary : summary + "\n" + DETAIL_MARK + "\n" + detail;
            if (!steps.isEmpty()) s += "\n" + STEPS_MARK + "\n- " + String.join("\n- ", steps);
            return s;
        }
    }

    public AiService(
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:claude-opus-5-5}") String model,
            @Value("${app.ai.effort:medium}") String effort,
            @Value("${app.ai.context-file:}") String contextFile,
            @Value("${app.ai.daily-limit:100}") int dailyLimit,
            @Value("${app.ai.global-daily-limit:300}") int globalDailyLimit,
            @Value("${app.ai.allowed-emails:}") String allowedEmails,
            @Value("${app.push.zone:Asia/Seoul}") String zone) {
        this.client = apiKey.isBlank() ? null : AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                // 사진 여러 장 + 긴 코드 답은 1분을 넘기기도 합니다.
                .timeout(Duration.ofSeconds(180))
                .build();
        this.model = model;
        this.effort = BetaOutputConfig.Effort.of(effort);
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

    /** 이 사용자가 Claude 기능을 쓸 수 있는지 (키가 있고, 허용 목록에 있음). 앱이 메뉴를 숨기는 데 씁니다. */
    public boolean isEnabledFor(String email) {
        return client != null && email != null && allowedEmails.contains(email.strip().toLowerCase(Locale.ROOT));
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
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 기능이 꺼져 있어요.");
        }
        if (!isEnabledFor(email)) {
            // 스토어에서 받은 다른 사용자가 키 주인의 비용으로 쓰지 못하게 막습니다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 계정은 Claude 기능을 쓸 수 없어요.");
        }
        List<BetaContentBlockParam> imageBlocks = toImageBlocks(images);
        takeQuota(userId);

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                // 생각(thinking) 토큰도 여기에 포함되므로 답 길이보다 넉넉하게 잡습니다.
                .maxTokens(16000L)
                // 규칙 + 프로젝트 자료는 매번 같으므로 캐시합니다 (두 번째 질문부터 싸고 빠름).
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
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

        BetaMessage res;
        try {
            res = client.beta().messages().create(params.build());
        } catch (RateLimitException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 물어봐 주세요.");
        } catch (AnthropicServiceException e) {
            log.error("Claude API 오류 status={}", e.statusCode(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude 응답을 받지 못했어요.");
        } catch (AnthropicIoException e) {
            log.error("Claude API 연결 실패", e);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Claude에 연결할 수 없어요.");
        }

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
