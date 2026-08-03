package com.integration.auth;

import com.integration.domain.EmailVerification;
import com.integration.repository.EmailVerificationRepository;
import com.integration.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues and verifies the 6-digit signup codes. The password stays on the client
 * until final signup — only the email is needed to request a code — so no pending
 * plaintext credentials are ever stored server-side.
 */
@Service
public class EmailVerificationService {

    private final EmailVerificationRepository verifications;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final SecureRandom random = new SecureRandom();

    private final int ttlMinutes;
    private final int resendCooldownSeconds;
    private final int maxAttempts;

    public EmailVerificationService(EmailVerificationRepository verifications,
                                    UserRepository users,
                                    PasswordEncoder passwordEncoder,
                                    MailService mailService,
                                    @Value("${app.verification.code-ttl-minutes}") int ttlMinutes,
                                    @Value("${app.verification.resend-cooldown-seconds}") int resendCooldownSeconds,
                                    @Value("${app.verification.max-attempts}") int maxAttempts) {
        this.verifications = verifications;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.ttlMinutes = ttlMinutes;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** Generate a fresh signup code for {@code email} and mail it. */
    @Transactional
    public void requestCode(String email) {
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        issue(email, false);
    }

    /**
     * Generate a fresh password-reset code and mail it. Unknown addresses are a silent
     * no-op — the caller returns the same 204 either way, so the response can't be used
     * to probe which emails have an account. (The resend cooldown still 429s, but that
     * only fires after a real mail has already gone out to the address owner.)
     */
    @Transactional
    public void requestPasswordResetCode(String email) {
        if (!users.existsByEmail(email)) return;
        issue(email, true);
    }

    /** Shared issue path: rotate the challenge row for {@code email}, then mail the code. */
    private void issue(String email, boolean passwordReset) {
        Instant now = Instant.now();
        EmailVerification ev = verifications.findByEmail(email).orElse(null);
        if (ev != null && Duration.between(ev.getLastSentAt(), now).getSeconds() < resendCooldownSeconds) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another code");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));

        if (ev == null) {
            ev = new EmailVerification(email, codeHash, expiresAt, now);
        } else {
            ev.setCodeHash(codeHash);
            ev.setExpiresAt(expiresAt);
            ev.setLastSentAt(now);
            ev.setAttempts(0);
        }
        verifications.save(ev);

        // Send only after the row is persisted so a mail failure doesn't leave a
        // half-written challenge — the @Transactional rolls the save back on throw.
        if (passwordReset) {
            mailService.sendPasswordResetCode(email, code, ttlMinutes);
        } else {
            mailService.sendVerificationCode(email, code, ttlMinutes);
        }
    }

    /**
     * Validate {@code code} for {@code email}; on success the challenge is consumed
     * (deleted) so it can't be replayed. Throws 400 on any invalid/expired/exhausted code.
     */
    @Transactional
    public void verifyAndConsume(String email, String code) {
        EmailVerification ev = verifications.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No verification code requested for this email"));

        if (Instant.now().isAfter(ev.getExpiresAt())) {
            verifications.delete(ev);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification code expired");
        }
        if (ev.getAttempts() >= maxAttempts) {
            verifications.delete(ev);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many incorrect attempts. Please request a new code");
        }
        if (!passwordEncoder.matches(code, ev.getCodeHash())) {
            ev.setAttempts(ev.getAttempts() + 1);
            verifications.save(ev);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect verification code");
        }
        verifications.delete(ev);
    }
}
