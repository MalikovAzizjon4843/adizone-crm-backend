package com.crm.miniapp;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code /api/app/**} javob shakllari — frontend shartnomasi: docs/design/miniapp-api.md.
 * Pul summalari {@code BigDecimal} (JSON raqam), sanalar ISO; matnlar (oy nomi, hafta kuni,
 * "700 000 UZS") frontendda formatlanadi.
 */
public final class AppDtos {

    private AppDtos() {
    }

    public record AuthRequest(@NotBlank(message = "{app.auth.initDataRequired}") String initData) {
    }

    public record AuthResponse(String token, String tokenType, long expiresIn, Profile profile) {
    }

    /** Auth javobi va {@code GET /api/app/me}: kim kirdi va qaysi o'quvchilarni ko'radi. */
    public record Profile(Long identityId, String kind, String telegramFirstName, String phoneMasked,
                          Long defaultStudentId, List<StudentBrief> students, Support support) {
    }

    public record StudentBrief(Long id, String firstName, String lastName, String relation,
                               List<GroupBrief> groups) {
    }

    public record GroupBrief(Long id, String name, String courseName) {
    }

    public record Support(String phone, String address) {
    }

    // ── Bosh sahifa ──
    public record Home(StudentHeader student, List<GroupCard> groups, Lesson nextLesson, Balance balance,
                       AttendanceSummary attendance) {
    }

    public record StudentHeader(Long id, String firstName, String lastName, String initials) {
    }

    /** {@code weekdays} — {@code MONDAY…SUNDAY}; {@code status} — ACTIVE / FROZEN / TRIAL. */
    public record GroupCard(Long id, String name, String courseName, String teacherName, List<String> weekdays,
                            String startTime, String endTime, String room, String format, String status) {
    }

    /**
     * Bitta dars. {@code status}: PLANNED, CANCELLED (bekor yoki bayram — {@code note} = bayram nomi),
     * MOVED (asl kun, {@code movedTo} — yangi kun), EXTRA (qo'shimcha yoki ko'chirib kelingan — {@code movedFrom}).
     */
    public record Lesson(LocalDate date, Long groupId, String groupName, String startTime, String endTime,
                         String room, String status, String teacherName, String substituteTeacherName,
                         LocalDate movedTo, LocalDate movedFrom, String note, Long startsInMinutes) {
    }

    /**
     * Billing v2 snapshot'i (ledgerdan, faqat o'qish). {@code balance} &lt; 0 — qarz; {@code debt} = |manfiy qism|.
     * {@code status}: PAID, PENDING, OVERDUE, FROZEN, TRIAL. Keyingi to'lov — {@link NextPayment} va
     * {@code nextPaymentState}.
     */
    public record Balance(BigDecimal balance, BigDecimal debt, String status, LocalDate debtSince,
                          NextPayment nextPayment, String nextPaymentState) {
    }

    /**
     * Keyingi to'lov — faqat {@code nextPaymentState = SCHEDULED} bo'lsa (sana bugun yoki keyin). Aks holda
     * {@code nextPayment = null}: HOLD — yozilma billing migratsiyasida ushlab turilgan ({@code billing_hold});
     * NONE — rejalashtirilgan to'lov yo'q (sinov, muzlatilgan, narx yo'q yoki sana o'tib ketgan — qarz
     * {@code debt}/{@code debtSince} da).
     */
    public record NextPayment(LocalDate date, BigDecimal amount) {
    }

    /**
     * {@code total} — faqat belgilangan (davomat yozuvi bor) darslar; {@code rate} = (keldi + kechikdi) / total × 100,
     * total 0 bo'lsa null. {@code unmarked} — o'tgan va bugungi boshlangan, lekin belgilanmagan darslar (rate ga kirmaydi).
     */
    public record AttendanceSummary(String month, int present, int late, int absent, int excused, int total,
                                    Integer rate, int unmarked) {
    }

    // ── Jadval ──
    public record Schedule(LocalDate from, LocalDate to, String address, List<GroupCard> groups,
                           List<Lesson> lessons) {
    }

    // ── Davomat ──
    /** {@code total}/{@code rate}/{@code unmarked} — {@link AttendanceSummary} bilan bir xil qoida. */
    public record AttendanceMonth(String month, Counts counts, int total, Integer rate, int unmarked,
                                  List<UnmarkedLesson> unmarkedLessons, List<AttendanceDay> days,
                                  LastMissed lastMissed) {
    }

    public record UnmarkedLesson(LocalDate date, Long groupId, String groupName) {
    }

    public record Counts(int present, int late, int absent, int excused) {
    }

    /** {@code status}: PRESENT, LATE, ABSENT, EXCUSED; {@code note} — faqat sababli kelmaganlik sababi. */
    public record AttendanceDay(LocalDate date, Long groupId, String groupName, String status, String note) {
    }

    public record LastMissed(LocalDate date, String weekday, String groupName) {
    }

    // ── To'lov ──
    public record Payments(Balance balance, List<EnrollmentFee> enrollments, List<PaymentItem> history,
                           HowToPay howToPay) {
    }

    public record EnrollmentFee(Long studentGroupId, Long groupId, String groupName, String courseName,
                                String paymentType, BigDecimal monthlyFee, BigDecimal discountPercent,
                                BigDecimal finalFee, BigDecimal balance, BigDecimal debt, String status,
                                LocalDate debtSince, NextPayment nextPayment, String nextPaymentState) {
    }

    /** {@code status}: PAID yoki CANCELLED ("bekor qilingan"). {@code amount} — chegirmadan keyingi summa. */
    public record PaymentItem(Long id, LocalDate date, LocalDate periodFrom, LocalDate periodTo, String groupName,
                              String method, String methodLabel, BigDecimal amount, String status,
                              String receiptNumber) {
    }

    /** Onlayn to'lov yo'q (MVP): kassa manzili va yordam telefoni (Sozlamalar {@code center.*}). */
    public record HowToPay(boolean onlinePayment, String cashierAddress, String supportPhone) {
    }

    // ── Profil ──
    public record ProfileScreen(StudentProfile student, List<Enrollment> enrollments, Link link, Support support) {
    }

    public record StudentProfile(Long id, String fullName, String phoneMasked, LocalDate birthDate) {
    }

    public record Enrollment(Long groupId, String groupName, String courseName, String format, String room,
                             String status, String teacherName, LocalDate joinDate) {
    }

    public record Link(String kind, String phoneMasked, LocalDateTime linkedAt, List<LinkedStudent> students) {
    }

    public record LinkedStudent(Long id, String fullName, String relation) {
    }
}
