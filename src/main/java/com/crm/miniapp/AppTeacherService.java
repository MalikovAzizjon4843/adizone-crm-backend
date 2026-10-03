package com.crm.miniapp;

import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.request.AttendanceUnlockCreateDto;
import com.crm.dto.response.AbsenceNoticeResponse;
import com.crm.dto.response.AttendanceUnlockResponseDto;
import com.crm.entity.Attendance;
import com.crm.entity.Group;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.service.AbsenceNoticeService;
import com.crm.service.AttendanceAccessService;
import com.crm.service.AttendanceService;
import com.crm.service.AttendanceUnlockRequestService;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * O'qituvchi rejimi (docs/design/telegram-platform.md §11.4). Davomat qoidalari takrorlanmaydi: o'qish huquqi —
 * {@link AttendanceAccessService}, yozish — {@link AttendanceService#markAttendance}, ochish so'rovi —
 * {@link AttendanceUnlockRequestService}; ular xodim nomidan (run-as, {@link #asStaff}) chaqiriladi — o'rinbosar
 * qoidalari, audit, billing va ota-ona xabari CRM'dagidek ishlaydi.
 *
 * <p>Ilova qatlami faqat qo'shimcha tekshiradi: o'tgan kun — amaldagi ochish ruxsatisiz 403 {@code attendance.locked};
 * so'rovdagi o'quvchi shu guruhda bo'lishi shart (403 {@code app.student.forbidden}).
 *
 * <p>Tranzaksiya yo'q (sinf darajasida): har chaqirilgan servis o'zinikini ochadi.
 */
@Service
@RequiredArgsConstructor
public class AppTeacherService {

    private static final Set<GroupStatus> OPEN_GROUPS = EnumSet.of(GroupStatus.ACTIVE, GroupStatus.FORMING);

    private final MiniAppAuthService authService;
    private final MiniAppQueryService queryService;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final LessonSubstitutionRepository substitutionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final AbsenceNoticeService absenceNoticeService;
    private final AttendanceAccessService attendanceAccessService;
    private final AttendanceService attendanceService;
    private final AttendanceUnlockRequestService unlockService;
    private final GroupScheduleService groupScheduleService;
    private final com.crm.service.AttendanceDueService attendanceDueService;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final Clock billingClock;

    private record Staff(User user, Teacher teacher) {
    }

    // ── Bugungi darslar ──────────────────────────────────────────────────

    public AppDtos.TeacherDay today(AppPrincipal principal, LocalDate date) {
        Staff staff = staff(principal);
        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDate day = date != null ? date : now.toLocalDate();
        return tx.execute(s -> {
            List<AppDtos.TeacherLesson> lessons = new ArrayList<>();
            for (Group g : groupRepository.findByTeacher_IdAndStatusInOrderByIdAsc(staff.teacher().getId(), OPEN_GROUPS)) {
                Optional<LessonSubstitution> sub = substitutionRepository.findActive(g.getId(), day);
                String substitute = sub.filter(x -> !x.getSubstituteTeacher().getId().equals(staff.teacher().getId()))
                    .map(x -> MiniAppQueryService.teacherName(x.getSubstituteTeacher())).orElse(null);
                for (AppDtos.Lesson l : queryService.groupLessons(g, day, day, now)) {
                    if (held(l) && !attendanceDueService.isDue(g, day)) {
                        continue;    // guruh hali boshlanmagan / shu kuni faol o'quvchi yo'q
                    }
                    lessons.add(lesson(staff, g, l, "ORIGINAL", substitute, day));
                }
            }
            for (LessonSubstitution sub : substitutionRepository.findBySubstitute(staff.teacher().getId(), day, day,
                    SubstitutionStatus.CANCELLED)) {
                for (AppDtos.Lesson l : queryService.groupLessons(sub.getGroup(), day, day, now)) {
                    if (held(l) && !attendanceDueService.isDue(sub.getGroup(), day)) {
                        continue;
                    }
                    lessons.add(lesson(staff, sub.getGroup(), l, "SUBSTITUTE", null, day));
                }
            }
            lessons.sort(Comparator.comparing((AppDtos.TeacherLesson l) -> l.startTime() != null ? l.startTime() : "")
                .thenComparing(AppDtos.TeacherLesson::groupId));
            return new AppDtos.TeacherDay(day, lessons);
        });
    }

    /** Dars o'tiladi (bekor/ko'chirilgan emas) — davomat shu darslarga. */
    private static boolean held(AppDtos.Lesson l) {
        return "PLANNED".equals(l.status()) || "EXTRA".equals(l.status());
    }

    private AppDtos.TeacherLesson lesson(Staff staff, Group g, AppDtos.Lesson l, String role, String substitute,
                                         LocalDate day) {
        boolean held = held(l);
        boolean canMark = held && substitute == null && lockReason(staff, g.getId(), day, false) == null;
        int marked = attendanceRepository.findByGroup_IdAndAttendanceDate(g.getId(), day).size();
        int total = studentGroupRepository.findByGroup_IdAndIsActiveTrue(g.getId()).size();
        int notices = absenceNoticeService.byStudent(g.getId(), day).size();
        return new AppDtos.TeacherLesson(g.getId(), g.getGroupName(), day, l.startTime(), l.endTime(), l.room(),
            l.status(), role, substitute, canMark, marked, total, notices);
    }

    // ── Davomat ──────────────────────────────────────────────────────────

    public AppDtos.TeacherAttendance attendance(AppPrincipal principal, Long groupId, LocalDate date) {
        Staff staff = staff(principal);
        LocalDate day = date != null ? date : LocalDate.now(billingClock);
        asStaff(staff.user(), () -> {
            attendanceAccessService.assertCanRead(groupId, day);
            return null;
        });
        return view(staff, groupId, day);
    }

    public AppDtos.TeacherAttendance save(AppPrincipal principal, Long groupId, LocalDate date,
                                          AppDtos.TeacherAttendanceSave request) {
        Staff staff = staff(principal);
        LocalDate today = LocalDate.now(billingClock);
        LocalDate day = date != null ? date : today;
        if (day.isAfter(today)) {
            throw CodedException.badRequest("app.attendance.future");
        }
        if (day.isBefore(today)) {
            AttendanceUnlockRequestService.UnlockState state =
                unlockService.unlockState(staff.teacher().getId(), groupId, day);
            if (state != AttendanceUnlockRequestService.UnlockState.VALID) {
                throw new CodedException(HttpStatus.FORBIDDEN, "attendance.locked")
                    .withData(Map.of("reason", state == AttendanceUnlockRequestService.UnlockState.EXPIRED
                        ? "UNLOCK_EXPIRED" : "PAST_DATE"));
            }
        }
        Set<Long> allowed = tx.execute(s -> studentsOf(groupId, day).keySet());
        AttendanceRequest attendance = new AttendanceRequest();
        attendance.setGroupId(groupId);
        attendance.setDate(day);
        List<AttendanceRequest.StudentAttendanceItem> items = new ArrayList<>();
        for (AppDtos.TeacherAttendanceItem item : request.items()) {
            if (allowed == null || !allowed.contains(item.studentId())) {
                throw CodedException.forbidden("app.student.forbidden");
            }
            AttendanceRequest.StudentAttendanceItem row = new AttendanceRequest.StudentAttendanceItem();
            row.setStudentId(item.studentId());
            row.setStatus(parseStatus(item.status()));
            row.setNotes(item.notes());
            row.setExcused(item.excused());
            row.setExcuseReason(item.excuseReason());
            items.add(row);
        }
        attendance.setAttendances(items);
        // Guruh egaligi / o'rinbosar kuni / dars bor kun — mavjud servis tekshiradi (xodim nomidan)
        asStaff(staff.user(), () -> attendanceService.markAttendance(attendance));
        return view(staff, groupId, day);
    }

    // ── Ochish so'rovlari ────────────────────────────────────────────────

    public AppDtos.UnlockInfo requestUnlock(AppPrincipal principal, AppDtos.UnlockCreate request) {
        Staff staff = staff(principal);
        AttendanceUnlockCreateDto dto = new AttendanceUnlockCreateDto();
        dto.setGroupId(request.groupId());
        dto.setAttendanceDate(request.date());
        dto.setNote(request.note());
        return unlockInfo(asStaff(staff.user(), () -> unlockService.createRequest(dto)));
    }

    public List<AppDtos.UnlockInfo> unlockRequests(AppPrincipal principal, Long groupId, LocalDate date) {
        Staff staff = staff(principal);
        return asStaff(staff.user(), () -> unlockService.getMyRequests(groupId, date)).stream()
            .map(AppTeacherService::unlockInfo).toList();
    }

    // ── Ichki ────────────────────────────────────────────────────────────

    private AppDtos.TeacherAttendance view(Staff staff, Long groupId, LocalDate day) {
        return tx.execute(s -> {
            Group group = groupRepository.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
            Map<Long, AbsenceNoticeResponse> notices = absenceNoticeService.byStudent(groupId, day);
            Map<Long, Attendance> marked = new LinkedHashMap<>();
            attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, day)
                .forEach(a -> marked.put(a.getStudent().getId(), a));
            List<AppDtos.TeacherAttendanceStudent> students = new ArrayList<>();
            studentsOf(groupId, day).forEach((studentId, name) -> {
                Attendance a = marked.get(studentId);
                AbsenceNoticeResponse n = notices.get(studentId);
                students.add(new AppDtos.TeacherAttendanceStudent(studentId, name,
                    a != null ? a.getStatus().name() : null, a != null ? a.getNotes() : null,
                    a != null ? a.getExcused() : null, a != null ? a.getExcuseReason() : null,
                    n != null ? new AppDtos.AbsenceBrief(n.id(), n.type(), n.comment(), n.createdAt()) : null));
            });
            String lock = lockReason(staff, groupId, day, true);
            AppDtos.UnlockInfo unlock = asStaff(staff.user(), () -> unlockService.getMyRequests(groupId, day))
                .stream().findFirst().map(AppTeacherService::unlockInfo).orElse(null);
            return new AppDtos.TeacherAttendance(groupId, group.getGroupName(), day, lock == null, lock, unlock, students);
        });
    }

    /** Faol o'quvchilar + shu kuni belgilangan (keyin chiqib ketgan) o'quvchilar, ism bo'yicha. */
    private Map<Long, String> studentsOf(Long groupId, LocalDate day) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findByGroup_IdAndIsActiveTrue(groupId)) {
            out.put(sg.getStudent().getId(), MiniAppQueryService.fullName(sg.getStudent()));
        }
        for (Attendance a : attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, day)) {
            out.putIfAbsent(a.getStudent().getId(), MiniAppQueryService.fullName(a.getStudent()));
        }
        Map<Long, String> sorted = new LinkedHashMap<>();
        out.entrySet().stream().sorted(Map.Entry.comparingByValue())
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** null — tahrirlash mumkin; aks holda FUTURE / SUBSTITUTED / NO_LESSON / PAST_DATE / UNLOCK_EXPIRED. */
    private String lockReason(Staff staff, Long groupId, LocalDate day, boolean checkLesson) {
        LocalDate today = LocalDate.now(billingClock);
        if (day.isAfter(today)) {
            return "FUTURE";
        }
        Optional<LessonSubstitution> sub = substitutionRepository.findActive(groupId, day);
        if (sub.isPresent() && !sub.get().getSubstituteTeacher().getId().equals(staff.teacher().getId())) {
            return "SUBSTITUTED";
        }
        if (checkLesson && !groupScheduleService.hasLessonOn(groupId, day)) {
            return "NO_LESSON";
        }
        if (day.isBefore(today)) {
            return switch (unlockService.unlockState(staff.teacher().getId(), groupId, day)) {
                case VALID -> null;
                case EXPIRED -> "UNLOCK_EXPIRED";
                case NONE -> "PAST_DATE";
            };
        }
        return null;
    }

    private Staff staff(AppPrincipal principal) {
        User user = authService.requireTeacher(principal);
        Teacher teacher = teacherRepository.findByUser_Id(user.getId())
            .orElseThrow(() -> CodedException.forbidden("app.teacher.profileMissing"));
        return new Staff(user, teacher);
    }

    /**
     * Xodim nomidan bajarish (run-as): {@code TeacherAccessService} va audit joriy foydalanuvchini SecurityContext'dan
     * oladi. Faqat faol TEACHER useri uchun ({@link MiniAppAuthService#requireTeacher}) — admin huquqi app'ga o'tmaydi.
     */
    static <T> T asStaff(User user, Supplier<T> body) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        SecurityContextHolder.setContext(context);
        try {
            return body.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static AttendanceStatus parseStatus(String raw) {
        try {
            return AttendanceStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw CodedException.badRequest("app.attendance.statusInvalid", raw);
        }
    }

    private static AppDtos.UnlockInfo unlockInfo(AttendanceUnlockResponseDto dto) {
        return new AppDtos.UnlockInfo(dto.getId(), dto.getStatus() != null ? dto.getStatus().name() : null,
            dto.getTeacherNote(), dto.getCreatedAt(), dto.getReviewedAt(), dto.getExpiresAt());
    }
}
