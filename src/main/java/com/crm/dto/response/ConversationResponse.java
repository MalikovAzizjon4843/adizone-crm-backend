package com.crm.dto.response;

import com.crm.entity.enums.ConversationType;
import com.crm.entity.enums.MessageType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Suhbatlar ro'yxatining bitta qatori.
 *
 * <p>DIRECT da {@code title} va {@code photoUrl} suhbatdoshdan olinadi —
 * frontend o'zi hisoblab o'tirmaydi. GROUP da {@code title} guruh nomi,
 * {@code photoUrl} null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResponse {

    private Long id;
    private ConversationType type;

    /** DIRECT da suhbatdosh ismi, GROUP da guruh nomi. */
    private String title;

    /** DIRECT da suhbatdosh rasmi, GROUP da null. */
    private String photoUrl;

    /** DIRECT da bitta (suhbatdosh), GROUP da barcha faol a'zolar. */
    private List<ChatUserResponse> participants;

    private String lastMessageText;
    private LocalDateTime lastMessageAt;
    private Long lastMessageSenderId;

    /**
     * Oxirgi xabarning turi. Biriktirmali xabarda matn bo'lmasligi
     * mumkin, ya'ni {@code lastMessageText} null keladi — ro'yxatda
     * bo'sh qator turmasin, frontend shu maydonga qarab "Rasm" yoki
     * "Fayl" deb yozadi.
     */
    private MessageType lastMessageType;

    private long unreadCount;

    /** Joriy foydalanuvchi qayergacha o'qigani. */
    private Long lastReadMessageId;

    /**
     * DIRECT da suhbatdosh qayergacha o'qigani, GROUP da null.
     * Frontend buni o'z xabari id si bilan taqqoslab ✓✓ chizadi.
     */
    private Long peerLastReadMessageId;

    private Boolean isPinned;
    private Boolean isMuted;
}
