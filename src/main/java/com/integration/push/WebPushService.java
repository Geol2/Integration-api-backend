package com.integration.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.integration.domain.PushSubscription;
import com.integration.repository.PushSubscriptionRepository;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;

/**
 * Sends Web Push messages (VAPID / aes128gcm) to a user's subscribed browsers.
 *
 * <p>Push is optional infrastructure: with no VAPID keys configured the service starts
 * in a disabled state and every send is a no-op, so the API still boots on a dev machine
 * that has not generated keys. Generate a pair with {@link VapidKeyGenerator}.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    private final PushSubscriptionRepository subs;
    private final ObjectMapper json;
    /** Null when VAPID keys are unset — see {@link #isEnabled()}. */
    private final PushService pushService;
    private final String publicKey;

    public WebPushService(PushSubscriptionRepository subs,
                          ObjectMapper json,
                          @Value("${app.push.vapid.public-key:}") String publicKey,
                          @Value("${app.push.vapid.private-key:}") String privateKey,
                          @Value("${app.push.vapid.subject:}") String subject) {
        this.subs = subs;
        this.json = json;
        this.publicKey = publicKey;

        if (publicKey.isBlank() || privateKey.isBlank()) {
            this.pushService = null;
            log.warn("Web push disabled — set VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY to enable reminders.");
            return;
        }
        // The JDK's own EC provider can't do the ECDH/HKDF dance web-push needs; BouncyCastle can.
        Security.addProvider(new BouncyCastleProvider());
        PushService svc = null;
        try {
            svc = new PushService(publicKey, privateKey, subject.isBlank() ? "mailto:noreply@geol2.com" : subject);
        } catch (Exception e) {
            log.error("Web push disabled — VAPID keys rejected: {}", e.getMessage());
        }
        this.pushService = svc;
    }

    public boolean isEnabled() {
        return pushService != null;
    }

    /** The VAPID public key the browser needs to create a subscription. Empty when disabled. */
    public String publicKey() {
        return isEnabled() ? publicKey : "";
    }

    /** Payload delivered to the service worker's push handler; field names match sw.js. */
    public record Payload(String title, String body, String url, String tag) {}

    /**
     * Fans the payload out to every browser this user has subscribed.
     *
     * <p>A 404/410 from the push service means the browser dropped the subscription
     * (uninstalled the PWA, cleared site data) — that row is deleted so it stops being
     * retried. Any other failure is logged and skipped; one dead device must never
     * prevent the user's other devices from being notified.
     *
     * @return how many endpoints accepted the message
     */
    public int sendToUser(Long userId, Payload payload) {
        if (!isEnabled()) return 0;

        List<PushSubscription> targets = subs.findByUserId(userId);
        if (targets.isEmpty()) return 0;

        byte[] body;
        try {
            body = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Could not serialise push payload", e);
            return 0;
        }

        int sent = 0;
        for (PushSubscription s : targets) {
            try {
                var res = pushService.send(new Notification(s.getEndpoint(), s.getP256dh(), s.getAuth(), body));
                int status = res.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    log.info("Dropping expired push subscription {} (user {})", s.getId(), userId);
                    subs.delete(s);
                } else if (status >= 200 && status < 300) {
                    sent++;
                } else {
                    log.warn("Push rejected with {} for subscription {} (user {})", status, s.getId(), userId);
                }
            } catch (Exception e) {
                log.warn("Push failed for subscription {} (user {}): {}", s.getId(), userId, e.toString());
            }
        }
        return sent;
    }
}
