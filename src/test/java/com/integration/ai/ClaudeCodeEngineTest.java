package com.integration.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 가짜 `claude` 스크립트로 구독 먼저 → 한도면 API 순서를 확인합니다(진짜 Claude Code·API는 부르지 않음). */
class ClaudeCodeEngineTest {

    @TempDir Path tmp;
    private HttpServer api;
    private final AtomicInteger apiCalls = new AtomicInteger();

    @AfterEach
    void stop() { if (api != null) api.stop(0); }

    /** stdin·인자·작업 폴더·API 키 유무를 파일로 남기고, mode에 따라 성공/한도/로그인 실패를 흉내 냅니다. */
    private Path fakeClaude(String mode) throws Exception {
        Path bin = tmp.resolve("claude");
        Files.writeString(bin, """
                #!/usr/bin/env bash
                D="%s"
                echo "$@" > "$D/args.txt"
                while [ $# -gt 0 ]; do [ "$1" = "--system-prompt-file" ] && cp "$2" "$D/system.md"; shift; done
                pwd > "$D/cwd.txt"
                echo "${ANTHROPIC_API_KEY:-<none>}" > "$D/apikey.txt"
                echo x >> "$D/calls.txt"
                cat > "$D/stdin.txt"
                echo '{"type":"system","subtype":"init","tools":["Read"]}'
                case "%s" in
                  ok)
                    echo "{\\"type\\":\\"assistant\\",\\"message\\":{\\"content\\":[{\\"type\\":\\"tool_use\\",\\"name\\":\\"Grep\\",\\"input\\":{\\"pattern\\":\\"login\\"}},{\\"type\\":\\"tool_use\\",\\"name\\":\\"Read\\",\\"input\\":{\\"file_path\\":\\"$(pwd)/src/Auth.java\\"}}]}}"
                    echo '{"type":"result","subtype":"success","is_error":false,"result":"src/Auth.java:3 login()에서 해요.\\n@@상세@@\\nvoid login() {}\\n@@할일@@\\n- Auth.login() 검증"}' ;;
                  limit)
                    echo '{"type":"result","subtype":"success","is_error":true,"result":"Claude AI usage limit reached|%d"}' ;;
                  nologin)
                    echo '{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}' ;;
                esac
                """.formatted(tmp, mode, Instant.now().plusSeconds(7200).getEpochSecond()));
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private String startApi() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            apiCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body = """
                    {"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5",
                     "content":[{"type":"text","text":"API가 답했어요"}],"stop_reason":"end_turn","stop_sequence":null,
                     "usage":{"input_tokens":1,"output_tokens":1}}""".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        api.start();
        return "http://127.0.0.1:" + api.getAddress().getPort();
    }

    private AiService service(String apiKey, String url, ClaudeCodeEngine cc) {
        AiService ai = new AiService(apiKey, "claude-opus-5-5", "medium", "", 100, 300, "me@x.com", "Asia/Seoul", url);
        ai.setClaudeCode(cc);
        return ai;
    }

    @Test
    void subscriptionAnswersFirstWithReadOnlyToolsAndNoApiKey() throws Exception {
        Path proj = Files.createDirectories(tmp.resolve("proj/src"));
        ClaudeCodeEngine cc = new ClaudeCodeEngine("auto", fakeClaude("ok").toString());
        AiService ai = service("test-key", startApi(), cc);
        List<String> progress = new ArrayList<>();

        AiService.Answer a = ai.ask(1L, "me@x.com", "로그인 어디서 해?",
                List.of(new AiService.Turn("user", "앞 질문"), new AiService.Turn("assistant", "앞 답")),
                List.of(new AiService.Image("image/jpeg", "QUJD")), new AiService.ProjectRef("demo2", proj.getParent()), progress::add);

        assertThat(a.engine()).isEqualTo("subscription");
        assertThat(a.summary()).isEqualTo("src/Auth.java:3 login()에서 해요.");
        assertThat(a.detail()).isEqualTo("void login() {}");
        assertThat(a.steps()).containsExactly("Auth.login() 검증");
        assertThat(progress).containsExactly("검색: login", "읽기: src/Auth.java");
        assertThat(apiCalls).hasValue(0);   // API 비용 0

        // API 키는 Claude Code에 넘어가지 않습니다(넘어가면 구독 대신 API로 청구됨)
        assertThat(System.getenv("ANTHROPIC_API_KEY")).isNotBlank();
        assertThat(Files.readString(tmp.resolve("apikey.txt")).strip()).isEqualTo("<none>");
        // 읽기 도구만, 프로젝트 폴더 안에서, 제한 모드
        String args = Files.readString(tmp.resolve("args.txt"));
        assertThat(args).contains("--restricted", "--strict-mcp-config", "--tools Read,Grep,Glob", "--permission-mode dontAsk",
                "--model claude-opus-5-5", "--effort medium", "--system-prompt-file");
        // 명령줄에는 ASCII만(로캘이 UTF-8이 아니어도 안 깨지게), 한글 규칙은 UTF-8 파일로
        assertThat(args.chars().allMatch(c -> c < 128)).isTrue();
        assertThat(Files.readString(tmp.resolve("system.md"), StandardCharsets.UTF_8)).contains("프로젝트 \"demo2\"", "안경");
        assertThat(Files.readString(tmp.resolve("cwd.txt")).strip()).isEqualTo(proj.getParent().toRealPath().toString());
        // 사진은 이미지 블록, 이전 대화는 글로
        String stdin = Files.readString(tmp.resolve("stdin.txt"));
        assertThat(stdin).contains("\"type\":\"image\"", "\"media_type\":\"image/jpeg\"", "\"data\":\"QUJD\"", "앞 질문", "앞 답", "로그인 어디서 해?");
    }

