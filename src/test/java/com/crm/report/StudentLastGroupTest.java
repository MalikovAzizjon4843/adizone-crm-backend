package com.crm.report;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.response.LeftStudentResponse;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.service.GroupService;
import com.crm.service.StudentRosterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O'quvchilar ro'yxati: joriy guruhi yo'qlarga oxirgi guruh (o'qituvchi bilan), sana, sabab; ?lastTeacherId=. */
class StudentLastGroupTest extends AbstractBillingIT {

    @Autowired GroupService groups;
    @Autowired StudentRosterService roster;

    private void leave(Long student, Long group, String date, ExitReasonCode code) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ADMIN);
        groups.removeStudentFromGroup(group, student, "LEFT", null, code);
    }

    @Test
    void list_lastGroup_teacher_reason_andFilter() throws Exception {
        Long t1 = fixtures.teacher();
        Long t2 = fixtures.teacher();
        Long g1 = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t1);
        Long g2 = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t2);

        Long left = fixtures.student();                      // faqat G1 (T1) da edi, PRICE bilan ketdi
        fixtures.enrollment(left, g1).start(d("01.09.2026")).save();
        Long moved = fixtures.student();                     // avval G1 (T1), oxirgisi G2 (T2)
        fixtures.enrollment(moved, g1).start(d("01.09.2026")).save();
        fixtures.enrollment(moved, g2).start(d("01.09.2026")).save();
        Long studying = fixtures.student();                  // hozir G1 da
        fixtures.enrollment(studying, g1).start(d("01.09.2026")).save();

        leave(left, g1, "10.10.2026", ExitReasonCode.PRICE);
        leave(moved, g1, "05.10.2026", ExitReasonCode.SCHEDULE);
        leave(moved, g2, "12.10.2026", ExitReasonCode.NO_TIME);

        mvc.perform(get("/api/students").param("size", "50").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.id == " + left + ")].lastGroup.id").value(g1.intValue()))
            .andExpect(jsonPath("$.data.content[?(@.id == " + left + ")].lastGroup.teacherId").value(t1.intValue()))
            .andExpect(jsonPath("$.data.content[?(@.id == " + left + ")].leftAt").value("2026-10-10"))
            .andExpect(jsonPath("$.data.content[?(@.id == " + left + ")].exitReasonCode").value("PRICE"))
            .andExpect(jsonPath("$.data.content[?(@.id == " + left + ")].exitReasonLabel")
                .value(ExitReasonCode.PRICE.getLabel()))
            .andExpect(jsonPath("$.data.content[?(@.id == " + moved + ")].lastGroup.id").value(g2.intValue()))
            .andExpect(jsonPath("$.data.content[?(@.id == " + studying + ")].currentGroupId").value(g1.intValue()))
            .andExpect(jsonPath("$.data.content[?(@.id == " + studying + ")].lastGroup").value(
                org.hamcrest.Matchers.contains((Object) null)));

        // Filtr: oxirgi guruh o'qituvchisi — T1 da faqat "left" (moved ning oxirgisi T2), joriy guruhdagilar yo'q
        mvc.perform(get("/api/students").param("lastTeacherId", String.valueOf(t1))
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(left.intValue()));
        mvc.perform(get("/api/students").param("lastTeacherId", String.valueOf(t2))
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", containsInAnyOrder(moved.intValue())));

        // "Chiqib ketganlar": o'qituvchi va filtr
        List<LeftStudentResponse> rows = roster.getLeftStudents(t1);
        assertThat(rows).extracting(LeftStudentResponse::getId).containsExactly(left);
        assertThat(rows.get(0).getLastTeacherId()).isEqualTo(t1);
        assertThat(rows.get(0).getLastTeacherName()).isNotBlank();
        assertThat(rows.get(0).getExitReasonLabel()).isEqualTo(ExitReasonCode.PRICE.getLabel());
        assertThat(roster.getLeftStudents(null)).extracting(LeftStudentResponse::getId)
            .containsExactlyInAnyOrder(left, moved);
    }
}
