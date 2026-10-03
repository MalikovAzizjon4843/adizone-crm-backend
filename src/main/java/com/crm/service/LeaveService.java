package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.request.LeaveDecisionRequest;
import com.crm.dto.request.LeaveSubmitRequest;
import com.crm.dto.response.AffectedLessonDto;
import com.crm.dto.response.LeaveResponse;
import com.crm.dto.response.LeaveSummaryDto;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Group;
import com.crm.entity.Leave;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.LeaveStatus;
import com.crm.entity.enums.LeaveType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeaveRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ta'tillar — leaves-exams-contracts §1.
 *
 * <pre>
 *            submit                approve(paid)
 *  (yo'q) ──────► PENDING ─────────────────────► APPROVED
 *                    │  reject(note)                │ cancel (SA/A; payroll APPROVED/PAID bo'lsa — 409)
 *                    ▼                              ▼
 *                 REJECTED         cancel ──►   CANCELLED
 * </pre>
 *
 * <ul>
 *   <li>Barcha xodimlar (D1). Xodim — o'zi uchun; SA/A — istalgan xodim uchun.</li>
 *   <li>Bir xodimda PENDING/APPROVED ta'tillar ustma-ust tushmaydi — xodim qatori qulf ostida tekshiriladi
 *       (parallel ikki so'rov ham); PostgreSQL da V61 EXCLUDE qo'shimcha himoya (btree_gist bo'lsa).</li>
 *   <li>Haqli/haqsiz — faqat tasdiqlashda ({@code paid} majburiy, D2); haqsiz kunlar oylikning belgilangan
 *       qismidan ayiriladi ({@code LEAVE_DEDUCTION}, §3.1).</li>
 *   <li>Hard delete yo'q (L-09) — bekor qilish.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    /** Bitta ariza ko'pi bilan shuncha kalendar kun (TAXMIN — keyin sozlamaga). */
    public static final int MAX_DAYS = 60;

    private static final Set<LeaveStatus> BLOCKING = EnumSet.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);
    private static final Set<UserRole> STAFF = EnumSet.of(
        UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_HEAD, UserRole.SALES_MANAGER, UserRole.ACCOUNTANT,
        UserRole.TEACHER);

    private final LeaveRepository leaveRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final TeacherAccessService teacherAccessService;
    private final GroupScheduleService groupScheduleService;
    private final WorkdayCalendar workdayCalendar;
    private final PayrollPeriodGuard payrollGuard;
    private final LeaveStatusJob leaveStatusJob;
    private final LessonSubstitutionService substitutionService;
    private final SalaryCalculationService salaryCalculationService;
    private final EntityManager entityManager;
    private final Clock billingClock;

    /** {@code GET /api/leaves} filtri — hammasi ixtiyoriy. */
    public record Filter(LeaveStatus status, Long userId, Long teacherId, LocalDate from, LocalDate to, Boolean paid) {
    }

    // ── O'qish ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> search(Filter f, int page, int size) {
        Specification<Leave> spec = Specification.where(null);
        if (f.status() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), f.status()));
        }
        if (f.userId() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("user").get("id"), f.userId()));
        }
        if (f.teacherId() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("teacher").get("id"), f.teacherId()));
        }
        if (f.from() != null) {
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("toDate"), f.from()));
        }
        if (f.to() != null) {
            spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("fromDate"), f.to()));
        }
        if (f.paid() != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("paid"), f.paid()));
        }
        return page(leaveRepository.findAll(spec, pageable(page, size)), page, size);
    }

    /** O'zi haqidagi va o'zi bergan arizalar. */
    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> my(int page, int size) {
        Long me = teacherAccessService.getCurrentUserOrThrow().getId();
        Specification<Leave> spec = (r, q, cb) -> cb.or(
            cb.equal(r.get("user").get("id"), me),
            cb.equal(r.get("requester").get("id"), me));
        return page(leaveRepository.findAll(spec, pageable(page, size)), page, size);
    }

    /** @deprecated {@code GET /api/leaves?userId=} — xodim o'zinikini {@code /my} da ko'radi. */
    @Deprecated
    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> byUser(Long userId, int page, int size) {
        User me = teacherAccessService.getCurrentUserOrThrow();
        if (!isManager(me) && !me.getId().equals(userId)) {
            throw CodedException.forbidden("leave.forbidden");
        }
        Specification<Leave> spec = (r, q, cb) -> cb.or(
            cb.equal(r.get("user").get("id"), userId),
            cb.equal(r.get("requester").get("id"), userId));
        return page(leaveRepository.findAll(spec, pageable(page, size)), page, size);
    }

    @Transactional(readOnly = true)
    public LeaveResponse get(Long id) {
        Leave leave = find(id);
        assertCanView(leave);
        return toResponse(leave);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return leaveRepository.countPending();
    }

    /** O'qituvchining shu ta'til davridagi rejadagi darslari (+ "darsni X o'tdi" belgisi). */
    @Transactional(readOnly = true)
    public List<AffectedLessonDto> affectedLessons(Long id) {
        return affectedLessons(find(id));
    }

    /**
     * Ta'til haqsiz deb tasdiqlansa oylikdan qancha ayiriladi — holatidan qat'i nazar (PENDING ham), oylar
     * bo'yicha. Formula — payroll ({@link SalaryCalculationService#leaveDeductionPreview}).
     */
    @Transactional(readOnly = true)
    public List<SalaryCalculationService.LeaveDeductionMonth> deductionPreview(Long id) {
        Leave leave = find(id);
        if (leave.getUser() == null) {
            throw CodedException.badRequest("leave.userMissing", id);
        }
        return salaryCalculationService.leaveDeductionPreview(leave.getUser(), leave.getFromDate(), leave.getToDate());
    }

    /** Yil bo'yicha APPROVED ta'tillar: haqli/haqsiz kalendar va ish kunlari, tur bo'yicha. */
    @Transactional(readOnly = true)
    public LeaveSummaryDto summary(Long userId, int year) {
        User me = teacherAccessService.getCurrentUserOrThrow();
        Long target = userId != null ? userId : me.getId();
        if (!isManager(me) && !me.getId().equals(target)) {
            throw CodedException.forbidden("leave.forbidden");
        }
        LocalDate yFrom = LocalDate.of(year, 1, 1);
        LocalDate yTo = LocalDate.of(year, 12, 31);
        int paidDays = 0;
        int unpaidDays = 0;
        int paidWork = 0;
        int unpaidWork = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Leave l : leaveRepository.findOverlapping(target, yFrom, yTo, Set.of(LeaveStatus.APPROVED), -1L)) {
            LocalDate from = l.getFromDate().isBefore(yFrom) ? yFrom : l.getFromDate();
            LocalDate to = l.getToDate().isAfter(yTo) ? yTo : l.getToDate();
            int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
            int work = workdayCalendar.workdays(from, to);
            if (Boolean.FALSE.equals(l.getPaid())) {
                unpaidDays += days;
                unpaidWork += work;
            } else {
                paidDays += days;
                paidWork += work;
            }
            byType.merge(l.getLeaveType().name(), days, Integer::sum);
        }
        return LeaveSummaryDto.builder().userId(target).year(year)
            .paidDays(paidDays).unpaidDays(unpaidDays).paidWorkdays(paidWork).unpaidWorkdays(unpaidWork)
            .byType(byType).build();
    }

    // ── Amallar ─────────────────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Leave",
        summary = "'Ta''til arizasi: ' + #result.userName + ' ' + #result.fromDate + ' — ' + #result.toDate",
        entityId = "#result.id", label = "#result.userName")
    public LeaveResponse submit(LeaveSubmitRequest request) {
        User requester = teacherAccessService.getCurrentUserOrThrow();
        User target = resolveTarget(requester, request);
        LeaveType type = LeaveType.parseOrNull(request.getLeaveType());
        if (type == null) {
            throw CodedException.badRequest("leave.type.invalid", request.getLeaveType());
        }
        LocalDate from = request.getFromDate();
        LocalDate to = request.getToDate();
        if (from == null || to == null || to.isBefore(from)) {
            throw CodedException.badRequest("leave.dates.invalid");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw CodedException.badRequest("leave.tooLong", MAX_DAYS);
        }

        lockUser(target.getId());
        assertNoOverlap(target.getId(), from, to, -1L);

        Leave leave = Leave.builder()
            .user(target)
            .requester(requester)
            .teacher(teacherRepository.findByUser_Id(target.getId()).orElse(null))
            .leaveType(type)
            .fromDate(from)
            .toDate(to)
            .reason(trimToNull(request.getReason()))
            .status(LeaveStatus.PENDING)
            .build();
        AuditContext.change("status", null, LeaveStatus.PENDING);
        return toResponse(leaveRepository.save(leave));
    }

    /**
     * PENDING → APPROVED. {@code paid} majburiy (400 {@code leave.paidRequired}); ADMIN o'z ta'tilini
     * tasdiqlay olmaydi (403 {@code leave.selfApprove} — SA tasdiqlaydi); kesishuv qulf ostida qayta
     * tekshiriladi; haqsiz ta'til APPROVED/PAID oylik davriga tushsa — 409 {@code leave.payrollLocked}.
     */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Leave",
        summary = "'Ta''til tasdiqlandi: ' + #result.userName + ' (' + (#result.paid ? 'haqli' : 'haqsiz') + ')'",
        entityId = "#id", label = "#result.userName")
    public LeaveResponse approve(Long id, LeaveDecisionRequest request) {
        if (request == null || request.getPaid() == null) {
            throw CodedException.badRequest("leave.paidRequired");
        }
        String note = optionalNote(request.getNote());
        User me = teacherAccessService.getCurrentUserOrThrow();
        Leave leave = lockAndRefresh(id);
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("leave.notPending", leave.getStatus());
        }
        if (me.getRole() == UserRole.ADMIN && leave.getUser() != null && me.getId().equals(leave.getUser().getId())) {
            throw CodedException.forbidden("leave.selfApprove");
        }
        assertNoOverlap(leave.getUser().getId(), leave.getFromDate(), leave.getToDate(), leave.getId());
        if (!request.getPaid()) {
            payrollGuard.assertUnlocked(leave.getUser().getId(), leave.getFromDate(), leave.getToDate(),
                "leave.payrollLocked");
        }
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setPaid(request.getPaid());
        leave.setDecidedBy(me);
        leave.setDecidedAt(LocalDateTime.now(billingClock));
        leave.setDecisionNote(note);
        AuditContext.change("status", LeaveStatus.PENDING, LeaveStatus.APPROVED);
        AuditContext.change("paid", null, request.getPaid());
        Leave saved = leaveRepository.save(leave);
        syncTeacherStatus(saved);
        LeaveResponse response = toResponse(saved);
        response.setAffectedLessons(affectedLessons(saved));
        return response;
    }

    /** PENDING → REJECTED; izoh majburiy (3–500) va {@code reason} ga qo'shilmaydi (L-08). */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Leave",
        summary = "'Ta''til rad etildi: ' + #result.userName + ' — ' + #result.decisionNote",
        entityId = "#id", label = "#result.userName")
    public LeaveResponse reject(Long id, String note) {
        String why = requiredNote(note);
        User me = teacherAccessService.getCurrentUserOrThrow();
        Leave leave = lockAndRefresh(id);
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("leave.notPending", leave.getStatus());
        }
        leave.setStatus(LeaveStatus.REJECTED);
        leave.setPaid(null);
        leave.setDecidedBy(me);
        leave.setDecidedAt(LocalDateTime.now(billingClock));
        leave.setDecisionNote(why);
        AuditContext.change("status", LeaveStatus.PENDING, LeaveStatus.REJECTED);
        return toResponse(leaveRepository.save(leave));
    }

    /**
     * Bekor qilish: xodim — o'zining PENDING arizasi; SA/A — PENDING yoki APPROVED. APPROVED ta'til davri
     * APPROVED/PAID oylikka tushsa — 409 {@code leave.payrollLocked}. Ta'tilga bog'langan, hali o'tilmagan
     * (PLANNED) o'rinbosar belgilari bekor qilinadi.
     */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Leave",
        summary = "'Ta''til bekor qilindi: ' + #result.userName", entityId = "#id", label = "#result.userName")
    public LeaveResponse cancel(Long id, String note) {
        String why = optionalNote(note);
        User me = teacherAccessService.getCurrentUserOrThrow();
        Leave leave = lockAndRefresh(id);
        boolean owner = isOwner(leave, me.getId());
        if (!isManager(me)) {
            if (!owner) {
                throw CodedException.forbidden("leave.forbidden");
            }
            if (leave.getStatus() != LeaveStatus.PENDING) {
                throw new ConflictException("leave.notPending", leave.getStatus());
            }
        } else if (!BLOCKING.contains(leave.getStatus())) {
            throw new ConflictException("leave.notCancellable", leave.getStatus());
        }
        LeaveStatus old = leave.getStatus();
        if (old == LeaveStatus.APPROVED) {
            payrollGuard.assertUnlocked(leave.getUser().getId(), leave.getFromDate(), leave.getToDate(),
                "leave.payrollLocked");
        }
        leave.setStatus(LeaveStatus.CANCELLED);
        leave.setPaid(null);
        leave.setCancelledBy(me);
        leave.setCancelledAt(LocalDateTime.now(billingClock));
        if (why != null) {
            leave.setDecisionNote(why);
        }
        AuditContext.change("status", old, LeaveStatus.CANCELLED);
        Leave saved = leaveRepository.save(leave);
        substitutionService.cancelPlannedForLeave(saved.getId());
        syncTeacherStatus(saved);
        return toResponse(saved);
    }

    /**
     * @deprecated {@code POST /{id}/approve|reject|cancel}. Bir bosqich alias: {@code APPROVED} — {@code paid}
     * majburiy (yo'q bo'lsa 400 {@code leave.paidRequired}), {@code REJECTED} — {@code reason} izoh sifatida.
     */
    @Deprecated
    @Transactional
    public LeaveResponse legacyStatus(Long id, Map<String, Object> body) {
        Object raw = body != null ? body.get("status") : null;
        LeaveStatus status = raw != null ? LeaveStatus.parseOrNull(raw.toString()) : null;
        String note = body != null && body.get("note") != null ? body.get("note").toString()
            : body != null && body.get("reason") != null ? body.get("reason").toString() : null;
        if (status == LeaveStatus.APPROVED) {
            LeaveDecisionRequest r = new LeaveDecisionRequest();
            Object paid = body.get("paid");
            r.setPaid(paid == null ? null : Boolean.valueOf(paid.toString()));
            r.setNote(note);
            return approve(id, r);
        }
        if (status == LeaveStatus.REJECTED) {
            return reject(id, note);
        }
        if (status == LeaveStatus.CANCELLED) {
            return cancel(id, note);
        }
        throw CodedException.badRequest("leave.status.invalid", raw);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    /** SA/A — istalgan xodim ({@code userId} yoki eski {@code teacherId}); boshqalar — faqat o'zi. */
    private User resolveTarget(User requester, LeaveSubmitRequest request) {
        User target = requester;
        if (isManager(requester)) {
            if (request.getUserId() != null) {
                target = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            } else if (request.getTeacherId() != null) {
                Teacher teacher = teacherRepository.findById(request.getTeacherId())
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
                if (teacher.getUser() == null) {
                    throw CodedException.badRequest("leave.teacherNoUser", request.getTeacherId());
                }
                target = teacher.getUser();
            }
        }
        if (target.getRole() == null || !STAFF.contains(target.getRole())) {
            throw CodedException.badRequest("leave.userNotStaff", target.getId());
        }
        return target;
    }

    private void assertNoOverlap(Long userId, LocalDate from, LocalDate to, Long excludeId) {
        List<Leave> overlapping = leaveRepository.findOverlapping(userId, from, to, BLOCKING, excludeId);
        if (!overlapping.isEmpty()) {
            Leave o = overlapping.get(0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("leaveId", o.getId());
            data.put("fromDate", o.getFromDate());
            data.put("toDate", o.getToDate());
            data.put("status", o.getStatus());
            throw new ConflictException("leave.overlap", o.getFromDate(), o.getToDate()).withData(data);
        }
    }

    /** Xodim qatori qulfi — bir xodim ta'tillari bo'yicha amallar ketma-ket (kesishuv tekshiruvi to'g'ri). */
    private void lockUser(Long userId) {
        try {
            entityManager.createQuery("SELECT u.id FROM User u WHERE u.id = :id", Long.class)
                .setParameter("id", userId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", com.crm.billing.BillingLocks.LOCK_TIMEOUT_MS)
                .getResultList();
        } catch (PessimisticLockException | LockTimeoutException | PessimisticLockingFailureException e) {
            ConflictException busy = new ConflictException("concurrency.busy");
            busy.initCause(e);
            throw busy;
        }
    }

    private Leave lockAndRefresh(Long id) {
        Leave leave = find(id);
        if (leave.getUser() == null) {
            throw CodedException.badRequest("leave.userMissing", id);
        }
        lockUser(leave.getUser().getId());
        entityManager.refresh(leave);
        return leave;
    }

    /** Bugungi kunga ta'sir qilsa o'qituvchi statusi darhol (job kutilmaydi). */
    private void syncTeacherStatus(Leave leave) {
        if (leave.getTeacher() != null) {
            leaveStatusJob.syncTeacher(leave.getTeacher().getId(), LocalDate.now(billingClock));
        }
    }

    List<AffectedLessonDto> affectedLessons(Leave leave) {
        if (leave.getTeacher() == null) {
            return List.of();
        }
        List<AffectedLessonDto> out = new ArrayList<>();
        for (Group g : groupRepository.findByTeacher_IdAndStatus(leave.getTeacher().getId(), GroupStatus.ACTIVE)) {
            for (LocalDate d = leave.getFromDate(); !d.isAfter(leave.getToDate()); d = d.plusDays(1)) {
                if (!groupScheduleService.hasLessonOn(g.getId(), d)) {
                    continue;
                }
                GroupScheduleService.LessonSlot slot = groupScheduleService.slotOn(g.getId(), d).orElse(null);
                AffectedLessonDto dto = AffectedLessonDto.builder()
                    .groupId(g.getId()).groupName(g.getGroupName()).lessonDate(d)
                    .startTime(slot != null ? slot.startTime() : null)
                    .endTime(slot != null ? slot.endTime() : null)
                    .build();
                substitutionService.active(g.getId(), d).ifPresent(s -> {
                    dto.setSubstitutionId(s.getId());
                    dto.setSubstituteTeacherId(s.getSubstituteTeacher().getId());
                    dto.setSubstituteTeacherName(teacherName(s.getSubstituteTeacher()));
                    dto.setSubstitutionStatus(s.getStatus().name());
                });
                out.add(dto);
            }
        }
        out.sort(Comparator.comparing(AffectedLessonDto::getLessonDate)
            .thenComparing(a -> a.getStartTime() != null ? a.getStartTime() : ""));
        return out;
    }

    private void assertCanView(Leave leave) {
        User me = teacherAccessService.getCurrentUserOrThrow();
        if (!isManager(me) && !isOwner(leave, me.getId())) {
            throw CodedException.forbidden("leave.forbidden");
        }
    }

    private static boolean isOwner(Leave leave, Long userId) {
        return (leave.getUser() != null && userId.equals(leave.getUser().getId()))
            || (leave.getRequester() != null && userId.equals(leave.getRequester().getId()));
    }

    static boolean isManager(User u) {
        return u.getRole() == UserRole.SUPER_ADMIN || u.getRole() == UserRole.ADMIN;
    }

    private static String requiredNote(String note) {
        String why = note != null ? note.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("leave.noteRequired");
        }
        return why;
    }

    private static String optionalNote(String note) {
        String why = trimToNull(note);
        if (why != null && why.length() > 500) {
            throw CodedException.badRequest("leave.noteRequired");
        }
        return why;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public Leave find(Long id) {
        return leaveRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", id));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private PageResponse<LeaveResponse> page(Page<Leave> p, int page, int size) {
        return PageResponse.<LeaveResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).toList())
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    private static String teacherName(Teacher t) {
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

    private LeaveResponse toResponse(Leave l) {
        return LeaveResponse.builder()
            .id(l.getId()).uuid(l.getUuid())
            .userId(l.getUser() != null ? l.getUser().getId() : null)
            .userName(l.getUser() != null ? userName(l.getUser())
                : l.getTeacher() != null ? teacherName(l.getTeacher()) : null)
            .userRole(l.getUser() != null && l.getUser().getRole() != null ? l.getUser().getRole().name() : null)
            .teacherId(l.getTeacher() != null ? l.getTeacher().getId() : null)
            .teacherName(teacherName(l.getTeacher()))
            .requesterId(l.getRequester() != null ? l.getRequester().getId() : null)
            .requesterName(userName(l.getRequester()))
            .leaveType(l.getLeaveType())
            .fromDate(l.getFromDate()).toDate(l.getToDate())
            .days((int) ChronoUnit.DAYS.between(l.getFromDate(), l.getToDate()) + 1)
            .workdays(workdayCalendar.workdays(l.getFromDate(), l.getToDate()))
            .reason(l.getReason())
            .status(l.getStatus())
            .paid(l.getPaid())
            .decidedById(l.getDecidedBy() != null ? l.getDecidedBy().getId() : null)
            .decidedByName(userName(l.getDecidedBy()))
            .decidedAt(l.getDecidedAt())
            .decisionNote(l.getDecisionNote())
            .cancelledAt(l.getCancelledAt())
            .cancelledByName(userName(l.getCancelledBy()))
            .createdAt(l.getCreatedAt())
            .build();
    }
}
