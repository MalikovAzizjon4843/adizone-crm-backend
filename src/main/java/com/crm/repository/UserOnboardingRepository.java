package com.crm.repository;

import com.crm.entity.UserOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserOnboardingRepository extends JpaRepository<UserOnboarding, UserOnboarding.Key> {

    List<UserOnboarding> findByUserIdOrderByTourKeyAsc(Long userId);

    long countByUserId(Long userId);

    /**
     * Idempotent belgilash: parallel ikki PUT ham PK xatosisiz o'tadi, birinchi {@code seen_at} saqlanadi.
     * {@code ON CONFLICT DO NOTHING} — PostgreSQL va H2 (PostgreSQL rejimi) da bir xil.
     */
    @Modifying
    @Query(value = "INSERT INTO user_onboarding (user_id, tour_key, seen_at) VALUES (:userId, :key, :seenAt) "
        + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("key") String key, @Param("seenAt") LocalDateTime seenAt);

    @Modifying
    @Query("DELETE FROM UserOnboarding o WHERE o.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
