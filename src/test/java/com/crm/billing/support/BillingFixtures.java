package com.crm.billing.support;

import com.crm.entity.CashRegister;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CourseRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Billing testlari uchun ma'lumot: kurs, guruh, o'quvchi, yozilma, kassa.
 * Har chaqiruv o'z tranzaksiyasida saqlaydi va id qaytaradi — test keyin
 * o'zi tanlagan tranzaksiyada qayta o'qiydi.
 */
@RequiredArgsConstructor
public class BillingFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Testlar tozalaydigan jadvallar (FK tekshiruvi vaqtincha o'chiriladi). */
    private static final List<String> TABLES = List.of(
        "balance_transactions", "billing_periods", "income", "payments", "cash_transactions",
        "cash_registers", "bonus_penalties", "attendance", "student_status_history",
        "student_groups", "students", "group_schedule_days", "groups", "courses", "teachers",
        "billing_job_runs", "billing_migration_runs", "audit_logs",
        "lead_assignments", "lead_status_history", "lead_comments", "lead_notes", "tasks", "leads",
        "holidays", "lesson_exceptions", "director_daily_stats", "director_digest_log",
        "payroll", "salary_rules",
        "exam_results", "exam_registrations", "exams", "contracts", "contract_templates",
        "contract_number_counters", "notice_reads", "notice_target_roles", "notices",
        "lesson_substitutions", "leave_requests", "attendance_unlock_requests",
        "homework_submissions", "homeworks", "group_transfer_batches", "expenses");

    private final CourseRepository courseRepository;
    private final GroupRepository groupRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final CashRegisterRepository cashRegisterRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    private Boolean postgres;

    public void wipe() {
        if (isPostgres()) {
            // pgtest: FK tekshiruvini o'chirib bo'lmaydi (superuser kerak) — bitta TRUNCATE.
            // CASCADE ro'yxatdagilarga FK bilan bog'langan test jadvallarini ham tozalaydi
            // (contracts, homework_submissions ...); users ga tegmaydi — unda FK yo'q.
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " CASCADE");
            return;
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : TABLES) {
                jdbc.execute("TRUNCATE TABLE " + table);
            }
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    private boolean isPostgres() {
        if (postgres == null) {
            postgres = jdbc.execute((ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres"));
        }
        return postgres;
    }

    public Long course(long monthlyPrice) {
        return course(monthlyPrice, null);
    }

    public Long course(long monthlyPrice, Long lessonPrice) {
        return tx.execute(s -> courseRepository.save(Course.builder()
            .courseName("Kurs " + SEQ.incrementAndGet())
            .durationMonths(6)
            .lessonsCount(48)
            .monthlyPrice(BigDecimal.valueOf(monthlyPrice))
            .lessonPrice(lessonPrice != null ? BigDecimal.valueOf(lessonPrice) : null)
            .build()).getId());
    }

    public Long group(Long courseId) {
        return group(courseId, GroupStatus.ACTIVE);
    }

    public Long group(Long courseId, GroupStatus status) {
        return group(courseId, status, null);
    }

    public Long group(Long courseId, GroupStatus status, Long teacherId) {
        return tx.execute(s -> groupRepository.save(Group.builder()
            .groupName("Guruh " + SEQ.incrementAndGet())
            .course(courseRepository.findById(courseId).orElseThrow())
            .teacher(teacherId != null ? teacherRepository.findById(teacherId).orElseThrow() : null)
            .startDate(LocalDate.of(2026, 1, 1))
            .status(status)
            .maxStudents(100)
            .build()).getId());
    }

    public Long teacher() {
        int n = SEQ.incrementAndGet();
        return tx.execute(s -> teacherRepository.save(Teacher.builder()
            .firstName("Ustoz" + n)
            .lastName("Karimov")
            .phone("+99891" + String.format("%07d", n))
            .build()).getId());
    }

    public Long student() {
        int n = SEQ.incrementAndGet();
        return tx.execute(s -> studentRepository.save(Student.builder()
            .firstName("Ali" + n)
            .lastName("Valiyev")
            .phone("+99890" + String.format("%07d", n))
            .status(StudentStatus.ACTIVE)
            .build()).getId());
    }

    /** Yozilma qurilishi — kerakli maydonlarni test o'zi beradi. */
    public EnrollmentBuilder enrollment(Long studentId, Long groupId) {
        return new EnrollmentBuilder(studentId, groupId);
    }

    public Long cashRegister(boolean acceptOnline) {
        return tx.execute(s -> {
            CashRegister r = new CashRegister();
            r.setName("Kassa " + SEQ.incrementAndGet());
            r.setStatus(CashRegisterStatus.ACTIVE);
            r.setAcceptOnlinePayment(acceptOnline);
            return cashRegisterRepository.save(r).getId();
        });
    }

    /** Test foydalanuvchisi va SecurityContext (joriy thread uchun). */
    public void loginAs(UserRole role) {
        String username = "test-" + role.name().toLowerCase();
        tx.executeWithoutResult(s -> {
            if (userRepository.findByUsername(username).isEmpty()) {
                userRepository.save(User.builder()
                    .username(username)
                    .password("x")
                    .firstName("Test")
                    .lastName(role.name())
                    .role(role)
                    .build());
            }
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    public final class EnrollmentBuilder {
        private final Long studentId;
        private final Long groupId;
        private PaymentType paymentType = PaymentType.MONTHLY;
        private LocalDate paymentStartDate = LocalDate.of(2026, 9, 15);
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal override;
        private BigDecimal lessonPrice;
        private boolean trial;

        private EnrollmentBuilder(Long studentId, Long groupId) {
            this.studentId = studentId;
            this.groupId = groupId;
        }

        public EnrollmentBuilder start(LocalDate date) {
            this.paymentStartDate = date;
            return this;
        }

        public EnrollmentBuilder discount(String percent) {
            this.discount = new BigDecimal(percent);
            return this;
        }

        public EnrollmentBuilder override(long fee) {
            this.override = BigDecimal.valueOf(fee);
            return this;
        }

        public EnrollmentBuilder perLesson(long price) {
            this.paymentType = PaymentType.PER_LESSON;
            this.lessonPrice = BigDecimal.valueOf(price);
            return this;
        }

        public EnrollmentBuilder trial() {
            this.trial = true;
            return this;
        }

        public Long save() {
            return tx.execute(s -> studentGroupRepository.save(StudentGroup.builder()
                .student(studentRepository.findById(studentId).orElseThrow())
                .group(groupRepository.findById(groupId).orElseThrow())
                .joinDate(paymentStartDate)
                .paymentStartDate(paymentStartDate)
                .paymentType(paymentType)
                .discountPercentage(discount)
                .monthlyPriceOverride(override)
                .lessonPrice(lessonPrice)
                .isTrial(trial)
                .isActive(true)
                .balance(BigDecimal.ZERO)
                .build()).getId());
        }
    }
}
