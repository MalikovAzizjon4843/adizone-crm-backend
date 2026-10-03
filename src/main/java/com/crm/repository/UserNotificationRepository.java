package com.crm.repository;

import com.crm.entity.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    Page<UserNotification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);

    /** Yig'ish: shu obyekt bo'yicha o'qilmagan bildirishnoma (chat — har xabarga yangi qator emas). */
    Optional<UserNotification> findFirstByUserIdAndTypeAndEntityTypeAndEntityIdAndReadAtIsNullOrderByIdDesc(
        Long userId, String type, String entityType, Long entityId);

    @Modifying
    @Query("UPDATE UserNotification n SET n.readAt = :at WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    @Modifying
    @Query("DELETE FROM UserNotification n WHERE n.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
