package com.crm.controller;

import com.crm.dto.request.ChatReadRequest;
import com.crm.dto.request.ChatSendRequest;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatReadReceiptResponse;
import com.crm.entity.User;
import com.crm.service.ChatAccessService;
import com.crm.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * Chatning STOMP kirish nuqtalari.
 *
 * <p>Manzillar: {@code /app/chat.send} va {@code /app/chat.read}.
 * Ikkalasi ham natijani {@code /topic/conversation.{id}} ga tarqatadi —
 * bitta topik, chunki frontend baribir shu suhbat uchun bitta obuna
 * ochadi; hodisa turi {@code type} maydonida.
 *
 * <p>Kim yuborayotgani tanadan olinmaydi: {@code Principal} handshake'da
 * tekshirilgan tokendan keladi, ya'ni boshqa odam nomidan yozib
 * bo'lmaydi.
 *
 * <p>Tarqatish servisdan keyin — servis metodi qaytgach tranzaksiya
 * yopilgan bo'ladi va xabar bazada ko'rinadi.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatSocketController {

    /** Suhbat topikining prefiksi; to'liq manzil — prefiks + suhbat id si. */
    private static final String CONVERSATION_TOPIC = "/topic/conversation.";

    /** Shaxsiy xatoliklar navbati: mijoz {@code /user/queue/errors} ga obuna bo'ladi. */
    private static final String ERROR_QUEUE = "/queue/errors";

    private final ChatService chatService;
    private final ChatAccessService chatAccessService;
    private final SimpMessagingTemplate messagingTemplate;

    /** TASK 4 — xabar yuborish. */
    @MessageMapping("/chat.send")
    public void send(@Valid @Payload ChatSendRequest request, Principal principal) {
        User sender = chatAccessService.userOf(principal);
        ChatMessageResponse message = chatService.send(sender, request);
        messagingTemplate.convertAndSend(topicOf(message.getConversationId()), message);
    }

    /** TASK 5 — o'qilgan belgisi. */
    @MessageMapping("/chat.read")
    public void read(@Valid @Payload ChatReadRequest request, Principal principal) {
        User user = chatAccessService.userOf(principal);
        Optional<ChatReadReceiptResponse> receipt = chatService.markRead(user, request);
        // empty — kursor oldinga surilmadi, ya'ni aytadigan yangilik yo'q.
        receipt.ifPresent(event ->
            messagingTemplate.convertAndSend(topicOf(event.getConversationId()), event));
    }

    /**
     * Xatolikni yuboruvchining o'ziga qaytaradi.
     *
     * <p>STOMP da javob tushunchasi yo'q: bu bo'lmasa rad etilgan xabar
     * jimgina yo'qolib, frontend optimistik qo'yilgan xabarni abadiy
     * "yuborilmoqda" holatida qoldirardi. {@code clientId} qaytmaydi,
     * shuning uchun mijoz eng oxirgi urinishini bekor qiladi.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleException(Exception exception, Principal principal) {
        String message = exception.getMessage() != null
            ? exception.getMessage()
            : "Xabarni yuborib bo'lmadi";
        log.warn("Chat STOMP xatosi ({}): {}",
            principal != null ? principal.getName() : "anonim", message);

        if (principal != null) {
            messagingTemplate.convertAndSendToUser(principal.getName(), ERROR_QUEUE,
                Map.of("type", "ERROR", "message", message));
        }
    }

    private String topicOf(Long conversationId) {
        return CONVERSATION_TOPIC + conversationId;
    }
}
