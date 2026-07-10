package com.integration.repository;

import com.integration.domain.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<Settings, Long> {
    // PK is userId, so findById(userId) already scopes to the owner.
}
