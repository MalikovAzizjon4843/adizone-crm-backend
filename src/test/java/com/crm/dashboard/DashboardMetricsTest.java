package com.crm.dashboard;

import com.crm.billing.AccrualService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dashboard.DirectorDtos.AttendanceSection;
import com.crm.dashboard.DirectorDtos.CollectionsSection;
import com.crm.dashboard.DirectorDtos.DebtorsSection;
import com.crm.dashboard.DirectorDtos.FunnelActivity;
import com.crm.dashboard.DirectorDtos.FunnelCohort;
import com.crm.dashboard.DirectorDtos.OperatorRow;
import com.crm.dashboard.DirectorDtos.RetentionCohort;
import com.crm.dashboard.DirectorDtos.TrialsSection;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.entity.Attendance;
import com.crm.entity.DirectorDailyStat;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Holiday;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.LessonException;
import com.crm.entity.Payment;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.DirectorDailyStatRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadService;
import com.crm.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Direktor dashboardi, 3-bosqich: §1 ta'riflari, raqamli misollar bilan (Asia/Tashkent). */
class DashboardMetricsTest extends AbstractBillingIT {

    private static final AtomicInteger RECEIPT = new AtomicInteger(5000);

    @Autowired FunnelMetricsService funnel;
    @Autowired CollectionsMetricsService collections;
    @Autowired DebtorMetricsService debtors;
    @Autowired AttendanceMetricsService attendance;
    @Autowired TrialMetricsService trials;
    @Autowired RetentionMetricsService retention;
    @Autowired OperatorMetricsService operators;
    @Autowired LeadService leadService;
    @Autowired LeadFunnelTracker tracker;
    @Autowired LeadRepository leadRepo;
    @Autowired LeadAssignmentRepository assignmentRepo;
    @Autowired UserRepository userRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;
    @Autowired HolidayRepository holidayRepo;
    @Autowired LessonExceptionRepository exceptionRepo;
    @Autowired TaskRepository taskRepo;
    @Autowired DirectorDailyStatRepository statRepo;
    @Autowired AccrualService accrual;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    // ── yordamchilar ────────────────────────────────────────────────────

    private static LocalDateTime t(String date, int h, int m) {
        return d(date).atTime(h, m);
    }

    private DashboardPeriod day(String date) {
        return DashboardPeriod.of("DAY", d(date), null, null, LocalDateTime.now(clock));
    }

    private DashboardPeriod custom(String from, String to) {
        return DashboardPeriod.of("CUSTOM", null, d(from), d(to), LocalDateTime.now(clock));
    }

    private User user(UserRole role) {
        fixtures.loginAs(role);
        return userRepo.findByUsername("test-" + role.name().toLowerCase()).orElseThrow();
    }

