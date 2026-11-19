package com.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구독(Claude Pro/Max)으로 답하기 — 서버에 설치하고 구독 계정으로 로그인한 Claude Code(`claude -p`)를
 * 실행합니다. API 키를 쓰지 않으므로 API 비용이 들지 않고, 구독 한도에 걸리면 AiService가 API로 넘어갑니다.
 * (같은 방식: Geol2/rig 의 claude_code.py)
 *
 * 안전장치
 *  - API 키를 자식 프로세스 환경에서 지웁니다. 남겨 두면 Claude Code가 구독 대신 API로 청구합니다.
 *  - --restricted: 명령 실행 도구가 없고, 파일 도구는 작업 폴더(프로젝트) 안으로만 제한됩니다.
 *    --tools 로 Read·Grep·Glob(읽기)만 켜고, 프로젝트가 없으면 도구를 하나도 주지 않습니다.
 *  - 사용자·프로젝트 설정 파일과 MCP 서버는 무시합니다(--restricted, --strict-mcp-config).
 *
 * 로그인(서버에서 한 번): 백엔드를 띄우는 사용자로 `claude setup-token`을 실행해 받은 토큰을
 * CLAUDE_CODE_OAUTH_TOKEN 환경 변수로 넣거나, 그 사용자로 `claude` → /login.
 */
