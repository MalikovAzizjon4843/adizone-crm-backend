package com.crm.repository;

import com.crm.entity.TelegramOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TelegramOutboxRepository extends JpaRepository<TelegramOutbox, Long> {

    @Query("""
        SELECT o FROM TelegramOutbox o
        WHERE o.status = :pending AND o.notBefore <= :now
        ORDER BY o.priority ASC, o.id ASC
        """)
    List<TelegramOutbox> findDue(@Param("now") LocalDateTime now, @Param("pending") TelegramOutbox.Status pending,
                                Pageable pageable);

    default List<TelegramOutbox> findDue(LocalDateTime now, Pageable pageable) {
        return findDue(now, TelegramOutbox.Status.PENDING, pageable);
    }

    List<TelegramOutbox> findByChatIdOrderByIdAsc(Long chatId);

    /**
     * Dedupe: kalit band bo'lsa hech narsa yozilmaydi va biznes tranzaksiyasi buzilmaydi.
     * {@code ON CONFLICT DO NOTHING} — PostgreSQL va H2 (PostgreSQL rejimi) da bir xil.
     */
    @Modifying
    @Query(value = """
        INSERT INTO telegram_outbox (chat_id, text, reply_markup, silent, priority, status, attempts,
                                     not_before, dedupe_key, event_code, created_at)
        VALUES (:chatId, :text, :markup, :silent, :priority, 'PENDING', 0, :notBefore, :dedupeKey, :eventCode, :createdAt)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("chatId") Long chatId, @Param("text") String text, @Param("markup") String markup,
                       @Param("silent") boolean silent, @Param("priority") String priority,
                       @Param("notBefore") LocalDateTime notBefore, @Param("dedupeKey") String dedupeKey,
                       @Param("eventCode") String eventCode, @Param("createdAt") LocalDateTime createdAt);

    /**
     * Yuborilmagan (PENDING) qatorlarni bekor qilish: kalit {@code prefix} bilan boshlanadi. Kalit
     * {@code <kalit>:x<id>} ga almashadi — shu hodisa yana yuz bersa qayta qo'yish mumkin.
     */
    @Modifying
    @Query(value = """
        UPDATE telegram_outbox SET status = 'CANCELLED', dedupe_key = CONCAT(dedupe_key, ':x', id)
        WHERE status = 'PENDING' AND dedupe_key LIKE CONCAT(:prefix, '%')
        """, nativeQuery = true)
    int cancelPendingByKeyPrefix(@Param(value = "prefix") String prefix);

    /** Telegram 403: shu chatning qolgan PENDING qatorlari ham yuborilmaydi. */
    @Modifying
    @Query(value = """
        UPDATE TelegramOutbox o SET o.status = :failed, o.lastError = :error
        WHERE o.chatId = :chatId AND o.status = :pending
        """)
    int failPendingForChat(@Param(value = "chatId") Long chatId, @Param(value = "error") String error,
                           @Param(value = "pending") TelegramOutbox.Status pending,
                           @Param(value = "failed") TelegramOutbox.Status failed);

    @Modifying
    @Query("DELETE FROM TelegramOutbox o WHERE o.createdAt < :before AND o.status <> :pending")
    int deleteFinishedOlderThan(@Param("before") LocalDateTime before, @Param("pending") TelegramOutbox.Status pending);

    default int deleteFinishedOlderThan(LocalDateTime before) {
        return deleteFinishedOlderThan(before, TelegramOutbox.Status.PENDING);
    }
}
