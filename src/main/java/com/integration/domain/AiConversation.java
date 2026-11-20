package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Claude 대화 하나 (G2 안경·웹 공통). 프로젝트마다 여러 개를 둘 수 있습니다. */
@Entity
@Table(name = "ai_conversations", indexes = {
        @Index(name = "idx_ai_conv_user_updated", columnList = "user_id, updated_at"),
        @Index(name = "idx_ai_conv_user_external", columnList = "user_id, external_id")})
@Getter
@Setter
@NoArgsConstructor
public class AiConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Claude가 읽을 프로젝트 이름. null = 일반 질문(프로젝트 없음). */
    @Column(length = 40)
    private String project;

    /** 첫 질문으로 자동으로 붙고, 사용자가 바꿀 수 있습니다. */
    @Column(nullable = false, length = 100)
    private String title;

    /** 밖에서 가져온 대화의 원래 ID(예: PC Claude Code 세션 ID). 다시 동기화할 때 같은 대화를 찾는 데 씁니다. 직접 만든 대화는 null. */
    @Column(name = "external_id", length = 80)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
