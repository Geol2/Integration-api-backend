package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A browser's Web Push endpoint, owned by a {@link User}. One row per device/browser —
 * a user who installs the PWA on both a phone and a laptop has two.
 *
 * <p>endpoint is the push service URL the browser handed us (FCM, Mozilla, Apple …);
 * p256dh/auth are the client's encryption keys, base64url-encoded exactly as
 * {@code PushSubscription.toJSON()} produced them. All three are opaque to us and are
 * passed straight to the web-push library.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Push service URL. Unique — re-subscribing the same browser updates the row instead of duplicating it. */
    @Column(nullable = false, unique = true, length = 500)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    /** Purely diagnostic — helps tell "iPhone" from "desktop Chrome" when debugging a missing alert. */
    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
