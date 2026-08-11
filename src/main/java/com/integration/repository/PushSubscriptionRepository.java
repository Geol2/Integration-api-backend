package com.integration.repository;

import com.integration.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUserId(Long userId);

    /**
     * 알림을 받을 수 있는 계정만 추립니다 — 습관 알림 스케줄러가 매분 돌면서
     * 구독조차 없는 계정의 습관·로그를 조회하지 않도록 하는 1차 필터입니다.
     */
    @Query("select distinct s.userId from PushSubscription s")
    List<Long> findDistinctUserIds();
    Optional<PushSubscription> findByEndpoint(String endpoint);
    void deleteByEndpointAndUserId(String endpoint, Long userId);
    long countByUserId(Long userId);
}
