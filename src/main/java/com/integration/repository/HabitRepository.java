package com.integration.repository;

import com.integration.domain.Habit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    /** sortOrder는 아직 UI에서 바꿀 수 없어 전부 0이라, id로 한 번 더 정렬해 순서를 고정합니다. */
    List<Habit> findByUserIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long userId);

    Optional<Habit> findByIdAndUserId(Long id, Long userId);
}