    @Test
    void usageLimitFallsBackToApiAndSkipsSubscriptionUntilReset() throws Exception {
        ClaudeCodeEngine cc = new ClaudeCodeEngine("auto", fakeClaude("limit").toString());
        AiService ai = service("test-key", startApi(), cc);
        List<String> progress = new ArrayList<>();

        AiService.Answer a = ai.ask(1L, "me@x.com", "질문", List.of(), List.of(), null, progress::add);
        assertThat(a.engine()).isEqualTo("api");
        assertThat(a.summary()).isEqualTo("API가 답했어요");
        assertThat(progress).contains("구독 한도 → API로 답해요");
        assertThat(cc.ready()).isFalse();
        assertThat(cc.state().pausedUntil()).isAfter(Instant.now().plusSeconds(7000));   // 풀리는 시각까지 쉼
        assertThat(cc.state().reason()).isEqualTo("구독 한도");

        // 다음 질문은 구독을 건너뛰고 바로 API
        ai.ask(1L, "me@x.com", "두 번째", List.of(), List.of(), null, p -> {});
        assertThat(Files.readAllLines(tmp.resolve("calls.txt"))).hasSize(1);
        assertThat(apiCalls).hasValue(2);
        // 프로젝트 없는 질문은 도구 없이, 임시 폴더에서
        assertThat(Files.readString(tmp.resolve("args.txt"))).contains("--tools  ").doesNotContain("--allowedTools");
        assertThat(Files.readString(tmp.resolve("system.md"), StandardCharsets.UTF_8)).contains("프로젝트가 선택되지 않아");
    }

    @Test
    void notLoggedInFallsBackBrieflyAndWithoutApiKeyExplains() throws Exception {
        ClaudeCodeEngine cc = new ClaudeCodeEngine("auto", fakeClaude("nologin").toString());
        AiService ai = service("test-key", startApi(), cc);
        assertThat(ai.ask(1L, "me@x.com", "q", List.of(), List.of(), null, p -> {}).engine()).isEqualTo("api");
        assertThat(cc.state().pausedUntil()).isBefore(Instant.now().plusSeconds(11 * 60));   // 짧게만 쉼

        // API 키가 없고 구독도 한도면, 기다리라고 안내
        ClaudeCodeEngine cc2 = new ClaudeCodeEngine("auto", fakeClaude("limit").toString());
        AiService subOnly = service("", "", cc2);
        assertThat(subOnly.isEnabledFor("me@x.com")).isTrue();   // API 키 없이도 구독만으로 켜짐
        assertThatThrownBy(() -> subOnly.ask(1L, "me@x.com", "q", List.of(), List.of(), null, p -> {}))
                .hasMessageContaining("구독 한도에 걸렸고 API 키가 없어요");
    }

    @Test
    void apiOnlyModeAndMissingClaudeUseApi() throws Exception {
        String url = startApi();
        ClaudeCodeEngine off = new ClaudeCodeEngine("api", fakeClaude("ok").toString());
        assertThat(service("test-key", url, off).ask(1L, "me@x.com", "q", List.of(), List.of(), null, p -> {}).engine()).isEqualTo("api");
        ClaudeCodeEngine missing = new ClaudeCodeEngine("auto", tmp.resolve("no-such-claude").toString());
        assertThat(missing.installed()).isFalse();
        assertThat(service("test-key", url, missing).ask(1L, "me@x.com", "q", List.of(), List.of(), null, p -> {}).engine()).isEqualTo("api");
        assertThat(Files.exists(tmp.resolve("calls.txt"))).isFalse();
    }

    @Test
    void findsClaudeWithStrayWhitespaceOrInHomeLocalBin() throws Exception {
        Path bin = fakeClaude("ok");
        // Jenkins에 붙여 넣다 들어간 공백·줄바꿈
        assertThat(new ClaudeCodeEngine("auto", "  " + bin + "\n").installed()).isTrue();
        // 경로를 안 적어도 ~/.local/bin/claude 에서 찾음 (PATH에 없어도)
        String oldHome = System.getProperty("user.home");
        try {
            Path home = Files.createDirectories(tmp.resolve("home/.local/bin"));
            // (이 기계 PATH에 진짜 claude가 있을 수 있어 테스트용 이름을 씁니다)
            Files.copy(bin, home.resolve("claude-g2-test"));
            System.setProperty("user.home", tmp.resolve("home").toString());
            ClaudeCodeEngine cc = new ClaudeCodeEngine("auto", "claude-g2-test");
            assertThat(cc.installed()).isTrue();
            assertThat(cc.resolveBin()).isEqualTo(home.resolve("claude-g2-test").toString());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
