package com.crm.miniapp;

import com.crm.entity.GroupScheduleDay;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.LessonSubstitutionRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sabab bildirish (docs/design/telegram-platform.md §11.2). Bugun — 2026-09-15 (seshanba) 12:00; guruh
 * dushanba / chorshanba / juma 18:30–20:00.
 */
class MiniAppAbsenceTest extends MiniAppItBase {

    private static final long PARENT_TG = 1201;
    private static final long TEACHER_TG = 1202;

    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private LessonSubstitutionRepository substitutionRepository;

    private Long studentId;
    private Long groupId;
    private Long teacherId;
    private User teacherUser;
    private String parentToken;

    @BeforeEach
    void setUp() throws Exception {
        String parentPhone = phone();
        studentId = student("Ali", "Karimov", phone(), parentPhone);
        String teacherPhone = phone();
        teacherUser = staff(UserRole.TEACHER, teacherPhone);
        teacherId = teacherProfile(teacherUser);
        groupId = lessonGroup(teacherId);
        fixtures.enrollment(studentId, groupId).start(d("01.09.2026")).save();

        shareContact(PARENT_TG, parentPhone);
        shareContact(TEACHER_TG, teacherPhone);       // o'qituvchi rejimi
        parentToken = appToken(PARENT_TG);
        botApi.reset();
    }

