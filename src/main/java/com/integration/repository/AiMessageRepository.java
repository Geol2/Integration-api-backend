package com.integration.repository;

import com.integration.domain.AiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findByConversationIdOrderByIdAsc(Long conversationId);
    long countByConversationId(Long conversationId);
    void deleteByConversationId(Long conversationId);
}
