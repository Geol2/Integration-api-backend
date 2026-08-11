package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 습관 트래커의 "정의" 쪽. 계정당 보통 몇 개뿐이라 프론트가 통째로 받아갑니다.
 * 날짜별 체크 기록은 계속 쌓이므로 {@link HabitLog}로 분리돼 있습니다.
 */
@Entity
@Table(name = "habits")
@Getter
@Setter
@NoArgsConstructor
public class Habit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    /** 목록에서 습관을 알아보게 하는 이모지. 비어 있으면 프론트가 기본값을 씁니다. */
    @Column(nullable = false)
    private String emoji = "";

    /** 체크 표시·연속일 뱃지에 쓰는 색(HEX). 비어 있으면 프론트 기본 색. */
    @Column(nullable = false)
    private String color = "";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /**
     * 보관한 습관은 목록에서 빠지지만 지난 기록은 남습니다. 1단계 UI에는 삭제만 있고
     * 보관 버튼이 없지만, "기록을 잃지 않고 그만두기"를 나중에 붙일 수 있게 미리 둡니다.
     */
    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
