package com.integration.web;

import com.integration.domain.PushSubscription;
import com.integration.push.WebPushService;
import com.integration.repository.PushSubscriptionRepository;
import com.integration.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushSubscriptionRepository repo;
    private final WebPushService push;
    private final CurrentUserService currentUser;

    public PushController(PushSubscriptionRepository repo, WebPushService push, CurrentUserService currentUser) {
        this.repo = repo;
        this.push = push;
        this.currentUser = currentUser;
    }

    public record PublicKeyDto(boolean enabled, String publicKey, long subscriptions) {}
    public record SubscribeRequest(String endpoint, String p256dh, String auth) {}

    /**
     * What the client needs before it can subscribe: whether push is configured at all,
     * the VAPID application server key, and how many devices this account already has
     * registered (so the UI can show the toggle as on).
     */
    @GetMapping("/public-key")
    public PublicKeyDto publicKey() {
        long count = repo.countByUserId(currentUser.requireId());
        return new PublicKeyDto(push.isEnabled(), push.publicKey(), count);
    }

    /**
     * Registers this browser. Keyed on the endpoint, so re-subscribing the same browser
     * (or one that moved to another account on a shared device) updates the existing row
     * rather than accumulating duplicates that would each deliver the same alert.
     */
    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public void subscribe(@RequestBody SubscribeRequest req, HttpServletRequest http) {
        if (req.endpoint() == null || req.endpoint().isBlank()
                || req.p256dh() == null || req.auth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint/p256dh/auth required");
        }
        Long userId = currentUser.requireId();
        PushSubscription s = repo.findByEndpoint(req.endpoint()).orElseGet(PushSubscription::new);
        s.setUserId(userId);
        s.setEndpoint(req.endpoint());
        s.setP256dh(req.p256dh());
        s.setAuth(req.auth());
        String ua = http.getHeader("User-Agent");
        s.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 300)));
        repo.save(s);
    }

    /** Turning alerts off. Scoped to the caller so one account can't unsubscribe another's device. */
    @DeleteMapping("/subscriptions")
    @Transactional
    public void unsubscribe(@RequestParam String endpoint) {
        repo.deleteByEndpointAndUserId(endpoint, currentUser.requireId());
    }

    /** "테스트 알림 보내기" — proves the whole chain works without waiting for an appointment. */
    @PostMapping("/test")
    public java.util.Map<String, Object> test() {
        int sent = push.sendToUser(currentUser.requireId(), new WebPushService.Payload(
                "🔔 알림 테스트",
                "알림이 정상적으로 도착했어요. 이제 약속 시간 전에 알려드릴게요.",
                null,
                "push-test"));
        return java.util.Map.of("sent", sent);
    }
}
