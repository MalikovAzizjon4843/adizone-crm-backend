package com.crm.telegram;

import com.crm.appeal.AppealBotFlow;
import com.crm.entity.AppIdentity;
import com.crm.entity.Student;
import com.crm.entity.TelegramUpdate;
import com.crm.miniapp.MiniAppLinkService;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.TelegramUpdateRepository;
import com.crm.service.CenterSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bot update'lari (docs/design/telegram-platform.md §1, §3.4) — minimal bot, faqat shaxsiy chat:
 * <ul>
 *   <li>{@code /start} (va boshqa matn) — "Ilovani ochish" ({@code web_app}, inline) va bog'lanmagan
 *       bo'lsa "Telefon raqamni yuborish" ({@code request_contact}, reply klaviatura). Ikki xabar:
 *       klaviaturadagi {@code web_app} tugmasi Mini App'ga initData bermaydi, kontakt tugmasi esa faqat
 *       reply klaviaturada bo'ladi;</li>
 *   <li>kontakt — {@code contact.user_id == from.id} sharti (birovning kontakt kartasi rad),
 *       {@link MiniAppLinkService#linkFromContact};</li>
 *   <li>{@code /stop} — uzish.</li>
 * </ul>
 * Guruh/kanal chatlari, botlar va boshqa update turlari e'tiborsiz. Javoblar commit'dan keyin
 * {@link TelegramBotApi} orqali yuboriladi — bu servis faqat ularni qaytaradi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramUpdateHandler {

    public static final String BTN_OPEN_APP = "Ilovani ochish";
    public static final String BTN_SEND_PHONE = "Telefon raqamni yuborish";

    private final MiniAppLinkService linkService;
    private final AppIdentityRepository identityRepository;
    private final TelegramUpdateRepository updateRepository;
    private final CenterSettingsService centerSettingsService;
    private final TelegramProperties properties;
    private final Clock billingClock;
    private final AppealBotFlow appealFlow;

    /**
     * Yuboriladigan javob. {@code callbackQueryId} berilgan bo'lsa — bu xabar emas, {@code answerCallbackQuery}
     * ({@code html} — qisqa matn, null bo'lishi mumkin).
     */
    public record Outgoing(long chatId, String html, Map<String, Object> replyMarkup, String callbackQueryId) {

        public Outgoing(long chatId, String html, Map<String, Object> replyMarkup) {
            this(chatId, html, replyMarkup, null);
        }

        public static Outgoing answer(String callbackQueryId, String text) {
            return new Outgoing(0, text, null, callbackQueryId);
        }

        public boolean isCallbackAnswer() {
            return callbackQueryId != null;
        }
    }

    /**
     * {@code update_id} ni qayd qiladi; avval ko'rilgan bo'lsa {@code false} (Telegram qayta yuborgan).
     * Alohida tranzaksiya: parallel takrorda UNIQUE xatosi asosiy ishni buzmasin.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean register(long updateId, String kind) {
        if (updateRepository.existsById(updateId)) {
            return false;
        }
        try {
            updateRepository.saveAndFlush(TelegramUpdate.builder()
                .updateId(updateId)
                .kind(kind)
                .receivedAt(LocalDateTime.now(billingClock))
                .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Transactional
    public List<Outgoing> handle(JsonNode update) {
        JsonNode callback = update.path("callback_query");
        if (!callback.isMissingNode() && !callback.isNull()) {
            return handleCallback(callback);
        }
        JsonNode message = update.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return List.of();
        }
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        if (!"private".equals(chat.path("type").asText()) || from.path("is_bot").asBoolean(false)
                || !from.path("id").canConvertToLong()) {
            return List.of();
        }
        long chatId = chat.path("id").asLong();
        long fromId = from.path("id").asLong();
        if (chatId != fromId) {
            return List.of();
        }

        JsonNode contact = message.path("contact");
        boolean hasContact = !contact.isMissingNode() && !contact.isNull();
        if (hasContact && !appealFlow.awaitingContact(chatId)) {
            return handleContact(chatId, from, contact);
        }
        String text = message.path("text").asText("").trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("/stop")) {
            return handleStop(chatId, fromId);
        }
        // Murojaatlar (§12): yangi oqim → murojaat xabariga reply → ochiq suhbat qadami
        if (appealFlow.isStartCommand(text)) {
            return appealFlow.start(chatId, from);
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("/start")) {
            // /start — ochiq murojaat suhbati tashlanadi (matn sifatida qabul qilinmasin)
            appealFlow.reset(chatId);
            return handleStart(chatId, fromId);
        }
        Optional<List<Outgoing>> reply = appealFlow.onReplyTo(chatId, message);
        if (reply.isPresent()) {
            return reply.get();
        }
        List<Outgoing> step = appealFlow.onMessage(chatId, from, message);
        if (step != null) {
            return step;
        }
        return handleStart(chatId, fromId);
    }

    /**
     * Inline tugma ({@code callback_query}) — faqat shaxsiy chat va {@code apl:*} (murojaatlar, §12). Har doim
     * {@code answerCallbackQuery} (aks holda tugmada "yuklanmoqda" qoladi).
     */
    private List<Outgoing> handleCallback(JsonNode callback) {
        String id = callback.path("id").asText(null);
        if (id == null) {
            return List.of();
        }
        JsonNode from = callback.path("from");
        JsonNode chat = callback.path("message").path("chat");
        String data = callback.path("data").asText("");
        if (!"private".equals(chat.path("type").asText()) || from.path("is_bot").asBoolean(false)
                || !from.path("id").canConvertToLong() || chat.path("id").asLong() != from.path("id").asLong()
                || !data.startsWith(AppealBotFlow.CB)) {
            return List.of(Outgoing.answer(id, null));
        }
        AppealBotFlow.CallbackResult result = appealFlow.onCallback(chat.path("id").asLong(), from, data);
        List<Outgoing> out = new ArrayList<>();
        out.add(Outgoing.answer(id, result.answer()));
        out.addAll(result.messages());
        return out;
    }

    private List<Outgoing> handleStart(long chatId, long fromId) {
        AppIdentity identity = identityRepository.findByTelegramUserId(fromId)
            .filter(AppIdentity::isActive).orElse(null);
        List<Outgoing> out = new ArrayList<>();
        if (identity != null) {
            out.add(new Outgoing(chatId,
                "👋 <b>Adizone</b> mini ilovasiga xush kelibsiz!\n\nHisobingiz ulangan. "
                    + "Dars jadvali, davomat va to'lovlarni ilovada ko'ring.",
                withAppealRow(openAppMarkup(), identity.getLanguage())));
            return out;
        }
        out.add(new Outgoing(chatId,
            "👋 <b>Adizone</b> mini ilovasiga xush kelibsiz!\n\n"
                + "Dars jadvali, davomat va to'lovlarni shu yerda ko'rasiz.",
            withAppealRow(openAppMarkup(), null)));
        out.add(new Outgoing(chatId,
            "Hisobingizni ulash uchun markazda ro'yxatdan o'tgan telefon raqamingizni yuboring — "
                + "pastdagi «" + BTN_SEND_PHONE + "» tugmasini bosing.",
            contactKeyboard()));
        return out;
    }

    private List<Outgoing> handleContact(long chatId, JsonNode from, JsonNode contact) {
        long fromId = from.path("id").asLong();
        if (!contact.path("user_id").canConvertToLong() || contact.path("user_id").asLong() != fromId) {
            linkService.recordRejected(fromId);
            return List.of(new Outgoing(chatId,
                "⚠️ Faqat o'zingizning raqamingizni yuboring: «" + BTN_SEND_PHONE + "» tugmasini bosing.",
                contactKeyboard()));
        }
        MiniAppLinkService.LinkResult result = linkService.linkFromContact(fromId, chatId,
            textOrNull(from, "username"), textOrNull(from, "first_name"),
            contact.path("phone_number").asText(null));
        String support = supportPhone();
        return switch (result.outcome()) {
            case LINKED -> List.of(new Outgoing(chatId,
                "✅ Hisob ulandi.\n\n" + studentsLine(result) + "\n\nIlovani oching:",
                openAppMarkup()));
            case NOT_FOUND -> List.of(new Outgoing(chatId,
                "❌ Bu raqam markaz bazasida topilmadi.\n\n"
                    + "Markazda ro'yxatdan o'tgan raqam bilan ulaning yoki menejerga murojaat qiling"
                    + (support.isBlank() ? "." : ": " + escape(support)),
                removeKeyboard()));
            case LIMITED -> List.of(new Outgoing(chatId,
                "⏳ Urinishlar soni oshib ketdi. 24 soatdan keyin qayta urinib ko'ring"
                    + (support.isBlank() ? "." : " yoki qo'ng'iroq qiling: " + escape(support)),
                removeKeyboard()));
        };
    }

    private List<Outgoing> handleStop(long chatId, long fromId) {
        AppIdentity identity = identityRepository.findByTelegramUserId(fromId)
            .filter(AppIdentity::isActive).orElse(null);
        if (identity == null) {
            return List.of(new Outgoing(chatId, "Hisob ulanmagan.", removeKeyboard()));
        }
        linkService.unlink(identity, "BOT_STOP");
        return List.of(new Outgoing(chatId,
            "Hisob uzildi. Qayta ulash uchun /start ni bosing.", removeKeyboard()));
    }

    public static String studentsLine(MiniAppLinkService.LinkResult result) {
        String names = result.students().stream()
            .sorted(java.util.Comparator.comparing(Student::getId))
            .map(s -> escape(s.getFirstName() + " " + s.getLastName()))
            .collect(Collectors.joining(", "));
        List<String> lines = new ArrayList<>();
        if (!names.isEmpty()) {
            lines.add(result.identity().getKind() == AppIdentity.Kind.PARENT
                ? "👨‍👩‍👧 Farzand(lar): " + names
                : "👤 O'quvchi: " + names);
        }
        if (result.identity().getStaffUserId() != null) {
            lines.add("👩‍🏫 O'qituvchi rejimi: davomat va xabarlar");
        }
        return String.join("\n", lines);
    }

    private Map<String, Object> openAppMarkup() {
        String url = properties.getWebappUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        return Map.of("inline_keyboard",
            List.of(List.of(Map.of("text", BTN_OPEN_APP, "web_app", Map.of("url", url)))));
    }

    /** Inline klaviaturaga "✍️ Taklif / shikoyat" qatori (flag o'chiq — o'zgarmaydi). */
    @SuppressWarnings(value = "unchecked")
    private Map<String, Object> withAppealRow(Map<String, Object> markup, String language) {
        List<Map<String, Object>> row = appealFlow.menuRow(language);
        if (row == null) {
            return markup;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (markup != null && markup.get("inline_keyboard") instanceof List<?> existing) {
            rows.addAll((List<List<Map<String, Object>>>) existing);
        }
        rows.add(row);
        return Map.of("inline_keyboard", rows);
    }

    private static Map<String, Object> contactKeyboard() {
        return Map.of(
            "keyboard", List.of(List.of(Map.of("text", BTN_SEND_PHONE, "request_contact", true))),
            "resize_keyboard", true,
            "one_time_keyboard", true);
    }

    private static Map<String, Object> removeKeyboard() {
        return Map.of("remove_keyboard", true);
    }

    private String supportPhone() {
        String v = centerSettingsService.values().get("supportPhone");
        return v != null ? v : "";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** {@code parse_mode=HTML} uchun. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
