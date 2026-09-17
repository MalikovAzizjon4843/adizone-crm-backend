package com.crm.repository;

import com.crm.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Suhbat lentasining birinchi sahifasi: yangi → eski.
     *
     * <p>Saralash {@code id} bo'yicha, {@code createdAt} bo'yicha emas —
     * bir soniyada kelgan ikki xabar ham barqaror tartibda turadi va
     * kursor ({@code before}) aynan shu ustunga tayanadi.
     *
     * <p>O'chirilgan xabarlar ham keladi: ular lentada "Xabar o'chirildi"
     * bo'lib qoladi. Aks holda javob zanjiri uzilib, suhbat mantiqsiz
     * ko'rinardi. Matni va biriktirmalari {@code ChatService} da olib
     * tashlanadi — qidiruv va hisoblagichlar esa ularni ko'rmaydi.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.conversation.id = :conversationId
        ORDER BY m.id DESC
        """)
    List<Message> findLatest(@Param("conversationId") Long conversationId, Pageable pageable);

    /** Keyingi sahifa: {@code before} dan eski xabarlar. */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.conversation.id = :conversationId
          AND m.id < :beforeId
        ORDER BY m.id DESC
        """)
    List<Message> findBefore(@Param("conversationId") Long conversationId,
                             @Param("beforeId") Long beforeId,
                             Pageable pageable);

    /**
     * Berilgan suhbatlarning oxirgi xabari — bitta so'rovda, har biri uchun
     * alohida emas. Ichki so'rov har bir suhbatning eng katta {@code id} sini
     * beradi, tashqisi shu xabarlarni yuboruvchisi bilan o'qiydi.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.id IN (
            SELECT MAX(m2.id) FROM Message m2
            WHERE m2.conversation.id IN :conversationIds AND m2.deletedAt IS NULL
            GROUP BY m2.conversation.id
        )
        """)
    List<Message> findLastMessages(@Param("conversationIds") Collection<Long> conversationIds);

    /**
     * Suhbat → o'qilmaganlar soni, bitta GROUP BY bilan.
     *
     * <p>Chegara — ishtirokchining {@code lastReadMessageId} si; u null
     * bo'lsa 0 olinadi, ya'ni hammasi o'qilmagan hisoblanadi. O'z xabaring
     * sanalmaydi.
     *
     * <p>Natija: {@code [conversationId, count]} juftliklari. Nol
     * o'qilmagani bor suhbat qatorsiz qaytadi — xizmat uni 0 deb oladi.
     */
    @Query("""
        SELECT m.conversation.id, COUNT(m) FROM Message m
        WHERE m.conversation.id IN :conversationIds
          AND m.deletedAt IS NULL
          AND m.sender.id <> :userId
          AND m.id > COALESCE((
                SELECT p.lastReadMessageId FROM ConversationParticipant p
                WHERE p.conversation.id = m.conversation.id AND p.user.id = :userId
              ), 0L)
        GROUP BY m.conversation.id
        """)
    List<Object[]> countUnreadGrouped(@Param("userId") Long userId,
                                      @Param("conversationIds") Collection<Long> conversationIds);

    /**
     * Sidebar/header belgisi uchun umumiy o'qilmaganlar — bitta so'rov.
     * Faqat foydalanuvchi hali a'zo bo'lgan suhbatlar hisobga olinadi.
     */
    @Query("""
        SELECT COUNT(m) FROM Message m
        WHERE m.deletedAt IS NULL
          AND m.sender.id <> :userId
          AND m.conversation.id IN (
                SELECT p.conversation.id FROM ConversationParticipant p
                WHERE p.user.id = :userId AND p.leftAt IS NULL
              )
          AND m.id > COALESCE((
                SELECT p2.lastReadMessageId FROM ConversationParticipant p2
                WHERE p2.conversation.id = m.conversation.id AND p2.user.id = :userId
              ), 0L)
        """)
    long countUnreadForUser(@Param("userId") Long userId);

    /**
     * {@code around} oynasining yuqori yarmi: berilgan xabardan boshlab
     * yangiroqlari, ESKI → YANGI tartibda.
     *
     * <p>Tartib ataylab teskari: {@code LIMIT} kursorga eng yaqin
     * xabarlarni kesib olishi kerak, lentaning eng oxiridagilarni emas.
     * Servis ro'yxatni qayta ag'daradi.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.conversation.id = :conversationId
          AND m.id >= :fromId
        ORDER BY m.id ASC
        """)
    List<Message> findFrom(@Param("conversationId") Long conversationId,
                           @Param("fromId") Long fromId,
                           Pageable pageable);

    /**
     * Umumiy qidiruv: foydalanuvchi a'zo bo'lgan BARCHA suhbatlardan.
     *
     * <p>{@code JOIN FETCH m.conversation} kerak — natijada har bir xabar
     * yonida suhbat nomi ko'rsatiladi va usiz har bir qator uchun alohida
     * so'rov ketardi.
     *
     * <p>{@code ESCAPE '!'} — chatda {@code %} va {@code _} oddiy belgi:
     * "50% chegirma" ni qidirgan odam butun arxivni emas, o'sha xabarni
     * topishi kerak. Naqsh {@code ChatService} da tayyorlanadi.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        JOIN FETCH m.conversation
        WHERE m.deletedAt IS NULL
          AND LOWER(m.text) LIKE :pattern ESCAPE '!'
          AND m.conversation.id IN (
                SELECT p.conversation.id FROM ConversationParticipant p
                WHERE p.user.id = :userId AND p.leftAt IS NULL
              )
        ORDER BY m.id DESC
        """)
    List<Message> searchForUser(@Param("userId") Long userId,
                                @Param("pattern") String pattern,
                                Pageable pageable);

    /** Bitta suhbat ichidagi qidiruv — a'zolik chaqiruvchida tekshirilgan. */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.conversation.id = :conversationId
          AND m.deletedAt IS NULL
          AND LOWER(m.text) LIKE :pattern ESCAPE '!'
        ORDER BY m.id DESC
        """)
    List<Message> searchInConversation(@Param("conversationId") Long conversationId,
                                       @Param("pattern") String pattern,
                                       Pageable pageable);

    /**
     * Javob berilgan xabarlarning qisqa ko'rinishi uchun — bir sahifaga
     * bitta so'rov.
     *
     * <p>O'chirilganlari ham keladi: javob sarlavhasi qoladi, matni esa
     * {@code null} bo'ladi.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.id IN :ids
        """)
    List<Message> findAllWithSender(@Param("ids") Collection<Long> ids);

    /** O'qilgan belgisini qo'yishdan oldin: xabar shu suhbatga tegishlimi. */
    boolean existsByIdAndConversationId(Long id, Long conversationId);
}
