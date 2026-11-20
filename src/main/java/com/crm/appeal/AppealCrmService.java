package com.crm.appeal;

import com.crm.dto.response.PageResponse;
import com.crm.entity.AppIdentity;
import com.crm.entity.Appeal;
import com.crm.entity.AppealMessage;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.notification.NotificationType;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppealMessageRepository;
import com.crm.repository.AppealRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.TeacherAccessService;
import com.crm.telegram.TelegramUpdateHandler;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRM: murojaatlarni ko'rib chiqish ({@code /api/appeals}, docs/design/telegram-platform.md §12.4).
 *
 * <p><b>Ruxsat</b> (SecurityConfig — faqat SA va ADMIN; boshqalar 403):
 * <ul>
 *   <li>SUPER_ADMIN — hammasi;</li>
 *   <li>ADMIN — faqat o'zi mas'ul ({@code assignee_user_id}) bo'lganlari; boshqasi — 404 {@code appeal.notFound};</li>
 *   <li>TEACHER turidagi murojaat predmeti bo'lgan o'qituvchining useri (u admin yoki mas'ul bo'lsa ham) uni
 *       hech qachon ko'rmaydi — ro'yxat, sanoq, hisobot va 404.</li>
 * </ul>
 * Anonim murojaatda yuboruvchi bloki yo'q ({@code sender = null}); {@code telegram_chat_id} hech qachon chiqmaydi.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppealCrmService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_STATS_DAYS = 30;

    private final AppealRepository appealRepository;
    private final AppealMessageRepository messageRepository;
    private final AppealService appealService;
    private final TeacherAccessService teacherAccessService;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final AppIdentityRepository identityRepository;
    private final AppealTexts texts;
    private final AppealProperties properties;
    private final Clock billingClock;

    public record Filter(Appeal.Status status, Appeal.Type type, Long assigneeId, LocalDate from, LocalDate to,
                         String q) {
    }

    /** Joriy xodim: SA mi va o'zi qaysi o'qituvchi profili (TEACHER turidagi murojaatlar unga ko'rinmaydi). */
    private record Viewer(User user, boolean superAdmin, Set<Long> ownTeacherIds) {
    }

    // ── O'qish ───────────────────────────────────────────────────────────

    public PageResponse<AppealDtos.ListItem> list(Filter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw CodedException.badRequest("appeal.date.invalidRange");
        }
        Viewer viewer = viewer();
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Page<Appeal> result = appealRepository.findAll(scope(viewer).and(filter(filter)),
            PageRequest.of(Math.max(0, page), pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<Appeal> appeals = result.getContent();
        Map<Long, List<AppealMessage>> messages = messageRepository.findByAppealIdInOrderByIdAsc(
                appeals.stream().map(Appeal::getId).toList()).stream()
            .collect(Collectors.groupingBy(AppealMessage::getAppealId));
        Names names = names(appeals, List.of());
        List<AppealDtos.ListItem> items = appeals.stream().map(a -> {
            List<AppealMessage> ms = messages.getOrDefault(a.getId(), List.of());
            return new AppealDtos.ListItem(a.getId(), a.getNumber(), a.getType().name(), a.getStatus().name(),
                a.isAnonymous(), a.getSource().name(), AppealService.preview(a.getText()),
                a.isAnonymous() ? null : senderName(a, names), names.user(a.getAssigneeUserId()),
                names.teacher(a.getTeacherId()), ms.size(),
                ms.isEmpty() ? null : ms.get(ms.size() - 1).getCreatedAt(),
                a.getCreatedAt(), a.getFirstResponseAt(), a.getResolvedAt());
        }).toList();
        return PageResponse.<AppealDtos.ListItem>builder()
            .content(items)
            .pageNumber(result.getNumber())
            .pageSize(result.getSize())
            .totalElements(result.getTotalElements())
            .totalPages(result.getTotalPages())
            .last(result.isLast())
            .build();
    }

    public AppealDtos.Detail get(Long id) {
        return detail(load(id, viewer()));
    }

    /** Sidebar badge — joriy xodim ko'radiganlari. */
    public AppealDtos.Counts counts() {
        Viewer viewer = viewer();
        long fresh = appealRepository.count(scope(viewer).and(statusIs(Appeal.Status.NEW)));
        long inProgress = appealRepository.count(scope(viewer).and(statusIs(Appeal.Status.IN_PROGRESS)));
        return new AppealDtos.Counts(fresh, inProgress);
    }

    /**
     * Hisobot: {@code [from, to]} (yaratilgan sana, Toshkent) — tur va holat bo'yicha soni, o'rtacha birinchi javob
     * va hal qilish vaqti (daqiqa, yaxlitlangan). Standart: oxirgi {@value #DEFAULT_STATS_DAYS} kun.
     */
    public AppealDtos.Stats stats(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(billingClock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_STATS_DAYS - 1L);
        if (start.isAfter(end)) {
            throw CodedException.badRequest("appeal.date.invalidRange");
        }
        List<Appeal> appeals = appealRepository.findAll(scope(viewer())
            .and(filter(new Filter(null, null, null, start, end, null))));
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Appeal.Type t : Appeal.Type.values()) {
            byType.put(t.name(), 0L);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Appeal.Status s : Appeal.Status.values()) {
            byStatus.put(s.name(), 0L);
        }
        long responded = 0;
        long responseMinutes = 0;
        long resolved = 0;
        long resolutionMinutes = 0;
        for (Appeal a : appeals) {
            byType.merge(a.getType().name(), 1L, Long::sum);
            byStatus.merge(a.getStatus().name(), 1L, Long::sum);
            if (a.getFirstResponseAt() != null) {
                responded++;
                responseMinutes += Math.max(0, Duration.between(a.getCreatedAt(), a.getFirstResponseAt()).toMinutes());
            }
            if (a.getResolvedAt() != null) {
                resolved++;
                resolutionMinutes += Math.max(0, Duration.between(a.getCreatedAt(), a.getResolvedAt()).toMinutes());
            }
        }
        return new AppealDtos.Stats(start.toString(), end.toString(), appeals.size(), byType, byStatus,
            responded, responded == 0 ? null : Math.round((double) responseMinutes / responded),
            resolved, resolved == 0 ? null : Math.round((double) resolutionMinutes / resolved));
    }

    // ── O'zgartirish ─────────────────────────────────────────────────────

    /**
     * Holat, mas'ul, o'qituvchi. Mas'ulni faqat SA o'zgartiradi (ADMIN — 403 {@code appeal.assign.forbidden}).
     * NEW ga qaytarib bo'lmaydi; RESOLVED / REJECTED — {@code resolved_at} qo'yiladi (va sozlamaga ko'ra
     * yuboruvchiga bot xabari), qayta ochilsa tozalanadi.
     */
    @Transactional
    public AppealDtos.Detail patch(Long id, AppealDtos.PatchRequest request) {
        Viewer viewer = viewer();
        Appeal appeal = load(id, viewer);
        LocalDateTime now = LocalDateTime.now(billingClock);

        Appeal.Status closedNow = null;
        if (request.getStatus() != null) {
            Appeal.Status target = parseStatus(request.getStatus());
            if (target != appeal.getStatus()) {
                if (target == Appeal.Status.NEW) {
                    throw CodedException.badRequest("appeal.status.invalidTransition");
                }
                appeal.setStatus(target);
                appeal.setResolvedAt(target.closed() ? now : null);
                if (target.closed()) {
                    closedNow = target;
                }
            }
        }
        if (request.isTeacherSet()) {
            Long teacherId = request.getTeacherId();
            if (teacherId != null) {
                if (appeal.getType() != Appeal.Type.TEACHER) {
                    throw CodedException.badRequest("appeal.teacher.typeMismatch");
                }
                if (!teacherRepository.existsById(teacherId)) {
                    throw CodedException.badRequest("appeal.teacher.notFound");
                }
                Long subject = appealService.teacherUserId(teacherId);
                if (subject != null && subject.equals(appeal.getAssigneeUserId())) {
                    throw CodedException.badRequest("appeal.assignee.isSubject");
                }
            }
            appeal.setTeacherId(teacherId);
        }
        Long newlyAssigned = null;
        if (request.isAssigneeSet()) {
            if (!viewer.superAdmin()) {
                throw CodedException.forbidden("appeal.assign.forbidden");
            }
            Long userId = request.getAssigneeUserId();
            if (userId != null) {
                userRepository.findById(userId)
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .filter(u -> u.getRole() == UserRole.SUPER_ADMIN || u.getRole() == UserRole.ADMIN)
                    .orElseThrow(() -> CodedException.badRequest("appeal.assignee.invalid"));
                if (userId.equals(appealService.teacherUserId(appeal.getTeacherId()))) {
                    throw CodedException.badRequest("appeal.assignee.isSubject");
                }
            }
            if (!Objects.equals(userId, appeal.getAssigneeUserId())) {
                newlyAssigned = userId;
            }
            appeal.setAssigneeUserId(userId);
        }
        appeal.setUpdatedAt(now);
        appealRepository.save(appeal);

        if (newlyAssigned != null && !newlyAssigned.equals(viewer.user().getId())) {
            appealService.notifyUsers(appeal, Set.of(newlyAssigned), NotificationType.APPEAL_NEW, "assigned",
                "appeal:assigned:" + appeal.getId() + ":" + newlyAssigned + ":" + stamp(now),
                appeal.getNumber(), texts.type(appeal.getType(), AppealTexts.locale("uz")),
                AppealService.preview(appeal.getText()));
        }
        if (closedNow != null && properties.isNotifySenderOnClose()) {
            Locale locale = AppealTexts.locale(appeal.getLanguage());
            appealService.enqueueToSender(appeal,
                texts.t("appeal.bot.closed." + closedNow.name(), locale, appeal.getNumber()),
                appealService.replyButton(appeal, locale),
                "appeal:closed:" + appeal.getId() + ":" + stamp(now), "APPEAL_CLOSED");
        }
        return detail(appeal);
    }

    /**
     * Xodim javobi. {@code internal=false} — yuboruvchiga bot (outbox, MANUAL: darhol, sokin soatda ovozsiz)
     * "✍️ Javob yozish" tugmasi bilan; birinchi javob vaqti qo'yiladi, NEW → IN_PROGRESS. {@code internal=true} —
     * faqat xodimlar uchun izoh, yuborilmaydi.
     */
    @Transactional
    public AppealDtos.Message addStaffMessage(Long id, AppealDtos.StaffMessageRequest request) {
        Viewer viewer = viewer();
        Appeal appeal = load(id, viewer);
        String text = appealService.normalizeText(request == null ? null : request.text());
        boolean internal = request != null && Boolean.TRUE.equals(request.internal());
        LocalDateTime now = LocalDateTime.now(billingClock);
        AppealMessage message = messageRepository.save(AppealMessage.builder()
            .appealId(appeal.getId())
            .authorType(AppealMessage.AuthorType.STAFF)
            .authorUserId(viewer.user().getId())
            .text(text)
            .createdAt(now)
            .delivered(false)
            .internal(internal)
            .build());
        if (!internal) {
            Locale locale = AppealTexts.locale(appeal.getLanguage());
            String html = texts.t("appeal.bot.staffReply", locale, appeal.getNumber(),
                TelegramUpdateHandler.escape(text));
            message.setDelivered(appealService.enqueueToSender(appeal, html, appealService.replyButton(appeal, locale),
                "appeal:reply:" + message.getId(), "APPEAL_REPLY"));
            messageRepository.save(message);
            if (appeal.getFirstResponseAt() == null) {
                appeal.setFirstResponseAt(now);
            }
            if (appeal.getStatus() == Appeal.Status.NEW) {
                appeal.setStatus(Appeal.Status.IN_PROGRESS);
            }
        }
        appeal.setUpdatedAt(now);
        appealRepository.save(appeal);
        return toMessage(message, Map.of(viewer.user().getId(), viewer.user()));
    }

    // ── Ruxsat ───────────────────────────────────────────────────────────

    private Viewer viewer() {
        User user = teacherAccessService.getCurrentUserOrThrow();
        if (user.getRole() != UserRole.SUPER_ADMIN && user.getRole() != UserRole.ADMIN) {
            throw CodedException.forbidden("appeal.forbidden");
        }
        Set<Long> ownTeachers = teacherRepository.findByUser_Id(user.getId()).map(Teacher::getId)
            .map(Set::of).orElse(Set.of());
        return new Viewer(user, user.getRole() == UserRole.SUPER_ADMIN, ownTeachers);
    }

    private Appeal load(Long id, Viewer viewer) {
        return appealRepository.findOne(scope(viewer).and(idIs(id)))
            .orElseThrow(() -> CodedException.notFound("appeal.notFound"));
    }

    private static Specification<Appeal> scope(Viewer viewer) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (!viewer.superAdmin()) {
                ps.add(cb.equal(root.get("assigneeUserId"), viewer.user().getId()));
            }
            if (!viewer.ownTeacherIds().isEmpty()) {
                ps.add(cb.or(cb.isNull(root.get("teacherId")),
                    cb.not(root.get("teacherId").in(viewer.ownTeacherIds()))));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static Specification<Appeal> idIs(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    private static Specification<Appeal> statusIs(Appeal.Status status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Appeal> filter(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.status() != null) {
                ps.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.type() != null) {
                ps.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.assigneeId() != null) {
                ps.add(cb.equal(root.get("assigneeUserId"), f.assigneeId()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), f.from().atStartOfDay()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.<LocalDateTime>get("createdAt"), f.to().plusDays(1).atStartOfDay()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().strip().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.add(cb.or(
                    cb.like(cb.lower(root.<String>get("number")), like, '\\'),
                    cb.like(cb.lower(root.<String>get("text")), like, '\\'),
                    cb.like(cb.lower(root.<String>get("contactName")), like, '\\'),
                    cb.like(root.<String>get("contactPhone"), like, '\\')));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    // ── Moslash ──────────────────────────────────────────────────────────

    private AppealDtos.Detail detail(Appeal a) {
        List<AppealMessage> messages = messageRepository.findByAppealIdOrderByIdAsc(a.getId());
        Names names = names(List.of(a), messages);
        AppealDtos.Sender sender = null;
        if (!a.isAnonymous()) {
            AppIdentity identity = a.getAppIdentityId() == null ? null
                : identityRepository.findById(a.getAppIdentityId()).orElse(null);
            sender = new AppealDtos.Sender(identity != null,
                identity != null ? identity.getKind().name() : null,
                senderName(a, names),
                a.getContactPhone() != null ? a.getContactPhone()
                    : identity != null ? identity.getPhoneCanonical() : null,
                names.student(a.getStudentId()), names.parent(a.getParentId()));
        }
        Map<Long, User> users = names.users;
        return new AppealDtos.Detail(a.getId(), a.getNumber(), a.getType().name(), a.getStatus().name(),
            a.isAnonymous(), a.getSource().name(), a.getLanguage(), a.getText(), sender,
            names.user(a.getAssigneeUserId()), names.teacher(a.getTeacherId()), a.getCreatedAt(), a.getUpdatedAt(),
            a.getFirstResponseAt(), a.getResolvedAt(),
            messages.stream().map(m -> toMessage(m, users)).toList());
    }

    private static AppealDtos.Message toMessage(AppealMessage m, Map<Long, User> users) {
        User author = m.getAuthorUserId() == null ? null : users.get(m.getAuthorUserId());
        return new AppealDtos.Message(m.getId(), m.getAuthorType().name(),
            author == null ? null : new AppealDtos.Ref(author.getId(), userName(author)),
            m.getText(), m.isInternal(), m.isDelivered(), m.getCreatedAt());
    }

    /** Ulanmagan — kiritilgan ism; ulangan — ota-ona yozuvi, o'quvchi yoki Telegram ismi. */
    private static String senderName(Appeal a, Names names) {
        if (a.getContactName() != null) {
            return a.getContactName();
        }
        AppealDtos.Ref parent = names.parent(a.getParentId());
        if (parent != null) {
            return parent.name();
        }
        AppealDtos.Ref student = names.student(a.getStudentId());
        return student != null ? student.name() : null;
    }

    private Names names(Collection<Appeal> appeals, Collection<AppealMessage> messages) {
        Set<Long> userIds = new java.util.HashSet<>();
        appeals.stream().map(Appeal::getAssigneeUserId).filter(Objects::nonNull).forEach(userIds::add);
        messages.stream().map(AppealMessage::getAuthorUserId).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Teacher> teachers = teacherRepository.findAllById(appeals.stream().map(Appeal::getTeacherId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(Teacher::getId, Function.identity()));
        // Anonimlarda bu ustunlar baribir NULL
        Map<Long, Student> students = studentRepository.findAllById(appeals.stream().map(Appeal::getStudentId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Parent> parents = parentRepository.findAllById(appeals.stream().map(Appeal::getParentId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(Parent::getId, Function.identity()));
        return new Names(users, teachers, students, parents);
    }

    private record Names(Map<Long, User> users, Map<Long, Teacher> teachers, Map<Long, Student> students,
                         Map<Long, Parent> parents) {

        AppealDtos.Ref user(Long id) {
            User u = id == null ? null : users.get(id);
            return u == null ? null : new AppealDtos.Ref(u.getId(), userName(u));
        }

        AppealDtos.Ref teacher(Long id) {
            Teacher t = id == null ? null : teachers.get(id);
            return t == null ? null : new AppealDtos.Ref(t.getId(), AppealService.teacherName(t));
        }

        AppealDtos.Ref student(Long id) {
            Student s = id == null ? null : students.get(id);
            return s == null ? null : new AppealDtos.Ref(s.getId(),
                (Objects.toString(s.getFirstName(), "") + " " + Objects.toString(s.getLastName(), "")).trim());
        }

        AppealDtos.Ref parent(Long id) {
            Parent p = id == null ? null : parents.get(id);
            return p == null ? null : new AppealDtos.Ref(p.getId(), p.getFullName());
        }
    }

    private static String userName(User u) {
        String name = (Objects.toString(u.getFirstName(), "") + " " + Objects.toString(u.getLastName(), "")).trim();
        return name.isEmpty() ? u.getUsername() : name;
    }

    static Appeal.Status parseStatus(String raw) {
        try {
            return Appeal.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("appeal.status.invalid", raw);
        }
    }

    private static long stamp(LocalDateTime now) {
        return now.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
