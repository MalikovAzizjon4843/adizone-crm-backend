package com.crm.security;

import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.TeacherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T-01 (PUT — PATCH semantikasi), T-03 (faol guruhli o'qituvchini nofaol qilish → 409), T-06 (ro'yxat). */
class TeacherListPatchStatusTest extends Phase5ItBase {

    @Autowired
    TeacherRepository teacherRepository;

    private Teacher teacher(Long id) {
        return inTx(() -> teacherRepository.findById(id).orElseThrow());
    }

    private Long teacherWith(String lastName, String status) {
        Long id = fixtures.teacher();
        jdbc.update("UPDATE teachers SET last_name = ?, status = ?, is_active = ?, monthly_salary = 4000000,"
                + " joining_date = ?, notes = 'izoh' WHERE id = ?",
            lastName, status, !"INACTIVE".equals(status), LocalDate.of(2025, 9, 1), id);
        return id;
    }

    // ── T-01 ───────────────────────────────────────────────────────────

    @Test
    void put_onlySentFieldsChange() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        Long id = teacherWith("Karimov", "ACTIVE");

        // Eski frontend kabi: monthlySalary, joiningDate yuborilmaydi
        mvc.perform(put("/api/teachers/" + id).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Yangi\",\"notes\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.firstName").value("Yangi"))
            .andExpect(jsonPath("$.data.lastName").value("Karimov"));
        Teacher t = teacher(id);
        assertThat(t.getMonthlySalary()).isEqualByComparingTo(new BigDecimal("4000000"));
        assertThat(t.getJoiningDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(t.getNotes()).isNull();          // "" — tozalash
        assertThat(t.getStatus()).isEqualTo("ACTIVE");

        // Majburiy maydon yuborilsa — bo'sh bo'lmasin
        mvc.perform(put("/api/teachers/" + id).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"   \"}"))
            .andExpect(status().isBadRequest());
        assertThat(teacher(id).getLastName()).isEqualTo("Karimov");
    }

    // ── T-03 ───────────────────────────────────────────────────────────

    @Test
    void deactivate_withActiveGroups_409_listedInData_thenAllowedAfterReassign() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        Long id = teacherWith("Guruhli", "ACTIVE");
        Long course = fixtures.course(700_000);
        Long active = fixtures.group(course, GroupStatus.ACTIVE, id);
        Long forming = fixtures.group(course, GroupStatus.FORMING, id);
        fixtures.group(course, GroupStatus.COMPLETED, id);

        mvc.perform(delete("/api/teachers/" + id).with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("teacher.hasActiveGroups"))
            .andExpect(jsonPath("$.data.teacherId").value(id.intValue()))
            .andExpect(jsonPath("$.data.groups.length()").value(2))
            .andExpect(jsonPath("$.data.groups[0].id").value(active.intValue()))
            .andExpect(jsonPath("$.data.groups[1].id").value(forming.intValue()));
        mvc.perform(put("/api/teachers/" + id).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\",\"firstName\":\"Ozgarmasin\"}"))
            .andExpect(status().isConflict());
        Teacher still = teacher(id);
        assertThat(still.getStatus()).isEqualTo("ACTIVE");
        assertThat(still.getFirstName()).isNotEqualTo("Ozgarmasin");   // butun PUT rollback

        // Ta'til — guruhlar bilan ham mumkin
        mvc.perform(put("/api/teachers/" + id).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ON_LEAVE\"}"))
            .andExpect(status().isOk());

        // Guruhlar boshqa o'qituvchiga o'tgach — nofaol qilinadi
        Long other = fixtures.teacher();
        jdbc.update("UPDATE groups SET teacher_id = ? WHERE id IN (?, ?)", other, active, forming);
        mvc.perform(delete("/api/teachers/" + id).with(as(admin)))
            .andExpect(status().isOk());
        assertThat(teacher(id).getStatus()).isEqualTo("INACTIVE");
    }

    // ── T-06 ───────────────────────────────────────────────────────────

    @Test
    void list_legacyListWithoutPage_pagedWithPage_filters() throws Exception {
        User accountant = newUser(UserRole.ACCOUNTANT);
        String tag = "Tq" + UUID.randomUUID().toString().substring(0, 6);
        teacherWith(tag + "a", "ACTIVE");
        teacherWith(tag + "b", "ON_LEAVE");
        teacherWith(tag + "c", "INACTIVE");

        // page yo'q: eski shakl, activeOnly=true default → ACTIVE + ON_LEAVE
        mvc.perform(get("/api/teachers").param("q", tag).with(as(accountant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/teachers").param("q", tag).param("activeOnly", "false").with(as(accountant)))
            .andExpect(jsonPath("$.data.length()").value(3));

        // page bor: PageResponse; status berilsa activeOnly e'tiborsiz
        mvc.perform(get("/api/teachers").param("q", tag).param("status", "INACTIVE")
                .param("page", "0").param("size", "10").with(as(accountant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].lastName").value(tag + "c"));
        mvc.perform(get("/api/teachers").param("q", tag).param("activeOnly", "false")
                .param("page", "0").param("size", "2").with(as(accountant)))
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].lastName").value(tag + "a"));

        mvc.perform(get("/api/teachers").param("status", "NOPE").with(as(accountant)))
            .andExpect(status().isBadRequest());
    }
}
