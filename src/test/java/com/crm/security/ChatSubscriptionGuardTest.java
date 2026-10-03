package com.crm.security;

import com.crm.config.ChatChannelInterceptor;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * CH-01 (P0): STOMP SUBSCRIBE faqat oq ro'yxat bo'yicha.
 *
 * <p>Avval interceptor faqat {@code /topic/conversation.} prefiksini tekshirardi; SimpleBroker
 * esa obunada Ant-naqshni qabul qiladi — {@code SUBSCRIBE /topic/**} bilan har qanday xodim
 * barcha suhbatlarni o'qirdi. Haqiqiy interceptor bean'i va bazadagi a'zolik bilan tekshiriladi.
 */
class ChatSubscriptionGuardTest extends Phase5ItBase {

    @Autowired
    ChatChannelInterceptor interceptor;
    @Autowired
    ChatService chatService;

    private final MessageChannel channel = mock(MessageChannel.class);

    private Message<?> subscribe(User user, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-1");
        accessor.setSessionId("session-1");
        accessor.setUser(new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void wildcardAndUnknownDestinations_areRejected() {
        User outsider = newUser(UserRole.TEACHER);

        for (String destination : List.of(
                "/topic/**", "/topic/*", "/topic/conversation.*", "/topic/conv*",
                "/topic/conversation.{id}", "/topic/conversation.1?", "/queue/**",
                "/queue/errors-user1234", "/topic/conversation.", "/topic/conversation.12/x",
                "/topic/other", "/user/queue/other")) {
            assertThatThrownBy(() -> interceptor.preSend(subscribe(outsider, destination), channel))
                .as(destination)
                .isInstanceOf(MessagingException.class);
        }
    }

    @Test
    void conversationTopic_onlyForParticipants_staticTopicsAllowed() {
        User alice = newUser(UserRole.ADMIN);
        User bob = newUser(UserRole.TEACHER);
        User eve = newUser(UserRole.ACCOUNTANT);
        Long conversationId = chatService.getOrCreateDirect(alice, bob.getId()).getId();
        String topic = ChatChannelInterceptor.CONVERSATION_TOPIC_PREFIX + conversationId;

        assertThat(interceptor.preSend(subscribe(alice, topic), channel)).isNotNull();
        assertThat(interceptor.preSend(subscribe(bob, topic), channel)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(subscribe(eve, topic), channel))
            .isInstanceOf(MessagingException.class);

        assertThat(interceptor.preSend(subscribe(eve, "/topic/presence"), channel)).isNotNull();
        assertThat(interceptor.preSend(subscribe(eve, "/user/queue/errors"), channel)).isNotNull();
        // Xodim bildirishnomalari (CRM qo'ng'iroqchasi) — o'z navbati
        assertThat(interceptor.preSend(subscribe(eve, "/user/queue/notifications"), channel)).isNotNull();
    }
}
