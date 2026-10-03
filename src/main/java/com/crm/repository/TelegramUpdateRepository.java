package com.crm.repository;

import com.crm.entity.TelegramUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface TelegramUpdateRepository extends JpaRepository<TelegramUpdate, Long> {

    @Modifying
    @Query("DELETE FROM TelegramUpdate u WHERE u.receivedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
