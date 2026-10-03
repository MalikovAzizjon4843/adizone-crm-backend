package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.Conversation;
import com.crm.entity.Message;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AppIdentityRepository;
import com.crm.service.ExternalChatListener;
import com.crm.telegram.TelegramOutboxService;
import com.crm.telegram.TelegramProperties;
import com.crm.telegram.TelegramUpdateHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * EXTERNAL chat push'lari (telegram-platform §11.3), outbox orqali, NORMAL: bir suhbat bo'yicha 5 daqiqalik
 * oynada bitta, sokin soatlarda (21:00–08:00) — 08:00 da bitta.
 * <ul>
 *   <li>xodim yozdi → Mini App foydalanuvchisiga;</li>
 *   <li>Mini App foydalanuvchisi o'qituvchiga yozdi → o'qituvchiga (app'da o'qituvchi rejimida ulangan bo'lsa).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppChatPushService implements ExternalChatListener {

    private static final int PREVIEW = 100;

    private final AppIdentityRepository identityRepository;
    private final TelegramOutboxService outboxService;
    private final TelegramProperties telegramProperties;

    @Override
    public void onStaffMessage(Conversation conversation, User sender, Message message) {
        identityRepository.findById(conversation.getExternalIdentityId())
            .filter(i -> i.isActive() && i.getChatId() != null)
            .ifPresent(i -> outboxService.enqueue(i.getChatId(),
                "💬 <b>" + TelegramUpdateHandler.escape(fullName(sender)) + "</b> (" + staffLabel(sender.getRole())
                    + "):\n" + preview(message),
                button(conversation.getId()), TelegramOutbox.Priority.NORMAL,
                outboxService.windowKey("chat:" + conversation.getId() + ":app:" + i.getId()), "CHAT_MESSAGE_APP"));
    }

    /** App foydalanuvchisi yozdi — faqat TEACHER suhbatida o'qituvchiga (SUPPORT/DIRECTOR CRM'da ko'radi). */
    public void onAppMessage(Conversation conversation, Message message) {
        if (!"TEACHER".equals(conversation.getExternalTarget()) || conversation.getExternalStaffUserId() == null) {
            return;
        }
        Long userId = conversation.getExternalStaffUserId();
        identityRepository.findFirstByStaffUserIdAndStatus(userId, AppIdentity.Status.ACTIVE)
            .filter(i -> i.getChatId() != null)
            .ifPresent(i -> outboxService.enqueue(i.getChatId(),
                "💬 <b>" + TelegramUpdateHandler.escape(conversation.getTitle()) + "</b>:\n" + preview(message),
                button(conversation.getId()), TelegramOutbox.Priority.NORMAL,
                outboxService.windowKey("chat:" + conversation.getId() + ":staff:" + userId), "CHAT_MESSAGE_STAFF"));
    }

    /** Xodim yorlig'i app'da: "ustoz" / "direktor" / "menejer" — rol kodi ko'rsatilmaydi. */
    static String staffLabel(UserRole role) {
        if (role == UserRole.TEACHER) {
            return "ustoz";
        }
        return role == UserRole.SUPER_ADMIN ? "direktor" : "menejer";
    }

    private Map<String, Object> button(Long conversationId) {
        String url = telegramProperties.getWebappUrl();
        return TelegramOutboxService.openAppButton("Javob berish",
            url == null || url.isBlank() ? null : url + "/#/chats/" + conversationId);
    }

    private static String preview(Message message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return "📎 Rasm";
        }
        String cut = text.length() > PREVIEW ? text.substring(0, PREVIEW) + "..." : text;
        return TelegramUpdateHandler.escape(cut);
    }

    private static String fullName(User u) {
        return (u.getFirstName() + " " + u.getLastName()).trim();
    }
}
