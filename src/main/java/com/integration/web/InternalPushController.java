package com.integration.web;

import com.integration.push.WebPushService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 서버-대-서버 전용 엔드포인트. n8n(회의록 저장 워크플로)이 Google Drive 업로드
 * 성공/실패를 이 API로 알려주면, 여기서 해당 사용자에게 Web Push를 보냅니다.
 *
 * <p>인증은 사용자 JWT가 아니라 공유 비밀({@code X-Internal-Key})로 합니다. n8n은
 * 로그인 사용자가 아니므로 토큰이 없고, 대신 이 키를 아는 서버만 호출할 수 있습니다.
 * {@code /internal/**}는 SecurityConfig에서 permitAll이지만, push는 아래 키 검증으로
 * 막고 health는 상태 문자열만 반환합니다. 키가 비어 있으면(fail-closed) 전부 403입니다.
 */
@RestController
@RequestMapping("/internal")
public class InternalPushController {

    private final WebPushService push;
    private final String internalKey;
    /** 알림을 탭했을 때 열 주소 — 리마인더 푸시와 같은 앱 URL을 씁니다. */
    private final String appUrl;

    public InternalPushController(
            WebPushService push,
            @Value("${app.internal.api-key:}") String internalKey,
            @Value("${app.push.app-url:https://momentum.geol2.com}") String appUrl) {
        this.push = push;
        this.internalKey = internalKey;
        this.appUrl = appUrl;
    }

    /** 젠킨스/컨테이너 헬스체크용. 비밀 없이 상태만 반환하므로 노출돼도 안전합니다. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/push")
    public ResponseEntity<Void> push(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestBody PushRequest req) {
        // 키 미설정(blank)이면 아무도 통과 못 합니다 — 실수로 열린 채 배포되는 것을 막습니다.
        if (internalKey.isBlank() || !internalKey.equals(key)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (req == null || req.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        push.sendToUser(req.userId(), new WebPushService.Payload(
                req.title() != null ? req.title() : "알림",
                req.body() != null ? req.body() : "",
                appUrl,
                "recording"));
        // 구독 기기가 0이어도(푸시 비활성/미구독) n8n엔 성공으로 응답합니다 — 전달은 best-effort.
        return ResponseEntity.accepted().build();
    }

    public record PushRequest(Long userId, String title, String body) {}
}
