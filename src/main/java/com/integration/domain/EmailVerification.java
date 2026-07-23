package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A pending email-verification challenge issued during signup. One active row per
 * email address; requesting a new code overwrites the previous one. The row is
 * deleted once the code is consumed (successful signup) or exhausted/expired.
 */
@Entity
@Table(name = "email_verifications")
@Getter
@Setter
@NoArgsConstructor
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash of the 6-digit code — never store the code in plaintext. */
    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** When the most recent code was sent — used to enforce the resend cooldown. */
    @Column(nullable = false)
    private Instant lastSentAt;

    /** Incorrect verification attempts against the current code. */
    @Column(nullable = false)
    private int attempts = 0;

    public EmailVerification(String email, String codeHash, Instant expiresAt, Instant lastSentAt) {
        this.email = email;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = lastSentAt;
    }
}
