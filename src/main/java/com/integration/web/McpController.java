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
    private final com.integration.repository.NoteRepository notes;

    public McpController(ConversationService conversations, CurrentUserService currentUser, AiService ai,
                         com.integration.repository.NoteRepository notes) {
        this.notes = notes;
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
                            "instructions", "사용자의 모멘텀 앱(momentum.geol2.com)과 연결돼요. '이 대화 모멘텀에 저장해줘' → save_conversation, '모멘텀 메모에 적어줘' → save_memo, 예전 모멘텀 대화 찾기·읽기 → list_conversations · read_conversation.");
                }
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", List.of(SAVE_TOOL, MEMO_TOOL, LIST_TOOL, READ_TOOL));
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

    private static final Map<String, Object> MEMO_TOOL = Map.of(
            "name", "save_memo",
            "description", "사용자의 모멘텀 앱 '메모'(화면에 붙는 포스트잇)에 짧은 글을 저장합니다. 사용자가 \"모멘텀 메모에 저장해줘/적어줘\"라고 하면 쓰세요. 대화 전체가 아니라 메모할 내용만 넣으세요.",
            "inputSchema", Map.of("type", "object",
                    "properties", Map.of("text", Map.of("type", "string", "description", "메모 내용(2000자 이하)")),
                    "required", List.of("text")));

    private static final Map<String, Object> LIST_TOOL = Map.of(
            "name", "list_conversations",
            "description", "사용자의 모멘텀에 저장된 대화 목록(최근 순)을 봅니다. 예전에 모멘텀에서 나눈 대화를 찾을 때 쓰세요.",
            "inputSchema", Map.of("type", "object",
                    "properties", Map.of("limit", Map.of("type", "integer", "description", "몇 개(기본 20, 최대 50)"),
                            "query", Map.of("type", "string", "description", "제목에 들어간 말로 거르기(선택)"))));

    private static final Map<String, Object> READ_TOOL = Map.of(
            "name", "read_conversation",
            "description", "모멘텀에 저장된 대화 하나의 질문·답을 읽습니다(list_conversations 의 id). 최근 질문부터 limit개.",
            "inputSchema", Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "integer"),
                            "limit", Map.of("type", "integer", "description", "최근 몇 개 질문(기본 20)")),
                    "required", List.of("id")));

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        switch (name) {
            case "save_memo": return saveMemo(args);
            case "list_conversations": return listConversations(args);
            case "read_conversation": return readConversation(args);
            case "save_conversation": break;
            default: return toolText("모르는 도구: " + name, true);
        }

        User user = currentUser.require();
        // 저장만 하고 서버에서 Claude를 부르지 않으므로 비용이 없어요 → 로그인한 사용자면 누구나(자기 계정에 저장).

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

    private Map<String, Object> saveMemo(Map<String, Object> args) {
        User user = currentUser.require();
        String text = args.get("text") == null ? "" : String.valueOf(args.get("text")).strip();
        if (text.isEmpty()) return toolText("메모할 내용이 없어요.", true);
        if (text.length() > 2000) text = text.substring(0, 2000);
        com.integration.domain.Note n = new com.integration.domain.Note();
        n.setUserId(user.getId());
        n.setText(text);
        // 화면 왼쪽 위쯤에 살짝 기울여 붙임(메인 화면에서 끌어서 옮길 수 있어요)
        n.setX(40 + Math.random() * 120);
        n.setY(140 + Math.random() * 120);
        n.setRot((Math.random() - 0.5) * 6);
        n.setWidth(240);
        n.setHeight(Math.min(320, 90 + text.length() / 3.0));
        n.setDateKey(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString());
        n.setTs(System.currentTimeMillis());
        notes.save(n);
        return toolText("모멘텀 메모에 저장했어요. 메인 화면(momentum.geol2.com)과 안경 '메모'에서 볼 수 있어요.", false);
    }

    private Map<String, Object> listConversations(Map<String, Object> args) {
        User user = currentUser.require();
        int limit = Math.max(1, Math.min(50, args.get("limit") instanceof Number n ? n.intValue() : 20));
        String q = args.get("query") == null ? "" : String.valueOf(args.get("query")).strip().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ConversationService.ConversationView c : conversations.list(user.getId())) {
            if (!q.isEmpty() && (c.title() == null || !c.title().toLowerCase(java.util.Locale.ROOT).contains(q))) continue;
            sb.append("- id ").append(c.id()).append(" · ").append(c.title()).append(" · ").append(c.updatedAt()).append('\n');
            if (++count >= limit) break;
        }
        return toolText(count == 0 ? "저장된 대화가 없어요." : sb.toString(), false);
    }

    private Map<String, Object> readConversation(Map<String, Object> args) {
        User user = currentUser.require();
        if (!(args.get("id") instanceof Number idn)) return toolText("id 가 필요해요.", true);
        int limit = Math.max(1, Math.min(50, args.get("limit") instanceof Number n ? n.intValue() : 20));
        ConversationService.Detail d;
        try {
            d = conversations.detail(user.getId(), idn.longValue());
        } catch (RuntimeException e) {
            return toolText("그 대화를 찾을 수 없어요.", true);
        }
        List<ConversationService.TurnView> turns = d.turns();
        StringBuilder sb = new StringBuilder("대화: ").append(d.conversation().title()).append('\n');
        for (ConversationService.TurnView t : turns.subList(Math.max(0, turns.size() - limit), turns.size())) {
            sb.append("\nQ: ").append(t.question()).append("\nA: ").append(t.answer());
            if (t.detail() != null && !t.detail().isBlank()) sb.append('\n').append(t.detail().length() > 2000 ? t.detail().substring(0, 2000) + "…" : t.detail());
            sb.append('\n');
        }
        String out = sb.length() > 60000 ? sb.substring(sb.length() - 60000) : sb.toString();
        return toolText(out, false);
    }

    private static Map<String, Object> toolText(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }
}
