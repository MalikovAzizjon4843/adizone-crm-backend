package com.crm;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.BalanceAdjustRequest;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.entity.enums.UserRole;
import com.crm.repository.UserRepository;
import com.crm.service.LeadService;
import com.crm.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ixtiyoriy filtrli so'rovlar — H2 yashil, PostgreSQL 500 bergan holatlar.
 *
 * <p>{@code (:p IS NULL OR col >= :p)} PostgreSQL'da {@code could not determine data type
 * of parameter $n} beradi: Hibernate har bir {@code :p} ni alohida JDBC parametri qiladi,
 * pgjdbc esa sana/vaqtni tipsiz (unspecified) yuboradi — {@code $n IS NULL} da tipni
 * aniqlab bo'lmaydi. H2 buni sezmaydi, shuning uchun bu testlarning asl qiymati
 * {@code mvn test -Dspring.profiles.active=pgtest} da.
 */
class FilteredQueriesRegressionTest extends AbstractBillingIT {

    @Autowired StudentService students;
    @Autowired LeadService leadService;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    private static MockHttpServletRequestBuilder asSuperAdmin(MockHttpServletRequestBuilder req) {
        return req.with(user("test-super_admin").roles("SUPER_ADMIN"));
    }

    // ── GET /api/students/{id}/balance-history ────────────────────────────────

    private Long adjust(Long student, Long group, long amount, LocalDateTime createdAt) {
        BalanceAdjustRequest r = new BalanceAdjustRequest();
        r.setGroupId(group);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setNote("Test");
        Long id = students.adjustBalance(student, r).getId();
        jdbc.update("UPDATE balance_transactions SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    @Test
    void balanceHistory_optionalFilters() throws Exception {
        Long student = fixtures.student();
        Long groupA = fixtures.group(fixtures.course(700_000));
        Long groupB = fixtures.group(fixtures.course(500_000));
        fixtures.enrollment(student, groupA).start(d("15.10.2026")).save();
        fixtures.enrollment(student, groupB).start(d("15.10.2026")).save();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long txA = adjust(student, groupA, 100_000, d("10.09.2026").atTime(10, 0));
        Long txB = adjust(student, groupB, 200_000, d("20.09.2026").atTime(23, 59, 59));
        String url = "/api/students/" + student + "/balance-history";

        // Filtrsiz — pgtest da aynan shu chaqiruv 500 bergan ($4: :from IS NULL)
        mvc.perform(asSuperAdmin(get(url)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].id").value(txB.intValue()))
            .andExpect(jsonPath("$.data[1].id").value(txA.intValue()));

        mvc.perform(asSuperAdmin(get(url).param("from", "2026-09-15")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(txB.intValue()));

        // to — kun oxirigacha (23:59:59 ham kiradi)
        mvc.perform(asSuperAdmin(get(url).param("to", "2026-09-20")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(asSuperAdmin(get(url).param("from", "2026-09-10").param("to", "2026-09-10")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(txA.intValue()));

        mvc.perform(asSuperAdmin(get(url).param("groupId", groupA.toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(txA.intValue()));

        mvc.perform(asSuperAdmin(get(url).param("groupId", groupA.toString()).param("from", "2026-09-15")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ── GET /api/leads/kanban-stats (filtrli Criteria yo'li) ──────────────────

    private Long lead(String name, String phone, String source, Long assignedUserId, LocalDateTime createdAt) {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName(name);
        r.setPhone(phone);
        r.setSource(source);
        r.setAssignedUserId(assignedUserId);
        Long id = leadService.createLeadByStaff(r).getId();
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    @Test
    void kanbanStats_filteredPath() throws Exception {
        fixtures.loginAs(UserRole.SALES_MANAGER);
        Long operator = userRepo.findByUsername("test-sales_manager").orElseThrow().getId();
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long a = lead("Ali Valiyev", "+998901111111", "INSTAGRAM", null, d("01.09.2026").atTime(9, 0));
        lead("Bobur Karimov", "+998902222222", "TELEGRAM", null, d("05.09.2026").atTime(9, 0));
        lead("Sardor Aliyev", "+998903333333", "INSTAGRAM", operator, d("10.09.2026").atTime(9, 0));
        jdbc.update("UPDATE leads SET amount = 150000 WHERE id = ?", a);

        // Filtrsiz yo'l (JPQL) — taqqoslash uchun
        mvc.perform(asSuperAdmin(get("/api/leads/kanban-stats")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unassigned.count").value(2))
            .andExpect(jsonPath("$.data.columns[?(@.status == 'NEW')].count").value(3));

        // Bug: ?unassigned=true → 500
        mvc.perform(asSuperAdmin(get("/api/leads/kanban-stats").param("unassigned", "true")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unassigned.count").value(2))
            .andExpect(jsonPath("$.data.unassigned.totalAmount").value(is(150000.0), Double.class))
            .andExpect(jsonPath("$.data.columns[?(@.status == 'NEW')].count").value(2))
            .andExpect(jsonPath("$.data.columns[0].status").value("NEW"))
            .andExpect(jsonPath("$.data.columns[0].totalAmount").value(is(150000.0), Double.class));

        mvc.perform(asSuperAdmin(get("/api/leads/kanban-stats").param("assignedUserId", operator.toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unassigned.count").value(0))
            .andExpect(jsonPath("$.data.columns[?(@.status == 'NEW')].count").value(1));

        mvc.perform(asSuperAdmin(get("/api/leads/kanban-stats")
                .param("search", "aliyev").param("source", "instagram")
                .param("fromDate", "2026-09-02").param("toDate", "2026-09-10")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unassigned.count").value(0))
            .andExpect(jsonPath("$.data.columns[?(@.status == 'NEW')].count").value(1));
    }
}
