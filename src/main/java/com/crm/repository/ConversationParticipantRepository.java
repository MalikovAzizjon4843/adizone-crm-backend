package com.crm.repository;

import com.crm.entity.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, Long> {

    /**
     * Joriy foydalanuvchining suhbatlari — qadalganlar yuqorida, keyin
     * oxirgi xabar vaqti bo'yicha. Hech qachon xabar bo'lmagan suhbat
     * oxirida turadi ({@code lastMessageAt} null).
     *
     * <p>{@code JOIN FETCH} bor: keyin har bir qator uchun suhbat alohida
     * so'ralmasin.
     */
    @Query("""
        SELECT p FROM ConversationParticipant p
        JOIN FETCH p.conversation c
        WHERE p.user.id = :userId AND p.leftAt IS NULL
        ORDER BY p.isPinned DESC,
                 CASE WHEN c.lastMessageAt IS NULL THEN 1 ELSE 0 END,
                 c.lastMessageAt DESC,
                 c.id DESC
        """)
    List<ConversationParticipant> findActiveForUser(@Param("userId") Long userId);

    /**
     * Bir nechta suhbatning faol ishtirokchilari, foydalanuvchisi bilan —
     * ro'yxatdagi DIRECT suhbatdosh nomi va GROUP a'zolari uchun bitta so'rov.
     */
    @Query("""
        SELECT p FROM ConversationParticipant p
        JOIN FETCH p.user
        WHERE p.conversation.id IN :conversationIds AND p.leftAt IS NULL
        ORDER BY p.id ASC
        """)
    List<ConversationParticipant> findActiveByConversationIds(
        @Param("conversationIds") Collection<Long> conversationIds);

    /** Yozish va o'qish huquqini tekshirish uchun — faol a'zolikni qaytaradi. */
    @Query("""
        SELECT p FROM ConversationParticipant p
        WHERE p.conversation.id = :conversationId
          AND p.user.id = :userId
          AND p.leftAt IS NULL
        """)
    Optional<ConversationParticipant> findActive(@Param("conversationId") Long conversationId,
                                                 @Param("userId") Long userId);

    boolean existsByConversationIdAndUserIdAndLeftAtIsNull(Long conversationId, Long userId);
}
