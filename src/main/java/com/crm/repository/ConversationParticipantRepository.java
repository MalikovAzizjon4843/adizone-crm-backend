package com.crm.repository;

import com.crm.entity.ConversationParticipant;
import com.crm.entity.enums.ConversationType;
import org.springframework.data.domain.Pageable;
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

    /**
     * Foydalanuvchining suhbatdoshlari: u bilan bitta suhbatda turgan
     * hamma, o'zidan tashqari. Onlayn holatning boshlang'ich suratini
     * shular uchun beramiz — 100 xodimning hammasi kerak emas.
     *
     * <p>Natija: {@code [userId, lastSeenAt]}. Onlaynlik bazada emas,
     * xotirada, shuning uchun u bu yerda yo'q.
     */
    @Query("""
        SELECT DISTINCT p.user.id, p.user.lastSeenAt
        FROM ConversationParticipant p
        WHERE p.leftAt IS NULL
          AND p.user.id <> :userId
          AND p.conversation.id IN (
                SELECT mine.conversation.id FROM ConversationParticipant mine
                WHERE mine.user.id = :userId AND mine.leftAt IS NULL
              )
        """)
    List<Object[]> findPeerPresence(@Param("userId") Long userId);

    /**
     * Nom bo'yicha qidiruv — foydalanuvchining o'z a'zoliklarini qaytaradi,
     * suhbat id larini emas: ro'yxat qatorini qurish uchun baribir shu
     * qatorlar kerak, ya'ni ikkinchi so'rov yo'q.
     *
     * <p>GROUP nomi bo'yicha, DIRECT esa suhbatdosh ismi bo'yicha
     * qidiriladi. GROUP ichidagi a'zo ismi ataylab qidirilmaydi:
     * "nom bo'yicha" degani guruh nomi, aks holda bitta keng tarqalgan
     * ism butun ro'yxatni qaytarardi.
     */
    @Query("""
        SELECT p FROM ConversationParticipant p
        JOIN FETCH p.conversation c
        WHERE p.user.id = :userId AND p.leftAt IS NULL
          AND (
               (c.type = :groupType
                    AND LOWER(c.title) LIKE :pattern ESCAPE '!')
            OR (c.type = :directType AND EXISTS (
                    SELECT 1 FROM ConversationParticipant peer
                    WHERE peer.conversation.id = c.id
                      AND peer.leftAt IS NULL
                      AND peer.user.id <> :userId
                      AND LOWER(CONCAT(peer.user.firstName, ' ', peer.user.lastName))
                            LIKE :pattern ESCAPE '!'
                  ))
          )
        ORDER BY c.lastMessageAt DESC, c.id DESC
        """)
    List<ConversationParticipant> searchByName(@Param("userId") Long userId,
                                               @Param("pattern") String pattern,
                                               @Param("groupType") ConversationType groupType,
                                               @Param("directType") ConversationType directType,
                                               Pageable pageable);
}
