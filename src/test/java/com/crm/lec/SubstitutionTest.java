package com.crm.lec;

import com.crm.dashboard.AttendanceMetricsService;
import com.crm.dashboard.DashboardPeriod;
import com.crm.dashboard.DirectorDtos.LessonRow;
import com.crm.dashboard.LessonCalendarService;
import com.crm.entity.LessonException;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5-bosqich: "darsni X o'tdi" (o'rinbosar), davomat ruxsati, billing o'zgarmasligi (D6), kabinet va
 * direktor dashboardi (§2). Davomatning "kelajak sana" tekshiruvi haqiqiy soat bo'yicha — shu sababli
 * davomat testlari bugungi (haqiqiy) sanadan foydalanadi.
 */
class SubstitutionTest extends LecItBase {

    private static final LocalDate MON = LocalDate.of(2026, 9, 21);
    private static final LocalDate WED = LocalDate.of(2026, 9, 23);

    @Autowired LessonCalendarService lessonCalendarService;
    @Autowired AttendanceMetricsService attendanceMetrics;

    private TeacherUser main;
    private TeacherUser sub;
    private Long group;
    private User admin;

    @BeforeEach
    void setUp() {
        main = newTeacher();
        sub = newTeacher();
        admin = newUser(UserRole.ADMIN);
        group = fixtures.group(fixtures.course(600_000, 50_000L), GroupStatus.ACTIVE, main.teacherId());
    }

    private ResultActions assign(User as, Long groupId, LocalDate date, Long substitute) throws Exception {
        return mvc.perform(post("/api/substitutions").with(as(as)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"groupId\":" + groupId + ",\"lessonDate\":\"" + date + "\",\"substituteTeacherId\":" + substitute + "}"));
    }

    private ResultActions mark(User as, LocalDate date, Long student) throws Exception {
        return mvc.perform(post("/api/attendance/mark").with(as(as)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"groupId\":" + group + ",\"date\":\"" + date + "\",\"attendances\":[{\"studentId\":" + student
                + ",\"status\":\"PRESENT\"}]}"));
    }

    @Test
    void assign_validations_sameTeacher_noLesson_timeConflict_duplicate_inactive() throws Exception {
        schedule(group, "14:00", "15:30", "MONDAY", "WEDNESDAY");
        Long subGroup = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, sub.teacherId());
        schedule(subGroup, "15:00", "16:30", "MONDAY");

        assign(admin, group, WED, main.teacherId())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.sameTeacher"));
        assign(admin, group, LocalDate.of(2026, 9, 22), sub.teacherId())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.noLesson"));
        assign(admin, group, MON, sub.teacherId())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("substitution.timeConflict"))
            .andExpect(jsonPath("$.data.groupId").value(subGroup.intValue()));
        assign(main.user(), group, WED, sub.teacherId()).andExpect(status().isForbidden());

        assign(admin, group, WED, sub.teacherId())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PLANNED"))
            .andExpect(jsonPath("$.data.originalTeacherId").value(main.teacherId().intValue()))
            .andExpect(jsonPath("$.data.startTime").value("14:00"));
        TeacherUser third = newTeacher();
        assign(admin, group, WED, third.teacherId())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("substitution.exists"));

        inTx(() -> {
            Teacher t = teacherRepository.findById(third.teacherId()).orElseThrow();
            t.setStatus(Teacher.STATUS_ON_LEAVE);
            teacherRepository.save(t);
        });
        assign(admin, group, LocalDate.of(2026, 9, 28), third.teacherId())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.teacherInactive"));

        // Ko'plikda — hammasi yoki hech biri
        mvc.perform(post("/api/substitutions/bulk").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"groupId\":" + group + ",\"lessonDate\":\"2026-09-30\",\"substituteTeacherId\":"
                    + sub.teacherId() + "},{\"groupId\":" + group + ",\"lessonDate\":\"2026-09-29\",\"substituteTeacherId\":"
                    + sub.teacherId() + "}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.noLesson"))
            .andExpect(jsonPath("$.data.index").value(1));
        mvc.perform(get("/api/substitutions").param("groupId", group.toString()).with(as(newUser(UserRole.ACCOUNTANT))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void attendance_substituteMarksOnlyThatDay_mainTeacherBlocked_conducted_billingUnchanged() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusWeeks(1);
        schedule(group, "09:00", "10:30", today.getDayOfWeek().name());
        Long student = fixtures.student();
        fixtures.enrollment(student, group).start(lastWeek.minusWeeks(1)).perLesson(50_000).save();

        long subId = data(assign(admin, group, today, sub.teacherId()).andExpect(status().isCreated()).andReturn())
            .get("id").asLong();

        mark(main.user(), today, student)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("substitution.lessonTaken"));
        mark(sub.user(), today, student).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM lesson_substitutions WHERE id = ?", String.class, subId))
            .isEqualTo("CONDUCTED");
        assertThat(jdbc.queryForObject("SELECT conducted_by FROM lesson_substitutions WHERE id = ?", Long.class, subId))
            .isEqualTo(sub.user().getId());

        // D6: dars uchun yechim va uning o'qituvchisi — guruh o'qituvchisi, summa o'zgarmaydi
        assertThat(jdbc.queryForObject(
            "SELECT teacher_id FROM balance_transactions WHERE type = 'LESSON_CHARGE' AND student_id = ?", Long.class, student))
            .isEqualTo(main.teacherId());
        assertThat(jdbc.queryForObject(
            "SELECT amount FROM balance_transactions WHERE type = 'LESSON_CHARGE' AND student_id = ?", BigDecimal.class, student))
            .isEqualByComparingTo("-50000");

        // O'qish: o'rinbosar — faqat o'sha kun; asosiy o'qituvchi — har doim
        mvc.perform(get("/api/attendance/group/" + group).param("date", today.toString()).with(as(sub.user())))
            .andExpect(status().isOk());
        mvc.perform(get("/api/attendance/group/" + group).param("date", lastWeek.toString()).with(as(sub.user())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/attendance/group/" + group).param("date", today.toString()).with(as(main.user())))
            .andExpect(status().isOk());
        // Boshqa kun (belgisiz) — o'rinbosar belgilay olmaydi
        mark(sub.user(), lastWeek, student).andExpect(status().isForbidden());
        // Admin — har doim
        mark(admin, lastWeek, student).andExpect(status().isOk());
    }

    @Test
    void pastDate_substituteNeedsUnlock_likeOwnTeacher() throws Exception {
        LocalDate lastWeek = LocalDate.now().minusWeeks(1);
        schedule(group, "09:00", "10:30", lastWeek.getDayOfWeek().name());
        Long student = fixtures.student();
        fixtures.enrollment(student, group).start(lastWeek.minusWeeks(1)).save();
        assign(admin, group, lastWeek, sub.teacherId()).andExpect(status().isCreated());

        mark(sub.user(), lastWeek, student).andExpect(status().isForbidden());
        long req = data(mvc.perform(post("/api/attendance/unlock-requests").with(as(sub.user()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":" + group + ",\"attendanceDate\":\"" + lastWeek + "\",\"note\":\"Kech qoldim\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.teacherId").value(sub.teacherId().intValue()))
            .andReturn()).get("id").asLong();
        // Asosiy o'qituvchi o'rinbosar kuni uchun ochish so'ray olmaydi
        mvc.perform(post("/api/attendance/unlock-requests").with(as(main.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":" + group + ",\"attendanceDate\":\"" + lastWeek + "\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/attendance/unlock-requests/" + req + "/approve").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        mark(sub.user(), lastWeek, student).andExpect(status().isOk());
    }

    @Test
    void assignAfterAttendance_isConducted_cancelAndLessonExceptionCancelPlanned() throws Exception {
        LocalDate today = LocalDate.now();
        schedule(group, "09:00", "10:30", today.getDayOfWeek().name(), today.plusDays(1).getDayOfWeek().name());
        Long student = fixtures.student();
        fixtures.enrollment(student, group).start(today.minusWeeks(2)).save();
        mark(admin, today, student).andExpect(status().isOk());

        // Dars o'tib bo'lgan — belgi darhol CONDUCTED
        long conducted = data(assign(admin, group, today, sub.teacherId())
            .andExpect(jsonPath("$.data.status").value("CONDUCTED")).andReturn()).get("id").asLong();
        mvc.perform(post("/api/substitutions/" + conducted + "/cancel").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Xato belgi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post("/api/substitutions/" + conducted + "/cancel").with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("substitution.alreadyCancelled"));

        // Ertangi dars bekor qilindi (LessonException) — o'tilmagan belgi ham bekor
        LocalDate tomorrow = today.plusDays(1);
        long planned = data(assign(admin, group, tomorrow, sub.teacherId()).andReturn()).get("id").asLong();
        loginAs(admin);
        lessonCalendarService.addException(group, new LessonCalendarService.ExceptionRequest(
            tomorrow, LessonException.Kind.CANCELLED, null, "Bayram arafasi"));
        assertThat(jdbc.queryForObject("SELECT status FROM lesson_substitutions WHERE id = ?", String.class, planned))
            .isEqualTo("CANCELLED");
    }

    @Test
    void teacherCabinet_andDirectorDashboard_attributeLessonToSubstitute() throws Exception {
        LocalDate today = LocalDate.now();
        schedule(group, "11:00", "12:30", today.getDayOfWeek().name());
        Long student = fixtures.student();
        fixtures.enrollment(student, group).start(today.minusWeeks(2)).save();
        assign(admin, group, today, sub.teacherId()).andExpect(status().isCreated());

        mvc.perform(get("/api/teacher/dashboard").with(as(sub.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayLessons[0].groupId").value(group.intValue()))
            .andExpect(jsonPath("$.data.todayLessons[0].substitute").value(true))
            .andExpect(jsonPath("$.data.todayLessons[0].startTime").value("11:00"));
        mvc.perform(get("/api/teacher/dashboard").with(as(main.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.todayLessons[*].groupId", not(hasItem(group.intValue()))));
        mvc.perform(get("/api/substitutions/my").with(as(sub.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));

        DashboardPeriod day = DashboardPeriod.of("DAY", today, null, null, today.atTime(23, 0));
        List<LessonRow> forSub = attendanceMetrics.rows(day, sub.teacherId(), "PLANNED");
        assertThat(forSub).extracting(LessonRow::groupId).containsExactly(group);
        assertThat(forSub.get(0).teacherId()).isEqualTo(sub.teacherId());
        assertThat(attendanceMetrics.rows(day, main.teacherId(), "PLANNED")).isEmpty();
    }

    @Test
    void leaveCancel_cancelsLinkedPlannedSubstitutions() throws Exception {
        schedule(group, "14:00", "15:30", "MONDAY", "WEDNESDAY");
        User sa = newUser(UserRole.SUPER_ADMIN);
        JsonNode leave = data(mvc.perform(post("/api/leaves").with(as(main.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaveType\":\"SICK\",\"fromDate\":\"2026-09-21\",\"toDate\":\"2026-09-23\"}"))
            .andExpect(status().isCreated()).andReturn());
        long leaveId = leave.get("id").asLong();
        mvc.perform(post("/api/leaves/" + leaveId + "/approve").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paid\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.affectedLessons.length()").value(2));
        long s = data(mvc.perform(post("/api/substitutions").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":" + group + ",\"lessonDate\":\"2026-09-23\",\"substituteTeacherId\":" + sub.teacherId()
                    + ",\"leaveRequestId\":" + leaveId + "}"))
            .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(get("/api/leaves/" + leaveId + "/affected-lessons").with(as(admin)))
            .andExpect(jsonPath("$.data[1].substitutionId").value((int) s))
            .andExpect(jsonPath("$.data[1].substituteTeacherId").value(sub.teacherId().intValue()));

        mvc.perform(post("/api/leaves/" + leaveId + "/cancel").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Ishga chiqdi\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM lesson_substitutions WHERE id = ?", String.class, s))
            .isEqualTo("CANCELLED");
    }
}
