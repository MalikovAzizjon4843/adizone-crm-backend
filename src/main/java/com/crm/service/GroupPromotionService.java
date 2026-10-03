package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingLocks;
import com.crm.billing.BillingSnapshot;
import com.crm.billing.BillingStatusService;
import com.crm.billing.EnrollmentLifecycleService;
import com.crm.billing.EnrollmentPricing;
import com.crm.billing.Money;
import com.crm.dto.request.GroupPromoteRequest;
import com.crm.dto.response.GroupPromoteDtos.GroupInfo;
import com.crm.dto.response.GroupPromoteDtos.Moved;
import com.crm.dto.response.GroupPromoteDtos.Preview;
import com.crm.dto.response.GroupPromoteDtos.Result;
import com.crm.dto.response.GroupPromoteDtos.StudentRow;
import com.crm.dto.response.GroupPromoteDtos.Warning;
import com.crm.entity.Group;
import com.crm.entity.GroupTransferBatch;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupTransferBatchRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Guruhga ommaviy ko'chirish (phase6-api §5). Har o'quvchi — {@link EnrollmentLifecycleService#transfer}
 * (billing-v2 §6.8: balans {@code TRANSFER_OUT/IN} bilan to'liq ko'chadi, langar uzluksiz, narx shartlari —
 * individual narx va chegirma — saqlanadi). Bitta tranzaksiya: bitta xato butun amalni bekor qiladi.
 * O'quvchilar id o'sishida qayta ishlanadi (billing qulf tartibi).
 */
@Service
@RequiredArgsConstructor
public class GroupPromotionService {

    public static final int MAX_DAYS_AHEAD = 31;
    private static final int IDEMPOTENCY_KEY_MAX = 64;
    private static final String EXIT_REASON = "TRANSFERRED";

    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentRepository studentRepository;
    private final GroupTransferBatchRepository batchRepository;
    private final EnrollmentLifecycleService lifecycle;
    private final BillingStatusService statusService;
    private final TeacherAccessService accessService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock billingClock;

    /** Tekshirilgan so'rov: o'quvchilar takrorsiz, o'sish tartibida. */
    private record Normalized(Long fromId, Long targetId, List<Long> studentIds, LocalDate date, String note) {
        String hash() {
            return sha256(fromId + "|" + targetId + "|" + studentIds + "|" + date);
        }
    }

    // ── Preview ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Preview preview(Long fromId, GroupPromoteRequest request) {
        Normalized n = normalize(fromId, request);
        Group from = group(n.fromId());
        Group target = group(n.targetId());
        return build(n, from, target);
    }

    // ── Apply ───────────────────────────────────────────────────────────

    /** Takroriy {@code Idempotency-Key}: saqlangan javob (audit qayta yozilmaydi). Kalit boshqa so'rov uchun — 409. */
    @Transactional(readOnly = true)
    public Optional<Result> replay(Long fromId, GroupPromoteRequest request, String idempotencyKey) {
        String key = key(idempotencyKey);
        if (key == null) {
            return Optional.empty();
        }
        Normalized n = normalize(fromId, request);
        return batchRepository.findByIdempotencyKey(key).map(b -> {
            if (!b.getRequestHash().equals(n.hash())) {
                throw new ConflictException("group.promote.idempotency.mismatch");
            }
            return read(b.getResultJson()).replay();
        });
    }

    @Transactional
    @Audited(action = AuditAction.TRANSFER, entity = "Group", entityId = "#fromId",
        summary = "'Guruhga ko''chirildi: ' + #result.students().size() + ' ta o''quvchi, guruh '"
            + " + #result.fromGroupId() + ' → ' + #result.targetGroupId()")
    public Result promote(Long fromId, GroupPromoteRequest request, String idempotencyKey) {
        String key = key(idempotencyKey);
        Normalized n = normalize(fromId, request);
        lockGroup(n.targetId());
        Group from = group(n.fromId());
        Group target = group(n.targetId());

        Preview check = build(n, from, target);
        if (!check.canApply()) {
            throw new ConflictException("group.promote.blocked").withData(blockingData(check));
        }

        User actor = accessService.getCurrentUserOrThrow();
        // Kalit avval yoziladi: parallel ikkinchi so'rov UNIQUE da kutadi va bu tranzaksiya tugagach yiqiladi
        GroupTransferBatch batch = batchRepository.saveAndFlush(GroupTransferBatch.builder()
            .fromGroupId(n.fromId())
            .targetGroupId(n.targetId())
            .transferDate(n.date())
            .studentIds(n.studentIds().stream().map(String::valueOf).collect(Collectors.joining(",")))
            .note(n.note())
            .idempotencyKey(key)
            .requestHash(n.hash())
            .createdBy(actor)
            .createdAt(LocalDateTime.now(billingClock))
            .build());

        List<Moved> moved = new ArrayList<>();
        for (Long studentId : n.studentIds()) {
            EnrollmentLifecycleService.Transfer t = lifecycle.transfer(studentId, n.fromId(), target, null,
                EXIT_REASON, n.note(), n.date());
            Student s = t.from().getStudent();
            moved.add(new Moved(studentId, name(s), t.from().getId(), t.to().getId(), Money.normalize(t.moved())));
        }
        Result result = new Result(batch.getId(), n.fromId(), n.targetId(), n.date(), moved, null);
        batch.setResultJson(write(result));
        batchRepository.save(batch);
        return result;
    }

    // ── hisob ───────────────────────────────────────────────────────────

    private Preview build(Normalized n, Group from, Group target) {
        LocalDate today = statusService.today();
        long targetActive = studentGroupRepository.countByGroupIdAndIsActiveTrue(target.getId());
        List<Warning> groupWarnings = new ArrayList<>();
        if (target.getStatus() == GroupStatus.COMPLETED || target.getStatus() == GroupStatus.CANCELLED) {
            groupWarnings.add(new Warning("TARGET_CLOSED", true));
        }
        if (from.getStatus() == GroupStatus.COMPLETED || from.getStatus() == GroupStatus.CANCELLED) {
            groupWarnings.add(new Warning("SOURCE_CLOSED", false));
        }
        if (target.getMaxStudents() != null && targetActive + n.studentIds().size() > target.getMaxStudents()) {
            groupWarnings.add(new Warning("TARGET_FULL", true));
        }

        List<StudentRow> rows = new ArrayList<>();
        for (Long studentId : n.studentIds()) {
            rows.add(row(studentId, n.fromId(), target, today));
        }
        boolean canApply = groupWarnings.stream().noneMatch(Warning::blocking)
            && rows.stream().flatMap(r -> r.warnings().stream()).noneMatch(Warning::blocking);
        return new Preview(info(from, studentGroupRepository.countByGroupIdAndIsActiveTrue(from.getId())),
            info(target, targetActive), n.date(), canApply, groupWarnings, rows);
    }

    private StudentRow row(Long studentId, Long fromId, Group target, LocalDate today) {
        List<Warning> w = new ArrayList<>();
        Optional<StudentGroup> found = studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, fromId);
        if (found.isEmpty()) {
            String studentName = studentRepository.findById(studentId).map(GroupPromotionService::name).orElse(null);
            w.add(new Warning("NOT_IN_GROUP", true));
            return new StudentRow(studentId, studentName, null, null, null, null, null, null, null, null, w);
        }
        StudentGroup sg = found.get();
        if (studentGroupRepository.existsByStudentIdAndGroupIdAndIsActiveTrue(studentId, target.getId())) {
            w.add(new Warning("ALREADY_IN_TARGET", true));
        }
        if (sg.getFrozenFrom() != null) {
            w.add(new Warning("FROZEN", true));
        }
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            w.add(new Warning("TRIAL", false));
        }
        BillingSnapshot snap = statusService.snapshot(sg, today);
        BigDecimal debt = Money.nz(snap.debt());
        if (debt.signum() > 0) {
            w.add(new Warning("DEBTOR", false));
        }
        boolean perLesson = sg.getPaymentType() == PaymentType.PER_LESSON;
        StudentGroup next = StudentGroup.builder()
            .group(target)
            .paymentType(sg.getPaymentType())
            .monthlyPriceOverride(sg.getMonthlyPriceOverride())
            .discountPercentage(sg.getDiscountPercentage())
            .lessonPrice(sg.getLessonPrice())
            .build();
        BigDecimal oldPrice = perLesson ? EnrollmentPricing.effectiveLessonPrice(sg) : EnrollmentPricing.effectiveMonthlyFee(sg);
        BigDecimal newPrice = perLesson ? EnrollmentPricing.effectiveLessonPrice(next) : EnrollmentPricing.effectiveMonthlyFee(next);
        if (!Money.eq(oldPrice, newPrice)) {
            w.add(new Warning("PRICE_CHANGED", false));
        }
        return new StudentRow(studentId, name(sg.getStudent()), sg.getId(),
            sg.getPaymentType() != null ? sg.getPaymentType().name() : PaymentType.MONTHLY.name(),
            oldPrice, newPrice, newPrice.subtract(oldPrice), Money.nz(snap.balance()), debt,
            lifecycle.transferAnchor(sg), w);
    }

    private static Map<String, Object> blockingData(Preview p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("group", p.warnings().stream().filter(Warning::blocking).map(Warning::code).toList());
        List<Map<String, Object>> students = new ArrayList<>();
        for (StudentRow r : p.students()) {
            List<String> codes = r.warnings().stream().filter(Warning::blocking).map(Warning::code).toList();
            if (!codes.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("studentId", r.studentId());
                m.put("codes", codes);
                students.add(m);
            }
        }
        data.put("students", students);
        return data;
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private Normalized normalize(Long fromId, GroupPromoteRequest r) {
        if (fromId.equals(r.getTargetGroupId())) {
            throw CodedException.badRequest("group.promote.sameGroup");
        }
        LocalDate today = LocalDate.now(billingClock);
        LocalDate date = r.getDate() != null ? r.getDate() : today;
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw CodedException.badRequest("group.promote.date.invalid", MAX_DAYS_AHEAD);
        }
        List<Long> ids = new ArrayList<>(new TreeSet<>(r.getStudentIds().stream()
            .filter(java.util.Objects::nonNull).toList()));
        if (ids.isEmpty()) {
            throw CodedException.badRequest("group.promote.studentsRequired");
        }
        String note = r.getNote() != null && !r.getNote().isBlank() ? r.getNote().trim() : null;
        return new Normalized(fromId, r.getTargetGroupId(), ids, date, note);
    }

    private static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String k = raw.trim();
        if (k.length() > IDEMPOTENCY_KEY_MAX) {
            throw CodedException.badRequest("group.promote.idempotency.keyTooLong", IDEMPOTENCY_KEY_MAX);
        }
        return k;
    }

    private Group group(Long id) {
        return groupRepository.findById(id).orElseThrow(() -> CodedException.notFound("error.group.notFound", id));
    }

    private static GroupInfo info(Group g, long active) {
        Integer free = g.getMaxStudents() != null ? (int) Math.max(0, g.getMaxStudents() - active) : null;
        return new GroupInfo(g.getId(), g.getGroupName(), g.getStatus() != null ? g.getStatus().name() : null,
            g.getCourse() != null ? g.getCourse().getCourseName() : null, g.getMaxStudents(), active, free);
    }

    private void lockGroup(Long groupId) {
        try {
            entityManager.createQuery("SELECT g.id FROM Group g WHERE g.id = :id", Long.class)
                .setParameter("id", groupId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", BillingLocks.LOCK_TIMEOUT_MS)
                .getResultList();
        } catch (PessimisticLockException | LockTimeoutException | PessimisticLockingFailureException e) {
            ConflictException busy = new ConflictException("concurrency.busy");
            busy.initCause(e);
            throw busy;
        }
    }

    private static String name(Student s) {
        return s == null ? null : ((s.getFirstName() != null ? s.getFirstName() : "") + " "
            + (s.getLastName() != null ? s.getLastName() : "")).trim();
    }

    private String write(Result r) {
        try {
            return objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("group transfer result JSON", e);
        }
    }

    private Result read(String json) {
        try {
            return objectMapper.readValue(json, Result.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("group transfer result JSON", e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
