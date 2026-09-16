package com.crm.config;

import com.crm.entity.User;
import com.crm.service.ChatAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Kiruvchi STOMP kadrlarini tekshiradi.
 *
 * <p>Handshake kimligini allaqachon tasdiqlagan, lekin bu yetmaydi:
 * topik nomi ochiq matn, ya'ni ulangan har qanday foydalanuvchi
 * {@code /topic/conversation.42} ga obuna bo'lib, begona yozishmani
 * o'qiy olardi. Shuning uchun SUBSCRIBE da a'zolik alohida tekshiriladi.
 *
 * <p>CONNECT da {@code Principal} yo'qligi — himoyaning ikkinchi qavati:
 * handshake interceptori chetlab o'tilsa ham sessiya ochilmaydi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatChannelInterceptor implements ChannelInterceptor {

    /** {@code /topic/conversation.{id}} — id shu prefiksdan keyin keladi. */
    public static final String CONVERSATION_TOPIC_PREFIX = "/topic/conversation.";

    private final ChatAccessService chatAccessService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            requirePrincipal(accessor.getUser());
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            checkSubscription(accessor);
        }

        return message;
    }

    private void checkSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CONVERSATION_TOPIC_PREFIX)) {
            // /user/queue/… va boshqa shaxsiy manzillarni Spring o'zi
            // sessiyaga bog'laydi — qo'shimcha tekshiruv kerak emas.
            return;
        }

        Long conversationId = parseConversationId(
            destination.substring(CONVERSATION_TOPIC_PREFIX.length()));
        if (conversationId == null) {
            throw new MessagingException("Noto'g'ri topik: " + destination);
        }

        User user = chatAccessService.userOf(requirePrincipal(accessor.getUser()));
        if (!chatAccessService.isParticipant(conversationId, user.getId())) {
            log.warn("Chat: {} suhbatiga begona obuna urinishi, foydalanuvchi {}",
                conversationId, user.getId());
            throw new MessagingException("Bu suhbatga ruxsat yo'q");
        }
    }

    private Principal requirePrincipal(Principal principal) {
        if (principal == null) {
            throw new MessagingException("Avtorizatsiya talab qilinadi");
        }
        return principal;
    }

    private Long parseConversationId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
