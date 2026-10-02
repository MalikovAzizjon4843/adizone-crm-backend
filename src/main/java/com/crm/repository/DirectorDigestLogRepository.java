package com.crm.repository;

import com.crm.entity.DirectorDigestLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface DirectorDigestLogRepository extends JpaRepository<DirectorDigestLog, Long> {

    boolean existsByStatDateAndChatIdAndOkTrue(LocalDate statDate, String chatId);

    java.util.Optional<DirectorDigestLog> findByStatDateAndChatId(LocalDate statDate, String chatId);
}
