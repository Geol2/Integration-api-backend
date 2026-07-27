package com.integration.repository;

import com.integration.domain.Todo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long> {
    List<Todo> findByUserIdOrderBySortOrderAsc(Long userId);
    Optional<Todo> findByIdAndUserId(Long id, Long userId);

    /**
     * Reminder candidates for ReminderScheduler: unfinished, has a time, not yet notified,
     * and dated within the few days the scheduler cares about. Deliberately not filtered by
     * user — the scheduler serves everyone in one pass.
     */
    List<Todo> findByDoneFalseAndRemindedAtIsNullAndTimeLabelIsNotNullAndDateKeyIn(List<String> dateKeys);
}
