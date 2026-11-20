package com.crm.appeal;

import com.crm.entity.AppIdentity;
import com.crm.entity.Appeal;
import com.crm.entity.BotConversation;
import com.crm.notification.NotificationTemplates;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppealRepository;
import com.crm.repository.BotConversationRepository;
import com.crm.telegram.TelegramUpdateHandler;
import com.crm.telegram.TelegramUpdateHandler.Outgoing;
import com.crm.util.PhoneUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Botda "✍️ Taklif / shikoyat" oqimi (docs/design/telegram-platform.md §12.2). Holat — {@code bot_conversations}
 * (bir chat — bitta qator, {@link AppealProperties#getStateTtlMinutes()} daqiqada eskiradi):
 * <pre>
 *  TYPE (inline) → [TEACHER (inline, faqat TEACHER turi va ulangan, o'qituvchilari bo'lsa)] → ANON (inline)
 *     → [NAME → PHONE (request_contact) — faqat ulanmagan va anonim emas] → TEXT → CONFIRM (inline) → qabul
 * </pre>
 * Bekor qilish har qadamda: inline "❌ Bekor qilish", reply-klaviaturadagi tugma yoki {@code /cancel}.
 * Javob: xodim javobiga Telegram reply (xabardagi {@code MRJ-…} raqami bo'yicha, faqat shu chatning murojaati) yoki
 * "✍️ Javob yozish" ({@code apl:r:<id>}) → keyingi matn SENDER xabari.
 *
 * <p>Tranzaksiya — {@link TelegramUpdateHandler#handle}. {@link AppealService} ning tranzaksiyali metodlari
 * chaqirilishidan OLDIN hamma tekshiruv shu yerda (limit, matn): ular ichida xato bo'lsa tashqi tranzaksiya
 * rollback-only bo'lib qolardi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppealBotFlow {

    public static final String CB = "apl:";
    public static final String CB_NEW = "apl:new";
    public static final String CB_TYPE = "apl:t:";
    public static final String CB_TEACHER = "apl:th:";
    public static final String CB_ANON = "apl:a:";
    public static final String CB_SEND = "apl:ok";
    public static final String CB_CANCEL = "apl:x";
    public static final String CB_REPLY = "apl:r:";

    static final String FLOW_NEW = "APPEAL_NEW";
    static final String FLOW_REPLY = "APPEAL_REPLY";
    static final int CONFIRM_PREVIEW = 1000;
    private static final Pattern NUMBER = Pattern.compile("\\b([A-Z]{1,10}-\\d{5,})\\b");

    enum Step { TYPE, TEACHER, ANON, NAME, PHONE, TEXT, CONFIRM, REPLY_TEXT }

    private final BotConversationRepository conversationRepository;
    private final AppIdentityRepository identityRepository;
    private final AppealRepository appealRepository;
    private final AppealService appealService;
    private final AppealProperties properties;
    private final AppealTexts texts;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    /** Callback natijasi: {@code answerCallbackQuery} matni (null — bo'sh) va yuboriladigan xabarlar. */
    public record CallbackResult(String answer, List<Outgoing> messages) {
    }

    /** Ochiq suhbat holati. */
    private static final class State {
        final long chatId;
        final String flow;
        Step step;
        final Map<String, Object> data;

        State(long chatId, String flow, Step step, Map<String, Object> data) {
            this.chatId = chatId;
            this.flow = flow;
            this.step = step;
            this.data = data;
        }

        String lang() {
            return (String) data.get("lang");
        }

        Locale locale() {
            return AppealTexts.locale(lang());
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    // ── Kirish nuqtalari (TelegramUpdateHandler) ─────────────────────────

    /** {@code /taklif}, {@code /start taklif} yoki menyu tugmasi matni (uz / ru / en). */
    public boolean isStartCommand(String text) {
        if (text == null || !enabled()) {
            return false;
        }
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("/taklif") || lower.startsWith("/taklif@") || lower.equals("/start taklif")) {
            return true;
        }
        for (Locale l : List.of(NotificationTemplates.UZ, NotificationTemplates.RU, Locale.ENGLISH)) {
            if (t.equals(texts.t("appeal.bot.button", l))) {
                return true;
            }
        }
        return false;
    }

    public boolean isCancel(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        if (t.equalsIgnoreCase("/cancel") || t.equalsIgnoreCase("/bekor")) {
            return true;
        }
        for (Locale l : List.of(NotificationTemplates.UZ, NotificationTemplates.RU, Locale.ENGLISH)) {
            if (t.equals(texts.t("appeal.bot.cancel", l))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActive(long chatId) {
        return load(chatId).isPresent();
    }

    /** Ochiq suhbatni tashlash ({@code /start}). */
    @Transactional
    public void reset(long chatId) {
        clear(chatId);
    }

    /** Kontakt shu oqimga tegishlimi (PHONE qadami) — aks holda kontakt hisob ulash uchun. */
    public boolean awaitingContact(long chatId) {
        return load(chatId).map(s -> s.step == Step.PHONE).orElse(false);
    }

    /** {@code /start} menyusiga qo'shiladigan inline qator (flag o'chiq — null). */
    public List<Map<String, Object>> menuRow(String language) {
        if (!enabled()) {
            return null;
        }
        return List.of(Map.of("text", texts.t("appeal.bot.button", AppealTexts.locale(language)),
            "callback_data", CB_NEW));
    }

    /** Oqim boshi. */
    @Transactional
    public List<Outgoing> start(long chatId, JsonNode from) {
        String lang = language(from);
        Locale locale = AppealTexts.locale(lang);
        if (appealService.remainingToday(chatId) <= 0) {
            clear(chatId);
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.limit", locale, properties.getDailyLimit()),
                null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lang", lang);
        State state = new State(chatId, FLOW_NEW, Step.TYPE, data);
        save(state);
        return List.of(new Outgoing(chatId, texts.t("appeal.bot.chooseType", locale), typeKeyboard(locale)));
    }

    /** Ochiq suhbatdagi matn / boshqa xabar. Holat yo'q — null (chaqiruvchi o'zi ishlaydi). */
    @Transactional
    public List<Outgoing> onMessage(long chatId, JsonNode from, JsonNode message) {
        State state = load(chatId).orElse(null);
        if (state == null) {
            return null;
        }
        Locale locale = state.locale();
        String text = message.path("text").isTextual() ? message.path("text").asText() : null;
        if (isCancel(text)) {
            clear(chatId);
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.cancelled", locale), removeKeyboard()));
        }
        JsonNode contact = message.path("contact");
        boolean hasContact = !contact.isMissingNode() && !contact.isNull();
        return switch (state.step) {
            case NAME -> onName(state, text);
            case PHONE -> onPhone(state, from, hasContact ? contact : null, text);
            case TEXT -> onText(state, text);
            case REPLY_TEXT -> onReplyText(state, text);
            // Inline qadamlarda matn — shu qadam qayta so'raladi
            case TYPE -> List.of(new Outgoing(chatId, texts.t("appeal.bot.chooseType", locale), typeKeyboard(locale)));
            case TEACHER -> askTeacher(state);
            case ANON -> askAnonymous(state);
            case CONFIRM -> List.of(confirmMessage(state));
        };
    }

    /**
     * Botning murojaat xabariga Telegram reply: xabarda shu chatning murojaat raqami bo'lsa — SENDER xabari.
     * Mos kelmasa — empty (oddiy ishlov).
     */
    @Transactional
    public Optional<List<Outgoing>> onReplyTo(long chatId, JsonNode message) {
        JsonNode replied = message.path("reply_to_message");
        if (replied.isMissingNode() || replied.isNull() || !replied.path("from").path("is_bot").asBoolean(false)) {
            return Optional.empty();
        }
        String repliedText = replied.path("text").asText(replied.path("caption").asText(""));
        Matcher m = NUMBER.matcher(repliedText);
        Appeal appeal = null;
        while (appeal == null && m.find()) {
            appeal = appealRepository.findByNumberAndTelegramChatId(m.group(1), chatId).orElse(null);
        }
        if (appeal == null) {
            return Optional.empty();
        }
        Locale locale = AppealTexts.locale(appeal.getLanguage());
        String text = message.path("text").isTextual() ? message.path("text").asText() : null;
        return Optional.of(addSenderMessage(chatId, appeal, text, locale, null));
    }

    /** {@code apl:*} callback. */
    @Transactional
    public CallbackResult onCallback(long chatId, JsonNode from, String data) {
        if (CB_NEW.equals(data)) {
            if (!enabled()) {
                return new CallbackResult(null, List.of());
            }
            return new CallbackResult(null, start(chatId, from));
        }
        if (data.startsWith(CB_REPLY)) {
            return new CallbackResult(null, startReply(chatId, from, data.substring(CB_REPLY.length())));
        }
        State state = load(chatId).orElse(null);
        Locale fallback = AppealTexts.locale(language(from));
        if (CB_CANCEL.equals(data)) {
            clear(chatId);
            Locale locale = state != null ? state.locale() : fallback;
            return new CallbackResult(null,
                List.of(new Outgoing(chatId, texts.t("appeal.bot.cancelled", locale), removeKeyboard())));
        }
        if (state == null || !FLOW_NEW.equals(state.flow)) {
            return expired(chatId, fallback);
        }
        Locale locale = state.locale();
        if (data.startsWith(CB_TYPE) && state.step == Step.TYPE) {
            Optional<Appeal.Type> type = AppealService.parseType(data.substring(CB_TYPE.length()));
            if (type.isEmpty()) {
                return expired(chatId, locale);
            }
            state.data.put("type", type.get().name());
            if (type.get() == Appeal.Type.TEACHER && !teacherOptions(chatId).isEmpty()) {
                state.step = Step.TEACHER;
                save(state);
                return new CallbackResult(null, askTeacher(state));
            }
            state.step = Step.ANON;
            save(state);
            return new CallbackResult(null, askAnonymous(state));
        }
        if (data.startsWith(CB_TEACHER) && state.step == Step.TEACHER) {
            Long teacherId = parseLong(data.substring(CB_TEACHER.length()));
            if (teacherId != null && teacherId > 0) {
                Long wanted = teacherId;
                AppealService.TeacherOption option = teacherOptions(chatId).stream()
                    .filter(o -> o.id().equals(wanted)).findFirst().orElse(null);
                if (option == null) {
                    return expired(chatId, locale);
                }
                state.data.put("teacherId", option.id());
                state.data.put("teacherName", option.name());
            }
            state.step = Step.ANON;
            save(state);
            return new CallbackResult(null, askAnonymous(state));
        }
        if (data.startsWith(CB_ANON) && state.step == Step.ANON) {
            boolean anonymous = "1".equals(data.substring(CB_ANON.length()));
            state.data.put("anonymous", anonymous);
            if (!anonymous && identity(chatId) == null) {
                state.step = Step.NAME;
                save(state);
                return new CallbackResult(null,
                    List.of(new Outgoing(chatId, texts.t("appeal.bot.askName", locale), cancelKeyboard(locale))));
            }
            state.step = Step.TEXT;
            save(state);
            return new CallbackResult(null, List.of(askText(state)));
        }
        if (CB_SEND.equals(data) && state.step == Step.CONFIRM) {
            return new CallbackResult(null, submit(state));
        }
        return expired(chatId, locale);
    }

    // ── Qadamlar ─────────────────────────────────────────────────────────

    private List<Outgoing> onName(State state, String text) {
        Locale locale = state.locale();
        String name = text == null ? "" : text.strip();
        if (name.length() < 2 || name.length() > AppealService.NAME_MAX || name.startsWith("/")) {
            return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.badName", locale), cancelKeyboard(locale)));
        }
        state.data.put("name", name);
        state.step = Step.PHONE;
        save(state);
        return List.of(new Outgoing(state.chatId,
            texts.t("appeal.bot.askPhone", locale, texts.t("appeal.bot.sendPhone", locale)), phoneKeyboard(locale)));
    }

    private List<Outgoing> onPhone(State state, JsonNode from, JsonNode contact, String text) {
        Locale locale = state.locale();
        String phone;
        if (contact != null) {
            // Faqat o'z kontakti (birovning kartasi emas)
            if (!contact.path("user_id").canConvertToLong()
                    || contact.path("user_id").asLong() != from.path("id").asLong()) {
                return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.ownPhone", locale),
                    phoneKeyboard(locale)));
            }
            phone = PhoneUtils.canonical(contact.path("phone_number").asText(null));
        } else {
            phone = text == null ? null : PhoneUtils.canonical(text.strip());
        }
        if (phone == null) {
            return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.badPhone", locale), phoneKeyboard(locale)));
        }
        state.data.put("phone", phone);
        state.step = Step.TEXT;
        save(state);
        return List.of(askText(state));
    }

    private List<Outgoing> onText(State state, String text) {
        Locale locale = state.locale();
        if (text == null) {
            return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.textOnly", locale), cancelKeyboard(locale)));
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return List.of(askText(state));
        }
        if (t.length() > appealService.textMax()) {
            return List.of(new Outgoing(state.chatId,
                texts.t("appeal.bot.textTooLong", locale, String.valueOf(t.length()),
                    String.valueOf(appealService.textMax())),
                cancelKeyboard(locale)));
        }
        state.data.put("text", t);
        state.step = Step.CONFIRM;
        save(state);
        return List.of(confirmMessage(state));
    }

    private List<Outgoing> submit(State state) {
        long chatId = state.chatId;
        Locale locale = state.locale();
        if (appealService.remainingToday(chatId) <= 0) {
            clear(chatId);
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.limit", locale, properties.getDailyLimit()),
                removeKeyboard()));
        }
        Appeal.Type type = AppealService.parseType((String) state.data.get("type")).orElse(Appeal.Type.OTHER);
        String text = (String) state.data.get("text");
        boolean anonymous = Boolean.TRUE.equals(state.data.get("anonymous"));
        Long teacherId = state.data.get("teacherId") instanceof Number n ? n.longValue() : null;
        AppIdentity identity = identity(chatId);
        Long identityId = null;
        Long studentId = null;
        Long parentId = null;
        if (identity != null && !anonymous) {
            AppealService.SenderLink link = appealService.senderLink(identity, null);
            identityId = identity.getId();
            studentId = link.studentId();
            parentId = link.parentId();
        }
        Appeal appeal = appealService.create(new AppealService.NewAppeal(type, text, anonymous, Appeal.Source.BOT,
            chatId, state.lang(), identityId, studentId, parentId,
            identity == null ? (String) state.data.get("name") : null,
            identity == null ? (String) state.data.get("phone") : null,
            type == Appeal.Type.TEACHER ? teacherId : null));
        clear(chatId);
        return List.of(new Outgoing(chatId, texts.t("appeal.bot.accepted", locale, appeal.getNumber()),
            removeKeyboard()));
    }

    private List<Outgoing> startReply(long chatId, JsonNode from, String rawId) {
        Long id = parseLong(rawId);
        Appeal appeal = id == null ? null : appealRepository.findByIdAndTelegramChatId(id, chatId).orElse(null);
        if (appeal == null) {
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.notFound", AppealTexts.locale(language(from))),
                null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lang", appeal.getLanguage() != null ? appeal.getLanguage() : language(from));
        data.put("appealId", appeal.getId());
        State state = new State(chatId, FLOW_REPLY, Step.REPLY_TEXT, data);
        save(state);
        Locale locale = state.locale();
        return List.of(new Outgoing(chatId, texts.t("appeal.bot.askReply", locale, appeal.getNumber()),
            cancelKeyboard(locale)));
    }

    private List<Outgoing> onReplyText(State state, String text) {
        Long appealId = state.data.get("appealId") instanceof Number n ? n.longValue() : null;
        Appeal appeal = appealId == null ? null
            : appealRepository.findByIdAndTelegramChatId(appealId, state.chatId).orElse(null);
        if (appeal == null) {
            clear(state.chatId);
            return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.notFound", state.locale()),
                removeKeyboard()));
        }
        return addSenderMessage(state.chatId, appeal, text, state.locale(), state);
    }

    /** Tekshiruv shu yerda — {@link AppealService#addSenderMessage} xato bermasin (tranzaksiya rollback-only). */
    private List<Outgoing> addSenderMessage(long chatId, Appeal appeal, String text, Locale locale, State state) {
        if (text == null || text.isBlank()) {
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.textOnly", locale),
                state != null ? cancelKeyboard(locale) : null));
        }
        if (text.strip().length() > appealService.textMax()) {
            return List.of(new Outgoing(chatId, texts.t("appeal.bot.textTooLong", locale,
                String.valueOf(text.strip().length()), String.valueOf(appealService.textMax())),
                state != null ? cancelKeyboard(locale) : null));
        }
        appealService.addSenderMessage(appeal, text);
        if (state != null) {
            clear(chatId);
        }
        return List.of(new Outgoing(chatId, texts.t("appeal.bot.replyAdded", locale, appeal.getNumber()),
            removeKeyboard()));
    }

    // ── Xabarlar ─────────────────────────────────────────────────────────

    private List<Outgoing> askTeacher(State state) {
        Locale locale = state.locale();
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (AppealService.TeacherOption o : teacherOptions(state.chatId)) {
            rows.add(List.of(button(o.name(), CB_TEACHER + o.id())));
        }
        rows.add(List.of(button(texts.t("appeal.bot.teacherSkip", locale), CB_TEACHER + "0")));
        rows.add(List.of(button(texts.t("appeal.bot.cancel", locale), CB_CANCEL)));
        return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.chooseTeacher", locale),
            Map.of("inline_keyboard", rows)));
    }

    private List<Outgoing> askAnonymous(State state) {
        Locale locale = state.locale();
        return List.of(new Outgoing(state.chatId, texts.t("appeal.bot.askAnonymous", locale),
            Map.of("inline_keyboard", List.of(
                List.of(button(texts.t("appeal.bot.anonYes", locale), CB_ANON + "1"),
                    button(texts.t("appeal.bot.anonNo", locale), CB_ANON + "0")),
                List.of(button(texts.t("appeal.bot.cancel", locale), CB_CANCEL))))));
    }

    private Outgoing askText(State state) {
        Locale locale = state.locale();
        return new Outgoing(state.chatId, texts.t("appeal.bot.askText", locale, String.valueOf(appealService.textMax())),
            cancelKeyboard(locale));
    }

    private Outgoing confirmMessage(State state) {
        Locale locale = state.locale();
        Appeal.Type type = AppealService.parseType((String) state.data.get("type")).orElse(Appeal.Type.OTHER);
        boolean anonymous = Boolean.TRUE.equals(state.data.get("anonymous"));
        List<String> lines = new ArrayList<>();
        lines.add(texts.t("appeal.bot.confirm.type", locale, texts.type(type, locale)));
        if (state.data.get("teacherName") != null) {
            lines.add(texts.t("appeal.bot.confirm.teacher", locale,
                TelegramUpdateHandler.escape((String) state.data.get("teacherName"))));
        }
        if (anonymous) {
            lines.add(texts.t("appeal.bot.confirm.anonymous", locale));
        } else if (state.data.get("name") != null) {
            lines.add(texts.t("appeal.bot.confirm.named", locale,
                TelegramUpdateHandler.escape((String) state.data.get("name")),
                TelegramUpdateHandler.escape((String) state.data.get("phone"))));
        }
        String text = (String) state.data.get("text");
        String shown = text.length() > CONFIRM_PREVIEW ? text.substring(0, CONFIRM_PREVIEW) + "…" : text;
        String html = texts.t("appeal.bot.confirm", locale, String.join("\n", lines),
            TelegramUpdateHandler.escape(shown));
        return new Outgoing(state.chatId, html, Map.of("inline_keyboard", List.of(
            List.of(button(texts.t("appeal.bot.send", locale), CB_SEND)),
            List.of(button(texts.t("appeal.bot.cancel", locale), CB_CANCEL)))));
    }

    private CallbackResult expired(long chatId, Locale locale) {
        return new CallbackResult(texts.t("appeal.bot.expiredShort", locale),
            List.of(new Outgoing(chatId, texts.t("appeal.bot.expired", locale), null)));
    }

    private Map<String, Object> typeKeyboard(Locale locale) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        List<Map<String, Object>> row = new ArrayList<>();
        for (Appeal.Type type : Appeal.Type.values()) {
            row.add(button(texts.type(type, locale), CB_TYPE + type.name()));
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button(texts.t("appeal.bot.cancel", locale), CB_CANCEL)));
        return Map.of("inline_keyboard", rows);
    }

    private Map<String, Object> cancelKeyboard(Locale locale) {
        return Map.of("keyboard", List.of(List.of(Map.of("text", texts.t("appeal.bot.cancel", locale)))),
            "resize_keyboard", true);
    }

    private Map<String, Object> phoneKeyboard(Locale locale) {
        return Map.of("keyboard", List.of(
                List.of(Map.of("text", texts.t("appeal.bot.sendPhone", locale), "request_contact", true)),
                List.of(Map.of("text", texts.t("appeal.bot.cancel", locale)))),
            "resize_keyboard", true);
    }

    private static Map<String, Object> removeKeyboard() {
        return Map.of("remove_keyboard", true);
    }

    private static Map<String, Object> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    // ── Holat ────────────────────────────────────────────────────────────

    private Optional<State> load(long chatId) {
        BotConversation c = conversationRepository.findById(chatId).orElse(null);
        if (c == null) {
            return Optional.empty();
        }
        LocalDateTime expiry = LocalDateTime.now(billingClock).minusMinutes(properties.getStateTtlMinutes());
        Step step;
        try {
            step = Step.valueOf(c.getStep());
        } catch (IllegalArgumentException e) {
            step = null;
        }
        if (c.getUpdatedAt().isBefore(expiry) || step == null) {
            conversationRepository.delete(c);
            return Optional.empty();
        }
        return Optional.of(new State(chatId, c.getFlow(), step, readData(c.getData())));
    }

    private void save(State state) {
        BotConversation c = conversationRepository.findById(state.chatId)
            .orElseGet(() -> BotConversation.builder().chatId(state.chatId).build());
        c.setFlow(state.flow);
        c.setStep(state.step.name());
        c.setData(writeData(state.data));
        c.setUpdatedAt(LocalDateTime.now(billingClock));
        conversationRepository.save(c);
    }

    private void clear(long chatId) {
        conversationRepository.findById(chatId).ifPresent(conversationRepository::delete);
    }

    private Map<String, Object> readData(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private AppIdentity identity(long chatId) {
        return identityRepository.findByTelegramUserId(chatId).filter(AppIdentity::isActive).orElse(null);
    }

    private List<AppealService.TeacherOption> teacherOptions(long chatId) {
        AppIdentity identity = identity(chatId);
        return identity == null ? List.of() : appealService.teacherOptions(identity.getId());
    }

    /** Ulangan — identity tili; aks holda Telegram {@code language_code}; standart {@code uz}. */
    private String language(JsonNode from) {
        if (from != null && from.path("id").canConvertToLong()) {
            AppIdentity identity = identity(from.path("id").asLong());
            if (identity != null && identity.getLanguage() != null) {
                return identity.getLanguage();
            }
            String raw = NotificationTemplates.language(from.path("language_code").asText(null));
            if (raw != null) {
                return raw;
            }
        }
        return "uz";
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
