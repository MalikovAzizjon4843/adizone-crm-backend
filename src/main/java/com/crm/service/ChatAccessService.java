package com.crm.service;

import com.crm.config.Messages;
import com.crm.entity.ConversationParticipant;
import com.crm.entity.User;
import com.crm.exception.ForbiddenException;
import com.crm.repository.ConversationParticipantRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;

/**
 * Chatga kim kira olishini aniqlaydi — {@link LeadAccessService} naqshi bo'yicha.
 *
 * <p>Ikkita chaqiruvchi bor va ular foydalanuvchini har xil yo'l bilan
 * biladi: REST {@code SecurityContextHolder} dan, WebSocket esa handshake
 * bergan {@code Principal} dan. Shuning uchun ikkalasi uchun alohida
 * kirish nuqtasi.
 *
 * <p>Qoida bitta: suhbatga faqat uning faol ishtirokchisi kira oladi.
 * Rol tekshiruvi yo'q — chat hamma xodim uchun.
 */
@Service
@RequiredArgsConstructor
public class ChatAccessService {

    private final UserRepository userRepository;
    private final ConversationParticipantRepository participantRepository;
    private final Messages messages;

    /** REST uchun: joriy so'rovning foydalanuvchisi. */
    @Transactional(readOnly = true)
    public User currentUserOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            throw new ForbiddenException(messages.get("chat.auth.required"));
        }
        return byUsernameOrThrow(auth.getName());
    }

    /** WebSocket uchun: handshake tasdiqlagan {@code Principal}. */
    @Transactional(readOnly = true)
    public User userOf(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new ForbiddenException(messages.get("chat.auth.required"));
        }
        return byUsernameOrThrow(principal.getName());
    }

    private User byUsernameOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ForbiddenException(messages.get("chat.auth.required")));
    }

    /**
     * Suhbatdagi faol a'zolikni qaytaradi; a'zo bo'lmasa 403.
     *
     * <p>"Suhbat topilmadi" emas, aynan 403: begona odamga qaysi
     * {@code id} lar mavjudligini bilish imkonini bermaslik uchun.
     */
    @Transactional(readOnly = true)
    public ConversationParticipant requireParticipant(Long conversationId, Long userId) {
        return participantRepository.findActive(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException(messages.get("chat.notParticipant")));
    }

    /** Obunani tekshirish uchun — istisnosiz, faqat ha/yo'q. */
    @Transactional(readOnly = true)
    public boolean isParticipant(Long conversationId, Long userId) {
        return participantRepository
            .existsByConversationIdAndUserIdAndLeftAtIsNull(conversationId, userId);
    }
}
