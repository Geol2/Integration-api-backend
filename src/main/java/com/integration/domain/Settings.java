package com.integration.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 1:1 with {@link User}. Mirrors the frontend DEFAULT_SETTINGS object
 * (byeolbit_settings in localStorage). userId is both PK and FK.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Settings {

    @Id
    private Long userId;

    @Column(nullable = false)
    private String userName = "";

    @Column(nullable = false)
    private boolean use24h = true;

    @Column(nullable = false)
    private boolean showSeconds = true;

    @Column(nullable = false)
    private String tempUnit = "C";

    @Column(nullable = false)
    private boolean showQuote = true;

    @Column(nullable = false)
    private String background = "mountain";

    /**
     * How many minutes before a todo's time its reminder push fires. Nullable so the
     * column can be added to existing rows without a backfill; readers coalesce null
     * to the configured default.
     */
    @Column(name = "remind_lead_minutes")
    private Integer remindLeadMinutes = 10;

    /**
     * When true, returning to the tab (visibilitychange → visible) shuffles the scenery
     * background to a different one. Nullable so the column can be added to existing rows
     * without a backfill; readers coalesce null to true (feature on by default).
     */
    @Column(name = "shuffle_bg_on_return")
    private Boolean shuffleBgOnReturn = true;

    /**
     * 습관 알림(아침 예고 · 밤 미실시 확인)을 받을지 여부. 위 필드들과 같은 이유로 nullable이고,
     * 읽는 쪽에서 null은 "켜짐"으로 봅니다.
     */
    @Column(name = "habit_remind")
    private Boolean habitRemind = true;

    /** 아침 예고를 보낼 로컬 시각 "HH:mm". null이면 서버 기본값(app.push.habit.morning-time). */
    @Column(name = "habit_morning_time")
    private String habitMorningTime = "07:00";

    /** 밤에 미실시 습관을 확인시켜 줄 로컬 시각 "HH:mm". null이면 서버 기본값. */
    @Column(name = "habit_evening_time")
    private String habitEveningTime = "22:00";

    /**
     * 아침/밤 습관 알림을 마지막으로 보낸 날("2026-08-11").
     * <p>매분 도는 스케줄러가 "오늘 이미 보냈나"를 판단하는 유일한 근거입니다. 메모리에
     * 두지 않고 저장하는 이유는, 서버가 재시작돼도 같은 알림이 다시 가지 않게 하기 위함입니다.
     */
    @Column(name = "habit_morning_sent_on")
    private String habitMorningSentOn;

    @Column(name = "habit_evening_sent_on")
    private String habitEveningSentOn;

    public Settings(Long userId) {
        this.userId = userId;
    }
}