    private Long lessonGroup(Long teacher) {
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher);
        inTx(() -> {
            for (String day : new String[]{"MONDAY", "WEDNESDAY", "FRIDAY"}) {
                scheduleDayRepository.save(GroupScheduleDay.builder()
                    .group(groupRepository.findById(g).orElseThrow())
                    .dayOfWeek(day).startTime("18:30").endTime("20:00").build());
            }
        });
        return g;
    }

    private ResultActions notice(String token, Long student, Long group, String date, String type, String comment)
            throws Exception {
        return mvc.perform(post("/api/app/absence-notices").header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"studentId\":" + student + ",\"groupId\":" + group + ",\"lessonDate\":\"" + date
                + "\",\"type\":\"" + type + "\"" + (comment != null ? ",\"comment\":" + json(comment) : "") + "}"));
    }

    @Test
    void create_notifiesTeacher_andShowsInCrmAttendance() throws Exception {
        notice(parentToken, studentId, groupId, "2026-09-16", "ABSENT", "Kasal <isitma>")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.type").value("ABSENT"))
            .andExpect(jsonPath("$.data.canCancel").value(true));

        // O'qituvchiga — HIGH, darhol, 12:00 da ovozli
        assertThat(outboxFor(TEACHER_TG)).singleElement().satisfies(o -> {
            assertThat(o.getPriority()).isEqualTo(TelegramOutbox.Priority.HIGH);
            assertThat(o.getSilent()).isFalse();
            assertThat(o.getText()).contains("Ali Karimov").contains("16.09.2026 18:30").contains("Kelmaydi")
                .contains("Kasal &lt;isitma&gt;");
        });
        assertThat(outboxWorker.runOnce()).isEqualTo(1);
        assertThat(botApi.sent).singleElement().satisfies(s -> assertThat(s.chatId()).isEqualTo(TEACHER_TG));

        // CRM: o'qituvchi ro'yxati va davomat qatori
        mvc.perform(get("/api/absence-notices").param("groupId", groupId.toString()).param("date", "2026-09-16")
                .with(as(teacherUser)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].studentName").value("Ali Karimov"))
            .andExpect(jsonPath("$.data[0].submittedAs").value("PARENT"));
        mvc.perform(get("/api/attendance/group/{id}", groupId).param("date", "2026-09-16").with(as(teacherUser)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].studentId").value(studentId))
            .andExpect(jsonPath("$.data[0].absenceNotice.type").value("ABSENT"))
            .andExpect(jsonPath("$.data[0].absenceNotice.comment").value("Kasal <isitma>"));
    }

    @Test
    void validation_dateWindow_lessonDay_type_duplicate() throws Exception {
        notice(parentToken, studentId, groupId, "2026-09-15", "LATE", null)            // seshanba — dars yo'q
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.noLesson"));
        notice(parentToken, studentId, groupId, "2026-09-14", "LATE", null)            // o'tgan kun
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.dateOutOfRange"));
        notice(parentToken, studentId, groupId, "2026-10-02", "LATE", null)            // > 14 kun
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.dateOutOfRange"));
        notice(parentToken, studentId, groupId, "2026-09-16", "OTHER", " ")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.commentRequired"));
        notice(parentToken, studentId, groupId, "2026-09-16", "SICK", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.typeInvalid"));

        notice(parentToken, studentId, groupId, "2026-09-28", "LATE", null).andExpect(status().isOk());   // 13-kun (dushanba)
        notice(parentToken, studentId, groupId, "2026-09-30", "LATE", null)                                // 15-kun
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.dateOutOfRange"));
        notice(parentToken, studentId, groupId, "2026-09-28", "ABSENT", null)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("app.absence.duplicate"));
    }

    @Test
    void idor_foreignStudentAndGroup_403() throws Exception {
        Long foreignStudent = student("Begona", "Bola", phone(), null);
        Long otherGroup = lessonGroup(fixtures.teacher());
        fixtures.enrollment(foreignStudent, otherGroup).start(d("01.09.2026")).save();

        notice(parentToken, foreignStudent, otherGroup, "2026-09-16", "ABSENT", null)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.student.forbidden"));
        // O'z o'quvchisi, lekin u o'qimaydigan guruh
        notice(parentToken, studentId, otherGroup, "2026-09-16", "ABSENT", null)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.group.forbidden"));
        mvc.perform(get("/api/app/absence-notices").param("studentId", foreignStudent.toString())
                .header("Authorization", bearer(parentToken)))
            .andExpect(status().isForbidden());

        // CRM: begona o'qituvchi
        User otherTeacher = staff(UserRole.TEACHER, null);
        teacherProfile(otherTeacher);
        mvc.perform(get("/api/absence-notices").param("groupId", groupId.toString()).with(as(otherTeacher)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/absence-notices").param("groupId", groupId.toString())
                .with(as(staff(UserRole.SALES_MANAGER, null))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/absence-notices").param("groupId", groupId.toString())
                .with(as(staff(UserRole.ADMIN, null))))
            .andExpect(status().isOk());
    }

    @Test
    void listAndCancel_ownOnly() throws Exception {
        String body = notice(parentToken, studentId, groupId, "2026-09-18", "LATE", "Tirbandlik")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        mvc.perform(get("/api/app/absence-notices").header("Authorization", bearer(parentToken)))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].groupName").isString());

        // Boshqa identity (o'quvchining o'zi) — bekor qila olmaydi
        String selfPhone = inTx(() -> studentRepository.findById(studentId).orElseThrow().getPhone());
        shareContact(1203, selfPhone);
        String selfToken = appToken(1203);
        mvc.perform(post("/api/app/absence-notices/{id}/cancel", id).header("Authorization", bearer(selfToken)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.absence.forbidden"));
        mvc.perform(get("/api/app/absence-notices").header("Authorization", bearer(selfToken)))
            .andExpect(jsonPath("$.data", hasSize(0)));

        mvc.perform(post("/api/app/absence-notices/{id}/cancel", id).header("Authorization", bearer(parentToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.canCancel").value(false));
        mvc.perform(post("/api/app/absence-notices/{id}/cancel", id).header("Authorization", bearer(parentToken)))
            .andExpect(status().isConflict());
        // Bekor qilingandan keyin qayta yuborish mumkin
        notice(parentToken, studentId, groupId, "2026-09-18", "ABSENT", null).andExpect(status().isOk());
    }

    @Test
    void substitute_alsoNotified_quietHoursSilent() throws Exception {
        String subPhone = phone();
        User subUser = staff(UserRole.TEACHER, subPhone);
        Long subTeacher = teacherProfile(subUser);
        shareContact(1204, subPhone);
        inTx(() -> substitutionRepository.save(LessonSubstitution.builder()
            .group(groupRepository.findById(groupId).orElseThrow())
            .lessonDate(d("16.09.2026"))
            .originalTeacher(teacherRepository.findById(teacherId).orElseThrow())
            .substituteTeacher(teacherRepository.findById(subTeacher).orElseThrow())
            .status(SubstitutionStatus.PLANNED)
            .createdAt(LocalDateTime.now())
            .build()));

        botApi.reset();
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 22, 30));       // sokin soat
        String token = appToken(PARENT_TG);
        notice(token, studentId, groupId, "2026-09-16", "ABSENT", null).andExpect(status().isOk());

        assertThat(outboxFor(TEACHER_TG)).singleElement().satisfies(o -> {
            assertThat(o.getSilent()).isTrue();
            assertThat(o.getNotBefore()).isEqualTo(LocalDateTime.of(2026, 9, 15, 22, 30));
        });
        assertThat(outboxFor(1204)).hasSize(1);
        assertThat(outboxWorker.runOnce()).isEqualTo(2);
        assertThat(botApi.sent).allSatisfy(s -> assertThat(s.silent()).isTrue());
    }

    @Test
    void todayLessonEnded_rejected() throws Exception {
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 20, 30));       // chorshanba, dars 20:00 da tugagan
        String token = appToken(PARENT_TG);
        notice(token, studentId, groupId, "2026-09-16", "LATE", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.absence.lessonPassed"));
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 19, 0));        // dars davom etmoqda — ruxsat
        notice(appToken(PARENT_TG), studentId, groupId, "2026-09-16", "LATE", null).andExpect(status().isOk());
        assertThat(LocalDate.of(2026, 9, 16).getDayOfWeek().name()).isEqualTo("WEDNESDAY");
    }
}
