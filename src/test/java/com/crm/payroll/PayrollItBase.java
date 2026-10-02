package com.crm.payroll;

import com.crm.billing.AccrualService;
import com.crm.billing.LessonChargeService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.PayrollResponse;
import com.crm.entity.Attendance;
import com.crm.entity.BillingPeriod;
import com.crm.entity.BonusPenalty;
import com.crm.entity.Group;
import com.crm.entity.Payroll;
import com.crm.entity.SalaryRule;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.PayrollRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.PaymentService;
import com.crm.service.PayrollService;
import com.crm.service.SalaryCalculationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payroll v2 testlari asosi (docs/design/payroll-v2.md §12). Hisob oyi — sentyabr 2026;
 * {@link AbstractBillingIT} soatni 15.09.2026 ga qo'yadi, davrlar 15.09 dan boshlanadi.
 *
 * <p>Foydalanuvchilar testlar orasida tozalanmaydi — shuning uchun faqat SHAXSIY qoidalar
 * ishlatiladi (rol qoidasi boshqa testlardan qolgan xodimlarni ham hisobga olardi).
 */
abstract class PayrollItBase extends AbstractBillingIT {

    static final int SEP = 9;
    static final int OCT = 10;
    static final int YEAR = 2026;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired PayrollService payroll;
    @Autowired SalaryCalculationService calculator;
    @Autowired PaymentService payments;
    @Autowired AccrualService accrual;
    @Autowired LessonChargeService lessons;
    @Autowired PayrollRepository payrollRepo;
    @Autowired SalaryRuleRepository ruleRepo;
    @Autowired UserRepository userRepo;
    @Autowired TeacherRepository teacherRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired BonusPenaltyRepository bonusRepo;
    @Autowired BillingPeriodRepository periodRepo;
    @Autowired CashRegisterRepository registerRepo;
    @Autowired CashTransactionRepository cashRepo;
    @Autowired JdbcTemplate jdbc;

    record Staff(Long userId, Long teacherId) {
    }

    record Ids(Long student, Long group, Long sg) {
    }

    /** Xodim (foydalanuvchi) — har chaqiruvda yangi. */
    Long staffUser(UserRole role) {
        int n = SEQ.incrementAndGet();
        return inTx(() -> userRepo.save(User.builder()
            .username("pay-" + role.name().toLowerCase() + "-" + n + "-" + System.nanoTime())
            .password("x")
            .firstName("Xodim" + n)
            .lastName(role.name())
            .role(role)
            .isActive(true)
            .build()).getId());
    }

    /** TEACHER foydalanuvchi + bog'langan o'qituvchi profili. */
    Staff teacherStaff() {
        Long userId = staffUser(UserRole.TEACHER);
        Long teacherId = fixtures.teacher();
        inTx(() -> {
            Teacher t = teacherRepo.findById(teacherId).orElseThrow();
            t.setUser(userRepo.findById(userId).orElseThrow());
            teacherRepo.save(t);
        });
        return new Staff(userId, teacherId);
    }

    void rule(Long userId, UserRole role, long fixed, long perPaying, long perNew, Integer kpiThreshold, long kpiBonus) {
        inTx(() -> ruleRepo.save(SalaryRule.builder()
            .role(role)
            .user(userRepo.findById(userId).orElseThrow())
            .fixedSalary(BigDecimal.valueOf(fixed))
            .perPayingStudent(BigDecimal.valueOf(perPaying))
            .perNewStudent(BigDecimal.valueOf(perNew))
            .kpiThreshold(kpiThreshold)
            .kpiBonus(BigDecimal.valueOf(kpiBonus))
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .isActive(true)
            .build()));
    }

    void teacherRule(Staff s, long fixed, long perPaying) {
        rule(s.userId(), UserRole.TEACHER, fixed, perPaying, 0, null, 0);
    }

    /** MONTHLY yozilma: guruh o'qituvchisi {@code teacherId}, narx {@code fee}, langar {@code start}. */
    Ids monthly(Long teacherId, long fee, String start) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(fee), com.crm.entity.enums.GroupStatus.ACTIVE, teacherId);
        Long sg = fixtures.enrollment(student, group).start(d(start)).save();
        return new Ids(student, group, sg);
    }

    Ids perLesson(Long teacherId, long price, String start) {
        Long student = fixtures.student();
        Long group = fixtures.group(fixtures.course(700_000, price), com.crm.entity.enums.GroupStatus.ACTIVE, teacherId);
        Long sg = fixtures.enrollment(student, group).start(d(start)).perLesson(price).save();
        return new Ids(student, group, sg);
    }

    void accrue(Ids ids, String date) {
        clock.setDate(d(date));
        accrual.accrueUpTo(ids.sg(), d(date));
    }

    /** To'lov {@code enteredOn} kuni kiritiladi, {@code paymentDate} — to'lov sanasi (orqaga bo'lishi mumkin). */
    Long pay(Ids ids, long amount, String enteredOn, String paymentDate) {
        clock.setDate(d(enteredOn));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(ids.student());
        r.setGroupId(ids.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setPaymentDate(d(paymentDate));
        return payments.createPayment(r, null).getId();
    }

    Long pay(Ids ids, long amount, String date) {
        return pay(ids, amount, date, date);
    }

    Long attend(Ids ids, String date, AttendanceStatus status) {
        return inTx(() -> {
            Attendance a = attendanceRepo.findByStudentIdAndGroupIdAndAttendanceDate(ids.student(), ids.group(), d(date))
                .orElseGet(() -> Attendance.builder()
                    .student(studentRepo.findById(ids.student()).orElseThrow())
                    .group(groupRepo.findById(ids.group()).orElseThrow())
                    .attendanceDate(d(date))
                    .build());
            a.setStatus(status);
            Attendance saved = attendanceRepo.save(a);
            lessons.sync(saved);
            return saved.getId();
        });
    }

    void changeTeacher(Long groupId, Long teacherId) {
        inTx(() -> {
            Group g = groupRepo.findById(groupId).orElseThrow();
            g.setTeacher(teacherRepo.findById(teacherId).orElseThrow());
            groupRepo.save(g);
        });
    }

    Long teacherBonus(Long teacherId, BonusPenaltyKind kind, long amount, String effective) {
        return inTx(() -> {
            BonusPenalty b = new BonusPenalty();
            b.setKind(kind);
            b.setTargetType(BonusTargetType.TEACHER);
            b.setTeacher(teacherRepo.findById(teacherId).orElseThrow());
            b.setAmount(BigDecimal.valueOf(amount));
            b.setReason("Test");
            b.setStatus(BonusPenaltyStatus.PENDING);
            b.setEffectiveDate(d(effective));
            return bonusRepo.save(b).getId();
        });
    }

    BonusPenalty bonus(Long id) {
        return inTx(() -> bonusRepo.findById(id).orElseThrow());
    }

    Payroll payrollOf(Long userId, int month) {
        return inTx(() -> payrollRepo.findActive(userId, month, YEAR).orElseThrow());
    }

    Payroll payroll(Long id) {
        return inTx(() -> payrollRepo.findById(id).orElseThrow());
    }

    List<BillingPeriod> periods(Long sgId) {
        return inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sgId));
    }

    /** SA sifatida generate (sentyabr). */
    PayrollResponse draftFor(Long userId, int month) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.generatePayroll(month, YEAR, false);
        return payroll.getPayrollById(payrollOf(userId, month).getId());
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(CodedException.class)
            .extracting(e -> ((CodedException) e).getCode())
            .isEqualTo(code);
    }
}
