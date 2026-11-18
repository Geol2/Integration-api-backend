package com.integration.web;

import com.integration.ai.AiService;
import com.integration.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** G2 안경 "Claude에게 묻기". 로그인한 사용자만 쓸 수 있습니다(anyRequest().authenticated()). */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final int MAX_QUESTION_CHARS = 4000;

    private final AiService ai;
    private final CurrentUserService currentUser;

    public AiController(AiService ai, CurrentUserService currentUser) {
        this.ai = ai;
        this.currentUser = currentUser;
    }

    public record AskRequest(String question, List<AiService.Turn> history) {}
    public record AskResponse(String answer) {}

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req) {
        String q = req.question() == null ? "" : req.question().strip();
        if (q.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력하세요.");
        if (q.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 너무 길어요(최대 " + MAX_QUESTION_CHARS + "자).");
        }
        return new AskResponse(ai.ask(currentUser.requireId(), q, req.history()));
    }
}
