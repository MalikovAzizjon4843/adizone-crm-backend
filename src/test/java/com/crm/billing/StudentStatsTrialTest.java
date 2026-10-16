package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.StudentResponse;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O'quvchilar statistikasi (16.10.2026): {@code active} — ACTIVE va ochiq sinov yozilmasi yo'q, {@code trial} — ACTIVE va
 * ochiq sinov yozilmasi bor; bo'laklar yig'indisi = {@code total}. {@code trialOverdue} — sinov yozilmasining
 * {@code payment_start_date < bugun}. Bugun — 15.09.2026 (AbstractBillingIT).
 */
class StudentStatsTrialTest extends AbstractBillingIT {

    @Autowired StudentService students;
    @Autowired StudentRepository studentRepo;
    @Autowired StudentGroupRepository sgRepo;

    private Long student(StudentStatus status) {
        Long id = fixtures.student();
        if (status != StudentStatus.ACTIVE) {
            inTx(() -> {
                Student s = studentRepo.findById(id).orElseThrow();
                s.setStatus(status);
                studentRepo.save(s);
            });
        }
        return id;
    }

    private Long group() {
        return fixtures.group(fixtures.course(700_000));
    }

    private record Setup(Long active, Long activeFrozenTrial, Long trialFuture, Long trialOverdue, Long trialAndPaid) {
    }

    private Setup setup() {
        Long active = student(StudentStatus.ACTIVE);
        fixtures.enrollment(active, group()).start(d("01.09.2026")).save();

        Long activeFrozenTrial = student(StudentStatus.ACTIVE);                  // muzlatilgan sinov — ochiq emas
        fixtures.enrollment(activeFrozenTrial, group()).start(d("01.09.2026")).save();
        Long frozenTrialSg = fixtures.enrollment(activeFrozenTrial, group()).start(d("01.09.2026")).trial().save();
        inTx(() -> {
            StudentGroup sg = sgRepo.findById(frozenTrialSg).orElseThrow();
            sg.setFrozenFrom(d("05.09.2026"));
            sgRepo.save(sg);
        });

        Long trialFuture = student(StudentStatus.ACTIVE);
        fixtures.enrollment(trialFuture, group()).start(d("20.09.2026")).trial().save();

        Long trialOverdue = student(StudentStatus.ACTIVE);
        fixtures.enrollment(trialOverdue, group()).start(d("01.09.2026")).trial().save();

        Long trialAndPaid = student(StudentStatus.ACTIVE);                       // boshqa guruhda to'lovli + sinov
        fixtures.enrollment(trialAndPaid, group()).start(d("01.09.2026")).save();
        fixtures.enrollment(trialAndPaid, group()).start(d("25.09.2026")).trial().save();

        student(StudentStatus.FROZEN);
        student(StudentStatus.FROZEN);
        student(StudentStatus.LEFT);
        student(StudentStatus.SUSPENDED);
        student(StudentStatus.GRADUATED);
        student(StudentStatus.FINISHED);
        student(StudentStatus.ARCHIVED);
        return new Setup(active, activeFrozenTrial, trialFuture, trialOverdue, trialAndPaid);
    }

    private static long n(Map<String, Object> stats, String key) {
        return ((Number) stats.get(key)).longValue();
    }

    @Test
    void stats_bucketsSumToTotal_trialSplitFromActive_trialOverdueCounted() throws Exception {
        setup();
        Map<String, Object> stats = students.getStudentStats();

        assertThat(n(stats, "total")).isEqualTo(12);
        assertThat(n(stats, "active")).isEqualTo(2);                 // active + muzlatilgan sinovli
        assertThat(n(stats, "trial")).isEqualTo(3);
        assertThat(n(stats, "trialOverdue")).isEqualTo(1);
        assertThat(n(stats, "frozen")).isEqualTo(2);
        assertThat(n(stats, "left")).isEqualTo(1);
        assertThat(n(stats, "suspended")).isEqualTo(1);
        assertThat(n(stats, "graduated")).isEqualTo(1);
        assertThat(n(stats, "finished")).isEqualTo(1);
        assertThat(n(stats, "archived")).isEqualTo(1);
        long sum = List.of("active", "trial", "frozen", "left", "suspended", "graduated", "finished", "archived")
            .stream().mapToLong(k -> n(stats, k)).sum();
        assertThat(sum).as("invariant: bo'laklar yig'indisi = total").isEqualTo(n(stats, "total"));

        mvc.perform(get("/api/students/stats").with(user("a").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.trial").value(3))
            .andExpect(jsonPath("$.data.active").value(2))
            .andExpect(jsonPath("$.data.trialOverdue").value(1));
    }

    @Test
    void listFilter_activeMeansNoOpenTrial_trialSeparate() {
        Setup s = setup();
        fixtures.loginAs(UserRole.ADMIN);

        PageResponse<StudentResponse> active = students.getAllStudents(0, 50, null, StudentStatus.ACTIVE, null, false);
        PageResponse<StudentResponse> trial = students.getAllStudents(0, 50, null, StudentStatus.ACTIVE, null, true);
        assertThat(ids(active)).containsExactlyInAnyOrder(s.active(), s.activeFrozenTrial());
        assertThat(ids(trial)).containsExactlyInAnyOrder(s.trialFuture(), s.trialOverdue(), s.trialAndPaid());
        assertThat(active.getTotalElements()).isEqualTo(n(students.getStudentStats(), "active"));
        assertThat(trial.getTotalElements()).isEqualTo(n(students.getStudentStats(), "trial"));
        // trial berilmasa — avvalgidek barcha ACTIVE
        assertThat(students.getAllStudents(0, 50, null, StudentStatus.ACTIVE, null).getTotalElements()).isEqualTo(5);
    }

    @Test
    void trialOverdue_inTrialListFirst_andOnStudentCard() {
        Setup s = setup();
        fixtures.loginAs(UserRole.ADMIN);

        List<Map<String, Object>> list = students.getTrialStudents();
        assertThat(list).extracting(m -> m.get("studentId"))
            .containsExactly(s.trialOverdue(), s.trialFuture(), s.trialAndPaid());   // muddati o'tgan tepada
        assertThat(list.get(0).get("trialOverdue")).isEqualTo(true);
        assertThat(list.get(1).get("trialOverdue")).isEqualTo(false);
        assertThat(list).noneMatch(m -> s.activeFrozenTrial().equals(m.get("studentId")));

        assertThat(students.getStudentById(s.trialOverdue()).getTrialOverdue()).isTrue();
        assertThat(students.getStudentById(s.trialFuture()).getTrialOverdue()).isFalse();
        assertThat(students.getStudentById(s.active()).getTrialOverdue()).isFalse();
    }

    private static Set<Long> ids(PageResponse<StudentResponse> page) {
        return page.getContent().stream().map(StudentResponse::getId).collect(Collectors.toSet());
    }
}