    private Long lead(LocalDateTime createdAt, Long assignee) {
        clock.setDateTime(createdAt);
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName("Lid " + RECEIPT.incrementAndGet());
        r.setPhone("+998901112233");
        r.setAssignedUserId(assignee);
        Long id = leadService.createLeadByStaff(r).getId();
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    private void stage(Long leadId, String code, LocalDateTime at) {
        clock.setDateTime(at);
        leadService.updateStatus(leadId, code, null);
    }

    /** To'g'ridan PAID to'lov (kassasiz) — faqat dashboard o'qishi uchun. */
    private Long rawPayment(Long studentId, Long sgId, long cash, String date) {
        return inTx(() -> paymentRepo.save(Payment.builder()
            .student(studentRepo.findById(studentId).orElseThrow())
            .studentGroup(sgId != null ? sgRepo.findById(sgId).orElseThrow() : null)
            .amount(BigDecimal.valueOf(cash)).cashAmount(BigDecimal.valueOf(cash))
            .paymentDate(d(date)).status(PaymentStatus.PAID)
            .receiptNumber("T-" + RECEIPT.incrementAndGet()).build()).getId());
    }

    private void pay(Long student, Long group, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(student);
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private void attend(Long student, Long group, String date, LocalDateTime createdAt) {
        Long id = inTx(() -> attendanceRepo.save(Attendance.builder()
            .student(studentRepo.findById(student).orElseThrow()).group(groupRepo.findById(group).orElseThrow())
            .attendanceDate(d(date)).status(AttendanceStatus.PRESENT).build()).getId());
        jdbc.update("UPDATE attendance SET created_at = ? WHERE id = ?", createdAt, id);
    }

    // ── §1.1 voronka ────────────────────────────────────────────────────

    @Test
    void funnel_activity_independentEvents_andFirstPayments() {
        user(UserRole.SUPER_ADMIN);
        Long old = lead(t("30.09.2026", 18, 0), null);
        Long a = lead(t("01.10.2026", 9, 0), null);
        Long b = lead(t("01.10.2026", 10, 0), null);
        lead(t("01.10.2026", 23, 59), null);
        lead(t("02.10.2026", 0, 0), null);                       // ertangi kun — kirmaydi
        Long imported = lead(t("01.10.2026", 11, 0), null);
        jdbc.update("UPDATE leads SET import_batch = 'amo-1' WHERE id = ?", imported);
        stage(old, "CONTACTED", t("01.10.2026", 9, 30));
        stage(a, "CONTACTED", t("01.10.2026", 9, 40));
        stage(a, "VISITED_TRIAL", t("01.10.2026", 16, 0));
        inTx(() -> {
            Lead l = leadRepo.findById(b).orElseThrow();
            tracker.onStageEntered(l, "CONVERTED_OFFLINE", t("01.10.2026", 17, 0));
            leadRepo.save(l);
        });
        // birinchi to'lovlar: lid o'quvchisi (b) va lidsiz; c — eski to'lovi bor (birinchi emas)
        Long sb = fixtures.student();
        jdbc.update("UPDATE students SET converted_from_lead_id = ? WHERE id = ?", b, sb);
        rawPayment(sb, null, 700_000, "01.10.2026");
        Long walkIn = fixtures.student();
        rawPayment(walkIn, null, 500_000, "01.10.2026");
        Long c = fixtures.student();
        rawPayment(c, null, 500_000, "10.09.2026");
        rawPayment(c, null, 500_000, "01.10.2026");
        clock.setDate(d("05.10.2026"));

        FunnelActivity act = funnel.activity(day("01.10.2026"), FunnelMetricsService.Filter.defaults());

        assertThat(act.leadsCreated()).isEqualTo(3);
        assertThat(act.contacted()).isEqualTo(3);     // old, a (status), b (konvert → aloqa)
        assertThat(act.visited()).isEqualTo(1);
        assertThat(act.converted()).isEqualTo(1);
        assertThat(act.firstPayments().total()).isEqualTo(2);
        assertThat(act.firstPayments().fromLeads()).isEqualTo(1);
        assertThat(act.firstPayments().walkIn()).isEqualTo(1);
        assertThat(funnel.activity(day("01.10.2026"), new FunnelMetricsService.Filter(true, null)).leadsCreated())
            .isEqualTo(4);
        assertThat(funnel.activityRows(day("01.10.2026"), FunnelMetricsService.Step.FIRST_PAYMENT,
            FunnelMetricsService.Filter.defaults())).hasSize(2);
    }

    @Test
    void funnel_cohort_week_ratesAndMedianDays() {
        user(UserRole.SUPER_ADMIN);
        Long l1 = lead(t("28.09.2026", 9, 0), null);
        Long l2 = lead(t("29.09.2026", 9, 0), null);
        Long l3 = lead(t("30.09.2026", 9, 0), null);
        lead(t("01.10.2026", 9, 0), null);
        stage(l1, "CONTACTED", t("28.09.2026", 10, 0));
        stage(l1, "VISITED_TRIAL", t("30.09.2026", 10, 0));       // 2 kun
        stage(l2, "CONTACTED", t("30.09.2026", 10, 0));
        stage(l2, "VISITED_TRIAL", t("03.10.2026", 10, 0));       // 4 kun
        stage(l3, "CONTACTED", t("30.09.2026", 11, 0));
        inTx(() -> {
            Lead l = leadRepo.findById(l1).orElseThrow();
            tracker.onStageEntered(l, "CONVERTED_OFFLINE", t("02.10.2026", 9, 0));
            leadRepo.save(l);
        });
        Long s1 = fixtures.student();
        jdbc.update("UPDATE students SET converted_from_lead_id = ? WHERE id = ?", l1, s1);
        rawPayment(s1, null, 700_000, "03.10.2026");
        clock.setDate(d("10.10.2026"));

        FunnelCohort c = funnel.cohort(DashboardPeriod.of("WEEK", d("30.09.2026"), null, null,
            LocalDateTime.now(clock)), FunnelMetricsService.Filter.defaults());

        assertThat(c.size()).isEqualTo(4);
        assertThat(c.steps()).extracting(DirectorDtos.CohortStep::reached).containsExactly(4L, 3L, 2L, 1L, 1L);
        assertThat(c.steps().get(1).rate()).isEqualByComparingTo("75.0");
        assertThat(c.steps().get(2).rate()).isEqualByComparingTo("50.0");
        assertThat(c.steps().get(2).medianDaysToStep()).isEqualByComparingTo("2");   // {2, 4} → pastki mediana
        assertThat(c.steps().get(4).step()).isEqualTo("FIRST_PAYMENT");
        assertThat(c.steps().get(4).rate()).isEqualByComparingTo("25.0");
    }

    // ── §1.2 muddati kelgan to'lovlar ───────────────────────────────────

    @Test
    void collections_onTimeLatePendingUnpaid_byAsOf() {
        long[] ids = new long[6];
        for (int i = 0; i < 3; i++) {
            Long s = fixtures.student();
            Long g = fixtures.group(fixtures.course(700_000));
            Long sg = fixtures.enrollment(s, g).start(d("15.09.2026")).discount("10").save();
            ids[i * 2] = s;
            ids[i * 2 + 1] = g;
            if (i == 0) {
                pay(s, g, 630_000, "14.09.2026");                  // oldindan → ON_TIME
            }
            accrual.accrueUpTo(sg, d("15.09.2026"));
        }
        clock.setDate(d("16.09.2026"));
        CollectionsSection dayView = collections.summary(day("15.09.2026"));   // 16.09 holati
        assertThat(dayView.due().count()).isEqualTo(3);
        assertThat(dayView.due().amount()).isEqualByComparingTo("1890000");
        assertThat(dayView.collected().count()).isEqualTo(1);
        assertThat(dayView.onTime().count()).isEqualTo(1);
        // grace 0 (R1): grace_until = 15.09 — 16.09 da to'lanmaganlar UNPAID (kutilmoqda emas)
        assertThat(dayView.pending().count()).isZero();
        assertThat(dayView.unpaid().count()).isEqualTo(2);

        pay(ids[2], ids[3], 630_000, "20.09.2026");                // muddat 15.09 dan keyin → LATE (5 kun)
        clock.setDate(d("25.09.2026"));

        CollectionsSection month = collections.summary(DashboardPeriod.of("MONTH", d("15.09.2026"), null, null,
            LocalDateTime.now(clock)));                                          // 25.09 holati
        assertThat(month.onTime().count()).isEqualTo(1);
        assertThat(month.late().count()).isEqualTo(1);
        assertThat(month.unpaid().count()).isEqualTo(1);
        assertThat(month.unpaid().amount()).isEqualByComparingTo("630000");
        assertThat(month.collectionRate()).isEqualByComparingTo("66.7");
        assertThat(month.avgDelayDays()).isEqualByComparingTo("2.5");            // (0 + 5) / 2
        assertThat(month.avgLateDelayDays()).isEqualByComparingTo("5.0");
        assertThat(month.cashIn()).isEqualByComparingTo("1260000");
        assertThat(collections.summary(day("20.09.2026")).collectedOnDay().count()).isEqualTo(1);
        assertThat(collections.rows(DashboardPeriod.of("MONTH", d("15.09.2026"), null, null, LocalDateTime.now(clock)),
            CollectionsMetricsService.Bucket.LATE)).singleElement()
            .satisfies(r -> assertThat(r.delayDays()).isEqualTo(5L));
    }

    @Test
    void collections_excludeHoldRefundedAndPerLesson() {
        Long s = fixtures.student();
        Long g = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(s, g).start(d("15.09.2026")).save();
        accrual.accrueUpTo(sg, d("15.09.2026"));
        Long s2 = fixtures.student();
        Long sg2 = fixtures.enrollment(s2, g).start(d("15.09.2026")).save();
        accrual.accrueUpTo(sg2, d("15.09.2026"));
        jdbc.update("UPDATE student_groups SET billing_hold = TRUE WHERE id = ?", sg2);
        Long s3 = fixtures.student();
        Long g3 = fixtures.group(fixtures.course(700_000, 80_000L));
        fixtures.enrollment(s3, g3).start(d("15.09.2026")).perLesson(80_000).save();
        clock.setDate(d("16.09.2026"));

        CollectionsSection c = collections.summary(day("15.09.2026"));
        assertThat(c.due().count()).isEqualTo(1);
        assertThat(c.due().amount()).isEqualByComparingTo("700000");
        assertThat(c.estimated()).isFalse();
    }

    // ── §1.3 qarzdorlar ─────────────────────────────────────────────────

    @Test
    void debtors_live_newTodayAndOverdue7Plus() {
        Long s1 = fixtures.student();
        Long g = fixtures.group(fixtures.course(700_000));
        Long sg1 = fixtures.enrollment(s1, g).start(d("15.09.2026")).save();
        accrual.accrueUpTo(sg1, d("15.09.2026"));
        Long s2 = fixtures.student();
        Long sg2 = fixtures.enrollment(s2, g).start(d("05.09.2026")).save();
        accrual.accrueUpTo(sg2, d("05.09.2026"));
        clock.setDate(d("15.09.2026"));
        jdbc.update("UPDATE student_groups SET payment_status = 'OVERDUE'");

        DebtorsSection live = debtors.summary(day("15.09.2026"));

        assertThat(live.count()).isEqualTo(2);
        assertThat(live.amount()).isEqualByComparingTo("1400000");
        assertThat(live.newToday()).isEqualTo(1);        // R1: 15.09 — muddat kunining o'zida qarzdor
        assertThat(live.overdue7Plus()).isEqualTo(1);    // 05.09 → 10 kun
        assertThat(live.source()).isEqualTo("LIVE");
        assertThat(live.studentIds()).isNull();
    }

    @Test
    void debtors_history_onlyFromSnapshot_andClearedToday() throws Exception {
        clock.setDate(d("19.09.2026"));
        assertThat(debtors.summary(day("10.09.2026")).source()).isEqualTo("NONE");
        assertThat(debtors.summary(day("10.09.2026")).count()).isNull();

        DebtorsSection yesterday = new DebtorsSection(3L, BigDecimal.valueOf(2_100_000), 1L, 0L, null,
            BigDecimal.ZERO, "LIVE", List.of(901L, 902L, 903L));
        inTx(() -> statRepo.save(DirectorDailyStat.builder().statDate(d("18.09.2026")).section("debtors")
            .payload(write(yesterday)).computedAt(t("18.09.2026", 23, 55)).finalized(true).version(1).build()));

        DebtorsSection hist = debtors.summary(day("18.09.2026"));
        assertThat(hist.source()).isEqualTo("SNAPSHOT");
        assertThat(hist.count()).isEqualTo(3);
        assertThat(hist.studentIds()).isNull();
        // bugun: hech kim qarzdor emas → kecha qarzdor bo'lgan 3 tasi "yopildi"
        assertThat(debtors.summary(day("19.09.2026")).clearedToday()).isEqualTo(3);
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── §1.4 davomat intizomi ───────────────────────────────────────────

    private Long scheduledGroup(GroupStatus status, String... days) {
        Long g = fixtures.group(fixtures.course(700_000), status);
        for (String day : days) {
            inTx(() -> scheduleRepo.save(GroupScheduleDay.builder().group(groupRepo.findById(g).orElseThrow())
                .dayOfWeek(day).startTime("10:00").endTime("12:00").build()));
        }
        return g;
    }

    @Test
    void attendance_plannedTakenLateMissingUnplanned() {
        Long g = scheduledGroup(GroupStatus.ACTIVE, "MONDAY", "WEDNESDAY", "FRIDAY");
        Long s = fixtures.student();
        fixtures.enrollment(s, g).start(d("01.09.2026")).save();
        attend(s, g, "05.10.2026", t("05.10.2026", 11, 0));      // dushanba — o'z vaqtida
        attend(s, g, "07.10.2026", t("08.10.2026", 9, 0));       // chorshanba — ertasi kun
        attend(s, g, "06.10.2026", t("06.10.2026", 11, 0));      // seshanba — rejada yo'q
        clock.setDateTime(t("10.10.2026", 9, 0));                // juma 09.10 — o'tib ketgan

        AttendanceSection a = attendance.summary(custom("05.10.2026", "09.10.2026"), null);

        assertThat(a.planned()).isEqualTo(3);
        assertThat(a.taken()).isEqualTo(2);
        assertThat(a.takenOnTime()).isEqualTo(1);
        assertThat(a.missing()).isEqualTo(1);
        assertThat(a.unplanned()).isEqualTo(1);
        assertThat(a.rate()).isEqualByComparingTo("66.7");
    }

    @Test
    void attendance_holidayCancelExtraFrozenAndForming() {
        Long g = scheduledGroup(GroupStatus.ACTIVE, "MONDAY", "WEDNESDAY", "FRIDAY");
        Long s = fixtures.student();
        fixtures.enrollment(s, g).start(d("01.09.2026")).save();
        Long forming = scheduledGroup(GroupStatus.FORMING, "MONDAY");
        fixtures.enrollment(fixtures.student(), forming).start(d("01.09.2026")).save();
        Long frozenOnly = scheduledGroup(GroupStatus.ACTIVE, "FRIDAY");
        Long fs = fixtures.student();
        Long fsg = fixtures.enrollment(fs, frozenOnly).start(d("01.09.2026")).save();
        jdbc.update("UPDATE student_groups SET is_active = FALSE, frozen_from = ?, leave_date = ? WHERE id = ?",
            d("01.10.2026"), d("01.10.2026"), fsg);
        inTx(() -> holidayRepo.save(Holiday.builder().holidayDate(d("05.10.2026")).name("Bayram")
            .createdAt(t("01.10.2026", 9, 0)).build()));
        inTx(() -> exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("07.10.2026"))
            .kind(LessonException.Kind.CANCELLED).createdAt(t("01.10.2026", 9, 0)).build()));
        inTx(() -> exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("10.10.2026"))
            .kind(LessonException.Kind.EXTRA).createdAt(t("01.10.2026", 9, 0)).build()));
        clock.setDateTime(t("12.10.2026", 9, 0));

