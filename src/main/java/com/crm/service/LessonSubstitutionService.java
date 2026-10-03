package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.request.SubstitutionRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.SubstitutionResponse;
import com.crm.entity.Group;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeaveRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.TeacherRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Darsni X o'tdi" (o'rinbosar) — leaves-exams-contracts §2, buyurtmachi qarori.
 *
 * <ul>
 *   <li>O'rinbosar majburiy emas: o'qituvchilar o'zaro kelishadi, SA/A guruh + sana uchun belgilaydi
 *       (dars oldidan ham, keyin ham). Dars o'tilmasa — mavjud {@code LessonException CANCELLED}.</li>
 *   <li>O'rinbosar shu dars davomatini belgilaydi; davomat birinchi saqlanganda {@code PLANNED → CONDUCTED}.
 *       Belgilash paytida davomat allaqachon bo'lsa — darhol CONDUCTED.</li>
 *   <li>Haq: o'rinbosarga {@code SUBSTITUTE_LESSONS} (payroll), asosiy o'qituvchidan ayirilmaydi.</li>
 *   <li>Tekshiruvlar: o'rinbosar ≠ guruh o'qituvchisi, faol (ACTIVE); dars rejada bor; bir darsga bitta faol
 *       belgi (guruh qatori qulf ostida); o'rinbosarning shu kundagi darslari bilan vaqt to'qnashuvi — 409;
 *       o'rinbosarning shu oydagi oyligi APPROVED/PAID bo'lsa — 409.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LessonSubstitutionService {

    private final LessonSubstitutionRepository substitutionRepository;
    private final GroupRepository groupRepository;
    private final TeacherRepository teacherRepository;
    private final LeaveRepository leaveRepository;
    private final AttendanceRepository attendanceRepository;
    private final GroupScheduleService groupScheduleService;
    private final TeacherAccessService teacherAccessService;
    private final PayrollPeriodGuard payrollGuard;
    private final EntityManager entityManager;
    private final Clock billingClock;

    /** Filtr ({@code GET /api/substitutions}) — hammasi ixtiyoriy. */
    public record Filter(Long teacherId, Long substituteTeacherId, Long groupId, LocalDate from, LocalDate to,
                         SubstitutionStatus status) {
    }

    // ── O'qish ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<LessonSubstitution> active(Long groupId, LocalDate date) {
        return substitutionRepository.findActive(groupId, date);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubstitutionResponse> search(Filter f, int page, int size) {
        Specification<LessonSubstitution> spec = Specification.where(null);
        if (f.teacherId() != null) {
            spec = spec.and((r, q, cb) -> cb.or(
                cb.equal(r.get("originalTeacher").get("id"), f.teacherId()),
                cb.equal(r.get("substituteTeacher").get("id"), f.teacherId())));
        }
        if (f.substituteTeacherId() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("substituteTeacher").get("id"), f.substituteTeacherId()));
        }
        if (f.groupId() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("group").get("id"), f.groupId()));
        }
        if (f.from() != null) {
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("lessonDate"), f.from()));
        }
        if (f.to() != null) {
            spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("lessonDate"), f.to()));
        }
        if (f.status() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), f.status()));
        }
        Page<LessonSubstitution> p = substitutionRepository.findAll(spec,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("lessonDate"), Sort.Order.desc("id"))));
        return PageResponse.<SubstitutionResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).toList())
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    /**
     * O'qituvchi: o'zi asosiy (guruh o'qituvchisi — darsini boshqasi o'tadi) yoki o'rinbosar bo'lgan faol
     * belgilar; har yozuvda {@code role}. {@code from} berilmasa — bugun, {@code to} — {@code from + 1 yil}
     * (eng uzun oraliq ham shu).
     */
    @Transactional(readOnly = true)
    public List<SubstitutionResponse> my(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now(billingClock);
        LocalDate t = to != null ? to : f.plusYears(1);
        if (t.isBefore(f)) {
            throw CodedException.badRequest("substitution.range.invalid");
        }
        if (t.isAfter(f.plusYears(1))) {
            throw CodedException.badRequest("substitution.range.tooLong");
        }
        Teacher me = teacherAccessService.getCurrentTeacherOrThrow();
        return substitutionRepository.findByTeacher(me.getId(), f, t, SubstitutionStatus.CANCELLED).stream()
            .map(s -> {
                SubstitutionResponse r = toResponse(s);
                r.setRole(me.getId().equals(s.getSubstituteTeacher().getId())
                    ? SubstitutionResponse.Role.SUBSTITUTE : SubstitutionResponse.Role.ORIGINAL);
                return r;
            })
            .toList();
    }

    // ── Amallar ─────────────────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "LessonSubstitution",
        summary = "'Darsni boshqa o''qituvchi o''tdi: ' + #result.groupName + ' ' + #result.lessonDate"
            + " + ' — ' + #result.substituteTeacherName",
        entityId = "#result.id", label = "#result.groupName")
    public SubstitutionResponse create(SubstitutionRequest request) {
        return toResponse(createOne(request));
    }

    /** Ko'plikda — hammasi yoki hech biri: birinchi xato butun so'rovni bekor qiladi ({@code data.index}). */
    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "LessonSubstitution",
        summary = "'Darslar boshqa o''qituvchiga belgilandi: ' + #result.size() + ' ta'")
    public List<SubstitutionResponse> bulk(List<SubstitutionRequest> items) {
        List<SubstitutionResponse> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            try {
                out.add(toResponse(createOne(items.get(i))));
            } catch (CodedException e) {
                Map<String, Object> data = new LinkedHashMap<>();
                if (e.getData() != null) {
                    data.putAll(e.getData());
                }
                data.put("index", i);
                throw e.withData(data);
            }
        }
        return out;
    }

    private LessonSubstitution createOne(SubstitutionRequest r) {
        Group group = groupRepository.findById(r.getGroupId())
            .orElseThrow(() -> CodedException.notFound("error.group.notFound", r.getGroupId()));
        Teacher original = group.getTeacher();
        if (original == null) {
            throw CodedException.badRequest("substitution.noTeacher", group.getGroupName());
        }
        Teacher substitute = teacherRepository.findById(r.getSubstituteTeacherId())
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", r.getSubstituteTeacherId()));
        if (substitute.getId().equals(original.getId())) {
            throw CodedException.badRequest("substitution.sameTeacher");
        }
        if (!Teacher.STATUS_ACTIVE.equals(substitute.getStatus())) {
            throw CodedException.badRequest("substitution.teacherInactive", substitute.getStatus());
        }
        LocalDate date = r.getLessonDate();
        if (!groupScheduleService.hasLessonOn(group.getId(), date)) {
            throw CodedException.badRequest("substitution.noLesson", date);
        }
        if (r.getLeaveRequestId() != null && !leaveRepository.existsById(r.getLeaveRequestId())) {
            throw new ResourceNotFoundException("LeaveRequest", r.getLeaveRequestId());
        }

        lockGroup(group.getId());
        if (substitutionRepository.findActive(group.getId(), date).isPresent()) {
            throw new ConflictException("substitution.exists", group.getGroupName(), date);
        }
        assertNoTimeConflict(group, substitute, date);
        if (substitute.getUser() != null) {
            payrollGuard.assertUnlocked(substitute.getUser().getId(), date, date, "substitution.payrollLocked");
        }

        User actor = teacherAccessService.getCurrentUserOrThrow();
        LocalDateTime now = LocalDateTime.now(billingClock);
        boolean taken = attendanceRepository.findDistinctDatesByGroupAndDateBetween(group.getId(), date, date)
            .contains(date);
        LessonSubstitution s = LessonSubstitution.builder()
            .group(group)
            .lessonDate(date)
            .originalTeacher(original)
            .substituteTeacher(substitute)
            .leaveRequestId(r.getLeaveRequestId())
            .status(taken ? SubstitutionStatus.CONDUCTED : SubstitutionStatus.PLANNED)
            .conductedAt(taken ? now : null)
            .conductedBy(taken ? actor : null)
            .note(trim(r.getNote()))
            .createdBy(actor)
            .createdAt(now)
            .build();
        return substitutionRepository.save(s);
    }

    /**
     * PLANNED yoki CONDUCTED → CANCELLED (SA/A). O'tilgan dars bekor qilinsa o'rinbosar haqi kamayadi —
     * uning shu oydagi oyligi APPROVED/PAID bo'lsa 409 {@code substitution.payrollLocked}.
     */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "LessonSubstitution",
        summary = "'O''rinbosar belgisi bekor qilindi: ' + #result.groupName + ' ' + #result.lessonDate",
        entityId = "#id", label = "#result.groupName")
    public SubstitutionResponse cancel(Long id, String reason) {
        LessonSubstitution s = substitutionRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("substitution.notFound", id));
        lockGroup(s.getGroup().getId());
        entityManager.refresh(s);
        if (s.getStatus() == SubstitutionStatus.CANCELLED) {
            throw new ConflictException("substitution.alreadyCancelled");
        }
        if (s.getStatus() == SubstitutionStatus.CONDUCTED && s.getSubstituteTeacher().getUser() != null) {
            payrollGuard.assertUnlocked(s.getSubstituteTeacher().getUser().getId(), s.getLessonDate(),
                s.getLessonDate(), "substitution.payrollLocked");
        }
        AuditContext.change("status", s.getStatus(), SubstitutionStatus.CANCELLED);
        s.setStatus(SubstitutionStatus.CANCELLED);
        s.setCancelledBy(teacherAccessService.getCurrentUserOrThrow());
        s.setCancelledAt(LocalDateTime.now(billingClock));
        s.setCancelReason(trim(reason));
        return toResponse(substitutionRepository.save(s));
    }

    // ── Boshqa servislar chaqiradi ──────────────────────────────────────

    /** Davomat saqlandi (AttendanceService, shu tranzaksiyada): PLANNED → CONDUCTED. */
    @Transactional
    public void onAttendanceSaved(Long groupId, LocalDate date, User marker) {
        substitutionRepository.findActive(groupId, date)
            .filter(s -> s.getStatus() == SubstitutionStatus.PLANNED)
            .ifPresent(s -> {
                s.setStatus(SubstitutionStatus.CONDUCTED);
                s.setConductedAt(LocalDateTime.now(billingClock));
                s.setConductedBy(marker);
                substitutionRepository.save(s);
            });
    }

    /** Dars bekor qilindi yoki ko'chdi (LessonException): o'tilmagan belgi ham bekor. */
    @Transactional
    public void onLessonRemoved(Long groupId, LocalDate date) {
        substitutionRepository.findActive(groupId, date)
            .filter(s -> s.getStatus() == SubstitutionStatus.PLANNED)
            .ifPresent(s -> {
                s.setStatus(SubstitutionStatus.CANCELLED);
                s.setCancelledAt(LocalDateTime.now(billingClock));
                s.setCancelReason("Dars bekor qilindi yoki ko'chirildi");
                substitutionRepository.save(s);
            });
    }

    /** Ta'til bekor qilindi: unga bog'langan o'tilmagan belgilar ham bekor. */
    @Transactional
    public void cancelPlannedForLeave(Long leaveId) {
        for (LessonSubstitution s : substitutionRepository.findByLeaveRequestIdAndStatus(leaveId, SubstitutionStatus.PLANNED)) {
            s.setStatus(SubstitutionStatus.CANCELLED);
            s.setCancelledAt(LocalDateTime.now(billingClock));
            s.setCancelReason("Ta'til bekor qilindi");
            substitutionRepository.save(s);
        }
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /**
     * O'rinbosarning shu kundagi o'z darslari va boshqa o'rinbosarlik darslari bilan vaqt kesishuvi.
     * Vaqti noma'lum dars (jadvalda vaqt yo'q) to'qnashuv hisoblanmaydi.
     */
    private void assertNoTimeConflict(Group group, Teacher substitute, LocalDate date) {
        Optional<LocalTime[]> mine = times(group.getId(), date);
        if (mine.isEmpty()) {
            return;
        }
        List<Group> busy = new ArrayList<>(groupRepository.findByTeacher_IdAndStatus(substitute.getId(), GroupStatus.ACTIVE));
        substitutionRepository.findBySubstitute(substitute.getId(), date, date, SubstitutionStatus.CANCELLED)
            .forEach(s -> busy.add(s.getGroup()));
        for (Group other : busy) {
            if (other.getId().equals(group.getId()) || !groupScheduleService.hasLessonOn(other.getId(), date)) {
                continue;
            }
            // O'rinbosarning o'z guruhi shu kuni boshqasiga berilgan bo'lsa — u band emas
            Optional<LessonSubstitution> given = substitutionRepository.findActive(other.getId(), date);
            if (given.isPresent() && !given.get().getSubstituteTeacher().getId().equals(substitute.getId())) {
                continue;
            }
            Optional<LocalTime[]> t = times(other.getId(), date);
            if (t.isPresent() && t.get()[0].isBefore(mine.get()[1]) && mine.get()[0].isBefore(t.get()[1])) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("groupId", other.getId());
                data.put("groupName", other.getGroupName());
                data.put("startTime", t.get()[0].toString());
                data.put("endTime", t.get()[1].toString());
                throw new ConflictException("substitution.timeConflict", other.getGroupName(),
                    t.get()[0] + "–" + t.get()[1]).withData(data);
            }
        }
    }

    private Optional<LocalTime[]> times(Long groupId, LocalDate date) {
        return groupScheduleService.slotOn(groupId, date).flatMap(s -> {
            try {
                if (s.startTime() == null || s.endTime() == null) {
                    return Optional.empty();
                }
                return Optional.of(new LocalTime[]{LocalTime.parse(s.startTime()), LocalTime.parse(s.endTime())});
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        });
    }

    private void lockGroup(Long groupId) {
        try {
            entityManager.createQuery("SELECT g.id FROM Group g WHERE g.id = :id", Long.class)
                .setParameter("id", groupId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", com.crm.billing.BillingLocks.LOCK_TIMEOUT_MS)
                .getResultList();
        } catch (PessimisticLockException | LockTimeoutException | PessimisticLockingFailureException e) {
            ConflictException busy = new ConflictException("concurrency.busy");
            busy.initCause(e);
            throw busy;
        }
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    static String teacherName(Teacher t) {
        return t == null ? null : ((t.getFirstName() != null ? t.getFirstName() : "") + " "
            + (t.getLastName() != null ? t.getLastName() : "")).trim();
    }

    private static String userName(User u) {
        if (u == null) {
            return null;
        }
        String full = SalaryCalculationService.fullName(u);
        return full.isEmpty() ? u.getUsername() : full;
    }

    SubstitutionResponse toResponse(LessonSubstitution s) {
        GroupScheduleService.LessonSlot slot = groupScheduleService.slotOn(s.getGroup().getId(), s.getLessonDate())
            .orElse(null);
        return SubstitutionResponse.builder()
            .id(s.getId())
            .groupId(s.getGroup().getId())
            .groupName(s.getGroup().getGroupName())
            .lessonDate(s.getLessonDate())
            .startTime(slot != null ? slot.startTime() : null)
            .endTime(slot != null ? slot.endTime() : null)
            .originalTeacherId(s.getOriginalTeacher().getId())
            .originalTeacherName(teacherName(s.getOriginalTeacher()))
            .substituteTeacherId(s.getSubstituteTeacher().getId())
            .substituteTeacherName(teacherName(s.getSubstituteTeacher()))
            .leaveRequestId(s.getLeaveRequestId())
            .status(s.getStatus())
            .conductedAt(s.getConductedAt())
            .conductedByName(userName(s.getConductedBy()))
            .note(s.getNote())
            .createdByName(userName(s.getCreatedBy()))
            .createdAt(s.getCreatedAt())
            .cancelledAt(s.getCancelledAt())
            .cancelledByName(userName(s.getCancelledBy()))
            .cancelReason(s.getCancelReason())
            .build();
    }
}
