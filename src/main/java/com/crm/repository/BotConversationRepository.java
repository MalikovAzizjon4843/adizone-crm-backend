package com.crm.repository;

import com.crm.entity.BotConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface BotConversationRepository extends JpaRepository<BotConversation, Long> {

    @Modifying
    @Query(value = "DELETE FROM BotConversation c WHERE c.updatedAt < :before")
    int deleteOlderThan(@Param(value = "before") LocalDateTime before);
}
