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

    public static final int VERSION = 2;

    public static final String FIXED = "FIXED";
    public static final String PER_PAYING_STUDENT = "PER_PAYING_STUDENT";
    public static final String PER_NEW_STUDENT = "PER_NEW_STUDENT";
    public static final String KPI = "KPI";
    public static final String BONUS = "BONUS";
    public static final String PENALTY = "PENALTY";

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
        BigDecimal kpiBonus) {
    }

    /** {@code status} — faqat BONUS/PENALTY: PENDING (DRAFT dagi "kutilmoqda") yoki APPLIED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String code, String label, BigDecimal base, BigDecimal count, BigDecimal amount, String status) {
    }

    public record Items(
        List<PaidPeriod> paidPeriods,
        List<LessonEnrollment> lessonEnrollments,
        List<NewStudent> newStudents,
        Kpi kpi,
        List<Bonus> bonuses) {
    }

    /** MONTHLY: {@code countedOn = max(paidOn, periodStart)} — shu sana oyiga sanaladi (§2.1). */
    public record PaidPeriod(
        Long periodId,
        Long studentGroupId,
        Long studentId,
        String studentName,
        Long groupId,
        String groupName,
        LocalDate periodStart,
        LocalDate paidOn,
        LocalDate countedOn) {
    }

    /**
     * PER_LESSON: oyda net LESSON_CHARGE bo'lgan yozilma. {@code share = lessons / totalLessons} —
     * oyda o'qituvchi almashsa birlik darslar soni ulushida bo'linadi (§11 #2).
     */
    public record LessonEnrollment(
        Long studentGroupId,
        Long studentId,
        String studentName,
        Long groupId,
        String groupName,
        int lessons,
        int totalLessons,
        BigDecimal share) {
    }

    /** {@code source}: PERIOD (birinchi davri yopilgan) | PAYMENT (faqat PER_LESSON, birinchi to'lov). */
    public record NewStudent(Long studentId, String studentName, LocalDate countedOn, String source) {
    }

    public record Kpi(Integer threshold, long actual, boolean applied) {
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
