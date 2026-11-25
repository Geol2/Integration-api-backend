package com.integration.ai;

import com.integration.domain.UserApiKey;
import com.integration.repository.UserApiKeyRepository;
import com.integration.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사용자별 Anthropic API 키(BYOK). 키는 AES-GCM으로 암호화해 DB에 두고, 답할 때만 꺼내 그 사용자 질문에 씁니다.
 * 암호화 열쇠는 서버 비밀값(JWT_SECRET)에서 용도별로 파생 — DB만 털려서는 키를 못 읽어요.
 * 서버 주인의 키·구독으로 넘어가는 경로는 없습니다(AiService 참고).
 */
@Service
public class UserApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(UserApiKeyService.class);
    private static final Pattern KEY = Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,300}");
    private static final SecureRandom RNG = new SecureRandom();

    public record Info(boolean hasKey, String last4, Instant updatedAt) {}

    private final UserApiKeyRepository keys;
    private final UserRepository users;
    private final SecretKeySpec aes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public UserApiKeyService(UserApiKeyRepository keys, UserRepository users, @Value("${app.jwt.secret}") String secret) {
        this.keys = keys;
        this.users = users;
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(("momentum/user-api-key/v1:" + secret).getBytes(StandardCharsets.UTF_8));
            this.aes = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Info info(Long userId) {
        return keys.findByUserId(userId).map(k -> new Info(true, k.getLast4(), k.getUpdatedAt())).orElse(new Info(false, null, null));
    }

    public boolean hasKey(String email) {
        if (email == null) return false;
        return users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).or(() -> users.findByEmail(email.strip()))
                .flatMap(u -> keys.findByUserId(u.getId())).isPresent();
    }

    /** 답할 때만: 그 사용자의 키 평문. */
    public Optional<String> keyFor(Long userId) {
        return keys.findByUserId(userId).map(k -> decrypt(k.getCipher()));
    }

    /** 형식 확인 → Anthropic에 실제로 맞는 키인지 확인(무료 모델 목록 호출) → 암호화 저장. */
    public Info save(Long userId, String raw) {
        String key = raw == null ? "" : raw.strip();
        if (!KEY.matcher(key).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sk-ant- 로 시작하는 Anthropic API 키를 넣어 주세요.");
        verify(key);
        UserApiKey k = keys.findByUserId(userId).orElseGet(UserApiKey::new);
        k.setUserId(userId);
        k.setCipher(encrypt(key));
        k.setLast4(key.substring(key.length() - 4));
        k.setUpdatedAt(Instant.now());
        keys.save(k);
        return info(userId);
    }

    public void delete(Long userId) {
        keys.findByUserId(userId).ifPresent(keys::delete);
    }

    private void verify(String key) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=1"))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-api-key", key).header("anthropic-version", "2023-06-01").GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 401 || r.statusCode() == 403)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anthropic이 이 키를 받아 주지 않아요. 키를 다시 확인해 주세요.");
            if (r.statusCode() >= 400) log.warn("API 키 확인 응답 {} — 일단 저장합니다", r.statusCode());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "키를 확인하지 못했어요. 잠시 후 다시 해 주세요.");
        }
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("키 암호화 실패", e);
        }
    }

    String decrypt(String b64) {
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 API 키를 읽지 못했어요. 키를 다시 등록해 주세요.");
        }
    }
}
