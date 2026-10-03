package com.crm.repository;

import com.crm.entity.AppIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppIdentityRepository extends JpaRepository<AppIdentity, Long> {

    Optional<AppIdentity> findByTelegramUserId(Long telegramUserId);

    List<AppIdentity> findByStaffUserId(Long staffUserId);

    /** O'qituvchi rejimidagi faol identity (bot push uchun chat id). */
    Optional<AppIdentity> findFirstByStaffUserIdAndStatus(Long staffUserId, AppIdentity.Status status);
}
