package com.crm.lec;

import com.crm.entity.Expense;
import com.crm.entity.Lead;
import com.crm.entity.Payment;
import com.crm.entity.User;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.ExpenseCategory;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ExpenseRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.FinanceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * phase6-api §1–§2: {@code GET /api/analytics/overview} va {@code GET /api/analytics/staff?role=}.
 * Soat 2026-09-15; davr 01.09–14.09 (oldingi teng davr 18.08–31.08).
 */
class AnalyticsPhase6Test extends LecItBase {

    @Autowired PaymentRepository paymentRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired StudentGroupRepository studentGroupRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired FinanceService financeService;

    private User admin;
    private User sales;
    private TeacherUser t1;
    private TeacherUser t2;
    private Long g1;
    private Long g2;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
        sales = newUser(UserRole.SALES_MANAGER);
        t1 = newTeacher();
        t2 = newTeacher();
        g1 = fixtures.group(fixtures.course(600_000), GroupStatus.ACTIVE, t1.teacherId());
        g2 = fixtures.group(fixtures.course(800_000), GroupStatus.ACTIVE, t2.teacherId());

        Long a = fixtures.student();
        Long b = fixtures.student();
        Long c = fixtures.student();
        fixtures.enrollment(a, g1).start(LocalDate.of(2026, 8, 1)).save();
        fixtures.enrollment(b, g1).start(LocalDate.of(2026, 9, 10)).save();
        Long cSg = fixtures.enrollment(c, g2).start(LocalDate.of(2026, 8, 1)).save();
        inTx(() -> {
            var sg = studentGroupRepository.findById(cSg).orElseThrow();
            sg.setIsActive(false);
            sg.setLeaveDate(LocalDate.of(2026, 9, 8));
            sg.setExitDate(LocalDate.of(2026, 9, 8));
            sg.setExitReasonCode(ExitReasonCode.PRICE);
            studentGroupRepository.save(sg);
            var sb = studentRepository.findById(b).orElseThrow();
            sb.setAttributedUserId(sales.getId());
            studentRepository.save(sb);
        });

        payment(a, "2026-08-20", PaymentMethod.CASH, 300_000, PaymentStatus.PAID, null);
        payment(a, "2026-09-03", PaymentMethod.CARD, 500_000, PaymentStatus.PAID, admin);
        payment(b, "2026-09-10", PaymentMethod.CASH, 700_000, PaymentStatus.PAID, admin);
        payment(b, "2026-09-05", PaymentMethod.CASH, 999_000, PaymentStatus.CANCELLED, admin);
        inTx(() -> expenseRepository.save(Expense.builder().category(ExpenseCategory.RENT).title("Ijara")
            .amount(BigDecimal.valueOf(200_000)).expenseDate(LocalDate.of(2026, 9, 5)).build()));

