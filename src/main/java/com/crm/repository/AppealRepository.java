package com.crm.repository;

import com.crm.entity.Appeal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppealRepository extends JpaRepository<Appeal, Long>, JpaSpecificationExecutor<Appeal> {

    /** Kunlik limit: shu chatdan {@code since} dan keyin yuborilgan murojaatlar. */
    @Query(value = "SELECT COUNT(a) FROM Appeal a WHERE a.telegramChatId = :chatId AND a.createdAt >= :since")
    long countByChatSince(@Param(value = "chatId") Long chatId, @Param(value = "since") LocalDateTime since);

    /** Yuboruvchining o'z murojaatlari (anonimlari ham — kalit chat id). */
    List<Appeal> findByTelegramChatIdOrderByCreatedAtDescIdDesc(Long telegramChatId);

    Optional<Appeal> findByIdAndTelegramChatId(Long id, Long telegramChatId);

    Optional<Appeal> findByNumberAndTelegramChatId(String number, Long telegramChatId);
}
