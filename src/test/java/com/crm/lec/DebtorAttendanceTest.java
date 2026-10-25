package com.crm.lec;

import com.crm.billing.DebtorService;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.Attendance;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qarzdor belgisi davomatda (o'qituvchi qarzdorni ko'radi, summasiz; admin — summa bilan) va qarzdorlar ro'yxatida
 * bugungi davomat holati ({@code todayStatus}, {@code lastAttendedOn}, {@code ?today=}). Ta'rif — DebtorService.
 */
class DebtorAttendanceTest extends LecItBase {

    /** AttendanceService "bugun"ni tizim soatidan oladi — test ham haqiqiy bugungi sanada (g1 — bugun dars, g2 — yo'q). */
    private static final LocalDate DAY = LocalDate.now(java.time.ZoneId.of("Asia/Tashkent"));

    @Autowired DebtorService debtorService;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired StudentRepository studentRepository;

    private TeacherUser teacher;
    private User admin;
    private Long g1;          // o'qituvchining guruhi, bugun dars bor
    private Long g2;          // boshqa guruh, bugun dars yo'q
    private Long a;           // g1 da qarzdor
    private Long b;           // g1 da qarzsiz, g2 da qarzdor
    private Long c;           // qarzsiz

    @BeforeEach
    void setUp() {
        clock.setDate(DAY);
        teacher = newTeacher();
        admin = newUser(UserRole.ADMIN);
        g1 = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher.teacherId());
        g2 = fixtures.group(fixtures.course(500_000));
        schedule(g1, "10:00", "11:30", DAY.getDayOfWeek().name());
        schedule(g2, "10:00", "11:30", DAY.plusDays(1).getDayOfWeek().name());
        a = fixtures.student();
        b = fixtures.student();
        c = fixtures.student();
        debt(fixtures.enrollment(a, g1).start(DAY.minusDays(40)).save(), 300_000, DAY.minusDays(10));
        fixtures.enrollment(b, g1).start(DAY.minusDays(40)).save();
        debt(fixtures.enrollment(b, g2).start(DAY.minusDays(40)).save(), 200_000, DAY.minusDays(5));
        fixtures.enrollment(c, g1).start(DAY.minusDays(40)).save();
    }

    /** Snapshot (DebtorService shu maydonlardan o'qiydi): balans va qarz boshlangan sana. */
    private void debt(Long sgId, long amount, LocalDate since) {
        jdbc.update("UPDATE student_groups SET balance = ?, debt_since = ? WHERE id = ?", -amount, since, sgId);
    }

    private void attended(Long student, Long group, LocalDate date, AttendanceStatus st) {
        inTx(() -> attendanceRepository.save(Attendance.builder()
            .student(studentRepository.findById(student).orElseThrow())
            .group(groupRepository.findById(group).orElseThrow())
            .attendanceDate(date)
            .status(st)
            .build()));
    }

    // ── 1. Davomat ro'yxatida qarzdor belgisi ───────────────────────────

    @Test
    void groupAttendance_teacherSeesDebtorFlags_withoutAmounts_adminWithAmounts() throws Exception {
        String url = "/api/attendance/group/" + g1;
        var teacherJson = data(mvc.perform(get(url).param("date", DAY.toString()).with(as(teacher.user())))
            .andExpect(status().isOk()).andReturn());
        Map<Long, com.fasterxml.jackson.databind.JsonNode> rows = new java.util.HashMap<>();
        teacherJson.forEach(n -> rows.put(n.get("studentId").asLong(), n));
        assertThat(rows).hasSize(3);
        // A — shu guruhda qarzdor: 10 kun, summa o'qituvchiga yo'q
        assertThat(rows.get(a).get("debtor").asBoolean()).isTrue();
        assertThat(rows.get(a).get("debtDays").asLong()).isEqualTo(10);
        assertThat(rows.get(a).get("debtSince").asText()).isEqualTo(DAY.minusDays(10).toString());
        assertThat(rows.get(a).get("debtAmount").isNull()).isTrue();
        assertThat(rows.get(a).get("studentDebtAmount").isNull()).isTrue();
        assertThat(rows.get(a).get("otherGroupDebt").asBoolean()).isFalse();
        // B — shu guruhda qarzsiz, boshqa guruhda qarzdor
        assertThat(rows.get(b).get("debtor").asBoolean()).isFalse();
        assertThat(rows.get(b).get("studentDebtor").asBoolean()).isTrue();
        assertThat(rows.get(b).get("studentDebtDays").asLong()).isEqualTo(5);
        assertThat(rows.get(b).get("otherGroupDebt").asBoolean()).isTrue();
        assertThat(rows.get(b).get("debtDays").isNull()).isTrue();
        // C — qarzsiz
        assertThat(rows.get(c).get("debtor").asBoolean()).isFalse();
        assertThat(rows.get(c).get("studentDebtor").asBoolean()).isFalse();

        // Admin — summa bilan
        mvc.perform(get(url).param("date", DAY.toString()).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.studentId == " + a + ")].debtAmount").value(300000.0))
            .andExpect(jsonPath("$.data[?(@.studentId == " + b + ")].studentDebtAmount").value(200000.0))
            .andExpect(jsonPath("$.data[?(@.studentId == " + c + ")].debtAmount").value(org.hamcrest.Matchers.contains(
                org.hamcrest.Matchers.nullValue())));

        // Belgilash javobi (o'qituvchi) — ham belgi bilan, summasiz; oldingi kun jurnali (sana) — bugungi holat
        String body = "{\"groupId\":" + g1 + ",\"date\":\"" + DAY + "\",\"attendances\":["
            + "{\"studentId\":" + a + ",\"status\":\"PRESENT\"},"
            + "{\"studentId\":" + b + ",\"status\":\"PRESENT\"},"
            + "{\"studentId\":" + c + ",\"status\":\"PRESENT\"}]}";
        mvc.perform(post("/api/attendance/mark").with(as(teacher.user()))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.studentId == " + a + ")].debtor").value(true))
            .andExpect(jsonPath("$.data[?(@.studentId == " + a + ")].debtAmount").value(org.hamcrest.Matchers.contains(
                org.hamcrest.Matchers.nullValue())));
        mvc.perform(get(url).param("date", DAY.minusDays(7).toString()).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.studentId == " + a + ")].debtor").value(true));
    }

    @Test
    void debtFlags_singleDefinition_matchesDebtorsList() {
        loginAs(admin);
        Map<Long, DebtorService.DebtFlag> flags = debtorService.debtFlags(g1, List.of(a, b, c), DAY);
        DebtorsListResponse list = debtorService.debtors(DebtorService.Filter.defaults(), DAY);
        assertThat(list.getStudents()).extracting(DebtorsListResponse.DebtorStudent::getStudentId)
            .containsExactlyInAnyOrder(a, b);
        assertThat(flags.keySet()).containsExactlyInAnyOrder(a, b);
        for (DebtorsListResponse.DebtorStudent s : list.getStudents()) {
            DebtorService.DebtFlag f = flags.get(s.getStudentId());
            assertThat(f.studentDebtor()).isTrue();
            assertThat(f.studentDebtSince()).isEqualTo(s.getDebtSince());
            assertThat(f.studentDebtDays()).isEqualTo(s.getDaysOverdue());
            assertThat(f.studentDebtAmount()).isEqualByComparingTo(s.getDebt());
        }
    }

    // ── 2. Qarzdorlar ro'yxati: bugungi holat ───────────────────────────

    @Test
    void debtorsList_todayStatus_allStates_lastAttendedOn_andFilter() throws Exception {
        Long d = fixtures.student();
        Long e = fixtures.student();
        Long f = fixtures.student();
        Long g = fixtures.student();
        for (Long s : List.of(d, e, f, g)) {
            debt(fixtures.enrollment(s, g1).start(DAY.minusDays(40)).save(), 100_000, DAY.minusDays(3));
        }
        attended(a, g1, DAY.minusDays(7), AttendanceStatus.PRESENT);
        attended(a, g1, DAY, AttendanceStatus.PRESENT);
        attended(d, g1, DAY, AttendanceStatus.LATE);
        attended(e, g1, DAY, AttendanceStatus.ABSENT);
        attended(e, g1, DAY.minusDays(14), AttendanceStatus.LATE);
        attended(f, g1, DAY, AttendanceStatus.EXCUSED);
        attended(b, g2, DAY.minusDays(8), AttendanceStatus.PRESENT);
        // g — g1 da bugun dars bor, davomat yo'q; b — g2 da bugun dars yo'q

        loginAs(admin);
        Map<Long, DebtorsListResponse.DebtorStudent> byId = debtorService.debtors(DebtorService.Filter.defaults(), DAY)
            .getStudents().stream().collect(Collectors.toMap(DebtorsListResponse.DebtorStudent::getStudentId, s -> s));
        assertThat(byId.get(a).getTodayStatus()).isEqualTo("PRESENT");
        assertThat(byId.get(d).getTodayStatus()).isEqualTo("LATE");
        assertThat(byId.get(e).getTodayStatus()).isEqualTo("ABSENT");
        assertThat(byId.get(f).getTodayStatus()).isEqualTo("EXCUSED");
        assertThat(byId.get(g).getTodayStatus()).isEqualTo("NOT_MARKED");
        assertThat(byId.get(b).getTodayStatus()).isEqualTo("NO_LESSON");
        assertThat(byId.get(a).getLastAttendedOn()).isEqualTo(DAY);
        assertThat(byId.get(e).getLastAttendedOn()).isEqualTo(DAY.minusDays(14));     // ABSENT — kelgan emas
        assertThat(byId.get(b).getLastAttendedOn()).isEqualTo(DAY.minusDays(8));
        assertThat(byId.get(g).getLastAttendedOn()).isNull();
        assertThat(byId.get(a).getGroups()).singleElement().satisfies(gr -> {
            assertThat(gr.getTodayStatus()).isEqualTo("PRESENT");
            assertThat(gr.getLastAttendedOn()).isEqualTo(DAY);
        });

        // Filtr: bugun kelgan qarzdorlar (PRESENT + LATE); aniq holat; noto'g'ri — 400
        DebtorsListResponse present = debtorService.debtors(
            new DebtorService.Filter(DebtorService.Scope.ACTIVE, null, null, null, null, "present"), DAY);
        assertThat(present.getStudents()).extracting(DebtorsListResponse.DebtorStudent::getStudentId)
            .containsExactlyInAnyOrder(a, d);
        assertThat(present.getTotalDebtors()).isEqualTo(2);
        assertThat(debtorService.debtors(
                new DebtorService.Filter(DebtorService.Scope.ACTIVE, null, null, null, null, "NOT_MARKED"), DAY)
            .getStudents()).extracting(DebtorsListResponse.DebtorStudent::getStudentId).containsExactly(g);
        assertThatThrownBy(() -> debtorService.debtors(
                new DebtorService.Filter(DebtorService.Scope.ACTIVE, null, null, null, null, "kecha"), DAY))
            .isInstanceOfSatisfying(CodedException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("debtors.today.invalid"));

        mvc.perform(get("/api/payments/debtors").param("today", "present").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalDebtors").value(2))
            .andExpect(jsonPath("$.data.students[*].todayStatus").value(org.hamcrest.Matchers.containsInAnyOrder(
                "PRESENT", "LATE")));
        mvc.perform(get("/api/payments/debtors").param("today", "x").with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("debtors.today.invalid"));
    }
}
