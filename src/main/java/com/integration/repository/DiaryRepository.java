package com.integration.repository;

import com.integration.domain.Diary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiaryRepository extends JpaRepository<Diary, Long> {
    List<Diary> findByUserId(Long userId);
    Optional<Diary> findByUserIdAndDateKey(Long userId, String dateKey);
    Optional<Diary> findByIdAndUserId(Long id, Long userId);
    void deleteByUserIdAndDateKey(Long userId, String dateKey);
}
