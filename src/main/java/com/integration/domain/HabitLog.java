package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * "이 습관을 이 날 했다"는 한 줄. 있으면 완료, 없으면 미완료입니다.
 * <p>
 * (habit_id, date_key) 유니크 제약이 핵심입니다 — 체크는 PUT으로 멱등하게 동작해야
 * 하는데, 연타나 재시도가 겹쳐도 이 제약 덕분에 같은 날 기록이 두 줄 생기지 않습니다.
 */
@Entity
@Table(
    name = "habit_logs",
    uniqueConstraints = @UniqueConstraint(columnNames = {"habit_id", "date_key"}),
    indexes = @Index(name = "idx_habit_logs_user_date", columnList = "user_id, date_key")
)
@Getter
@Setter
@NoArgsConstructor
public class HabitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 조회를 습관 조인 없이 계정 단위로 끊기 위해 중복 저장합니다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "habit_id", nullable = false)
    private Long habitId;

    /**
     * 브라우저의 로컬 날짜 문자열, 예: "2026-08-11". 서버에서 시간대 변환을 하지 않습니다 —
     * 사용자가 "오늘"이라고 부르는 날은 브라우저 기준이고, 다른 리소스(todos·diaries)도
     * 같은 규칙을 씁니다. 항상 0패딩된 ISO 형식이라 문자열 비교로 기간 조회가 됩니다.
     */
    @Column(name = "date_key", nullable = false)
    private String dateKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
