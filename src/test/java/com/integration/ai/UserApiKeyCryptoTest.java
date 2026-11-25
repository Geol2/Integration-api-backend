package com.integration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 사용자 API 키 암호화: 왕복되고, 매번 다른 암호문, 다른 서버 비밀값으로는 못 읽음, 형식이 틀리면 저장 전에 거절. */
class UserApiKeyCryptoTest {

    private UserApiKeyService svc(String secret) {
        // 저장소는 이 테스트에서 안 씀(null) — 암호화·형식 검사만 봄
        return new UserApiKeyService(null, null, secret);
    }

    @Test
    void encryptDecryptRoundTrip() {
        UserApiKeyService s = svc("server-secret-server-secret-32bytes!!");
        String key = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789";
        String c1 = s.encrypt(key);
        String c2 = s.encrypt(key);
        assertThat(c1).isNotEqualTo(c2);          // IV가 달라 매번 다름
        assertThat(c1).doesNotContain("sk-ant");  // 평문이 안 보임
        assertThat(s.decrypt(c1)).isEqualTo(key);
        assertThat(s.decrypt(c2)).isEqualTo(key);
    }

    @Test
    void otherServerSecretCannotDecrypt() {
        String c = svc("server-secret-server-secret-32bytes!!").encrypt("sk-ant-api03-abcdefghijklmnopqrstuvwxyz");
        assertThrows(ResponseStatusException.class, () -> svc("different-secret-different-secret-32b").decrypt(c));
    }

    @Test
    void malformedKeyIsRejectedBeforeAnyNetworkCall() {
        UserApiKeyService s = svc("server-secret-server-secret-32bytes!!");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> s.save(1L, "not-a-key"));
        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThrows(ResponseStatusException.class, () -> s.save(1L, ""));
    }
}
