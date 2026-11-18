package com.integration.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 가짜 Claude 서버로 "도구 호출 → 결과 전달 → 최종 답" 왕복을 확인합니다. */
class AiAgentLoopTest {

    @TempDir Path tmp;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private String startFakeClaude(List<String> responses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.get(Math.min(requests.size(), responses.size()) - 1).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static String msg(String content, String stop) {
        return """
                {"id":"msg_x","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}""".formatted(content, stop);
    }

    @Test
    void readsProjectFilesThenAnswers() throws Exception {
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj.resolve("src"));
        Files.writeString(proj.resolve("src/OrderService.java"), "class OrderService { void save() {} }\n");

        String url = startFakeClaude(List.of(
                msg("""
                        [{"type":"text","text":"찾아볼게요."},
                         {"type":"tool_use","id":"tu_1","name":"search","input":{"query":"OrderService"}},
                         {"type":"tool_use","id":"tu_2","name":"read_file","input":{"path":"src/OrderService.java"}}]""", "tool_use"),
                msg("""
                        [{"type":"tool_use","id":"tu_3","name":"read_file","input":{"path":"../etc/passwd"}}]""", "tool_use"),
                msg("""
                        [{"type":"text","text":"src/OrderService.java:1 의 save()에 검증을 넣으세요.\\n@@상세@@\\nvoid save() { check(); }\\n@@할일@@\\n- OrderService.save() 검증 추가"}]""", "end_turn")));

        AiService ai = new AiService("test-key", "claude-opus-5-5", "medium", "", 100, 300, "me@x.com", "Asia/Seoul", url);
        List<String> progress = new ArrayList<>();
        AiService.Answer a = ai.ask(1L, "me@x.com", "save 어디 있어?", List.of(), List.of(),
                new AiService.ProjectRef("proj", proj), progress::add);

        assertThat(a.summary()).contains("src/OrderService.java:1");
        assertThat(a.detail()).isEqualTo("void save() { check(); }");
        assertThat(a.steps()).containsExactly("OrderService.save() 검증 추가");
        assertThat(progress).containsExactly("검색: OrderService", "읽기: src/OrderService.java", "읽기: ../etc/passwd");

        assertThat(requests).hasSize(3);
        // 첫 요청: 도구 3개와 프로젝트 안내가 들어갑니다.
        assertThat(requests.get(0)).doesNotContain("프로젝트가 선택되지 않아");
        assertThat(requests.get(0)).contains("\"list_files\"", "\"read_file\"", "\"search\"", "프로젝트 \\\"proj\\\"");
        // 두 번째 요청: 앞 답(tool_use)과 두 도구 결과가 같은 user 메시지로 갑니다.
        assertThat(requests.get(1)).contains("\"tool_use_id\":\"tu_1\"", "\"tool_use_id\":\"tu_2\"",
                "src/OrderService.java:1: class OrderService", "class OrderService { void save() {} }");
        // 세 번째: 프로젝트 밖 경로는 에러 결과로 돌려줍니다.
        assertThat(requests.get(2)).contains("\"tool_use_id\":\"tu_3\"", "\"is_error\":true", "프로젝트 밖");
    }

    @Test
    void plainQuestionSendsNoTools() throws Exception {
        String url = startFakeClaude(List.of(msg("[{\"type\":\"text\",\"text\":\"WHERE는 행, HAVING은 그룹을 거른다.\"}]", "end_turn")));
        AiService ai = new AiService("test-key", "claude-opus-5-5", "medium", "", 100, 300, "me@x.com", "Asia/Seoul", url);
        AiService.Answer a = ai.ask(1L, "me@x.com", "WHERE vs HAVING", List.of(), List.of());
        assertThat(a.summary()).contains("HAVING");
        assertThat(requests.get(0)).doesNotContain("\"tools\"");
        // 프로젝트 없이 물으면 '프로젝트를 고르라'는 안내 규칙이 들어갑니다.
        assertThat(requests.get(0)).contains("프로젝트가 선택되지 않아");
    }
}