@Service
public class ClaudeCodeEngine {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeEngine.class);

    /** 남아 있으면 Claude Code가 구독이 아니라 API로 청구하는 환경 변수들. */
    static final List<String> API_CREDENTIALS = List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");
    /** 한도에 걸렸는데 풀리는 시각을 모를 때 쉬는 시간. */
    static final Duration LIMIT_COOLDOWN = Duration.ofMinutes(60);
    /** 로그인 안 됨·실행 실패처럼 한도가 아닌 문제일 때 쉬는 시간(매번 느리게 실패하지 않게). */
    static final Duration ERROR_COOLDOWN = Duration.ofMinutes(10);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TURNS = 25;
    /** 구독 한도·사용량 초과를 뜻하는 Claude Code 메시지. */
    private static final Pattern LIMIT = Pattern.compile(
            "(?i)usage limit|limit reached|hit your (usage )?limit|rate.?limit|out of (extra )?usage|quota|weekly limit|session limit");
    /** "Claude AI usage limit reached|1763600000" 처럼 끝에 붙는 풀리는 시각(유닉스 초). */
    private static final Pattern RESET_EPOCH = Pattern.compile("\\|(\\d{10})\\b");

    /** 구독으로 답하지 못한 이유. limit=true면 구독 한도, 아니면 설정·실행 문제. */
    public static class Unavailable extends Exception {
        public final boolean limit;

        public Unavailable(String message, boolean limit) {
            super(message);
            this.limit = limit;
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final String bin;
    private final boolean enabled;
    private final AtomicReference<Instant> pausedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<String> pausedReason = new AtomicReference<>("");

    public ClaudeCodeEngine(@Value("${app.ai.engine:auto}") String engine,
                            @Value("${app.ai.claude-bin:claude}") String bin) {
        // Jenkins에 붙여 넣은 값에 공백·줄바꿈이 섞이면 경로 확인이 실패하므로 다듬습니다.
        this.bin = bin == null || bin.isBlank() ? "claude" : bin.strip();
        this.enabled = !"api".equalsIgnoreCase(engine.strip());
        if (!enabled) log.info("Claude 엔진: API만 사용 (AI_ENGINE=api)");
        else if (resolveBin() == null) {
            log.info("Claude 엔진: Claude Code 없음 — API만 사용 (찾아본 곳: {}, PATH, ~/.local/bin/claude)", this.bin);
        } else log.info("Claude 엔진: 구독(Claude Code {}) 먼저, 한도에 걸리면 API", resolveBin());
    }

    /** 지금 구독으로 시도해 볼지. 쉬는 중이거나 claude가 없으면 false. */
    public boolean ready() {
        return enabled && resolveBin() != null && Instant.now().isAfter(pausedUntil.get());
    }

    /** 설치돼 있는지(쉬는 중이어도 true). API 키 없이도 기능을 켤지 판단할 때 씁니다. */
    public boolean installed() {
        return enabled && resolveBin() != null;
    }

    /** 화면 표시용: 쉬는 중이면 언제까지·왜. */
    public record State(boolean installed, boolean ready, Instant pausedUntil, String reason) {}

    public State state() {
        Instant until = pausedUntil.get();
        boolean paused = Instant.now().isBefore(until);
        return new State(installed(), ready(), paused ? until : null, paused ? pausedReason.get() : null);
    }

    /**
     * @param systemPrompt 규칙(안경용 답변 형식 등). 프로젝트 안내는 여기서 덧붙입니다.
     * @param projectDir   읽게 할 프로젝트 폴더. null이면 도구 없이 답만 합니다.
     */
    public AiService.Answer ask(String model, String effort, String systemPrompt, String question,
                                List<AiService.Turn> history, List<AiService.Image> images,
                                String projectName, Path projectDir, Consumer<String> progress) throws Unavailable {
        String path = resolveBin();
        if (path == null) throw new Unavailable("Claude Code가 설치돼 있지 않아요", false);
        Path tmp = null;
        Process proc = null;
        try {
            tmp = Files.createTempDirectory("claude-ask-");
            Path work = projectDir != null ? projectDir : tmp;
            // 한글 규칙은 명령줄 인자가 아니라 UTF-8 파일로 넘깁니다. 서버 JVM이 UTF-8 로캘이 아니면
            // (Jenkins의 nohup java 등) 인자 속 한글이 ???로 깨집니다. 명령줄에는 ASCII만 둡니다.
            Path promptFile = tmp.resolve("system.md");
            Files.writeString(promptFile, systemPrompt + (projectDir != null ? projectRules(projectName) : NO_PROJECT_RULES),
                    StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(path, "-p",
                    "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                    "--model", model, "--effort", effort,
                    "--system-prompt-file", promptFile.toString(),
                    "--restricted", "--strict-mcp-config",
                    "--tools", projectDir != null ? "Read,Grep,Glob" : "",
                    "--permission-mode", "dontAsk",
                    "--max-turns", String.valueOf(MAX_TURNS)));
            if (projectDir != null) cmd.addAll(List.of("--allowedTools", "Read", "Grep", "Glob"));

            ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(false);
            Map<String, String> env = pb.environment();
            API_CREDENTIALS.forEach(env::remove);
            proc = pb.start();
            final Process p = proc;
            // stderr는 따로 버립니다(가득 차면 프로세스가 멈춤).
            Thread drain = new Thread(() -> { try { p.getErrorStream().transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) { } });
            drain.setDaemon(true);
            drain.start();

            try (OutputStream in = proc.getOutputStream()) {
                in.write(json.writeValueAsBytes(userMessage(question, history, images)));
                in.write('\n');
            }

            String finalText = null;
            JsonNode result = null;
            Path base = work.toRealPath();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    JsonNode ev;
                    try {
                        ev = json.readTree(line);
                    } catch (IOException e) {
                        continue;
                    }
                    String type = ev.path("type").asText();
                    if ("assistant".equals(type)) {
                        for (JsonNode block : ev.path("message").path("content")) {
                            if ("tool_use".equals(block.path("type").asText())) progress.accept(describe(block, base));
                        }
                    } else if ("result".equals(type)) {
                        result = ev;
                        finalText = ev.path("result").asText(null);
                    }
                }
            }
            if (!proc.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw fail("Claude Code 응답이 너무 오래 걸렸어요", false);
            }
            if (result == null) throw fail("Claude Code가 결과 없이 끝났어요 (exit " + proc.exitValue() + ")", false);
            String subtype = result.path("subtype").asText();
            if (result.path("is_error").asBoolean(false) || !"success".equals(subtype)) {
                String msg = finalText != null && !finalText.isBlank() ? finalText : result.path("errors").toString();
                if ("error_max_turns".equals(subtype)) {
                    // 한도 문제가 아니므로 쉬지 않고, 이번만 API로 넘깁니다.
                    throw new Unavailable("Claude Code가 도구를 너무 많이 썼어요", false);
                }
                boolean limit = LIMIT.matcher(msg).find();
                throw fail(msg, limit);
            }
            if (finalText == null || finalText.isBlank()) throw fail("Claude Code 답이 비었어요", false);
            return AiService.split(finalText.strip()).withEngine("subscription");
        } catch (IOException e) {
            throw fail("Claude Code를 실행하지 못했어요: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("중단됨", false);
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            if (tmp != null) {
                try {
                    ProjectStore.deleteTree(tmp);
                } catch (IOException ignored) {
                    // 작은 임시 폴더라 남아도 괜찮습니다.
                }
            }
        }
    }

    /** 실패를 기록하고 한동안 구독 시도를 쉽니다. */
    private Unavailable fail(String message, boolean limit) {
        Instant until;
        if (limit) {
            Matcher m = RESET_EPOCH.matcher(message);
            until = m.find() ? Instant.ofEpochSecond(Long.parseLong(m.group(1))) : Instant.now().plus(LIMIT_COOLDOWN);
        } else {
            until = Instant.now().plus(ERROR_COOLDOWN);
        }
        pausedUntil.set(until);
        String reason = limit ? "구독 한도" : message.length() > 200 ? message.substring(0, 200) : message;
        pausedReason.set(reason);
        log.warn("구독(Claude Code)으로 답하지 못해 {}까지 API를 씁니다: {}", until, reason);
        return new Unavailable(message, limit);
    }

    /** 이전 대화는 글로 붙이고(구독 세션을 매번 새로 열기 때문), 사진은 이미지 블록으로 보냅니다. */
    ObjectNode userMessage(String question, List<AiService.Turn> history, List<AiService.Image> images) {
        ObjectNode msg = json.createObjectNode();
        msg.put("type", "user");
        ObjectNode m = msg.putObject("message");
        m.put("role", "user");
        ArrayNode content = m.putArray("content");
        if (images != null) {
            for (AiService.Image img : images) {
                ObjectNode block = content.addObject();
                block.put("type", "image");
                ObjectNode src = block.putObject("source");
                src.put("type", "base64");
                src.put("media_type", img.mediaType());
                src.put("data", img.data());
            }
        }
        StringBuilder text = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            text.append("<이전_대화>\n");
            for (AiService.Turn t : history) {
                text.append("user".equals(t.role()) ? "[사용자]\n" : "[Claude]\n").append(t.text()).append("\n\n");
            }
            text.append("</이전_대화>\n\n이어서 묻는 질문:\n");
        }
        text.append(question);
        content.addObject().put("type", "text").put("text", text.toString());
        return msg;
    }

    /** 안경·화면에 보여 줄 진행 상황: "읽기: src/App.jsx" 같은 한 줄. */
    static String describe(JsonNode toolUse, Path base) {
        JsonNode in = toolUse.path("input");
        String name = toolUse.path("name").asText();
        return switch (name) {
            case "Read" -> "읽기: " + rel(in.path("file_path").asText(""), base);
            case "Grep" -> "검색: " + in.path("pattern").asText("");
            case "Glob" -> "목록: " + in.path("pattern").asText("");
            default -> name;
        };
    }

    private static String rel(String path, Path base) {
        try {
            Path p = Path.of(path);
            return p.isAbsolute() && p.normalize().startsWith(base) ? base.relativize(p.normalize()).toString() : path;
        } catch (RuntimeException e) {
            return path;
        }
    }

    /**
     * 실행 파일 찾기: 경로를 적었으면 그 경로, 아니면 PATH, 그래도 없으면 공식 설치 위치(~/.local/bin/claude).
     * Jenkins가 띄운 프로세스의 PATH에는 보통 ~/.local/bin이 없어서 마지막 단계가 필요합니다.
     */
    String resolveBin() {
        if (bin.contains(File.separator)) return Files.isExecutable(Path.of(bin)) ? bin : null;
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir, bin);
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        Path home = Path.of(System.getProperty("user.home"), ".local", "bin", bin);
        return Files.isExecutable(home) ? home.toString() : null;
    }

    private static String projectRules(String name) {
        return """

                지금 사용자의 프로젝트 "%s" 폴더에서 일하고 있다. 도구: Glob(파일 찾기), Grep(내용 검색), Read(파일 읽기).
                - 프로젝트에 관한 질문이면 추측하지 말고 먼저 Grep / Glob으로 찾고 Read로 실제 코드를 읽은 뒤 답한다.
                - 답에는 실제로 읽은 파일 경로(프로젝트 기준 상대 경로)와 줄 번호를 쓴다(예: src/g2/screens.js:120).
                - 도구는 꼭 필요한 만큼만 쓴다. 관련 파일을 몇 개 읽었으면 답한다.
                - 읽기만 할 수 있다. 수정은 사용자가 직접 하므로, 바꿀 코드를 @@상세@@ 아래에 정확히 쓴다.
                """.formatted(name);
    }

    private static final String NO_PROJECT_RULES = """

            지금은 프로젝트가 선택되지 않아 서버에 올린 코드(GitHub 저장소·zip)를 읽을 수 없다.
            사용자가 코드·zip·프로젝트 파일을 보고 답해 달라고 하면, 추측해서 답하지 말고 이렇게 안내한다:
            "폰이나 PC 화면의 '프로젝트' 목록에서 그 프로젝트를 고른 뒤 다시 물어봐 주세요."
            """;
}
