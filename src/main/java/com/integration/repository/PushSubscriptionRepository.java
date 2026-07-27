package com.integration.repository;

import com.integration.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUserId(Long userId);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    void deleteByEndpointAndUserId(String endpoint, Long userId);
    long countByUserId(Long userId);
}
