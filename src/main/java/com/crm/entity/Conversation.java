package com.crm.entity;

import com.crm.entity.converter.ConversationTypeConverter;
import com.crm.entity.enums.ConversationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ichki chatning bitta yozishmasi — DIRECT (ikki kishi) yoki GROUP.
 *
 * <p>{@code lastMessageAt} ataylab shu yerda takrorlanadi: suhbatlar
 * ro'yxati shu ustun bo'yicha saralanadi va aks holda har bir suhbat uchun
 * oxirgi xabar vaqtini alohida izlash kerak bo'lardi.
 *
 * <p>{@code directKey} — DIRECT suhbatning ikki ishtirokchisidan yasalgan
 * kalit ({@code kichikId:kattaId}), GROUP da {@code null}. UNIQUE bo'lgani
 * uchun bir juftlik uchun ikkinchi suhbat ochilmaydi: ikki so'rov bir vaqtda
 * kelsa ham, bazani o'zi ikkinchisini rad etadi. GROUP larda null —
 * PostgreSQL bir nechta null ni takror deb hisoblamaydi.
 */
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conversations_last_message", columnList = "last_message_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Convert(converter = ConversationTypeConverter.class)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private ConversationType type = ConversationType.DIRECT;

    /** Faqat GROUP uchun; DIRECT da null — nom suhbatdoshdan olinadi. */
    @Column(name = "title", length = 255)
    private String title;

    /** DIRECT uchun {@code kichikId:kattaId}, GROUP uchun null. */
    @Column(name = "direct_key", unique = true, length = 64, updatable = false)
    private String directKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** Ro'yxatni saralash uchun; har bir yangi xabarda yangilanadi. */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /** Ikki foydalanuvchi uchun tartibdan qat'i nazar bitta kalit. */
    public static String directKeyOf(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return min + ":" + max;
    }

    public boolean isDirect() {
        return type == ConversationType.DIRECT;
    }
}
