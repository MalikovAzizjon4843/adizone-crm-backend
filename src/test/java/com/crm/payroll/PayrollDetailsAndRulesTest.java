package com.crm.payroll;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollCalculationDetails.Line;
import com.crm.entity.SalaryRule;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payroll v2 — qaror 8 (calculationDetails tuzilgan JSON), 9 (SalaryRule yagona nomlar, faqat SA). */
class PayrollDetailsAndRulesTest extends PayrollItBase {

    @Autowired ObjectMapper objectMapper;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String role) {
        return r.with(user("test-" + role.toLowerCase()).roles(role));
    }

    // ── Qaror 8 ──────────────────────────────────────────────────────────

    @Test
    void calculationDetails_structuredLines_sumToNet() throws Exception {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        pay(monthly(t.teacherId(), 500_000, "18.09.2026"), 500_000, "18.09.2026");
        teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 200_000, "10.09.2026");
        teacherBonus(t.teacherId(), BonusPenaltyKind.PENALTY, 40_000, "11.09.2026");
        Long id = draftFor(t.userId(), SEP).getId();

        PayrollCalculationDetails d = calculator.fromJson(payroll(id).getCalculationDetails());
        assertThat(d.version()).isEqualTo(2);
        assertThat(d.lines()).extracting(Line::code).containsExactly(
            PayrollCalculationDetails.FIXED, PayrollCalculationDetails.PER_PAYING_STUDENT,
            PayrollCalculationDetails.BONUS, PayrollCalculationDetails.PENALTY);
        Line perStudent = d.lines().get(1);
        assertThat(perStudent.base()).isEqualByComparingTo("100000");
        assertThat(perStudent.count()).isEqualByComparingTo("2");
        assertThat(perStudent.amount()).isEqualByComparingTo("200000");
        assertThat(d.lines().get(3).amount()).isEqualByComparingTo("-40000");
        BigDecimal sum = d.lines().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(d.net());
        assertThat(d.net()).isEqualByComparingTo("3360000");
        assertThat(d.rule().fixedSalary()).isEqualByComparingTo("3000000");
        assertThat(d.rule().scope()).isEqualTo("PERSONAL");
        assertThat(d.items().paidPeriods()).hasSize(2);

        JsonNode stored = objectMapper.readTree(payroll(id).getCalculationDetails());
        assertThat(stored.isObject()).isTrue();
        assertThat(stored.get("lines").get(0).fieldNames()).toIterable()
            .contains("code", "label", "base", "count", "amount");
    }

    @Test
    void api_returnsCalculationDetailsAsObject_notString() throws Exception {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        Long id = draftFor(t.userId(), SEP).getId();

        mvc.perform(as(get("/api/payroll/" + id), "SUPER_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.calculationDetails.version").value(2))
            .andExpect(jsonPath("$.data.calculationDetails.lines[0].code").value("FIXED"))
            .andExpect(jsonPath("$.data.calculationDetails.lines[1].count").value(1))
            .andExpect(jsonPath("$.data.calculationDetails.items.paidPeriods[0].periodStart").value("2026-09-15"));

        mvc.perform(as(get("/api/payroll/calculate").param("month", "9").param("year", "2026"), "ACCOUNTANT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.userId == " + t.userId() + ")].calculationDetails.lines[0].code")
                .value(hasItem("FIXED")));

        mvc.perform(as(get("/api/payroll").param("month", "9").param("year", "2026").param("status", "DRAFT"), "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].calculationDetails.lines").isArray());
    }

    // ── Qaror 9 ──────────────────────────────────────────────────────────

    @Test
    void salaryRule_finalNames_roundTrip_andGetById() throws Exception {
        Long teacherUser = teacherStaff().userId();
        String body = """
            {"role":"TEACHER","userId":%d,"fixedSalary":3000000,"perPayingStudent":50000,
             "effectiveFrom":"2026-09-01"}
            """.formatted(teacherUser);

        String created = mvc.perform(as(post("/api/salary-rules"), "SUPER_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.fixedSalary").value(3000000))
            .andExpect(jsonPath("$.data.perPayingStudent").value(50000))
            .andExpect(jsonPath("$.data.perNewStudent").value(0))
            .andExpect(jsonPath("$.data.baseSalary").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("data").get("id").asLong();

        SalaryRule saved = inTx(() -> ruleRepo.findById(id).orElseThrow());
        assertThat(saved.getFixedSalary()).isEqualByComparingTo("3000000");
        assertThat(saved.getPerPayingStudent()).isEqualByComparingTo("50000");

        mvc.perform(as(get("/api/salary-rules/" + id), "SUPER_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fixedSalary").value(3000000))
            .andExpect(jsonPath("$.data.userId").value(teacherUser.intValue()));
        assertThat(calculator.calculateForUser(teacherUser, SEP, YEAR).getBaseSalary())
            .isEqualByComparingTo("3000000");
    }

    @Test
    void salaryRule_oldNamesAndWrongFieldsRejected_superAdminOnly() throws Exception {
        Long teacherUser = teacherStaff().userId();
        Long salesUser = staffUser(UserRole.SALES_MANAGER);

        mvc.perform(as(post("/api/salary-rules"), "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEACHER\",\"baseSalary\":3000000,\"perStudentFee\":50000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.field.unknown"))
            .andExpect(jsonPath("$.message", containsString("baseSalary")));

        mvc.perform(as(post("/api/salary-rules"), "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEACHER\",\"fixedSalary\":1,\"perNewStudent\":5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.field.notApplicable"));

        mvc.perform(as(post("/api/salary-rules"), "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEACHER\",\"userId\":" + salesUser + ",\"fixedSalary\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.userRoleMismatch"));

        mvc.perform(as(post("/api/salary-rules"), "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"TEACHER\",\"userId\":" + teacherUser + ",\"fixedSalary\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/salary-rules"), "ACCOUNTANT")).andExpect(status().isForbidden());
        assertThat(inTx(() -> ruleRepo.count())).isZero();
    }
}