        lead("INSTAGRAM", "2026-09-02T10:00:00", true, null);
        lead("WEBSITE", "2026-09-03T10:00:00", false, null);
        lead("INSTAGRAM", "2026-09-04T10:00:00", false, "amo-1");   // import — hisobga kirmaydi
        lead("WEBSITE", "2026-08-25T10:00:00", false, null);          // oldingi davr
    }

    private void payment(Long studentId, String date, PaymentMethod method, long amount, PaymentStatus st, User by) {
        inTx(() -> paymentRepository.save(Payment.builder()
            .student(studentRepository.findById(studentId).orElseThrow())
            .amount(BigDecimal.valueOf(amount)).cashAmount(BigDecimal.valueOf(amount))
            .paymentDate(LocalDate.parse(date)).paymentMethod(method).status(st)
            .receivedBy(by != null ? userRepository.findById(by.getId()).orElseThrow() : null)
            .build()));
    }

    private void lead(String source, String createdAt, boolean converted, String batch) {
        Long id = inTx(() -> leadRepository.save(Lead.builder().fullName("Lid " + source).phone("+998901110000")
            .status("NEW").source(source).importBatch(batch).converted(converted).build()).getId());
        jdbc.update("UPDATE leads SET created_at = ?, converted_at = ? WHERE id = ?",
            LocalDateTime.parse(createdAt), converted ? LocalDateTime.parse(createdAt).plusDays(1) : null, id);
    }

    private ResultActions overview(User as, String... params) throws Exception {
        var req = get("/api/analytics/overview").with(as(as));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req);
    }

    private static BigDecimal sum(JsonNode series) {
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode n : series) {
            s = s.add(n.decimalValue());
        }
        return s;
    }

    @Test
    void overview_financeStudentsLeadsGroups_seriesSumToTotals_previousPeriod() throws Exception {
        JsonNode d = data(overview(admin, "from", "2026-09-01", "to", "2026-09-14", "groupBy", "WEEK")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.period.previousFrom").value("2026-08-18"))
            .andExpect(jsonPath("$.data.period.previousTo").value("2026-08-31"))
            .andExpect(jsonPath("$.data.period.buckets.length()").value(3))
            .andExpect(jsonPath("$.data.period.buckets[1]").value("2026-09-07"))
            .andReturn());

        JsonNode fin = d.get("finance");
        assertThat(fin.get("income").get("value").decimalValue()).isEqualByComparingTo("1200000");
        assertThat(fin.get("income").get("previous").decimalValue()).isEqualByComparingTo("300000");
        assertThat(fin.get("income").get("changePercent").decimalValue()).isEqualByComparingTo("300.0");
        assertThat(sum(fin.get("income").get("series"))).isEqualByComparingTo("1200000");
        assertThat(fin.get("income").get("series").get(0).decimalValue()).isEqualByComparingTo("500000");
        assertThat(fin.get("income").get("series").get(1).decimalValue()).isEqualByComparingTo("700000");
        assertThat(fin.get("incomeByMethod").get("CARD").decimalValue()).isEqualByComparingTo("500000");
        assertThat(fin.get("incomeByMethod").get("CASH").decimalValue()).isEqualByComparingTo("700000");
        assertThat(fin.get("expenses").get("value").decimalValue()).isEqualByComparingTo("200000");
        assertThat(fin.get("net").get("value").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(sum(fin.get("net").get("series"))).isEqualByComparingTo("1000000");
        assertThat(fin.get("expenses").get("changePercent").isNull()).isTrue();     // oldingi 0
        // Moliya hisoboti bilan aynan bir xil
        assertThat(financeService.getFinanceReport(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14))
            .getTotalIncome()).isEqualByComparingTo(fin.get("income").get("value").decimalValue());

        JsonNode st = d.get("students");
        assertThat(st.get("newStudents").get("value").asInt()).isEqualTo(1);       // B (A — avgust)
        assertThat(st.get("newStudents").get("previous").asInt()).isEqualTo(1);
        assertThat(st.get("exits").get("value").asInt()).isEqualTo(1);             // C, PRICE
        assertThat(st.get("exitsByReason").get("PRICE").asInt()).isEqualTo(1);
        assertThat(st.get("activeAtEnd").get("value").asInt()).isEqualTo(2);       // A, B
        assertThat(st.get("activeAtEnd").get("previous").asInt()).isEqualTo(2);    // A, C
        assertThat(st.get("activeAtEnd").get("series").get(0).asInt()).isEqualTo(2); // 06.09: A, C

        JsonNode leads = d.get("leads");
        assertThat(leads.get("created").get("value").asInt()).isEqualTo(2);
        assertThat(leads.get("created").get("previous").asInt()).isEqualTo(1);
        assertThat(leads.get("converted").get("value").asInt()).isEqualTo(1);
        assertThat(leads.get("conversionRate").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(leads.get("bySource")).hasSize(2);

        JsonNode groups = d.get("groups");
        assertThat(groups.get("activeGroups").asInt()).isEqualTo(2);
        assertThat(groups.get("averageFillPercent").decimalValue()).isEqualByComparingTo("1.0");  // (2/100 + 0) / 2
        assertThat(groups.get("groups").get(0).get("groupId").asLong()).isEqualTo(g1);
        assertThat(groups.get("groups").get(0).get("students").asInt()).isEqualTo(2);
    }

    @Test
    void overview_validation_defaults_andRoles() throws Exception {
        overview(admin).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.period.from").value("2026-09-01"))
            .andExpect(jsonPath("$.data.period.to").value("2026-09-15"))
            .andExpect(jsonPath("$.data.period.groupBy").value("DAY"));
        overview(admin, "from", "2026-09-10", "to", "2026-09-01").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("analytics.period.invalid"));
        overview(admin, "from", "2026-01-01", "to", "2026-09-01", "groupBy", "DAY").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("analytics.groupBy.tooFine"));
        overview(admin, "groupBy", "YEAR").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("analytics.groupBy.invalid"));
        overview(newUser(UserRole.ACCOUNTANT)).andExpect(status().isForbidden());
        overview(newUser(UserRole.SALES_HEAD)).andExpect(status().isForbidden());
    }

    private ResultActions staff(User as, String role) throws Exception {
        var req = get("/api/analytics/staff").with(as(as)).param("from", "2026-09-01").param("to", "2026-09-14");
        if (role != null) {
            req = req.param("role", role);
        }
        return mvc.perform(req);
    }

    @Test
    void staff_teachers_ranked_withKpiDisciplineExitsAndLinks() throws Exception {
        JsonNode d = data(staff(admin, "TEACHER").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("TEACHER"))
            .andExpect(jsonPath("$.data.rows[*].teacherId", hasItem(t1.teacherId().intValue())))
            .andExpect(jsonPath("$.data.rows[*].teacherId", hasItem(t2.teacherId().intValue())))
            .andReturn());
        JsonNode rows = d.get("rows");
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i).get("rank").asInt()).isEqualTo(i + 1);
            assertThat(rows.get(i).get("teacher").get("attendance")).isNotNull();
            assertThat(rows.get(i).has("sales")).isFalse();
        }
        JsonNode r1 = find(rows, "teacherId", t1.teacherId());
        assertThat(r1.get("userId").asLong()).isEqualTo(t1.user().getId());
        assertThat(r1.get("teacher").get("activeStudents").asInt()).isEqualTo(2);
        assertThat(r1.get("links").get("kpi").asText()).startsWith("/api/teachers/" + t1.teacherId() + "/kpi");
        JsonNode r2 = find(rows, "teacherId", t2.teacherId());
        assertThat(r2.get("teacher").get("exits").asInt()).isEqualTo(1);
        assertThat(r2.get("teacher").get("exitsByReason").get("PRICE").asInt()).isEqualTo(1);
    }

    @Test
    void staff_salesAndAdmin_fromOperatorMetrics_salesHeadOnlySales() throws Exception {
        User head = newUser(UserRole.SALES_HEAD);
        JsonNode s = data(staff(head, "SALES").andExpect(status().isOk()).andReturn());
        for (JsonNode r : s.get("rows")) {
            assertThat(r.get("role").asText()).isIn("SALES_MANAGER", "SALES_HEAD");
            assertThat(r.get("sales")).isNotNull();
        }
        JsonNode sm = find(s.get("rows"), "userId", sales.getId());
        assertThat(sm.get("rank").asInt()).isEqualTo(1);
        assertThat(sm.get("sales").get("firstPayments").asInt()).isEqualTo(1);    // B attribyutsiyasi
        assertThat(sm.get("links").get("leads").asText())
            .contains("/api/dashboard/director/operators/" + sales.getId() + "/leads");

        JsonNode a = data(staff(admin, "ADMIN").andExpect(status().isOk()).andReturn());
        JsonNode ad = find(a.get("rows"), "userId", admin.getId());
        assertThat(ad.get("admin").get("paymentsReceived").get("count").asInt()).isEqualTo(2);  // CANCELLED yo'q
        assertThat(ad.get("admin").get("paymentsReceived").get("amount").decimalValue()).isEqualByComparingTo("1200000");

        staff(head, "TEACHER").andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("analytics.staff.roleForbidden"));
        staff(head, null).andExpect(status().isForbidden());
        staff(admin, "OWNER").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("analytics.staff.roleInvalid"));
        staff(admin, null).andExpect(status().isOk());                               // eski javob (deprecated)
        staff(sales, "SALES").andExpect(status().isForbidden());
    }

    private static JsonNode find(JsonNode rows, String field, Long id) {
        for (JsonNode r : rows) {
            if (r.hasNonNull(field) && r.get(field).asLong() == id) {
                return r;
            }
        }
        throw new AssertionError(field + "=" + id + " topilmadi: " + rows);
    }
}
