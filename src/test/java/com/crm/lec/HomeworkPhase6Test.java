package com.crm.lec;

import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** phase6-api §4: uy vazifalari — CRUD, egalik, fayl, o'quvchilar bo'yicha holat/baho/izoh. */
class HomeworkPhase6Test extends LecItBase {

    private TeacherUser t1;
    private TeacherUser t2;
    private User admin;
    private Long g1;
    private Long g2;
    private Long a;
    private Long b;
    private Long outsider;

    @BeforeEach
    void setUp() {
        t1 = newTeacher();
        t2 = newTeacher();
        admin = newUser(UserRole.ADMIN);
        g1 = fixtures.group(fixtures.course(600_000), GroupStatus.ACTIVE, t1.teacherId());
        g2 = fixtures.group(fixtures.course(600_000), GroupStatus.ACTIVE, t2.teacherId());
        a = fixtures.student();
        b = fixtures.student();
        outsider = fixtures.student();
        fixtures.enrollment(a, g1).save();
        fixtures.enrollment(b, g1).save();
        fixtures.enrollment(outsider, g2).save();
    }

    private ResultActions create(User as, String body) throws Exception {
        return mvc.perform(post("/api/homework").with(as(as)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createOk(User as, Long groupId) throws Exception {
        return data(create(as, "{\"title\":\"1-dars mashqlari\",\"groupId\":" + groupId
                + ",\"assignedDate\":\"2026-09-15\",\"dueDate\":\"2026-09-18\",\"marks\":10,"
                + "\"attachmentUrl\":\"/api/files/abc.pdf\",\"attachmentName\":\"mashq.pdf\"}")
            .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private ResultActions grade(User as, long hw, String items) throws Exception {
        return mvc.perform(put("/api/homework/" + hw + "/students").with(as(as)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[" + items + "]}"));
    }

    @Test
    void create_validation_ownership_attachment_listFilters() throws Exception {
        create(t1.user(), "{\"title\":\"X\",\"dueDate\":\"2026-09-20\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.group.required"));
        create(t1.user(), "{\"title\":\"X\",\"groupId\":" + g2 + ",\"dueDate\":\"2026-09-20\"}")
            .andExpect(status().isForbidden());
        create(t1.user(), "{\"title\":\"X\",\"groupId\":" + g1 + ",\"dueDate\":\"2026-09-20\",\"attachmentUrl\":\"https://evil.uz/a.pdf\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.attachment.invalid"));
        create(t1.user(), "{\"title\":\"X\",\"groupId\":" + g1 + ",\"assignedDate\":\"2026-09-15\",\"dueDate\":\"2026-09-10\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.dates.invalid"));

        long hw = createOk(t1.user(), g1);
        mvc.perform(get("/api/homework/" + hw).with(as(t1.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.teacherId").value(t1.teacherId().intValue()))
            .andExpect(jsonPath("$.data.attachmentUrl").value("/api/files/abc.pdf"))
            .andExpect(jsonPath("$.data.attachmentName").value("mashq.pdf"));
        createOk(admin, g2);

        mvc.perform(get("/api/homework").param("groupId", g1.toString()).with(as(t1.user())))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/homework").with(as(t1.user()))).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/homework").with(as(admin))).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/homework").param("from", "2026-09-19").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/homework").param("groupId", g1.toString()).with(as(t2.user())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/homework/" + hw).with(as(t2.user()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/homework").with(as(newUser(UserRole.ACCOUNTANT)))).andExpect(status().isForbidden());

        // O'chirish — faqat SA/A (soft); keyin ro'yxatda yo'q, holat sahifasi 404
        mvc.perform(delete("/api/homework/" + hw).with(as(t1.user()))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/homework/" + hw).with(as(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/homework/" + hw + "/students").with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void roster_grade_upsert_validation_atomic_andOwnership() throws Exception {
        long hw = createOk(t1.user(), g1);
        mvc.perform(get("/api/homework/" + hw + "/students").with(as(t1.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.summary.total").value(2))
            .andExpect(jsonPath("$.data.summary.notSubmitted").value(2))
            .andExpect(jsonPath("$.data.students[0].status").value("NOT_SUBMITTED"))
            .andExpect(jsonPath("$.data.students[0].inGroup").value(true));

        JsonNode r = data(grade(t1.user(), hw, "{\"studentId\":" + a + ",\"status\":\"SUBMITTED\",\"marksObtained\":8,"
                + "\"remarks\":\"Yaxshi\"},{\"studentId\":" + b + ",\"status\":\"late\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.summary.submitted").value(1))
            .andExpect(jsonPath("$.data.summary.late").value(1))
            .andExpect(jsonPath("$.data.summary.notSubmitted").value(0))
            .andExpect(jsonPath("$.data.summary.graded").value(1))
            .andReturn());
        assertThat(r.get("summary").get("averageMark").decimalValue()).isEqualByComparingTo("8");
        // Upsert: shu o'quvchi qayta — yangi yozuv emas
        grade(t1.user(), hw, "{\"studentId\":" + a + ",\"status\":\"SUBMITTED\",\"marksObtained\":9}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.total").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM homework_submissions WHERE homework_id = ?", Integer.class, hw))
            .isEqualTo(2);

        grade(t1.user(), hw, "{\"studentId\":" + a + ",\"status\":\"SUBMITTED\",\"marksObtained\":11}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.mark.range"))
            .andExpect(jsonPath("$.data.index").value(0));
        grade(t1.user(), hw, "{\"studentId\":" + b + ",\"status\":\"NOT_SUBMITTED\",\"marksObtained\":3}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.mark.notSubmitted"));
        grade(t1.user(), hw, "{\"studentId\":" + b + ",\"status\":\"DONE\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.status.invalid"));
        grade(t1.user(), hw, "{\"studentId\":" + a + ",\"status\":\"LATE\"},{\"studentId\":" + a + ",\"status\":\"LATE\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.student.duplicate"));
        // Hammasi yoki hech biri: 1-element to'g'ri, 2-chisi — guruhdan tashqari
        grade(t1.user(), hw, "{\"studentId\":" + a + ",\"status\":\"LATE\"},{\"studentId\":" + outsider
                + ",\"status\":\"SUBMITTED\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("homework.student.notInGroup"))
            .andExpect(jsonPath("$.data.index").value(1));
        assertThat(jdbc.queryForObject("SELECT status FROM homework_submissions WHERE homework_id = ? AND student_id = ?",
            String.class, hw, a)).isEqualTo("SUBMITTED");

        // Begona o'qituvchi
        grade(t2.user(), hw, "{\"studentId\":" + a + ",\"status\":\"LATE\"}").andExpect(status().isForbidden());
        long subId = jdbc.queryForObject("SELECT id FROM homework_submissions WHERE homework_id = ? AND student_id = ?",
            Long.class, hw, a);
        mvc.perform(put("/api/homework/submissions/" + subId).with(as(t2.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + a + ",\"marksObtained\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/homework/" + hw + "/submissions").with(as(t2.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + b + "}"))
            .andExpect(status().isForbidden());
        // Admin — hammasi
        grade(admin, hw, "{\"studentId\":" + b + ",\"status\":\"NOT_SUBMITTED\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.notSubmitted").value(1));
    }
}
