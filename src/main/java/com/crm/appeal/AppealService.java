package com.crm.appeal;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.Appeal;
import com.crm.entity.AppealMessage;
import com.crm.entity.Parent;
import com.crm.entity.Teacher;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.miniapp.MiniAppLinkService;
import com.crm.miniapp.MiniAppQueryService;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.AppealMessageRepository;
import com.crm.repository.AppealRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.telegram.MessageOrigin;
import com.crm.telegram.TelegramOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Murojaatlar yadrosi (docs/design/telegram-platform.md §12) — bot, Mini App va CRM uchun umumiy:
 * yaratish (raqam, kunlik limit, anonimlik), yuboruvchi xabari (yopilgan murojaat qayta ochiladi), xodimlarga
 * bildirishnoma ({@link NotificationService#dispatch}) va yuboruvchiga bot xabari (outbox).
 *
 * <p><b>Anonimlik</b> shu yerda majburlanadi: anonim murojaatda identity / o'quvchi / ota-ona / kontakt maydonlari
 * chaqiruvchi nima bergan bo'lishidan qat'i nazar NULL saqlanadi (bazada ham CHECK).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppealService {

    public static final String ENTITY = "APPEAL";
    public static final String SEQUENCE = "appeal_number_seq";
    static final int PREVIEW = 200;
    static final int NAME_MAX = 150;

    private final AppealRepository appealRepository;
    private final AppealMessageRepository messageRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final ParentRepository parentRepository;
    private final AppIdentityStudentRepository identityStudentRepository;
    private final MiniAppQueryService miniAppQueryService;
    private final NotificationService notificationService;
    private final TelegramOutboxService outboxService;
    private final AppealProperties properties;
    private final AppealTexts texts;
    private final JdbcTemplate jdbcTemplate;
    private final Clock billingClock;

    /** Yangi murojaat (bot yoki Mini App). {@code chatId} — yetkazish kaliti (shaxsiy chat = Telegram user id). */
    public record NewAppeal(Appeal.Type type, String text, boolean anonymous, Appeal.Source source, long chatId,
                            String language, Long identityId, Long studentId, Long parentId,
                            String contactName, String contactPhone, Long teacherId) {
    }

    /** Ulangan yuboruvchining CRM'dagi bog'lanishi: o'quvchi (bitta bo'lsa yoki tanlangan) va ota-ona yozuvi. */
    public record SenderLink(Long studentId, Long parentId) {
    }

    /** Tanlash uchun o'qituvchi. */
    public record TeacherOption(Long id, String name) {
    }

    // ── Yaratish ─────────────────────────────────────────────────────────

    @Transactional
    public Appeal create(NewAppeal c) {
        if (c.type() == null) {
            throw CodedException.badRequest("appeal.type.required");
        }
        String text = normalizeText(c.text());
        requireUnderLimit(c.chatId());
        if (c.teacherId() != null) {
            if (c.type() != Appeal.Type.TEACHER) {
                throw CodedException.badRequest("appeal.teacher.typeMismatch");
            }
            if (!teacherRepository.existsById(c.teacherId())) {
                throw CodedException.badRequest("appeal.teacher.notFound");
            }
        }
        boolean anon = c.anonymous();
        LocalDateTime now = LocalDateTime.now(billingClock);
        Appeal appeal = appealRepository.save(Appeal.builder()
            .number(nextNumber())
            .type(c.type())
            .text(text)
            .anonymous(anon)
            .source(c.source())
            .telegramChatId(c.chatId())
            .language(c.language())
            // Anonim: yuboruvchini aniqlaydigan hech narsa saqlanmaydi
            .appIdentityId(anon ? null : c.identityId())
            .studentId(anon ? null : c.studentId())
            .parentId(anon ? null : c.parentId())
            .contactName(anon ? null : blankToNull(c.contactName()))
            .contactPhone(anon ? null : blankToNull(c.contactPhone()))
            .teacherId(c.teacherId())
            .status(Appeal.Status.NEW)
            .createdAt(now)
            .updatedAt(now)
            .build());

        notifyStaff(appeal, NotificationType.APPEAL_NEW, null, "appeal:new:" + appeal.getId(),
            appeal.getNumber(), texts.type(appeal.getType(), AppealTexts.locale("uz")), preview(appeal.getText()));
        return appeal;
    }

    /** Matn: bo'sh joylar kesiladi; bo'sh — 400 {@code appeal.text.required}, uzun — 400 {@code appeal.text.tooLong}. */
    public String normalizeText(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw CodedException.badRequest("appeal.text.required");
        }
        if (text.length() > textMax()) {
            throw CodedException.badRequest("appeal.text.tooLong", textMax());
        }
        return text;
    }

    public int textMax() {
        return Math.min(3000, Math.max(1, properties.getTextMax()));
    }

    /** Kunlik limit (Toshkent kuni, bir chat): oshsa 429 {@code appeal.limit}. */
    public void requireUnderLimit(long chatId) {
        if (remainingToday(chatId) <= 0) {
            throw new CodedException(HttpStatus.TOO_MANY_REQUESTS, "appeal.limit", properties.getDailyLimit());
        }
    }

    public int remainingToday(long chatId) {
        LocalDateTime since = LocalDate.now(billingClock).atStartOfDay();
        long used = appealRepository.countByChatSince(chatId, since);
        return (int) Math.max(0, properties.getDailyLimit() - used);
    }

    String nextNumber() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('" + SEQUENCE + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Sequence qiymat qaytarmadi: " + SEQUENCE);
        }
        return format(properties.getNumberPrefix(), value);
    }

    static String format(String prefix, long value) {
        return (prefix == null || prefix.isBlank() ? "MRJ" : prefix) + "-" + String.format("%05d", value);
    }

    // ── Yuboruvchi ───────────────────────────────────────────────────────

    /**
     * Yuboruvchining yangi xabari (bot reply, "Javob yozish", Mini App). Yopilgan (RESOLVED / REJECTED) murojaat
     * {@code IN_PROGRESS} ga qaytadi. Xodimlarga {@link NotificationType#APPEAL_MESSAGE}.
     */
    @Transactional
    public AppealMessage addSenderMessage(Appeal appeal, String rawText) {
        String text = normalizeText(rawText);
        LocalDateTime now = LocalDateTime.now(billingClock);
        AppealMessage message = messageRepository.save(AppealMessage.builder()
            .appealId(appeal.getId())
            .authorType(AppealMessage.AuthorType.SENDER)
            .text(text)
            .createdAt(now)
            .delivered(true)
            .internal(false)
            .build());
        if (appeal.getStatus().closed()) {
            appeal.setStatus(Appeal.Status.IN_PROGRESS);
            appeal.setResolvedAt(null);
        }
        appeal.setUpdatedAt(now);
        appealRepository.save(appeal);
        notifyStaff(appeal, NotificationType.APPEAL_MESSAGE, null, "appeal:msg:" + message.getId(),
            appeal.getNumber(), preview(text));
        return message;
    }

    /** Ulangan identity → o'quvchi (tanlangan yoki yagona) va ota-ona yozuvi (telefon bo'yicha). */
    @Transactional(readOnly = true)
    public SenderLink senderLink(AppIdentity identity, Long preferredStudentId) {
        List<AppIdentityStudent> links = identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId());
        Long studentId = null;
        if (preferredStudentId != null) {
            studentId = links.stream().map(AppIdentityStudent::getStudentId)
                .filter(preferredStudentId::equals).findFirst()
                .orElseThrow(() -> CodedException.forbidden("app.student.forbidden"));
        } else if (links.size() == 1) {
            studentId = links.get(0).getStudentId();
        }
        Long parentId = null;
        boolean parent = links.stream().anyMatch(l -> l.getRelation() == AppIdentityStudent.Relation.PARENT);
        if (parent && identity.getPhoneCanonical() != null) {
            parentId = parentRepository.findByPhoneInAndIsActiveTrue(
                    MiniAppLinkService.variants(identity.getPhoneCanonical())).stream()
                .map(Parent::getId).min(Long::compareTo).orElse(null);
        }
        return new SenderLink(studentId, parentId);
    }

    /** O'qituvchi tanlovi: identity o'quvchilarining ochiq guruhlaridagi faol o'qituvchilar (ism bo'yicha). */
    @Transactional(readOnly = true)
    public List<TeacherOption> teacherOptions(Long identityId) {
        if (identityId == null) {
            return List.of();
        }
        Map<Long, TeacherOption> out = new LinkedHashMap<>();
        for (AppIdentityStudent link : identityStudentRepository.findByIdentityIdOrderByIdAsc(identityId)) {
            miniAppQueryService.openEnrollments(link.getStudentId()).stream()
                .map(sg -> sg.getGroup().getTeacher())
                .filter(Objects::nonNull)
                .filter(t -> !Boolean.FALSE.equals(t.getIsActive()))
                .forEach(t -> out.putIfAbsent(t.getId(), new TeacherOption(t.getId(), teacherName(t))));
        }
        return out.values().stream()
            .sorted(java.util.Comparator.comparing(TeacherOption::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public static String teacherName(Teacher t) {
        return (Objects.toString(t.getFirstName(), "") + " " + Objects.toString(t.getLastName(), "")).trim();
    }

    // ── Xodimlarga ───────────────────────────────────────────────────────

    /** Murojaat predmeti bo'lgan o'qituvchining useri (TEACHER turi); yo'q — null. */
    public Long teacherUserId(Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        return teacherRepository.findById(teacherId).map(Teacher::getUser).map(User::getId).orElse(null);
    }

    /**
     * SA (faol) va mas'ul — qo'ng'iroqcha va (ulangan bo'lsa) xodim boti, AUTOMATIC (sokin soatlar). Murojaat
     * predmeti bo'lgan o'qituvchi (TEACHER turi) hech qachon olmaydi.
     */
    void notifyStaff(Appeal appeal, NotificationType type, String variant, String dedupeKey, Object... args) {
        Set<Long> recipients = new LinkedHashSet<>();
        userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(Set.of(UserRole.SUPER_ADMIN))
            .forEach(u -> recipients.add(u.getId()));
        if (appeal.getAssigneeUserId() != null) {
            recipients.add(appeal.getAssigneeUserId());
        }
        notifyUsers(appeal, recipients, type, variant, dedupeKey, args);
    }

    void notifyUsers(Appeal appeal, Set<Long> userIds, NotificationType type, String variant, String dedupeKey,
                     Object... args) {
        Set<Long> recipients = new LinkedHashSet<>(userIds);
        Long subject = teacherUserId(appeal.getTeacherId());
        if (subject != null) {
            recipients.remove(subject);
        }
        if (recipients.isEmpty()) {
            return;
        }
        NotificationRequest.NotificationRequestBuilder b = NotificationRequest.builder()
            .type(type)
            .variant(variant)
            .staffUserIds(recipients)
            .link("/appeals/" + appeal.getId())
            .entityType(ENTITY)
            .entityId(appeal.getId())
            .dedupeKey(dedupeKey)
            .origin(MessageOrigin.AUTOMATIC);
        for (Object a : args) {
            b.arg(a);
        }
        notificationService.dispatch(b.build());
    }

    // ── Yuboruvchiga (bot) ───────────────────────────────────────────────

    /** Yuboruvchi chatiga bot xabari (MANUAL — darhol, sokin soatda ovozsiz). Navbatga qo'yildi — true. */
    boolean enqueueToSender(Appeal appeal, String html, Map<String, Object> markup, String dedupeKey,
                            String eventCode) {
        if (appeal.getTelegramChatId() == null) {
            return false;
        }
        return outboxService.enqueue(appeal.getTelegramChatId(), html, markup, TelegramOutbox.Priority.HIGH,
            dedupeKey, eventCode, MessageOrigin.MANUAL);
    }

    /** "✍️ Javob yozish" inline tugmasi ({@code apl:r:<id>}). */
    Map<String, Object> replyButton(Appeal appeal, Locale locale) {
        return Map.of("inline_keyboard", List.of(List.of(Map.of(
            "text", texts.t("appeal.bot.replyButton", locale),
            "callback_data", AppealBotFlow.CB_REPLY + appeal.getId()))));
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > PREVIEW ? oneLine.substring(0, PREVIEW - 1) + "…" : oneLine;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static Optional<Appeal.Type> parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Appeal.Type.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static List<Appeal.Type> types() {
        return new ArrayList<>(List.of(Appeal.Type.values()));
    }
}
