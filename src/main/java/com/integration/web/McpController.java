package com.integration.web;

import com.integration.ai.AiService;
import com.integration.ai.ConversationService;
import com.integration.domain.User;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 모멘텀 원격 MCP 서버 (Streamable HTTP, JSON 응답만 — SSE 스트림은 쓰지 않음).
 * claude.ai 설정 → 커넥터 → 커스텀 커넥터 추가에 {base}/mcp 를 넣으면, 웹·폰·데스크톱 어디서든
 * "이 대화 모멘텀에 저장해줘"로 대화를 저장할 수 있어요. 인증은 McpOAuthController 의 OAuth 로 받은 JWT.
 *
 * 도구
 *  - save_conversation : 질문·답 목록을 "모멘텀 질문" 목록에 저장(같은 conversation_id 로 다시 부르면 새 질문만 덧붙음)
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Pattern CHAT_ID = Pattern.compile("chat-[A-Za-z0-9_-]{6,70}");
    private static final Set<String> VERSIONS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final ConversationService conversations;
    private final CurrentUserService currentUser;
    private final AiService ai;

    public McpController(ConversationService conversations, CurrentUserService currentUser, AiService ai) {
        this.conversations = conversations;
        this.currentUser = currentUser;
        this.ai = ai;
    }

    @PostMapping
    public ResponseEntity<Object> rpc(@RequestBody Map<String, Object> msg) {
        Object id = msg.get("id");
        String method = String.valueOf(msg.get("method"));
        if (id == null) return ResponseEntity.accepted().build(); // 알림·응답은 받기만
        @SuppressWarnings("unchecked")
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        try {
            Object result = switch (method) {
                case "initialize" -> {
                    String v = String.valueOf(params.get("protocolVersion"));
                    yield Map.of(
                            "protocolVersion", VERSIONS.contains(v) ? v : "2025-06-18",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "momentum", "version", "1.0.0"),
                            "instructions", "사용자의 모멘텀 앱(momentum.geol2.com)에 대화를 저장합니다. 사용자가 '모멘텀에 저장해줘'라고 하면 save_conversation 을 쓰세요.");
                }
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", List.of(SAVE_TOOL));
                case "tools/call" -> callTool(params);
                default -> null;
            };
            if (result == null) return ok(Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32601, "message", "Method not found: " + method)));
            return ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (RuntimeException e) {
            return ok(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32603, "message", String.valueOf(e.getMessage()))));
        }
    }

    // SSE 스트림·세션 종료는 지원하지 않음
    @GetMapping
    public ResponseEntity<Void> noStream() { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build(); }

    @DeleteMapping
    public ResponseEntity<Void> noSession() { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build(); }

    private static ResponseEntity<Object> ok(Object body) { return ResponseEntity.ok(body); }

    private static final Map<String, Object> SAVE_TOOL = Map.of(
            "name", "save_conversation",
            "description", "지금 대화를 사용자의 모멘텀 앱에 저장합니다. 사용자가 \"모멘텀에 저장/올려줘\"라고 하면 쓰세요. "
                    + "대화의 질문·답을 처음부터 순서대로 빠짐없이 turns 에 넣으세요(답은 요약하지 말고 실제로 답한 내용 그대로). "
                    + "같은 대화를 나중에 다시 저장할 때는 처음 저장할 때 돌려받은 conversation_id 를 넘기면 새로 생긴 질문만 덧붙습니다.",
            "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "title", Map.of("type", "string", "description", "대화 제목(짧게, 한국어)"),
                            "turns", Map.of("type", "array", "description", "처음부터 지금까지의 질문·답 쌍",
                                    "items", Map.of("type", "object",
                                            "properties", Map.of("question", Map.of("type", "string"), "answer", Map.of("type", "string")),
                                            "required", List.of("question", "answer"))),
                            "conversation_id", Map.of("type", "string", "description", "이전에 저장할 때 받은 id(이어 저장할 때만)")),
                    "required", List.of("title", "turns")));

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!"save_conversation".equals(name)) return toolText("모르는 도구: " + name, true);

        User user = currentUser.require();
        if (!ai.isEnabledFor(user.getEmail())) return toolText("이 계정은 Claude 기능을 쓸 수 없어요.", true);

        List<ConversationService.ImportedTurn> turns = new ArrayList<>();
        if (args.get("turns") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> t)) continue;
                String q = t.get("question") == null ? "" : String.valueOf(t.get("question"));
                String a = t.get("answer") == null ? "" : String.valueOf(t.get("answer"));
                if (!q.isBlank() || !a.isBlank()) turns.add(new ConversationService.ImportedTurn(q, a, null));
            }
        }
        if (turns.isEmpty()) return toolText("저장할 질문·답이 없어요.", true);
        if (turns.size() > 2000) return toolText("한 번에 2000개까지 저장할 수 있어요.", true);

        String id = args.get("conversation_id") == null ? "" : String.valueOf(args.get("conversation_id")).strip();
        if (!CHAT_ID.matcher(id).matches()) id = "chat-" + UUID.randomUUID();
        String title = args.get("title") == null ? "" : String.valueOf(args.get("title"));
        ConversationService.ImportResult r = conversations.importTurns(user.getId(), id, title, turns);
        return toolText("모멘텀에 저장했어요 — 새 질문 " + r.added() + "개 (전체 " + turns.size() + "개). conversation_id: " + id
                + "\n이 대화를 나중에 다시 저장할 땐 이 conversation_id 를 넘겨 주세요. 보기: https://momentum.geol2.com/g2.html", false);
    }

    private static Map<String, Object> toolText(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }
}
