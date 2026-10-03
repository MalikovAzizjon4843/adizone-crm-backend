package com.crm.repository;

import com.crm.entity.AppLinkAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AppLinkAttemptRepository extends JpaRepository<AppLinkAttempt, Long> {

    long countByTelegramUserIdAndResultAndCreatedAtAfter(Long telegramUserId, AppLinkAttempt.Result result,
                                                        LocalDateTime after);

    @Modifying
    @Query("DELETE FROM AppLinkAttempt a WHERE a.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
