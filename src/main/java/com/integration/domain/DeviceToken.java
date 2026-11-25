package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 기기용 토큰 — PC·파이의 pc-agent, 동기화 스크립트처럼 사람이 로그인하지 않는 프로그램이 쓰는 열쇠.
 * 비밀번호를 파일에 두지 않아도 되고, 비밀번호를 바꿔도 끊기지 않으며, 기기별로 따로 끊을 수 있어요.
 * 토큰 자체(JWT)는 저장하지 않고 고유 id(jti)만 저장합니다 — 끊으면(revoked) 그 jti 의 토큰은 바로 무효.
 */
@Entity
@Table(name = "device_tokens")
@Getter
@Setter
@NoArgsConstructor
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, unique = true, length = 40)
    private String jti;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean revoked = false;

    public DeviceToken(Long userId, String name, String jti) {
        this.userId = userId;
        this.name = name;
        this.jti = jti;
    }
}
