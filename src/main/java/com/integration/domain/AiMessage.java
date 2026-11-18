package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 대화 속 질문·답 한 쌍. 긴 답·코드가 들어가므로 TEXT 컬럼을 씁니다(Diary와 같은 이유로 @Lob 아님). */
@Entity
@Table(name = "ai_messages", indexes = @Index(name = "idx_ai_msg_conv", columnList = "conversation_id, id"))
@Getter
@Setter
@NoArgsConstructor
public class AiMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 질문에 붙인 사진 수(사진 자체는 저장하지 않습니다). */
    @Column(name = "image_count", nullable = false)
    private int imageCount = 0;

    /** 안경용 요약 답. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    /** 폰·PC용 전체 답(수정 코드 등). 없으면 null. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 할 일로 저장할 작업 단계, 줄바꿈으로 구분. 없으면 null. */
    @Column(columnDefinition = "TEXT")
    private String steps;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
