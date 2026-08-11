package com.integration.repository;

import com.integration.domain.HabitLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitLogRepository extends JpaRepository<HabitLog, Long> {

    /** 연속일 계산용 — 최고 연속일은 전 기간을 봐야 해서 기간을 자를 수 없습니다. */
    List<HabitLog> findByUserId(Long userId);

    /**
     * 화면에 그릴 체크 표시용. dateKey가 0패딩된 ISO 문자열이라 사전순 비교가 곧 날짜순
     * 비교이므로, 날짜 타입으로 바꾸지 않고 그대로 범위 조회할 수 있습니다.
     */
    List<HabitLog> findByUserIdAndDateKeyBetween(Long userId, String from, String to);

    /** 하루치만 필요한 곳(습관 알림 스케줄러)에서 씁니다. */
    List<HabitLog> findByUserIdAndDateKey(Long userId, String dateKey);

    Optional<HabitLog> findByHabitIdAndDateKey(Long habitId, String dateKey);

    void deleteByHabitIdAndDateKey(Long habitId, String dateKey);

    void deleteByHabitId(Long habitId);
}
