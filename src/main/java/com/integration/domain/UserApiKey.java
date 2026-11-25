package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 사용자가 직접 등록한 Anthropic API 키(암호화해서 저장). 다른 사용자는 이 키로만 Claude를 씁니다. */
@Entity
@Table(name = "user_api_keys")
@Getter
@Setter
@NoArgsConstructor
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    /** base64(iv 12바이트 + AES-GCM 암호문). 평문 키는 어디에도 저장하지 않아요. */
    @Column(nullable = false, length = 1024)
    private String cipher;

    @Column(nullable = false, length = 8)
    private String last4;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
