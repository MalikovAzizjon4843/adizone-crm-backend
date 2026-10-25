package com.crm.service;

import com.crm.billing.LessonChargeService;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.AttendanceRequest;
import com.crm.dto.response.AbsenceNoticeResponse;
import com.crm.dto.response.AttendanceResponse;
import com.crm.dto.response.MissingAttendanceResponse;
import com.crm.dto.response.TeacherMissingAttendanceResponse;
import com.crm.entity.*;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    /** "Kelmadi" xabari faqat shu kunlik davomatga (bugun va kecha); eski kunlar tuzatilsa yuborilmaydi. */
    static final int ABSENT_NOTIFY_MAX_AGE_DAYS = 1;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final GroupScheduleService groupScheduleService;
    private final NotificationService notificationService;
    private final StudentPaymentLifecycleService studentPaymentLifecycleService;
    private final AttendanceUnlockRequestService attendanceUnlockRequestService;
    private final TeacherAccessService teacherAccessService;
    private final LessonChargeService lessonChargeService;
    private final com.crm.dashboard.AttendanceSignals attendanceSignals;
    private final AttendanceAccessService attendanceAccessService;
    private final LessonSubstitutionService lessonSubstitutionService;
    private final AbsenceNoticeService absenceNoticeService;
    private final AttendanceDueService attendanceDueService;
    private final com.crm.billing.DebtorService debtorService;
    private final com.crm.billing.BillingStatusService billingStatusService;

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Attendance",
        summary = "'Davomat belgilandi: ' + #result.size() + ' o''quvchi'",
        entityId = "#request.groupId")
    public List<AttendanceResponse> markAttendance(AttendanceRequest request) {
        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        LocalDate date = request.getDate();
        // Guruh o'qituvchisi yoki shu kungi o'rinbosar (leaves-exams-contracts §2.3)
        attendanceAccessService.assertCanMark(group, date);
        // "Bugun" — Asia/Tashkent (JVM default zonasi, CrmApplication.main).
        LocalDate today = LocalDate.now();
        if (date != null && date.isAfter(today)) {
            throw new BadRequestException("Kelajakdagi sana uchun davomat kiritib bo'lmaydi");
        }
        assertGroupHasLessonOnDate(group.getId(), date);

        User marker = teacherAccessService.getCurrentUserOrThrow();

        boolean isAdmin = teacherAccessService.isCurrentUserAdmin();

        if (!isAdmin && date != null && date.isBefore(today)) {
            Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();

            // APPROVED ruxsat muddatli (reviewedAt + app.attendance.unlock-valid-hours).
            switch (attendanceUnlockRequestService.unlockState(teacher.getId(), request.getGroupId(), date)) {
                case VALID -> {
                    // ruxsat amal qilmoqda
                }
                case EXPIRED -> throw new com.crm.exception.ForbiddenException(
                    "Ruxsat muddati tugagan, qayta so'rang");
                case NONE -> throw new com.crm.exception.ForbiddenException(
                    "Bu kun uchun ruxsat kerak. Admindan so'rang.");
            }
        }

        List<AttendanceResponse> results = new ArrayList<>();

        for (AttendanceRequest.StudentAttendanceItem item : request.getAttendances()) {
            // Holati yo'q element o'tkazib yuboriladi (avval PRESENT deb yozilardi — "belgilanmagan"
            // o'quvchi "keldi" bo'lib qolardi; eski panel hali yuborishi mumkin).
            if (item.getStatus() == null) {
                continue;
            }
            AttendanceStatus itemStatus = item.getStatus();
            if (itemStatus == AttendanceStatus.ABSENT || itemStatus == AttendanceStatus.EXCUSED || itemStatus == AttendanceStatus.LATE) {
                boolean hasNote = (item.getNotes() != null && !item.getNotes().isBlank()) ||
                                  (item.getExcuseReason() != null && !item.getExcuseReason().isBlank());
                if (!hasNote) {
                    throw new BadRequestException("Sabab kiritilishi shart");
                }
            }

            Student student = studentRepository.findById(item.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", item.getStudentId()));

            Attendance attendance = attendanceRepository
                .findByStudentIdAndGroupIdAndAttendanceDate(
                    item.getStudentId(), request.getGroupId(), request.getDate())
                .orElse(null);

            AttendanceStatus previousStatus = attendance != null ? attendance.getStatus() : null;

            if (attendance == null) {
                attendance = Attendance.builder()
                    .student(student)
                    .group(group)
                    .attendanceDate(request.getDate())
                    .build();
            }

            attendance.setStatus(itemStatus);
            attendance.setNotes(item.getNotes());
            attendance.setMarkedBy(marker);

            if (attendance.getStatus() == AttendanceStatus.ABSENT
                    && Boolean.TRUE.equals(item.getExcused())) {
                attendance.setStatus(AttendanceStatus.EXCUSED);
                attendance.setExcused(true);
                attendance.setExcuseReason(item.getExcuseReason());
            } else {
                attendance.setExcused(item.getExcused() != null ? item.getExcused() : false);
                attendance.setExcuseReason(item.getExcuseReason());
            }

            Attendance saved = attendanceRepository.save(attendance);
            results.add(toResponse(saved));

            // Billing v2 (§6.9): PER_LESSON ledger — davomat holatidan idempotent
            lessonChargeService.sync(saved);
            // Direktor dashboardi: sinov boshlanishi va lid tashrifi (§1.1, §1.5)
            attendanceSignals.onAttendanceSaved(saved);

            if (previousStatus != saved.getStatus()) {
                studentPaymentLifecycleService.onLessonAttended(
                    item.getStudentId(), request.getGroupId(), request.getDate());
            }

            if (previousStatus == AttendanceStatus.ABSENT && saved.getStatus() != AttendanceStatus.ABSENT) {
                // Tuzatildi (ABSENT → PRESENT/…): hali yuborilmagan "kelmadi" xabari bekor
                notificationService.cancelTelegram(absentKey(saved) + ":");
            } else if (saved.getStatus() == AttendanceStatus.ABSENT
                    && !request.getDate().isBefore(today.minusDays(ABSENT_NOTIFY_MAX_AGE_DAYS))) {
                // Ota-ona va o'quvchiga bot (ulangan bo'lsa); qayta saqlashda takrorlanmaydi (absent:{id})
                notificationService.dispatch(NotificationRequest.builder()
                    .type(NotificationType.STUDENT_ABSENT)
                    .studentId(student.getId())
                    .arg(student.getFirstName() + " " + student.getLastName())
                    .arg(group.getGroupName())
                    .arg(request.getDate().format(DAY))
                    .arg("")
                    .dedupeKey(absentKey(saved))
                    .priority(TelegramOutbox.Priority.HIGH)
                    .appPath("/#/attendance")
                    .build());
            }
        }

        if (!results.isEmpty()) {
            // "Darsni X o'tdi" belgisi bo'lsa — dars o'tildi (CONDUCTED), shu tranzaksiyada
            lessonSubstitutionService.onAttendanceSaved(group.getId(), date, marker);
        }
        return withDebt(results, group.getId());
    }

    /**
     * Dars bo'lmagan kunga davomat kiritishni bloklaydi (admin uchun ham). Manba —
     * {@link GroupScheduleService} (GET /groups/{id}/lesson-days bilan bir xil).
     */
    void assertGroupHasLessonOnDate(Long groupId, LocalDate date) {
        if (date == null) {
            throw new BadRequestException("Sana majburiy");
        }
        if (!groupScheduleService.hasLessonOn(groupId, date)) {
            throw new BadRequestException(
                "Bu kunda guruhda dars yo'q (" + dayToUzbek(date.getDayOfWeek().name()) + ")");
        }
    }

    @Transactional(readOnly = true)
    public MissingAttendanceResponse getMissingAttendance(Long groupId, LocalDate from, LocalDate to) {
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        teacherAccessService.assertOwnsGroup(group);
        return buildMissingForGroup(group, from, to);
    }

    @Transactional(readOnly = true)
    public TeacherMissingAttendanceResponse getMyMissingAttendance(LocalDate from, LocalDate to) {
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        List<Group> groups = groupRepository.findByTeacherId(teacher.getId());
        List<MissingAttendanceResponse> items = new ArrayList<>();
        int total = 0;
        for (Group group : groups) {
            MissingAttendanceResponse m = buildMissingForGroup(group, from, to);
            if (m.getMissingCount() > 0) {
                items.add(m);
                total += m.getMissingCount();
            }
        }
        return TeacherMissingAttendanceResponse.builder()
            .totalMissing(total)
            .groups(items)
            .build();
    }

    /** Qaysi kunlar "belgilanmagan" — faqat {@link AttendanceDueService} (guruh boshlanishi, bayram, istisno, faol o'quvchi). */
    private MissingAttendanceResponse buildMissingForGroup(Group group, LocalDate from, LocalDate to) {
        List<MissingAttendanceResponse.MissingDateItem> missing = attendanceDueService.missing(group, from, to).stream()
            .map(l -> MissingAttendanceResponse.MissingDateItem.builder()
                .date(l.date())
                .dayOfWeek(l.dayOfWeek())
                .startTime(l.startTime())
                .build())
            .toList();

        return MissingAttendanceResponse.builder()
            .groupId(group.getId())
            .groupName(group.getGroupName())
            .missingDates(missing)
            .missingCount(missing.size())
            .build();
    }

    static String dayToUzbek(String day) {
        if (day == null) {
            return "";
        }
        return switch (day.toUpperCase(Locale.ROOT)) {
            case "MONDAY" -> "Dushanba";
            case "TUESDAY" -> "Seshanba";
            case "WEDNESDAY" -> "Chorshanba";
            case "THURSDAY" -> "Payshanba";
            case "FRIDAY" -> "Juma";
            case "SATURDAY" -> "Shanba";
            case "SUNDAY" -> "Yakshanba";
            default -> day;
        };
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getGroupAttendance(Long groupId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        attendanceAccessService.assertCanRead(groupId, d);
        List<Attendance> existing = attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, d);
        // O'quvchi yonida Mini App sabab bildirishi (telegram-platform §11.2)
        Map<Long, AbsenceNoticeResponse> notices = absenceNoticeService.byStudent(groupId, d);

        if (!existing.isEmpty()) {
            return withDebt(existing.stream()
                .map(this::toResponse)
                .peek(r -> r.setAbsenceNotice(notices.get(r.getStudentId())))
                .collect(Collectors.toList()), groupId);
        }

        List<StudentGroup> activeStudents = studentGroupRepository.findByGroup_IdAndIsActiveTrue(groupId);

        return withDebt(activeStudents.stream()
            .map(sg -> AttendanceResponse.builder()
                .studentId(sg.getStudent().getId())
                .studentName(sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName())
                .groupId(groupId)
                .attendanceDate(d)
                .status(null)
                .notes("")
                .absenceNotice(notices.get(sg.getStudent().getId()))
                .build())
            .collect(Collectors.toList()), groupId);
    }

    /**
     * Qarzdor belgisi (o'qituvchi davomatda qarzdorni ko'radi; admin — kelgan-kelmaganini): bugungi holat, guruh
     * bo'yicha BITTA so'rov ({@link com.crm.billing.DebtorService#debtFlags} — /debtors bilan bir xil ta'rif).
     * Summa ({@code debtAmount}, {@code studentDebtAmount}) — faqat SA / ADMIN / ACCOUNTANT.
     */
    private List<AttendanceResponse> withDebt(List<AttendanceResponse> rows, Long groupId) {
        Map<Long, com.crm.billing.DebtorService.DebtFlag> flags = debtorService.debtFlags(groupId,
            rows.stream().map(AttendanceResponse::getStudentId).distinct().toList(), billingStatusService.today());
        boolean amounts = com.crm.billing.BillingAuth.hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT");
        for (AttendanceResponse r : rows) {
            com.crm.billing.DebtorService.DebtFlag f =
                flags.getOrDefault(r.getStudentId(), com.crm.billing.DebtorService.DebtFlag.NONE);
            r.setDebtor(f.debtor());
            r.setDebtSince(f.debtSince());
            r.setDebtDays(f.debtDays());
            r.setDebtAmount(amounts ? f.debtAmount() : null);
            r.setStudentDebtor(f.studentDebtor());
            r.setStudentDebtSince(f.studentDebtSince());
            r.setStudentDebtDays(f.studentDebtDays());
            r.setStudentDebtAmount(amounts ? f.studentDebtAmount() : null);
            r.setOtherGroupDebt(f.otherGroupDebt());
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getStudentAttendance(Long studentId) {
        teacherAccessService.assertOwnsStudent(studentId);
        return attendanceRepository.findByStudentIdOrderByAttendanceDateDesc(studentId)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** Bot xabari kaliti: har qabul qiluvchiga {@code :i<identityId>} qo'shiladi (NotificationService.dispatch). */
    static String absentKey(Attendance a) {
        return "absent:" + a.getId();
    }

    private AttendanceResponse toResponse(Attendance a) {
        return AttendanceResponse.builder()
            .id(a.getId())
            .studentId(a.getStudent().getId())
            .studentName(a.getStudent().getFirstName() + " " + a.getStudent().getLastName())
            .groupId(a.getGroup().getId())
            .groupName(a.getGroup().getGroupName())
            .attendanceDate(a.getAttendanceDate())
            .status(a.getStatus())
            .notes(a.getNotes())
            .excused(a.getExcused())
            .excuseReason(a.getExcuseReason())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
