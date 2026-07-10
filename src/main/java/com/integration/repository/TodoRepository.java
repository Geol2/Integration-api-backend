package com.integration.repository;

import com.integration.domain.Todo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long> {
    List<Todo> findByUserIdOrderBySortOrderAsc(Long userId);
    Optional<Todo> findByIdAndUserId(Long id, Long userId);
}
