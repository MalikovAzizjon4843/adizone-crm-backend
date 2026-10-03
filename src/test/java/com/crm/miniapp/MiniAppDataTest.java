package com.crm.miniapp;

import com.crm.entity.Attendance;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Payment;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mini App ekranlari ma'lumoti (docs/design/telegram-platform.md §3.5, §8 "Ma'lumot to'g'riligi").
 * Bugun — 2026-09-15 (seshanba) 12:00. Guruh: dushanba/chorshanba/juma 18:30–20:00, 700 000 UZS.
 */
class MiniAppDataTest extends MiniAppItBase {

    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private LessonExceptionRepository lessonExceptionRepository;
    @Autowired private LessonSubstitutionRepository substitutionRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StudentGroupRepository studentGroupRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.crm.billing.AccrualService accrualService;

    private Long studentId;
    private Long groupId;
    private Long sgId;
    private Long teacherId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String phone = phone();
        studentId = student("Dilnoza", "Karimova", phone, null);
        teacherId = fixtures.teacher();
        groupId = fixtures.group(fixtures.course(700_000), com.crm.entity.enums.GroupStatus.ACTIVE, teacherId);
        sgId = fixtures.enrollment(studentId, groupId).start(LocalDate.of(2026, 9, 1)).save();
        inTx(() -> {
            for (String day : new String[]{"MONDAY", "WEDNESDAY", "FRIDAY"}) {
                scheduleDayRepository.save(GroupScheduleDay.builder()
                    .group(groupRepository.findById(groupId).orElseThrow())
                    .dayOfWeek(day).startTime("18:30").endTime("20:00").build());
            }
        });
        shareContact(801, phone);
        token = appToken(801);
    }

    private org.springframework.test.web.servlet.ResultActions call(String path, String... params) throws Exception {
        var req = get(path).header("Authorization", bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andExpect(status().isOk());
    }

    private void exception(LocalDate date, LessonException.Kind kind, LocalDate movedTo) {
        inTx(() -> lessonExceptionRepository.save(LessonException.builder()
            .groupId(groupId).lessonDate(date).kind(kind).movedTo(movedTo).reason("ichki sabab")
            .createdAt(LocalDateTime.now()).build()));
    }

    // ── Jadval ───────────────────────────────────────────────────────────

    @Test
    void schedule_appliesExceptionsHolidaysAndSubstitute() throws Exception {
        exception(d("16.09.2026"), LessonException.Kind.CANCELLED, null);
        exception(d("18.09.2026"), LessonException.Kind.MOVED, d("19.09.2026"));
        exception(d("24.09.2026"), LessonException.Kind.EXTRA, null);
        inTx(() -> holidayRepository.save(Holiday.builder().holidayDate(d("23.09.2026")).name("Bayram")
            .createdAt(LocalDateTime.now()).build()));
        Long substituteId = fixtures.teacher();
        inTx(() -> substitutionRepository.save(LessonSubstitution.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .lessonDate(d("21.09.2026"))
            .originalTeacher(teacherRepository.findById(teacherId).orElseThrow())
            .substituteTeacher(teacherRepository.findById(substituteId).orElseThrow())
            .status(SubstitutionStatus.PLANNED)
            .createdAt(LocalDateTime.now())
            .build()));
        String teacherName = inTx(() -> {
            var t = teacherRepository.findById(teacherId).orElseThrow();
            return t.getFirstName() + " " + t.getLastName();
        });

        call("/api/app/schedule", "from", "2026-09-14", "to", "2026-09-27")
            .andExpect(jsonPath("$.data.address").value(ADDRESS))
            .andExpect(jsonPath("$.data.groups[0].weekdays", hasSize(3)))
            .andExpect(jsonPath("$.data.groups[0].weekdays[0]").value("MONDAY"))
            .andExpect(jsonPath("$.data.groups[0].startTime").value("18:30"))
            .andExpect(jsonPath("$.data.groups[0].teacherName").value(teacherName))
            .andExpect(jsonPath("$.data.lessons", hasSize(8)))
            .andExpect(jsonPath("$.data.lessons[0].date").value("2026-09-14"))
            .andExpect(jsonPath("$.data.lessons[0].status").value("PLANNED"))
            .andExpect(jsonPath("$.data.lessons[1].date").value("2026-09-16"))
            .andExpect(jsonPath("$.data.lessons[1].status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.lessons[1].note").doesNotExist())
            .andExpect(jsonPath("$.data.lessons[2].date").value("2026-09-18"))
            .andExpect(jsonPath("$.data.lessons[2].status").value("MOVED"))
            .andExpect(jsonPath("$.data.lessons[2].movedTo").value("2026-09-19"))
            .andExpect(jsonPath("$.data.lessons[3].date").value("2026-09-19"))
            .andExpect(jsonPath("$.data.lessons[3].status").value("EXTRA"))
            .andExpect(jsonPath("$.data.lessons[3].movedFrom").value("2026-09-18"))
            .andExpect(jsonPath("$.data.lessons[4].date").value("2026-09-21"))
            .andExpect(jsonPath("$.data.lessons[4].substituteTeacherName").isString())
            .andExpect(jsonPath("$.data.lessons[5].date").value("2026-09-23"))
            .andExpect(jsonPath("$.data.lessons[5].status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.lessons[5].note").value("Bayram"))
            .andExpect(jsonPath("$.data.lessons[6].date").value("2026-09-24"))
            .andExpect(jsonPath("$.data.lessons[6].status").value("EXTRA"))
            .andExpect(jsonPath("$.data.lessons[7].date").value("2026-09-25"))
            .andExpect(jsonPath("$.data.lessons[7].status").value("PLANNED"))
            .andExpect(jsonPath("$.data.lessons[7].teacherName").value(teacherName))
            .andExpect(jsonPath("$.data.lessons[7].substituteTeacherName").doesNotExist());
    }

    @Test
    void schedule_beforeJoinDate_noLessons_andRangeValidated() throws Exception {
        call("/api/app/schedule", "from", "2026-08-24", "to", "2026-08-30")
            .andExpect(jsonPath("$.data.lessons", hasSize(0)));
        mvc.perform(get("/api/app/schedule").param("from", "2026-09-10").param("to", "2026-09-01")
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("app.schedule.range.invalid"));
        mvc.perform(get("/api/app/schedule").param("from", "2026-01-01").param("to", "2026-06-01")
                .header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest());
    }

    // ── Bosh sahifa ──────────────────────────────────────────────────────

    @Test
    void home_nextLessonSkipsCancelledAndMoved() throws Exception {
        exception(d("16.09.2026"), LessonException.Kind.CANCELLED, null);
        exception(d("18.09.2026"), LessonException.Kind.MOVED, d("19.09.2026"));

        call("/api/app/home")
            .andExpect(jsonPath("$.data.student.initials").value("DK"))
            .andExpect(jsonPath("$.data.groups", hasSize(1)))
            .andExpect(jsonPath("$.data.groups[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.nextLesson.date").value("2026-09-19"))
            .andExpect(jsonPath("$.data.nextLesson.status").value("EXTRA"))
            .andExpect(jsonPath("$.data.nextLesson.startTime").value("18:30"))
            // 4 kun + 6.5 soat
            .andExpect(jsonPath("$.data.nextLesson.startsInMinutes").value(4 * 24 * 60 + 390))
            .andExpect(jsonPath("$.data.attendance.month").value("2026-09"));
    }

    @Test
    void home_todaysLessonLater_isNext() throws Exception {
        clock.setDate(d("16.09.2026"));    // chorshanba 12:00, dars 18:30
        token = appToken(801);             // kechagi token muddati o'tgan
        call("/api/app/home")
            .andExpect(jsonPath("$.data.nextLesson.date").value("2026-09-16"))
            .andExpect(jsonPath("$.data.nextLesson.startsInMinutes").value(390));
    }

    // ── Davomat ──────────────────────────────────────────────────────────

    @Test
    void attendance_countsAndHidesTeacherNotes() throws Exception {
        mark("02.09.2026", AttendanceStatus.PRESENT, false, null, "ichki izoh");
        mark("04.09.2026", AttendanceStatus.LATE, false, null, null);
        mark("07.09.2026", AttendanceStatus.ABSENT, false, null, null);
        mark("09.09.2026", AttendanceStatus.ABSENT, true, "Kasal", "ichki izoh 2");
        mark("11.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("28.08.2026", AttendanceStatus.ABSENT, false, null, null);   // boshqa oy

        call("/api/app/attendance", "month", "2026-09")
            .andExpect(jsonPath("$.data.month").value("2026-09"))
            .andExpect(jsonPath("$.data.counts.present").value(2))
            .andExpect(jsonPath("$.data.counts.late").value(1))
            .andExpect(jsonPath("$.data.counts.absent").value(1))
            .andExpect(jsonPath("$.data.counts.excused").value(1))
            .andExpect(jsonPath("$.data.total").value(5))
            .andExpect(jsonPath("$.data.rate").value(60))
            // 14.09 (dushanba) darsi bo'lgan, lekin belgilanmagan — foizga kirmaydi
            .andExpect(jsonPath("$.data.unmarked").value(1))
            .andExpect(jsonPath("$.data.unmarkedLessons[0].date").value("2026-09-14"))
            .andExpect(jsonPath("$.data.unmarkedLessons[0].groupId").value(groupId))
            .andExpect(jsonPath("$.data.days", hasSize(5)))
            .andExpect(jsonPath("$.data.days[0].note").doesNotExist())
            .andExpect(jsonPath("$.data.days[3].status").value("EXCUSED"))
            .andExpect(jsonPath("$.data.days[3].note").value("Kasal"))
            .andExpect(jsonPath("$.data.lastMissed.date").value("2026-09-07"))
            .andExpect(jsonPath("$.data.lastMissed.weekday").value("MONDAY"));

        call("/api/app/home")
            .andExpect(jsonPath("$.data.attendance.total").value(5))
            .andExpect(jsonPath("$.data.attendance.rate").value(60))
            .andExpect(jsonPath("$.data.attendance.unmarked").value(1));
        mvc.perform(get("/api/app/attendance").param("month", "sentabr").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("app.month.invalid"));
    }

    @Test
    void attendance_nothingMarked_rateNull_allPastLessonsUnmarked() throws Exception {
        exception(d("09.09.2026"), LessonException.Kind.CANCELLED, null);   // bekor dars — belgilanmagan emas

        // 01.09 dan bugungacha (15.09, seshanba): 2, 4, 7, 11, 14 — 5 ta dars
        call("/api/app/attendance", "month", "2026-09")
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.rate").doesNotExist())
            .andExpect(jsonPath("$.data.unmarked").value(5))
            .andExpect(jsonPath("$.data.unmarkedLessons[*].date").value(org.hamcrest.Matchers.contains(
                "2026-09-02", "2026-09-04", "2026-09-07", "2026-09-11", "2026-09-14")));
        call("/api/app/home")
            .andExpect(jsonPath("$.data.attendance.total").value(0))
            .andExpect(jsonPath("$.data.attendance.rate").doesNotExist())
            .andExpect(jsonPath("$.data.attendance.unmarked").value(5));
        // Kelajak oyi — darslar hali bo'lmagan
        call("/api/app/attendance", "month", "2026-10").andExpect(jsonPath("$.data.unmarked").value(0));
        // Qo'shilishdan oldingi oy
        call("/api/app/attendance", "month", "2026-08").andExpect(jsonPath("$.data.unmarked").value(0));
    }

    @Test
    void attendance_todaysLesson_unmarkedOnlyAfterStart() throws Exception {
        mark("02.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("04.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("07.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("09.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("11.09.2026", AttendanceStatus.PRESENT, false, null, null);
        mark("14.09.2026", AttendanceStatus.LATE, false, null, null);

        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 18, 0));   // chorshanba, dars 18:30 da
        token = appToken(801);
        call("/api/app/attendance").andExpect(jsonPath("$.data.unmarked").value(0))
            .andExpect(jsonPath("$.data.rate").value(100));

        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 18, 45));
        token = appToken(801);
        call("/api/app/attendance")
            .andExpect(jsonPath("$.data.unmarked").value(1))
            .andExpect(jsonPath("$.data.unmarkedLessons[0].date").value("2026-09-16"))
            .andExpect(jsonPath("$.data.total").value(6))
            .andExpect(jsonPath("$.data.rate").value(100));
    }

    private void mark(String date, AttendanceStatus status, boolean excused, String reason, String notes) {
        inTx(() -> attendanceRepository.save(Attendance.builder()
            .student(studentRepository.findById(studentId).orElseThrow())
            .group(groupRepository.findById(groupId).orElseThrow())
            .attendanceDate(d(date))
            .status(status)
            .excused(excused)
            .excuseReason(reason)
            .notes(notes)
            .build()));
    }

    // ── To'lov (faqat o'qish) ────────────────────────────────────────────

    /** Sentabr davri ledgerga yoziladi (billing v2 accrual) — qarz 700 000, debtSince 01.09. */
    private void accrueSeptember() {
        accrualService.accrueUpTo(sgId, d("15.09.2026"));
    }

    @Test
    void payments_snapshotHistoryAndHowToPay_readOnly() throws Exception {
        accrueSeptember();
        // Eski ustun ataylab boshqa (kelajak) sana — ishlatilmasligi kerak
        legacyNextPaymentDate(sgId, d("10.10.2026"));
        payment("01.08.2026", "700000", PaymentStatus.PAID, PaymentMethod.CLICK, "R-801");
        payment("05.08.2026", "100000", PaymentStatus.CANCELLED, PaymentMethod.CASH, "R-802");
        payment("06.08.2026", "50000", PaymentStatus.PENDING, PaymentMethod.CASH, "R-803");
        Integer ledgerBefore = jdbc.queryForObject("SELECT COUNT(*) FROM balance_transactions", Integer.class);

        call("/api/app/payments")
            .andExpect(jsonPath("$.data.balance.balance").value(-700000.0))
            .andExpect(jsonPath("$.data.balance.debt").value(700000.0))
            .andExpect(jsonPath("$.data.balance.status").value("OVERDUE"))
            .andExpect(jsonPath("$.data.balance.debtSince").value("2026-09-01"))
            // snapshot sanasi = debtSince (01.09) — bugundan oldin → null, NONE
            .andExpect(jsonPath("$.data.balance.nextPayment").doesNotExist())
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("NONE"))
            .andExpect(jsonPath("$.data.enrollments[0].monthlyFee").value(700000.0))
            .andExpect(jsonPath("$.data.enrollments[0].finalFee").value(700000.0))
            .andExpect(jsonPath("$.data.enrollments[0].status").value("OVERDUE"))
            .andExpect(jsonPath("$.data.enrollments[0].nextPayment").doesNotExist())
            .andExpect(jsonPath("$.data.enrollments[0].nextPaymentState").value("NONE"))
            .andExpect(jsonPath("$.data.history", hasSize(2)))
            .andExpect(jsonPath("$.data.history[0].receiptNumber").value("R-802"))
            .andExpect(jsonPath("$.data.history[0].status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.history[1].method").value("CLICK"))
            .andExpect(jsonPath("$.data.history[1].methodLabel").value("Click"))
            .andExpect(jsonPath("$.data.history[1].amount").value(700000.0))
            .andExpect(jsonPath("$.data.howToPay.onlinePayment").value(false))
            .andExpect(jsonPath("$.data.howToPay.cashierAddress").value(ADDRESS))
            .andExpect(jsonPath("$.data.howToPay.supportPhone").value(SUPPORT_PHONE));

        call("/api/app/home")
            .andExpect(jsonPath("$.data.balance.debt").value(700000.0))
            .andExpect(jsonPath("$.data.balance.status").value("OVERDUE"))
            .andExpect(jsonPath("$.data.balance.nextPayment").doesNotExist())
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("NONE"));

        // Faqat o'qish: saqlangan snapshot va ledger o'zgarmagan
        StudentGroup after = inTx(() -> studentGroupRepository.findById(sgId).orElseThrow());
        assertThat(after.getBalance()).isEqualByComparingTo("-700000");
        assertThat(after.getNextPaymentDate()).isEqualTo(d("10.10.2026"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_transactions", Integer.class))
            .isEqualTo(ledgerBefore);
    }

    @Test
    void nextPayment_futureEnrollment_scheduledFromSnapshot_notLegacyColumn() throws Exception {
        Long octGroup = fixtures.group(fixtures.course(500_000));
        Long octSg = fixtures.enrollment(studentId, octGroup).start(d("01.10.2026")).save();
        // @PrePersist eski ustunni paymentStartDate bilan to'ldiradi; boshqa sana qo'yamiz
        legacyNextPaymentDate(octSg, d("20.09.2026"));
        // Sentabr guruhidagi qarz → uning sanasi o'tgan (NONE), umumiy keyingi to'lov — oktabr guruhi
        accrueSeptember();

        call("/api/app/payments")
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("SCHEDULED"))
            .andExpect(jsonPath("$.data.balance.nextPayment.date").value("2026-10-01"))
            .andExpect(jsonPath("$.data.balance.nextPayment.amount").value(500000.0))
            .andExpect(jsonPath("$.data.balance.debt").value(700000.0))
            .andExpect(jsonPath("$.data.enrollments[?(@.studentGroupId == " + octSg + ")].nextPaymentState")
                .value("SCHEDULED"))
            .andExpect(jsonPath("$.data.enrollments[?(@.studentGroupId == " + sgId + ")].nextPaymentState")
                .value("NONE"));
        call("/api/app/home")
            .andExpect(jsonPath("$.data.balance.nextPayment.date").value("2026-10-01"))
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("SCHEDULED"));
    }

    @Test
    void nextPayment_billingHold_hold() throws Exception {
        inTx(() -> {
            StudentGroup sg = studentGroupRepository.findById(sgId).orElseThrow();
            sg.setPaymentStartDate(d("01.10.2026"));   // snapshot bo'yicha kelajak sana bo'lardi
            sg.setBillingHold(true);
        });

        call("/api/app/payments")
            .andExpect(jsonPath("$.data.balance.nextPayment").doesNotExist())
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("HOLD"))
            .andExpect(jsonPath("$.data.enrollments[0].nextPayment").doesNotExist())
            .andExpect(jsonPath("$.data.enrollments[0].nextPaymentState").value("HOLD"));
        call("/api/app/home").andExpect(jsonPath("$.data.balance.nextPaymentState").value("HOLD"));

        // Hold'siz shu yozilma — SCHEDULED (hold aniq sabab ekanini tasdiqlaydi)
        inTx(() -> studentGroupRepository.findById(sgId).orElseThrow().setBillingHold(false));
        call("/api/app/payments")
            .andExpect(jsonPath("$.data.balance.nextPaymentState").value("SCHEDULED"))
            .andExpect(jsonPath("$.data.balance.nextPayment.date").value("2026-10-01"))
            .andExpect(jsonPath("$.data.balance.nextPayment.amount").value(700000.0));
    }

    private void legacyNextPaymentDate(Long sg, LocalDate date) {
        inTx(() -> studentGroupRepository.findById(sg).orElseThrow().setNextPaymentDate(date));
    }

    private void payment(String date, String amount, PaymentStatus status, PaymentMethod method, String receipt) {
        inTx(() -> paymentRepository.save(Payment.builder()
            .student(studentRepository.findById(studentId).orElseThrow())
            .group(groupRepository.findById(groupId).orElseThrow())
            .studentGroup(studentGroupRepository.findById(sgId).orElseThrow())
            .amount(new BigDecimal(amount))
            .payableAmount(new BigDecimal(amount))
            .paymentDate(d(date))
            .status(status)
            .paymentMethod(method)
            .receiptNumber(receipt)
            .build()));
    }

    // ── Profil ───────────────────────────────────────────────────────────

    @Test
    void profile_enrollmentsAndLinks() throws Exception {
        call("/api/app/profile")
            .andExpect(jsonPath("$.data.student.fullName").value("Dilnoza Karimova"))
            .andExpect(jsonPath("$.data.student.phoneMasked").isString())
            .andExpect(jsonPath("$.data.enrollments", hasSize(1)))
            .andExpect(jsonPath("$.data.enrollments[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.link.kind").value("STUDENT"))
            .andExpect(jsonPath("$.data.link.students[0].relation").value("SELF"))
            .andExpect(jsonPath("$.data.support.phone").value(SUPPORT_PHONE));
    }
}
