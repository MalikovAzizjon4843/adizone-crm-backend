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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
            .andExpect(jsonPath("$.data[*].userId", hasItem(managerA.getId().intValue())))
            .andExpect(jsonPath("$.data[*].userId", hasItem(head.getId().intValue())));
        mvc.perform(get("/api/dashboard/director/collections/periods").with(as(head)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/director").with(as(managerA))).andExpect(status().isForbidden());
    }

    private long id(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    @Test
    void head_convertsAnyLead_managerOnlyOwn() throws Exception {
        String body = "{\"studyFormat\":\"OFFLINE\"}";
        mvc.perform(post("/api/leads/" + leadB + "/convert").with(as(managerA))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/leads/" + leadB + "/convert").with(as(head))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.leadId").value(leadB.intValue()));
        assertThat(inTx(() -> leadRepository.findById(leadB).orElseThrow().getConverted())).isTrue();
    }

    @Test
    void head_writesAndReassignsTasks_butDeletesOnlyOwnTasksAndNotes() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        // Rahbar menejerga vazifa yozadi va boshqasiga o'tkazadi
        long headTask = id(mvc.perform(post("/api/tasks").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Qayta qo'ng'iroq\",\"dueAt\":\"2030-01-10T10:00:00\",\"assignedTo\":"
                    + managerA.getId() + ",\"leadId\":" + leadA + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assignedToId").value(managerA.getId().intValue())));
        mvc.perform(patch("/api/tasks/" + headTask + "/reassign").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + managerB.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedToId").value(managerB.getId().intValue()));

        // Menejerning o'z vazifasi — rahbar o'chira olmaydi, admin o'chiradi
        long managerTask = id(mvc.perform(post("/api/tasks").with(as(managerA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"O'zim\",\"dueAt\":\"2030-01-11T10:00:00\"}"))
            .andExpect(status().isCreated()));
        mvc.perform(delete("/api/tasks/" + managerTask)
                .with(as(head)))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tasks/" + headTask)
                .with(as(head)))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/tasks/" + managerTask)
                .with(as(admin)))
            .andExpect(status().isOk());

        // Lid izohlari: begona — faqat SA/A, o'ziniki — rahbar ham
        long managerNote = id(mvc.perform(post("/api/leads/" + leadA + "/notes").with(as(managerA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Menejer izohi\"}"))
            .andExpect(status().isCreated()));
        long headNote = id(mvc.perform(post("/api/leads/" + leadA + "/notes").with(as(head))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Rahbar izohi\"}"))
            .andExpect(status().isCreated()));
        mvc.perform(delete("/api/leads/notes/" + managerNote)
                .with(as(head)))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/leads/notes/" + headNote)
                .with(as(head)))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/leads/notes/" + managerNote)
                .with(as(admin)))
            .andExpect(status().isOk());
    }

    @Test
    void head_cannotImportLeads_orEditLeadStages() throws Exception {
        var file = new MockMultipartFile("file", "lidlar.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/leads/import/preview").file(file).with(as(head)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/leads/import/batch-1").with(as(head))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/import/students").file(file).with(as(head)))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/lead-stages").with(as(head))).andExpect(status().isOk());
        mvc.perform(patch("/api/lead-stages/reorder").with(as(head)).contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/lead-stages/1")
                .with(as(head)))
            .andExpect(status().isForbidden());
    }

    @Test
    void notice_targetRolesAcceptSalesHead_visibleOnlyToThatRole() throws Exception {
        User sa = newUser(UserRole.SUPER_ADMIN);
        long notice = id(mvc.perform(post("/api/notices").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Oylik reja\",\"content\":\"Sotuv rejasi\",\"targetRoles\":[\"SALES_HEAD\"],"
                    + "\"isPublished\":true,\"isActive\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.targetRoles[0]").value("SALES_HEAD")));

        mvc.perform(get("/api/notices/active").with(as(head)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem((int) notice)));
        mvc.perform(get("/api/notices/" + notice).with(as(head))).andExpect(status().isOk());
        mvc.perform(get("/api/notices/active").with(as(managerA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", not(hasItem((int) notice))));
    }

    @Test
    void staffBonus_acceptsSalesHead() throws Exception {
        User sa = newUser(UserRole.SUPER_ADMIN);
        mvc.perform(post("/api/bonus-penalties").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BONUS\",\"targetType\":\"STAFF\",\"userId\":" + head.getId()
                    + ",\"amount\":300000,\"effectiveDate\":\"2026-09-10\",\"reason\":\"Reja bajarildi\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.userId").value(head.getId().intValue()));
        User accountant = newUser(UserRole.ACCOUNTANT);
        mvc.perform(post("/api/bonus-penalties").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BONUS\",\"targetType\":\"STAFF\",\"userId\":" + accountant.getId()
                    + ",\"amount\":300000,\"effectiveDate\":\"2026-09-10\",\"reason\":\"Reja\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bonus.staff.roleInvalid"));
        // Bonus moduli — SALES_HEAD ga yopiq
        mvc.perform(get("/api/bonus-penalties").with(as(head))).andExpect(status().isForbidden());
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
