package com.crm.miniapp;

import com.crm.entity.AbsenceNotice;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.TelegramOutbox;
import com.crm.exception.CodedException;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.repository.AbsenceNoticeRepository;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.StudentRepository;
import com.crm.telegram.TelegramOutboxService;
import com.crm.telegram.TelegramProperties;
import com.crm.telegram.TelegramUpdateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sabab bildirish — Mini App tomoni (docs/design/telegram-platform.md §11.2). Faqat o'quvchining ochiq guruhi,
 * bugun … +{@value #DAYS_AHEAD} kun ichidagi dars bor kun. O'qituvchi (va shu kungi o'rinbosar) app'da ulangan
 * bo'lsa — bot xabari (HIGH: darhol, sokin soatda ovozsiz).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MiniAppAbsenceService {

    static final int DAYS_AHEAD = 14;
    static final int COMMENT_MAX = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MiniAppAuthService authService;
    private final MiniAppQueryService queryService;
    private final AbsenceNoticeRepository repository;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final LessonSubstitutionRepository substitutionRepository;
    private final AppIdentityRepository identityRepository;
    private final TelegramOutboxService outboxService;
    private final TelegramProperties telegramProperties;
    private final Clock billingClock;
    private final NotificationService notificationService;

    @Transactional
    public AppDtos.AbsenceNoticeItem create(AppPrincipal principal, AppDtos.AbsenceNoticeRequest request) {
        Student student = authService.requireStudent(principal, request.studentId());
        StudentGroup sg = queryService.openEnrollments(student.getId()).stream()
            .filter(e -> e.getGroup().getId().equals(request.groupId()))
            .findFirst()
            .orElseThrow(() -> CodedException.forbidden("app.group.forbidden"));

        AbsenceNotice.Type type = parseType(request.type());
        String comment = request.comment() == null ? null : request.comment().trim();
        if (comment != null && comment.isEmpty()) {
            comment = null;
        }
        if (type == AbsenceNotice.Type.OTHER && comment == null) {
            throw CodedException.badRequest("app.absence.commentRequired");
        }
        if (comment != null && comment.length() > COMMENT_MAX) {
            throw CodedException.badRequest("app.absence.commentTooLong", COMMENT_MAX);
        }

        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDate today = now.toLocalDate();
        LocalDate date = request.lessonDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(DAYS_AHEAD))) {
            throw CodedException.badRequest("app.absence.dateOutOfRange", DAYS_AHEAD);
        }
        AppDtos.Lesson lesson = queryService.lessons(List.of(sg), date, date, now).stream()
            .filter(l -> "PLANNED".equals(l.status()) || "EXTRA".equals(l.status()))
            .findFirst()
            .orElseThrow(() -> CodedException.badRequest("app.absence.noLesson"));
        // Bugungi dars tugagan bo'lsa — kech (davomat allaqachon o'qituvchida)
        if (date.equals(today) && lesson.endTime() != null && ended(lesson.endTime(), now.toLocalTime())) {
            throw CodedException.badRequest("app.absence.lessonPassed");
        }
        if (repository.existsByStudentIdAndGroupIdAndLessonDateAndStatus(student.getId(), sg.getGroup().getId(),
                date, AbsenceNotice.Status.ACTIVE)) {
            throw new CodedException(HttpStatus.CONFLICT, "app.absence.duplicate");
        }

        String submittedAs = authService.links(principal.identityId()).stream()
            .filter(l -> l.getStudentId().equals(student.getId()))
            .map(l -> l.getRelation().name())
            .findFirst().orElse(AppIdentityStudent.Relation.PARENT.name());
        AbsenceNotice notice = repository.save(AbsenceNotice.builder()
            .identityId(principal.identityId())
            .studentId(student.getId())
            .groupId(sg.getGroup().getId())
            .lessonDate(date)
            .type(type)
            .comment(comment)
            .status(AbsenceNotice.Status.ACTIVE)
            .submittedAs(submittedAs)
            .createdAt(now)
            .build());

        notifyTeachers(notice, student, sg.getGroup(), lesson);
        return toItem(notice, student, sg.getGroup(), today);
    }

    /** O'z yozuvlari (shu identity yuborgan); {@code studentId} berilsa — IDOR tekshiruvi bilan filtr. */
    @Transactional(readOnly = true)
    public List<AppDtos.AbsenceNoticeItem> list(AppPrincipal principal, Long studentId) {
        List<AbsenceNotice> notices;
        if (studentId != null) {
            authService.requireStudent(principal, studentId);
            notices = repository.findByIdentityIdAndStudentIdOrderByCreatedAtDescIdDesc(principal.identityId(), studentId);
        } else {
            notices = repository.findByIdentityIdOrderByCreatedAtDescIdDesc(principal.identityId());
        }
        Map<Long, Student> students = studentRepository.findAllById(
                notices.stream().map(AbsenceNotice::getStudentId).distinct().toList()).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, Group> groups = groupRepository.findAllById(
                notices.stream().map(AbsenceNotice::getGroupId).distinct().toList()).stream()
            .collect(Collectors.toMap(Group::getId, Function.identity()));
        LocalDate today = LocalDate.now(billingClock);
        return notices.stream()
            .map(n -> toItem(n, students.get(n.getStudentId()), groups.get(n.getGroupId()), today))
            .toList();
    }

    @Transactional
    public AppDtos.AbsenceNoticeItem cancel(AppPrincipal principal, Long id) {
        AbsenceNotice notice = repository.findById(id)
            .filter(n -> n.getIdentityId().equals(principal.identityId()))
            .orElseThrow(() -> CodedException.forbidden("app.absence.forbidden"));
        LocalDate today = LocalDate.now(billingClock);
        if (notice.getStatus() != AbsenceNotice.Status.ACTIVE) {
            throw new CodedException(HttpStatus.CONFLICT, "app.absence.notActive");
        }
        if (notice.getLessonDate().isBefore(today)) {
            throw CodedException.badRequest("app.absence.lessonPassed");
        }
        notice.setStatus(AbsenceNotice.Status.CANCELLED);
        notice.setCancelledAt(LocalDateTime.now(billingClock));
        return toItem(notice, studentRepository.findById(notice.getStudentId()).orElse(null),
            groupRepository.findById(notice.getGroupId()).orElse(null), today);
    }

    /**
     * Guruh o'qituvchisi va shu kungi o'rinbosar — app'da o'qituvchi rejimida ulangan bo'lsa. Ulanmagan bo'lsa
     * xabar yo'q, bildirish baribir CRM davomatida ko'rinadi.
     */
    private void notifyTeachers(AbsenceNotice notice, Student student, Group group, AppDtos.Lesson lesson) {
        Set<Long> userIds = new LinkedHashSet<>();
        if (group.getTeacher() != null && group.getTeacher().getUser() != null) {
            userIds.add(group.getTeacher().getUser().getId());
        }
        substitutionRepository.findActive(group.getId(), notice.getLessonDate())
            .map(s -> s.getSubstituteTeacher())
            .map(Teacher::getUser)
            .filter(Objects::nonNull)
            .ifPresent(u -> userIds.add(u.getId()));

        String text = "📝 <b>Sabab bildirildi</b>\n"
            + "👤 " + TelegramUpdateHandler.escape(MiniAppQueryService.fullName(student)) + "\n"
            + "📚 " + TelegramUpdateHandler.escape(group.getGroupName()) + ", " + notice.getLessonDate().format(DATE)
            + (lesson.startTime() != null ? " " + lesson.startTime() : "") + "\n"
            + "❗ " + typeLabel(notice.getType())
            + (notice.getComment() != null ? ": " + TelegramUpdateHandler.escape(notice.getComment()) : "");
        String url = telegramProperties.getWebappUrl();
        Map<String, Object> button = TelegramOutboxService.openAppButton("Davomatni ochish",
            url == null || url.isBlank() ? null
                : url + "/#/teacher/attendance/" + group.getId() + "?date=" + notice.getLessonDate());
        for (Long userId : userIds) {
            identityRepository.findFirstByStaffUserIdAndStatus(userId, AppIdentity.Status.ACTIVE)
                .filter(i -> i.getChatId() != null)
                .ifPresent(i -> outboxService.enqueue(i.getChatId(), text, button, TelegramOutbox.Priority.HIGH,
                    "absence:" + notice.getId() + ":" + userId, "ABSENCE_NOTICE"));
        }
        // CRM qo'ng'iroqchasi — app'da ulanmagan o'qituvchi ham ko'radi (commit'dan keyin)
        notificationService.toUsers(NotificationType.ABSENCE_NOTICE, userIds, null,
            "Sabab bildirildi: " + MiniAppQueryService.fullName(student),
            group.getGroupName() + ", " + notice.getLessonDate().format(DATE)
                + (lesson.startTime() != null ? " " + lesson.startTime() : "") + " — " + typeLabel(notice.getType())
                + (notice.getComment() != null ? ": " + notice.getComment() : ""),
            "/attendance?groupId=" + group.getId() + "&date=" + notice.getLessonDate(),
            "AbsenceNotice", notice.getId());
    }

    private static boolean ended(String endTime, LocalTime now) {
        try {
            return !LocalTime.parse(endTime).isAfter(now);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static AbsenceNotice.Type parseType(String raw) {
        try {
            return AbsenceNotice.Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw CodedException.badRequest("app.absence.typeInvalid", raw);
        }
    }

    private static String typeLabel(AbsenceNotice.Type type) {
        return switch (type) {
            case ABSENT -> "Kelmaydi";
            case LATE -> "Kechikadi";
            case OTHER -> "Boshqa";
        };
    }

    private static AppDtos.AbsenceNoticeItem toItem(AbsenceNotice n, Student s, Group g, LocalDate today) {
        boolean canCancel = n.getStatus() == AbsenceNotice.Status.ACTIVE && !n.getLessonDate().isBefore(today);
        return new AppDtos.AbsenceNoticeItem(n.getId(), n.getStudentId(),
            s != null ? MiniAppQueryService.fullName(s) : null, n.getGroupId(), g != null ? g.getGroupName() : null,
            n.getLessonDate(), n.getType().name(), n.getComment(), n.getStatus().name(), n.getCreatedAt(),
            n.getCancelledAt(), canCancel);
    }
}
