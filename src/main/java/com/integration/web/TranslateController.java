package com.integration.web;

import com.integration.domain.User;
import com.integration.security.CurrentUserService;
import com.integration.translate.TranslateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 논문 PDF 번역. SecurityConfig의 anyRequest().authenticated() 아래에 있으므로
 * 로그인하지 않은 요청은 이 컨트롤러에 닿기도 전에 401로 끊깁니다.
 *
 * 프론트(PdfTranslator)가 문단을 3500자쯤씩 나눠 순서대로 보냅니다. 논문 한 편을 한
 * 요청에 담으면 Cloudflare Tunnel의 ~100초 상한에 반드시 걸립니다.
 */
@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    private final CurrentUserService currentUser;
    private final TranslateService translations;
    private final int maxChars;

    public TranslateController(CurrentUserService currentUser, TranslateService translations,
                               @Value("${app.translate.max-chars:20000}") int maxChars) {
        this.currentUser = currentUser;
        this.translations = translations;
        this.maxChars = maxChars;
    }

    public record TranslateRequest(
            @NotEmpty List<String> texts,
            String source,
            String target) {}

    public record TranslateResponse(List<String> translations) {}

    @PostMapping
    public TranslateResponse translate(@Valid @RequestBody TranslateRequest req) {
        // 한도를 넘는 요청은 어차피 LLM 응답을 기다리다 타임아웃납니다. 그 전에 왜
        // 실패했는지 알 수 있게 끊습니다.
        int chars = req.texts().stream().mapToInt(t -> t == null ? 0 : t.length()).sum();
        if (chars > maxChars) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Too much text in one request (" + chars + " > " + maxChars + " chars)");
        }

        String target = (req.target() == null || req.target().isBlank()) ? "ko" : req.target().trim();
        String source = (req.source() == null || req.source().isBlank()) ? "auto" : req.source().trim();

        User user = currentUser.require();
        List<String> out = translations.translate(req.texts(), source, target, user.getId(), user.getEmail());
        return new TranslateResponse(out);
    }
}