        AttendanceSection a = attendance.summary(custom("05.10.2026", "11.10.2026"), null);

        // reja: juma 09.10 + qo'shimcha shanba 10.10 (dushanba bayram, chorshanba bekor; FORMING va faqat muzlatilgan guruh — yo'q)
        assertThat(a.planned()).isEqualTo(2);
        assertThat(a.missing()).isEqualTo(2);
    }

    // ── §1.5 sinov → to'lov ─────────────────────────────────────────────

    private Long trialSg(String started) {
        Long s = fixtures.student();
        Long g = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(s, g).start(d(started)).trial().save();
        jdbc.update("UPDATE student_groups SET trial_started_at = ? WHERE id = ?", d(started), sg);
        return sg;
    }

    private void trialPaid(Long sg, String date) {
        Long student = inTx(() -> sgRepo.findById(sg).orElseThrow().getStudent().getId());
        rawPayment(student, sg, 700_000, date);
        jdbc.update("UPDATE student_groups SET is_trial = FALSE WHERE id = ?", sg);
    }

    @Test
    void trials_bucketsByDays_andConversionRate() {
        Long a = trialSg("01.10.2026");
        trialPaid(a, "01.10.2026");                                // D0
        Long b = trialSg("01.10.2026");
        trialPaid(b, "02.10.2026");                                // D1
        Long c = trialSg("02.10.2026");
        trialPaid(c, "06.10.2026");                                // D2_7 (4 kun)
        Long e = trialSg("02.10.2026");
        trialPaid(e, "15.10.2026");                                // D8_PLUS (13 kun)
        trialSg("03.10.2026");                                     // to'lamagan, 17 kun → NOT_PAID
        Long noShow = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000)))
            .start(d("02.10.2026")).trial().save();
        clock.setDate(d("20.10.2026"));

        TrialsSection t = trials.summary(custom("01.10.2026", "03.10.2026"));

        assertThat(t.cohort()).isEqualTo(5);
        assertThat(t.buckets().get("D0").count()).isEqualTo(1);
        assertThat(t.buckets().get("D1").count()).isEqualTo(1);
        assertThat(t.buckets().get("D2_7").count()).isEqualTo(1);
        assertThat(t.buckets().get("D8_PLUS").count()).isEqualTo(1);
        assertThat(t.buckets().get("NOT_PAID").count()).isEqualTo(1);
        assertThat(t.buckets().get("D0").percent()).isEqualByComparingTo("20.0");
        assertThat(t.conversionRate()).isEqualByComparingTo("80.0");
        assertThat(t.noShow()).isEqualTo(1);
        assertThat(trials.rows(custom("01.10.2026", "03.10.2026"), TrialMetricsService.Bucket.NO_SHOW))
            .singleElement().satisfies(r -> assertThat(r.studentGroupId()).isEqualTo(noShow));
    }

    @Test
    void trials_inTrialWithin14Days_excludedFromRate_andStayed30() {
        Long a = trialSg("01.10.2026");
        trialPaid(a, "01.10.2026");
        trialSg("05.10.2026");                                     // 10.10 da 5 kun → IN_TRIAL
        clock.setDate(d("10.10.2026"));

        TrialsSection t = trials.summary(custom("01.10.2026", "05.10.2026"));
        assertThat(t.buckets().get("IN_TRIAL").count()).isEqualTo(1);
        assertThat(t.conversionRate()).isEqualByComparingTo("100.0");      // 1 / (2 − 1)
        assertThat(t.stayed30()).isNull();                                   // 30 kun hali o'tmagan

        clock.setDate(d("05.11.2026"));
        TrialsSection later = trials.summary(custom("01.10.2026", "05.10.2026"));
        assertThat(later.buckets().get("NOT_PAID").count()).isEqualTo(1);    // 31 kun → qaror
        assertThat(later.stayed30()).isEqualByComparingTo("100.0");
    }

    // ── §1.6 qolish ─────────────────────────────────────────────────────

    @Test
    void retention_monthlyCohorts() {
        Long g = fixtures.group(fixtures.course(700_000));
        Long s1 = fixtures.student();
        Long sg1 = fixtures.enrollment(s1, g).start(d("05.08.2026")).save();
        rawPayment(s1, sg1, 700_000, "05.08.2026");
        Long s2 = fixtures.student();
        Long sg2 = fixtures.enrollment(s2, g).start(d("10.08.2026")).save();
        rawPayment(s2, sg2, 700_000, "10.08.2026");
        jdbc.update("UPDATE student_groups SET is_active = FALSE, leave_date = ?, exit_date = ? WHERE id = ?",
            d("20.09.2026"), d("20.09.2026"), sg2);
        Long s3 = fixtures.student();
        Long sg3 = fixtures.enrollment(s3, g).start(d("02.09.2026")).save();
        rawPayment(s3, sg3, 700_000, "02.09.2026");
        clock.setDate(d("15.10.2026"));

        List<RetentionCohort> cohorts = retention.cohorts(YearMonth.of(2026, 8), YearMonth.of(2026, 9), d("15.10.2026"));

        assertThat(cohorts).hasSize(2);
        assertThat(cohorts.get(0).size()).isEqualTo(2);
        assertThat(cohorts.get(0).retained()).containsExactly(
            new BigDecimal("100.0"), new BigDecimal("50.0"), new BigDecimal("50.0"));    // avg, sen, okt
        assertThat(cohorts.get(1).retained()).containsExactly(new BigDecimal("100.0"), new BigDecimal("100.0"));
    }

    @Test
    void retention_exits_churnPartialPauseTransferGraduated() {
        Long g1 = fixtures.group(fixtures.course(700_000));
        Long g2 = fixtures.group(fixtures.course(700_000));
        Long a = fixtures.student();                                   // CHURN (PRICE)
        Long sa = fixtures.enrollment(a, g1).start(d("01.09.2026")).save();
        Long b = fixtures.student();                                   // PARTIAL — boshqa guruhda o'qiyapti
        Long sb = fixtures.enrollment(b, g1).start(d("01.09.2026")).save();
        fixtures.enrollment(b, g2).start(d("01.09.2026")).save();
        Long c = fixtures.student();                                   // GRADUATED
        Long sc = fixtures.enrollment(c, g1).start(d("01.09.2026")).save();
        Long e = fixtures.student();                                   // PAUSE (muzlatish)
        Long se = fixtures.enrollment(e, g1).start(d("01.09.2026")).save();
        close(sa, ExitReasonCode.PRICE, "10.10.2026");
        close(sb, ExitReasonCode.SCHEDULE, "10.10.2026");
        close(sc, ExitReasonCode.GRADUATED, "11.10.2026");
        jdbc.update("UPDATE student_groups SET is_active = FALSE, frozen_from = ?, exit_date = ?, exit_reason_code = 'FROZEN' WHERE id = ?",
            d("12.10.2026"), d("12.10.2026"), se);
        clock.setDate(d("20.10.2026"));

        var exits = retention.summary(custom("01.10.2026", "20.10.2026")).exits();

        assertThat(exits.churned()).isEqualTo(1);
        assertThat(exits.graduated()).isEqualTo(1);
        assertThat(exits.paused()).isEqualTo(1);
        assertThat(exits.byReason()).containsEntry("PRICE", 1L).doesNotContainKey("SCHEDULE");
        assertThat(retention.exits(custom("01.10.2026", "20.10.2026")))
            .filteredOn(r -> r.studentId().equals(b)).singleElement()
            .satisfies(r -> assertThat(r.kind()).isEqualTo("PARTIAL"));
    }

    private void close(Long sg, ExitReasonCode code, String date) {
        jdbc.update("UPDATE student_groups SET is_active = FALSE, leave_date = ?, exit_date = ?, exit_reason_code = ? WHERE id = ?",
            d(date), d(date), code.name(), sg);
    }

    // ── §1.7 operatorlar ────────────────────────────────────────────────

    @Test
    void businessHours_skipNightAndSunday() {
        BusinessHours h = new BusinessHours(LocalTime.of(9, 0), LocalTime.of(20, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
        // shanba 03.10 19:50 → dushanba 05.10 09:10: 10 + 0 + 10 = 20 daqiqa
        assertThat(h.minutesBetween(t("03.10.2026", 19, 50), t("05.10.2026", 9, 10))).isEqualTo(20);
        assertThat(h.minutesBetween(t("05.10.2026", 7, 0), t("05.10.2026", 8, 59))).isZero();
        assertThat(h.minutesBetween(t("05.10.2026", 9, 0), t("06.10.2026", 9, 0))).isEqualTo(11 * 60);
    }

    @Test
    void operators_responseTimes_tasks_conversion() {
        User sm = user(UserRole.SALES_MANAGER);
        user(UserRole.ADMIN);
        user(UserRole.SUPER_ADMIN);
        Long l1 = lead(t("03.10.2026", 19, 50), sm.getId());      // shanba kechqurun
        Long l2 = lead(t("05.10.2026", 10, 0), sm.getId());
        Long l3 = lead(t("05.10.2026", 11, 0), sm.getId());       // javobsiz
        fixtures.loginAs(UserRole.SALES_MANAGER);
        stage(l1, "CONTACTED", t("05.10.2026", 9, 10));            // 20 ish daqiqasi
        stage(l2, "CONTACTED", t("05.10.2026", 10, 5));            // 5 daqiqa
        inTx(() -> {
            Lead l = leadRepo.findById(l2).orElseThrow();
            tracker.onStageEntered(l, "CONVERTED_OFFLINE", t("06.10.2026", 10, 0));
            leadRepo.save(l);
        });
        inTx(() -> taskRepo.save(Task.builder().title("Kech").dueAt(t("05.10.2026", 12, 0))
            .status(TaskStatus.DONE).completedAt(t("05.10.2026", 13, 0)).completedBy(sm).assignedTo(sm).build()));
        inTx(() -> taskRepo.save(Task.builder().title("Muddati o'tgan").dueAt(t("06.10.2026", 12, 0))
            .status(TaskStatus.OPEN).assignedTo(sm).build()));
        clock.setDateTime(t("09.10.2026", 12, 0));

        OperatorRow row = operators.operators(custom("03.10.2026", "08.10.2026")).stream()
            .filter(r -> r.userId().equals(sm.getId())).findFirst().orElseThrow();

        assertThat(row.assigned()).isEqualTo(3);
        assertThat(row.firstResponse().medianMinutes()).isEqualTo(5);           // {5, 20}
        assertThat(row.firstResponse().p90Minutes()).isEqualTo(20);
        assertThat(row.firstResponse().within15m()).isEqualByComparingTo("33.3");
        assertThat(row.noResponse()).isEqualTo(1);                              // l3: 24 ish soatidan ko'p
        assertThat(row.tasks().done()).isEqualTo(1);
        assertThat(row.tasks().doneLate()).isEqualTo(1);
        assertThat(row.tasks().overdueOpen()).isEqualTo(1);
        assertThat(row.converted()).isEqualTo(1);
        assertThat(row.conversionRate()).isEqualByComparingTo("33.3");
        assertThat(operators.leads(custom("03.10.2026", "08.10.2026"), sm.getId(),
            OperatorMetricsService.LeadMetric.NO_RESPONSE)).singleElement()
            .satisfies(r -> assertThat(r.leadId()).isEqualTo(l3));
        assertThat(inTx(() -> assignmentRepo.findAll()).stream().map(LeadAssignment::getLeadId)).contains(l1, l2, l3);
    }

    @Test
    void operators_importedLeadsExcluded_andPaymentsReceived() {
        User sm = user(UserRole.SALES_MANAGER);
        user(UserRole.SUPER_ADMIN);
        Long imported = lead(t("05.10.2026", 10, 0), sm.getId());
        jdbc.update("UPDATE leads SET import_batch = 'x' WHERE id = ?", imported);
        Long s = fixtures.student();
        Long g = fixtures.group(fixtures.course(700_000));
        fixtures.enrollment(s, g).start(d("05.10.2026")).save();
        fixtures.loginAs(UserRole.SALES_MANAGER);
        clock.setDate(d("05.10.2026"));
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(s);
        r.setGroupId(g);
        r.setAmount(BigDecimal.valueOf(700_000));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        jdbc.update("UPDATE students SET attributed_user_id = ? WHERE id = ?", sm.getId(), s);
        payments.createPayment(r, null);
        clock.setDate(d("06.10.2026"));

        OperatorRow row = operators.operators(day("05.10.2026")).stream()
            .filter(x -> x.userId().equals(sm.getId())).findFirst().orElseThrow();
        assertThat(row.assigned()).isZero();
        assertThat(row.paymentsReceived().count()).isEqualTo(1);
        assertThat(row.paymentsReceived().amount()).isEqualByComparingTo("700000");
        assertThat(row.firstPayments()).isEqualTo(1);
    }

    /** "Taxminiy" belgisi: backfill qilingan sinov sanalari va tayinlashlar (§7 #16). */
    @Test
    void estimatedFlags_forBackfilledTrialsAndAssignments() {
        Long live = trialSg("01.10.2026");
        Long backfilled = trialSg("02.10.2026");
        jdbc.update("UPDATE student_groups SET trial_source = 'BACKFILL' WHERE id = ?", backfilled);
        User sm = user(UserRole.SALES_MANAGER);
        user(UserRole.SUPER_ADMIN);
        Long l = lead(t("02.10.2026", 10, 0), sm.getId());
        clock.setDate(d("05.10.2026"));

        assertThat(trials.summary(day("01.10.2026")).estimated()).isFalse();
        assertThat(trials.summary(custom("01.10.2026", "02.10.2026")).estimated()).isTrue();
        assertThat(trials.rows(custom("01.10.2026", "02.10.2026"), TrialMetricsService.Bucket.IN_TRIAL))
            .filteredOn(r -> r.studentGroupId().equals(live)).singleElement()
            .satisfies(r -> assertThat(r.estimated()).isFalse());
        assertThat(operators.summary(day("02.10.2026")).estimated()).isFalse();
        jdbc.update("UPDATE lead_assignments SET source = 'BACKFILL' WHERE lead_id = ?", l);
        assertThat(operators.summary(day("02.10.2026")).estimated()).isTrue();
    }

    @Test
    void period_boundaries_tashkentWeekMonthCustom() {
        LocalDateTime now = t("07.10.2026", 15, 0);
        DashboardPeriod w = DashboardPeriod.of("WEEK", d("07.10.2026"), null, null, now);
        assertThat(w.from()).isEqualTo(d("05.10.2026"));
        assertThat(w.to()).isEqualTo(d("11.10.2026"));
        assertThat(w.asOf()).isEqualTo(now);
        DashboardPeriod m = DashboardPeriod.of("MONTH", d("07.09.2026"), null, null, now);
        assertThat(m.from()).isEqualTo(d("01.09.2026"));
        assertThat(m.to()).isEqualTo(d("30.09.2026"));
        assertThat(m.asOf()).as("kogorta hozirgacha o'lchanadi").isEqualTo(now);
        assertThat(m.contains(t("30.09.2026", 23, 59))).isTrue();
        assertThat(m.contains(t("01.10.2026", 0, 0))).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                DashboardPeriod.of("CUSTOM", null, d("01.01.2025"), d("10.01.2026"), now))
            .isInstanceOf(com.crm.exception.CodedException.class);
    }
}
