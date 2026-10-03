package com.crm.repository;

import com.crm.entity.AppIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppIdentityRepository extends JpaRepository<AppIdentity, Long> {

    Optional<AppIdentity> findByTelegramUserId(Long telegramUserId);
}
