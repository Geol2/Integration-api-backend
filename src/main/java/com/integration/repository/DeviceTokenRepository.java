package com.integration.repository;

import com.integration.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByJti(String jti);
    List<DeviceToken> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(Long userId);
    Optional<DeviceToken> findByIdAndUserId(Long id, Long userId);
}
