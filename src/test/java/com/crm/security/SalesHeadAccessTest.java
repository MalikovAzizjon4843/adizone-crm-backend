package com.crm.security;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.Lead;
import com.crm.entity.SalaryRule;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.TaskRepository;
import com.crm.service.SalaryCalculationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SALES_HEAD (sotuv bo'limi rahbari): barcha lidlar va jamoa vazifalari, istalgan menejerga tayinlash,
 * kanban operator filtri, dashboard (faqat funnel + operators), foydalanuvchi yaratish va oylik qoidasi.
 * SALES_MANAGER esa avvalgidek faqat o'zinikini ko'radi va tayinlay olmaydi.
 */
class SalesHeadAccessTest extends Phase5ItBase {

    @Autowired LeadRepository leadRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired com.crm.repository.LeadCommentRepository commentRepository;
    @Autowired SalaryRuleRepository salaryRuleRepository;
    @Autowired SalaryCalculationService calculator;
    @Autowired ObjectMapper objectMapper;

    private User head;
    private User managerA;
    private User managerB;
    private Long leadA;
    private Long leadB;
    private Long leadFree;

    @BeforeEach
    void setUp() {
        head = newUser(UserRole.SALES_HEAD);
        managerA = newUser(UserRole.SALES_MANAGER);
        managerB = newUser(UserRole.SALES_MANAGER);
        leadA = lead("Lid A", managerA);
        leadB = lead("Lid B", managerB);
        leadFree = lead("Lid erkin", null);
    }

    private Long lead(String name, User assignee) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName(name).phone("+998901112233").status("NEW")
            .assignedUser(assignee != null ? userRepository.findById(assignee.getId()).orElseThrow() : null)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .converted(false).build()).getId());
    }

    private void task(String title, User assignee, Long leadId) {
        inTx(() -> taskRepository.save(Task.builder().title(title).dueAt(LocalDateTime.now().plusDays(1))
            .status(TaskStatus.OPEN)
            .assignedTo(userRepository.findById(assignee.getId()).orElseThrow())
            .createdBy(userRepository.findById(assignee.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow()).build()));
    }

    private long kanbanTotal(User as, Long assignedUserId) throws Exception {
        var req = get("/api/leads/kanban-stats").with(as(as));
        if (assignedUserId != null) {
            req = req.param("assignedUserId", assignedUserId.toString());
        }
        JsonNode data = objectMapper.readTree(mvc.perform(req).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");
        long sum = 0;
        for (JsonNode c : data.get("columns")) {
            sum += c.get("count").asLong();
        }
        return sum;
    }

    @Test
    void head_seesAllLeads_managerOnlyOwn_kanbanOperatorFilter() throws Exception {
        // Ro'yxatdagi "oxirgi izoh" — eng kech yozilgani (so'rov endi ikkala bazada ishlaydi)
        for (String[] c : new String[][]{{"Birinchi", "2026-09-10T10:00"}, {"Oxirgi", "2026-09-12T10:00"},
                {"O'rtadagi", "2026-09-11T10:00"}}) {
            inTx(() -> commentRepository.save(com.crm.entity.LeadComment.builder()
                .lead(leadRepository.findById(leadA).orElseThrow())
                .author(userRepository.findById(managerA.getId()).orElseThrow())
                .text(c[0]).createdAt(LocalDateTime.parse(c[1])).build()));
        }
        mvc.perform(get("/api/leads").with(as(managerA)))
            .andExpect(jsonPath("$.data.content[0].lastCommentText").value("Oxirgi"));

        mvc.perform(get("/api/leads").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get("/api/leads/" + leadB).with(as(head))).andExpect(status().isOk());
        mvc.perform(get("/api/leads").with(as(managerA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(leadA.intValue()));
        mvc.perform(get("/api/leads/" + leadB).with(as(managerA))).andExpect(status().isForbidden());

        // Kanban: rahbar operator bo'yicha filtrlaydi; menejerda filtr majburan o'zi
        assertThat(kanbanTotal(head, null)).isEqualTo(3);
        assertThat(kanbanTotal(head, managerB.getId())).isEqualTo(1);
        assertThat(kanbanTotal(managerA, managerB.getId())).isEqualTo(1);   // baribir o'zining 1 tasi
        mvc.perform(get("/api/leads/stats").with(as(head))).andExpect(status().isOk());
        mvc.perform(get("/api/leads/stats").with(as(managerA))).andExpect(status().isForbidden());
        // Butun baza eksporti — faqat ma'muriyat
        mvc.perform(get("/api/leads/export").with(as(head))).andExpect(status().isForbidden());
    }

    @Test
    void head_assignsAndReassignsToAnyManager_managerCannotAssign() throws Exception {
        mvc.perform(get("/api/leads/operators").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem(managerB.getId().intValue())))
            .andExpect(jsonPath("$.data[*].id", hasItem(head.getId().intValue())));
        mvc.perform(get("/api/leads/operators").with(as(managerA))).andExpect(status().isForbidden());

        mvc.perform(patch("/api/leads/" + leadFree + "/assign").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + managerA.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedUserId").value(managerA.getId().intValue()));
        mvc.perform(patch("/api/leads/" + leadFree + "/assign").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + managerB.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedUserId").value(managerB.getId().intValue()));
        assertThat(inTx(() -> leadRepository.findById(leadFree).orElseThrow().getAssignedUser().getId()))
            .isEqualTo(managerB.getId());

        mvc.perform(patch("/api/leads/" + leadA + "/assign").with(as(managerA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + managerB.getId() + "}"))
            .andExpect(status().isForbidden());
        User teacher = newUser(UserRole.TEACHER);
        mvc.perform(patch("/api/leads/" + leadA + "/assign").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + teacher.getId() + "}"))
            .andExpect(status().isBadRequest());

        // Rahbar yaratgan lid — operator ko'rsatilsa istalgan menejerga
        mvc.perform(post("/api/leads").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Yangi lid\",\"phone\":\"+998901234567\",\"assignedUserId\":" + managerA.getId() + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assignedUserId").value(managerA.getId().intValue()));
    }

    @Test
    void head_seesTeamTasks_managerOnlyOwn() throws Exception {
        task("A ga qo'ng'iroq", managerA, leadA);
        task("B ga qo'ng'iroq", managerB, leadB);

        mvc.perform(get("/api/tasks").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/tasks").param("assignedTo", managerB.getId().toString()).with(as(head)))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/tasks").with(as(managerA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void head_dashboard_onlyFunnelAndOperators() throws Exception {
        mvc.perform(get("/api/dashboard/director").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.funnel").isNotEmpty())
            .andExpect(jsonPath("$.data.operators").isNotEmpty())
            .andExpect(jsonPath("$.data.collections").doesNotExist())
            .andExpect(jsonPath("$.data.attendance").doesNotExist());
        mvc.perform(get("/api/dashboard/director/operators").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].userId", hasItem(managerA.getId().intValue())));
        mvc.perform(get("/api/dashboard/director/collections/periods").with(as(head)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/director").with(as(managerA))).andExpect(status().isForbidden());
    }

    @Test
    void head_isStaff_createdByAdmin_salaryRuleLikeSalesManager() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        mvc.perform(post("/api/users").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Rahbar\",\"lastName\":\"Sotuv\",\"password\":\"Parol12345\",\"role\":\"SALES_HEAD\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.role").value("SALES_HEAD"));
        mvc.perform(get("/api/notices").with(as(head))).andExpect(status().isOk());
        mvc.perform(get("/api/students").with(as(head))).andExpect(status().isOk());

        User sa = newUser(UserRole.SUPER_ADMIN);
        mvc.perform(post("/api/salary-rules").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_HEAD\",\"userId\":" + head.getId() + ",\"perPayingStudent\":1000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("salaryRule.field.notApplicable"));
        mvc.perform(post("/api/salary-rules").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_HEAD\",\"userId\":" + head.getId() + ",\"kpiBonus\":1000}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/salary-rules").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SALES_HEAD\",\"userId\":" + head.getId()
                    + ",\"fixedSalary\":4000000,\"perNewStudent\":50000,\"effectiveFrom\":\"2026-01-01\"}"))
            .andExpect(status().isCreated());

        SalaryCalculationDto calc = calculator.calculateForUser(head.getId(), 9, 2026);
        assertThat(calc.getCalculable()).isTrue();
        assertThat(calc.getRole()).isEqualTo("SALES_HEAD");
        assertThat(calc.getCalculationDetails().lines()).extracting(PayrollCalculationDetails.Line::code)
            .containsExactly(PayrollCalculationDetails.FIXED, PayrollCalculationDetails.PER_NEW_STUDENT);
        assertThat(calc.getTotalAmount()).isEqualByComparingTo("4000000");
        assertThat(inTx(() -> salaryRuleRepository.findAll().stream()
            .filter(r -> r.getRole() == UserRole.SALES_HEAD).map(SalaryRule::getPerNewStudent).findFirst().orElseThrow()))
            .isEqualByComparingTo(BigDecimal.valueOf(50000));
    }
}
