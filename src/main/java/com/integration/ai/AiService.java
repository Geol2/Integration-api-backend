package com.integration.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
            """;

    private final AnthropicClient client;   // 키가 없으면 null — 기능 꺼짐
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final String systemPrompt;
    private final int dailyLimit;
    private final ZoneId zone;

    /** userId → 오늘 사용 횟수. 서버 한 대 기준의 간단한 비용 안전장치입니다. */
    private final Map<Long, DailyCount> usage = new ConcurrentHashMap<>();

    private record DailyCount(LocalDate day, int count) {}

    public record Turn(String role, String text) {}

    public AiService(
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:claude-opus-5-5}") String model,
            @Value("${app.ai.effort:medium}") String effort,
            @Value("${app.ai.context-file:}") String contextFile,
            @Value("${app.ai.daily-limit:100}") int dailyLimit,
            @Value("${app.push.zone:Asia/Seoul}") String zone) {
        this.client = apiKey.isBlank() ? null : AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(90))
                .build();
        this.model = model;
        this.effort = BetaOutputConfig.Effort.of(effort);
        this.dailyLimit = dailyLimit;
        this.zone = ZoneId.of(zone);
        this.systemPrompt = RULES + loadContext(contextFile);
        if (client == null) log.info("ANTHROPIC_API_KEY 미설정 — /api/ai/ask 비활성화");
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

    public String ask(Long userId, String question, List<Turn> history) {
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 기능이 꺼져 있어요.");
        }
        takeQuota(userId);

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                // 생각(thinking) 토큰도 여기에 포함되므로 답 길이보다 넉넉하게 잡습니다.
                .maxTokens(8000L)
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
        params.addUserMessage(question);

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
            return "이 질문에는 답할 수 없어요.";
        }
        String answer = res.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .strip();
        if (BetaStopReason.MAX_TOKENS.equals(stop)) answer += "\n...(답이 길어서 잘렸어요)";
        return answer.isEmpty() ? "답을 만들지 못했어요. 다시 물어봐 주세요." : answer;
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
        DailyCount next = usage.compute(userId, (id, c) ->
                c == null || !c.day().equals(today) ? new DailyCount(today, 1) : new DailyCount(today, c.count() + 1));
        if (next.count() > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "오늘 질문 횟수(" + dailyLimit + "회)를 다 썼어요.");
        }
    }
}
