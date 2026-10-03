package com.crm.miniapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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

    /**
     * Auth javobi va {@code GET /api/app/me}: kim kirdi, qaysi o'quvchilarni ko'radi, o'qituvchi rejimi bormi.
     * {@code roles}: {@code STUDENT} | {@code PARENT} (o'quvchilar bo'lsa) va {@code TEACHER} (§11.4).
     */
    public record Profile(Long identityId, String kind, List<String> roles, String telegramFirstName,
                          String phoneMasked, Long defaultStudentId, List<StudentBrief> students,
                          TeacherMode teacher, Support support) {
    }

    /** O'qituvchi rejimi: xodim useri va o'qituvchi profili (bo'lmasa {@code teacherId = null}). */
    public record TeacherMode(Long userId, Long teacherId, String fullName) {
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

    // ── Qo'lda ulash (§11.1) ──
    public record ManualLinkRequest(@NotBlank(message = "{app.auth.initDataRequired}") String initData,
                                    @NotBlank(message = "{app.link.phoneRequired}") String phone) {
    }

    /** {@code status}: PENDING (markaz tasdiqlashi kutilmoqda). */
    public record LinkRequestResult(Long requestId, String status, LocalDateTime createdAt) {
    }

    /**
     * Oxirgi qo'lda so'rov holati: {@code status} PENDING | APPROVED | REJECTED | CANCELLED | NONE (so'rov yo'q);
     * {@code reason} — faqat REJECTED da (xodim yozgan sabab).
     */
    public record LinkRequestStatus(Long requestId, String status, String reason, LocalDateTime createdAt,
                                    LocalDateTime decidedAt) {
    }

    /** CRM ro'yxati qatori ({@code GET /api/app-link-requests}). */
    public record LinkRequestRow(Long id, String status, String phone, Long telegramUserId, String telegramUsername,
                                 String firstName, String matchSummary, LocalDateTime createdAt,
                                 LocalDateTime decidedAt, String decidedByName, String rejectReason,
                                 Long identityId) {
    }

    public record RejectRequest(String reason) {
    }

    // ── Sabab bildirish (§11.2) ──
    public record AbsenceNoticeRequest(@NotNull(message = "{app.absence.studentRequired}") Long studentId,
                                       @NotNull(message = "{app.absence.groupRequired}") Long groupId,
                                       @NotNull(message = "{app.absence.dateRequired}") LocalDate lessonDate,
                                       @NotBlank(message = "{app.absence.typeRequired}") String type,
                                       String comment) {
    }

    /** {@code type}: ABSENT | LATE | OTHER; {@code status}: ACTIVE | CANCELLED. */
    public record AbsenceNoticeItem(Long id, Long studentId, String studentName, Long groupId, String groupName,
                                    LocalDate lessonDate, String type, String comment, String status,
                                    LocalDateTime createdAt, LocalDateTime cancelledAt, boolean canCancel) {
    }

    // ── Chat (§11.3) ──
    /** Yozish mumkin bo'lgan manzil: {@code target} TEACHER (userId bilan) | SUPPORT | DIRECTOR. */
    public record ChatContact(String target, Long userId, String name, String label, List<String> groups) {
    }

    public record OpenChatRequest(@NotBlank(message = "{app.chat.targetRequired}") String target, Long teacherUserId) {
    }

    /**
     * {@code side}: CLIENT — app foydalanuvchisi (o'quvchi/ota-ona) sifatida; STAFF — o'qituvchi rejimida xodim
     * sifatida. {@code status}: OPEN | CLOSED.
     */
    public record ChatRow(Long id, String side, String target, String title, String label, String lastMessageText,
                          String lastMessageType, LocalDateTime lastMessageAt, Boolean lastMessageMine, long unread,
                          String status) {
    }

    /** {@code type}: TEXT | IMAGE | FILE | VOICE | SYSTEM; o'chirilgan xabarda matn va fayllar null. */
    public record ChatMessageItem(Long id, String type, String text, List<ChatAttachmentItem> attachments,
                                  LocalDateTime createdAt, boolean mine, String senderName, String senderLabel,
                                  boolean deleted) {
    }

    public record ChatAttachmentItem(String url, String name, String contentType, Long size, Integer width,
                                     Integer height) {
    }

    public record ChatTextRequest(String text) {
    }

    public record ChatReadRequest(@NotNull(message = "{chat.message.notFound}") Long messageId) {
    }

    // ── O'qituvchi rejimi (§11.4) ──
    /**
     * {@code role}: ORIGINAL (guruh o'qituvchisi) | SUBSTITUTE (o'rinbosar); {@code status}: PLANNED | EXTRA |
     * CANCELLED | MOVED. {@code substituteTeacherName} — ORIGINAL darsni boshqa o'qituvchi o'tsa.
     */
    public record TeacherLesson(Long groupId, String groupName, LocalDate date, String startTime, String endTime,
                                String room, String status, String role, String substituteTeacherName,
                                boolean canMark, int marked, int total, int notices) {
    }

    public record TeacherDay(LocalDate date, List<TeacherLesson> lessons) {
    }

    /**
     * {@code lockReason}: null (tahrirlash mumkin) | FUTURE | PAST_DATE (ochish ruxsati kerak) | UNLOCK_EXPIRED |
     * SUBSTITUTED (darsni boshqa o'qituvchi o'tadi) | NO_LESSON.
     */
    public record TeacherAttendance(Long groupId, String groupName, LocalDate date, boolean editable,
                                    String lockReason, UnlockInfo unlockRequest,
                                    List<TeacherAttendanceStudent> students) {
    }

    public record TeacherAttendanceStudent(Long studentId, String fullName, String status, String notes,
                                           Boolean excused, String excuseReason, AbsenceBrief notice) {
    }

    public record AbsenceBrief(Long id, String type, String comment, LocalDateTime createdAt) {
    }

    /** {@code status}: PENDING | APPROVED | REJECTED; {@code expiresAt} — APPROVED ruxsat tugashi. */
    public record UnlockInfo(Long id, String status, String note, LocalDateTime requestedAt,
                             LocalDateTime reviewedAt, LocalDateTime expiresAt) {
    }

    public record TeacherAttendanceSave(
        @NotNull(message = "{attendance.attendances.required}")
        @jakarta.validation.constraints.Size(min = 1, max = 200, message = "{attendance.attendances.required}")
        List<@jakarta.validation.Valid TeacherAttendanceItem> items) {
    }

    /** {@code status}: PRESENT | LATE | ABSENT | EXCUSED (ABSENT/LATE/EXCUSED — sabab majburiy, mavjud qoida). */
    public record TeacherAttendanceItem(@NotNull(message = "{app.student.forbidden}") Long studentId,
                                        @NotNull(message = "{attendance.status.required}") String status,
                                        String notes, Boolean excused, String excuseReason) {
    }

    public record UnlockCreate(@NotNull(message = "{attendanceUnlock.groupId.required}") Long groupId,
                               @NotNull(message = "{attendanceUnlock.attendanceDate.required}") LocalDate date,
                               String note) {
    }
}
