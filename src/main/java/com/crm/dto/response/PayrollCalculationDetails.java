package com.crm.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Oylik hisobining tafsiloti — docs/design/payroll-v2.md §6.
 *
 * <p>{@code lines} — formulaning har bir qatori ({@code code, label, base, count, amount});
 * {@code Σ lines.amount = net}. {@code items} — qatorlarning asosi (qaysi davr, qaysi o'quvchi,
 * qaysi bonus). APPROVED/PAID da bu obyekt muzlatilgan snapshot bo'lib saqlanadi.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PayrollCalculationDetails(
    int version,
    int month,
    int year,
    String role,
    /** Cutover oyi (§11 #1): oyning bir qismi v2 dan oldin — natija taxminiy. */
    boolean estimated,
    RuleSnapshot rule,
    List<Line> lines,
    BigDecimal gross,
    BigDecimal bonusPenalty,
    BigDecimal net,
    Items items) {

    /**
     * Format va formula versiyasi. 3 (payroll-v2 §14, oktyabr 2026 oyligidan — {@code app.payroll.v3-from}): dars turi
     * bo'yicha ulush, chala to'lov ulushi, stavka, KPI pog'onalari; qatorlarda {@code formula}. Undan oldingi oylar —
     * 2 ({@link #VERSION_V2}). Oy versiyasiga mos kelmaydigan DRAFT ni tasdiqlash — 409 {@code payroll.recalculateRequired}.
     * leaves-exams-contracts §3 qatorlari v2 da qo'shimcha (ixtiyoriy) maydonlar sifatida qo'shilgan edi.
     */
    public static final int VERSION = 3;
    public static final int VERSION_V2 = 2;

    public static final String FIXED = "FIXED";
    /** v3: GROUP dars turidagi o'quvchilar (v2: barcha to'lagan o'quvchilar). */
    public static final String PER_PAYING_STUDENT = "PER_PAYING_STUDENT";
    /** v3: INDIVIDUAL dars turidagi o'quvchilar. */
    public static final String PER_INDIVIDUAL_STUDENT = "PER_INDIVIDUAL_STUDENT";
    public static final String PER_NEW_STUDENT = "PER_NEW_STUDENT";
    public static final String KPI = "KPI";
    public static final String BONUS = "BONUS";
    public static final String PENALTY = "PENALTY";
    /** Haqsiz ta'til: belgilangan oylikdan ish kunlari ulushi (manfiy), leaves-exams-contracts §3.1. */
    public static final String LEAVE_DEDUCTION = "LEAVE_DEDUCTION";
    /** O'rinbosar sifatida o'tilgan (CONDUCTED) darslar, §3.2. */
    public static final String SUBSTITUTE_LESSONS = "SUBSTITUTE_LESSONS";

    /** Hisobda ishlatilgan qoidaning nusxasi (keyin tahrirlansa ham snapshot o'zgarmaydi). */
    public record RuleSnapshot(
        Long id,
        String scope,
        String role,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal fixedSalary,
        BigDecimal perPayingStudent,
        BigDecimal perNewStudent,
        Integer kpiThreshold,
        BigDecimal kpiBonus,
        /** TEACHER: bir o'rinbosar darsi stavkasi (shaxsiy qoida → rol qoidasi); eski snapshot'da null. */
        BigDecimal substituteLessonRate,
        /** v3, TEACHER: INDIVIDUAL o'quvchi ulushi; null — belgilanmagan. */
        BigDecimal perStudentIndividual,
        /** v3: stavka (fiksa × stavka); v2 snapshot'da null. */
        BigDecimal employmentRate,
        /** v3, ADMIN: KPI pog'onalari ("faol > threshold"); v2 snapshot'da null. */
        List<KpiTier> kpiTiers) {

        /** v2 snapshot. */
        public RuleSnapshot(Long id, String scope, String role, LocalDate effectiveFrom, LocalDate effectiveTo,
                            BigDecimal fixedSalary, BigDecimal perPayingStudent, BigDecimal perNewStudent,
                            Integer kpiThreshold, BigDecimal kpiBonus, BigDecimal substituteLessonRate) {
            this(id, scope, role, effectiveFrom, effectiveTo, fixedSalary, perPayingStudent, perNewStudent,
                kpiThreshold, kpiBonus, substituteLessonRate, null, null, null);
        }
    }

    public record KpiTier(Integer threshold, BigDecimal bonus) {
    }

    /**
     * {@code status} — faqat BONUS/PENALTY: PENDING (DRAFT dagi "kutilmoqda") yoki APPLIED. {@code formula} (v3) —
     * qatorning o'qiladigan ko'rinishi, masalan "Guruh 23.4286 × 180 000", "KPI: 520 > 500 → 1 000 000".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String code, String label, BigDecimal base, BigDecimal count, BigDecimal amount, String status,
                       String formula) {

        public Line(String code, String label, BigDecimal base, BigDecimal count, BigDecimal amount, String status) {
            this(code, label, base, count, amount, status, null);
        }
    }

    public record Items(
        List<PaidPeriod> paidPeriods,
        List<LessonEnrollment> lessonEnrollments,
        List<NewStudent> newStudents,
        Kpi kpi,
        List<Bonus> bonuses,
        /** Oyga tushgan APPROVED ta'tillar (haqli ham — ko'rinish uchun; qator faqat haqsizdan). */
        List<LeaveItem> leaves,
        /** O'rinbosar sifatida o'tilgan (CONDUCTED) darslar. */
        List<SubstitutionItem> substitutions) {
    }

    /** {@code workdaysInMonth} — ta'tilning shu oyga tushgan ish kunlari (Du–Sha, bayramsiz). */
    public record LeaveItem(
        Long leaveId,
        LocalDate fromDate,
        LocalDate toDate,
        String leaveType,
        boolean paid,
        int workdaysInMonth) {
    }

    public record SubstitutionItem(
        Long substitutionId,
        Long groupId,
        String groupName,
        LocalDate lessonDate,
        Long originalTeacherId,
        String originalTeacherName,
        java.time.LocalDateTime conductedAt) {
    }

    /**
     * MONTHLY. v2: to'liq yopilgan davr, {@code countedOn = max(paidOn, periodStart)} oyida 1 birlik (§2.1).
     * v3 (§14): davrni yopgan real to'lov qismlari, har biri {@code max(to'lov sanasi, periodStart)} oyida —
     * {@code unit = paidInMonth / periodAmount} (oylar bo'yicha yig'indi ≤ 1); {@code partial} — oy oxirida davr hali
     * to'liq to'lanmagan; {@code paidTotal} — oy oxirigacha jami to'langan.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaidPeriod(
        Long periodId,
        Long studentGroupId,
        Long studentId,
        String studentName,
        Long groupId,
        String groupName,
        LocalDate periodStart,
        LocalDate paidOn,
        LocalDate countedOn,
        String lessonType,
        BigDecimal periodAmount,
        BigDecimal paidInMonth,
        BigDecimal paidTotal,
        BigDecimal unit,
        Boolean partial) {

        /** v2. */
        public PaidPeriod(Long periodId, Long studentGroupId, Long studentId, String studentName, Long groupId,
                          String groupName, LocalDate periodStart, LocalDate paidOn, LocalDate countedOn) {
            this(periodId, studentGroupId, studentId, studentName, groupId, groupName, periodStart, paidOn, countedOn,
                null, null, null, null, null, null);
        }
    }

    /**
     * PER_LESSON. v2: oyda net LESSON_CHARGE bo'lgan yozilma, {@code share = lessons / totalLessons} (§11 #2).
     * v3 (§14): o'qituvchi darslarini yopgan real to'lov qismlari shu oyda —
     * {@code share = min(1, paidInMonth / monthlyEquivalent)}, {@code monthlyEquivalent = dars narxi × lessons_per_month};
     * {@code lessons} — shu oyda to'lov tushgan darslar, {@code totalLessons} — 0.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LessonEnrollment(
        Long studentGroupId,
        Long studentId,
        String studentName,
        Long groupId,
        String groupName,
        int lessons,
        int totalLessons,
        BigDecimal share,
        String lessonType,
        BigDecimal paidInMonth,
        BigDecimal monthlyEquivalent) {

        /** v2. */
        public LessonEnrollment(Long studentGroupId, Long studentId, String studentName, Long groupId, String groupName,
                                int lessons, int totalLessons, BigDecimal share) {
            this(studentGroupId, studentId, studentName, groupId, groupName, lessons, totalLessons, share,
                null, null, null);
        }
    }

    /** {@code source}: PERIOD (birinchi davri yopilgan) | PAYMENT (faqat PER_LESSON, birinchi to'lov). */
    public record NewStudent(Long studentId, String studentName, LocalDate countedOn, String source) {
    }

    /**
     * v2: {@code actual ≥ threshold}. v3: {@code actual > threshold} — {@code threshold} bajarilgan eng yuqori pog'ona
     * (bajarilmasa — eng past pog'ona), {@code bonus} — qo'llangan summa, {@code tiers} — barcha pog'onalar.
     */
    public record Kpi(Integer threshold, long actual, boolean applied, BigDecimal bonus, List<KpiTier> tiers) {

        /** v2. */
        public Kpi(Integer threshold, long actual, boolean applied) {
            this(threshold, actual, applied, null, null);
        }
    }

    public record Bonus(
        Long id,
        String kind,
        BigDecimal amount,
        LocalDate effectiveDate,
        String reason,
        String status) {
    }
}
