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

    public record AskRequest(String question, List<AiService.Turn> history, List<AiService.Image> images) {}
    /** answer: 안경용 요약, detail: 폰용 전체 답(코드 등, 없으면 null). */
    public record AskResponse(String answer, String detail) {}

    private static final String PHOTO_DEFAULT_QUESTION =
            "사진 속 요구사항과 코드를 보고, 요구사항대로 동작하려면 어디를 어떻게 수정해야 하는지 알려줘.";

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest req) {
        String q = req.question() == null ? "" : req.question().strip();
        boolean hasImages = req.images() != null && !req.images().isEmpty();
        if (q.isEmpty() && hasImages) q = PHOTO_DEFAULT_QUESTION;
        if (q.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력하세요.");
        if (q.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문이 너무 길어요(최대 " + MAX_QUESTION_CHARS + "자).");
        }
        AiService.Answer a = ai.ask(currentUser.requireId(), q, req.history(), req.images());
        return new AskResponse(a.summary(), a.detail());
    }
}
