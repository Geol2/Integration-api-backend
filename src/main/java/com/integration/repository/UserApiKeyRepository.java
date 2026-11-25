package com.integration.repository;

import com.integration.domain.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {
    Optional<UserApiKey> findByUserId(Long userId);
}
