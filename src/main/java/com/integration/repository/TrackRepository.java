package com.integration.repository;

import com.integration.domain.Track;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackRepository extends JpaRepository<Track, Long> {
    List<Track> findByUserIdOrderBySortOrderAsc(Long userId);
    Optional<Track> findByIdAndUserId(Long id, Long userId);
}
