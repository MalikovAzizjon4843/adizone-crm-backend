package com.crm.security;

import com.crm.config.MetaProperties;
import com.crm.dto.request.LeadStageRequest;
import com.crm.entity.Lead;
import com.crm.entity.LeadStage;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStageRepository;
import com.crm.repository.SettingRepository;
import com.crm.repository.TaskRepository;
import com.crm.service.LeadSettingsService;
import com.crm.service.LeadStageService;
import com.crm.service.MetaLeadIngestService;
import com.crm.service.MetaLeadPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Yangi lidlar uchun standart mas'ul ({@code settings.leads.default_assignee_user_id}), {@code tasks.auto_created}
 * (V80) — mas'ul almashganda ko'chishi, va {@code POST /api/admin/repair/leads-missing-tasks}.
 *
 * <p>{@code settings} va {@code lead_stages} {@code fixtures.wipe()} da tozalanmaydi — har test oxirida qaytariladi.
 */
class LeadDefaultAssigneeTest extends Phase5ItBase {

    private static final List<String> TOUCHED = List.of("NEW", "CONTACTED");

    @Autowired LeadRepository leadRepository;
    @Autowired LeadStageRepository leadStageRepository;
    @Autowired LeadStageService leadStageService;
    @Autowired SettingRepository settingRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired MetaLeadIngestService metaLeadIngestService;
    @Autowired MetaProperties metaProperties;
    @Autowired ObjectMapper objectMapper;

    private User admin;
    private User superAdmin;
    private User manager;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
        superAdmin = newUser(UserRole.SUPER_ADMIN);
        manager = newUser(UserRole.SALES_MANAGER);
    }

    @AfterEach
    void restore() {
        TOUCHED.forEach(code -> requireTask(code, false));
        settingRepository.findBySettingKey(LeadSettingsService.DEFAULT_ASSIGNEE_KEY).ifPresent(settingRepository::delete);
        metaProperties.setTaskAssigneeUserId(null);
    }

    // ── sozlama ────────────────────────────────────────────────────────

    @Test
    void setting_getPut_validation_andRoles() throws Exception {
        mvc.perform(get("/api/settings/leads/default-assignee").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").isEmpty());

        putDefault(manager.getId(), admin)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(manager.getId().intValue()))
            .andExpect(jsonPath("$.data.role").value("SALES_MANAGER"))
            .andExpect(jsonPath("$.data.valid").value(true));
        mvc.perform(get("/api/settings/leads/default-assignee").with(as(superAdmin)))
            .andExpect(jsonPath("$.data.userId").value(manager.getId().intValue()));

        User inactive = newUser(UserRole.SALES_MANAGER);
        inTx(() -> {
            User u = userRepository.findById(inactive.getId()).orElseThrow();
            u.setIsActive(false);
            userRepository.save(u);
        });
        for (Long bad : new Long[]{inactive.getId(), newUser(UserRole.SALES_HEAD).getId(),
                newUser(UserRole.TEACHER).getId(), 999_999_999L}) {
            putDefault(bad, admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("lead.defaultAssignee.invalid"));
        }
        putDefault(admin.getId(), admin).andExpect(status().isOk());       // ADMIN ham mumkin

        // Faqat SA, A
        mvc.perform(get("/api/settings/leads/default-assignee").with(as(manager))).andExpect(status().isForbidden());
        putDefault(manager.getId(), manager).andExpect(status().isForbidden());

        // Tozalash
        mvc.perform(put("/api/settings/leads/default-assignee").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").isEmpty());
    }

    // ── kirgan lidlar ──────────────────────────────────────────────────

    @Test
    void publicLead_assignedToDefault_withAutoTask() throws Exception {
        requireTask("NEW", true);

        // Sozlama bo'sh — avvalgi xulq: mas'ulsiz, vazifasiz
        JsonNode before = publicLead("+998901110001");
        assertThat(before.get("assignedUserId").isNull()).isTrue();
        assertThat(before.get("openTaskCount").asLong()).isZero();

        setDefault(manager);
        JsonNode lead = publicLead("+998901110002");
        assertThat(lead.get("assignedUserId").asLong()).isEqualTo(manager.getId());
        List<Task> open = openTasks(lead.get("id").asLong());
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getTitle()).isEqualTo("Yangi lid: bog'lanish");
        assertThat(open.get(0).getAssignedTo().getId()).isEqualTo(manager.getId());
        assertThat(open.get(0).getAutoCreated()).isTrue();
        // Tayinlash tarixi (direktor dashboardi)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_assignments WHERE lead_id = ? AND user_id = ?",
            Integer.class, lead.get("id").asLong(), manager.getId())).isEqualTo(1);

        // Bosqich vazifa talab qilmasa — faqat biriktiriladi
        requireTask("NEW", false);
        JsonNode plain = publicLead("+998901110003");
        assertThat(plain.get("assignedUserId").asLong()).isEqualTo(manager.getId());
        assertThat(plain.get("openTaskCount").asLong()).isZero();

        // Sozlangan foydalanuvchi keyin nofaol — e'tiborsiz, avvalgi xulq
        inTx(() -> {
            User u = userRepository.findById(manager.getId()).orElseThrow();
            u.setIsActive(false);
            userRepository.save(u);
        });
        assertThat(publicLead("+998901110004").get("assignedUserId").isNull()).isTrue();
    }

    @Test
    void kanbanQuickAdd_defaultOnlyWhenNoOperatorChosen() throws Exception {
        requireTask("NEW", true);
        setDefault(manager);
        User other = newUser(UserRole.SALES_MANAGER);

        JsonNode byAdmin = staffLead("{\"fullName\":\"A\",\"phone\":\"+998901110011\"}", admin);
        assertThat(byAdmin.get("assignedUserId").asLong()).isEqualTo(manager.getId());
        assertThat(openTasks(byAdmin.get("id").asLong()).get(0).getAssignedTo().getId()).isEqualTo(manager.getId());

        JsonNode explicit = staffLead("{\"fullName\":\"B\",\"phone\":\"+998901110012\",\"assignedUserId\":"
            + other.getId() + "}", admin);
        assertThat(explicit.get("assignedUserId").asLong()).isEqualTo(other.getId());

        JsonNode bySm = staffLead("{\"fullName\":\"C\",\"phone\":\"+998901110013\"}", other);
        assertThat(bySm.get("assignedUserId").asLong()).isEqualTo(other.getId());
    }

    @Test
    void metaLead_metaAssigneeFirst_thenDefault() {
        requireTask("NEW", true);
        User metaUser = newUser(UserRole.SALES_MANAGER);
        setDefault(manager);

        metaProperties.setTaskAssigneeUserId(metaUser.getId());
        Long first = ingest("+998905550101");
        assertThat(assigneeOf(first)).isEqualTo(metaUser.getId());
        assertThat(openTasks(first)).extracting(t -> t.getAssignedTo().getId()).containsExactly(metaUser.getId());

        metaProperties.setTaskAssigneeUserId(null);
        Long second = ingest("+998905550102");
        assertThat(assigneeOf(second)).isEqualTo(manager.getId());
        assertThat(openTasks(second)).extracting(t -> t.getAssignedTo().getId()).containsExactly(manager.getId());
    }

    // ── mas'ul almashganda auto_created vazifalar ──────────────────────

    @Test
    void assignLead_movesOpenAutoTasksOnly_withAudit() throws Exception {
        User other = newUser(UserRole.SALES_MANAGER);
        Long leadId = lead("CONTACTED", manager);
        Long auto = task(leadId, manager, TaskStatus.OPEN, true);
        Long manual = task(leadId, manager, TaskStatus.OPEN, false);
        Long autoDone = task(leadId, manager, TaskStatus.DONE, true);

        mvc.perform(patch("/api/leads/" + leadId + "/assign").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + other.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedUserId").value(other.getId().intValue()));

        assertThat(assigneeOfTask(auto)).isEqualTo(other.getId());
        assertThat(assigneeOfTask(manual)).isEqualTo(manager.getId());
        assertThat(assigneeOfTask(autoDone)).isEqualTo(manager.getId());

        Map<String, Object> audit = awaitAudit("ASSIGN", "Lead");
        assertThat(audit.get("details_json").toString()).contains("autoTasksReassigned").contains(auto.toString());

        // Biriktirish olib tashlansa — vazifalar joyida (assigned_to NOT NULL)
        mvc.perform(patch("/api/leads/" + leadId + "/assign").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":null}"))
            .andExpect(status().isOk());
        assertThat(assigneeOfTask(auto)).isEqualTo(other.getId());
    }

    // ── bir martalik tuzatish ──────────────────────────────────────────

    @Test
    void repair_dryRunThenApply_perStage() throws Exception {
        requireTask("CONTACTED", true);
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 12, 0));
        Long assigned = lead("CONTACTED", manager);
        Long unassigned = lead("CONTACTED", null);
        Long withTask = lead("CONTACTED", manager);
        task(withTask, manager, TaskStatus.OPEN, false);
        lead("NEW", null);                                   // bosqich talab qilmaydi
        lead("REJECTED", manager);                           // yopiq

        // Standart mas'ulsiz: mas'ulsiz lid o'tkaziladi
        JsonNode dry = repair(true);
        assertThat(dry.get("dryRun").asBoolean()).isTrue();
        assertThat(dry.get("total").asLong()).isEqualTo(2);
        assertThat(dry.get("created").asLong()).isEqualTo(1);
        assertThat(dry.get("skippedNoAssignee").asLong()).isEqualTo(1);
        assertThat(dry.get("dueAt").asText()).isEqualTo("2026-09-15T18:00:00");
        assertThat(openTasks(assigned)).isEmpty();                              // hech narsa yozilmagan

        setDefault(admin);
        dry = repair(true);
        assertThat(dry.get("created").asLong()).isEqualTo(2);
        assertThat(dry.get("assignedToDefault").asLong()).isEqualTo(1);
        assertThat(assigneeOf(unassigned)).isNull();
        JsonNode stage = dry.get("byStage").get(0);
        assertThat(stage.get("status").asText()).isEqualTo("CONTACTED");
        assertThat(stage.get("total").asLong()).isEqualTo(2);

        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 18, 30));
        JsonNode applied = repair(false);
        assertThat(applied.get("dryRun").asBoolean()).isFalse();
        assertThat(applied.get("created").asLong()).isEqualTo(2);
        assertThat(applied.get("assignedToDefault").asLong()).isEqualTo(1);
        assertThat(applied.get("failed").asLong()).isZero();
        assertThat(applied.get("dueAt").asText()).isEqualTo("2026-09-16T10:00:00");

        Task a = openTasks(assigned).get(0);
        assertThat(a.getTitle()).isEqualTo("Bog'lanish");
        assertThat(a.getAssignedTo().getId()).isEqualTo(manager.getId());
        assertThat(a.getAutoCreated()).isTrue();
        assertThat(a.getDueAt()).isEqualTo(LocalDateTime.of(2026, 9, 16, 10, 0));
        assertThat(assigneeOf(unassigned)).isEqualTo(admin.getId());
        assertThat(openTasks(unassigned).get(0).getAssignedTo().getId()).isEqualTo(admin.getId());
        assertThat(openTasks(withTask)).hasSize(1);

        Map<String, Object> audit = awaitAudit("REPAIR", "Lead");
        assertThat(audit.get("summary").toString()).contains("2 ta vazifa");

        // Takror — tuzatadigan narsa qolmagan
        assertThat(repair(false).get("total").asLong()).isZero();

        // Faqat SUPER_ADMIN
        mvc.perform(post("/api/admin/repair/leads-missing-tasks").with(as(admin))).andExpect(status().isForbidden());
    }

    // ── yordamchilar ───────────────────────────────────────────────────

    private void requireTask(String code, boolean value) {
        LeadStage stage = leadStageRepository.findByCode(code).orElseThrow();
        if (Boolean.valueOf(value).equals(stage.getRequiresTask())) {
            return;
        }
        LeadStageRequest r = new LeadStageRequest();
        r.setNameUz(stage.getNameUz());
        r.setNameRu(stage.getNameRu());
        r.setNameEn(stage.getNameEn());
        r.setColor(stage.getColor());
        r.setRequiresTask(value);
        loginAs(admin);
        leadStageService.update(stage.getId(), r);
        SecurityContextHolder.clearContext();
    }

    private void setDefault(User user) {
        try {
            putDefault(user.getId(), admin).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions putDefault(Long userId, User as) throws Exception {
        return mvc.perform(put("/api/settings/leads/default-assignee").with(as(as))
            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + userId + "}"));
    }

    private JsonNode publicLead(String phone) throws Exception {
        return data(mvc.perform(post("/api/leads/public").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sayt lidi\",\"phone\":\"" + phone + "\"}"))
            .andExpect(status().isCreated()));
    }

    private JsonNode staffLead(String body, User as) throws Exception {
        return data(mvc.perform(post("/api/leads").with(as(as)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()));
    }

    private Long ingest(String phone) {
        var result = inTx(() -> metaLeadIngestService.ingest(new MetaLeadPayload("lg-" + System.nanoTime(),
            null, null, "ig", null,
            List.of(new MetaLeadPayload.FieldValue("full_name", List.of("Meta Lid")),
                new MetaLeadPayload.FieldValue("phone_number", List.of(phone))),
            "{}"), false));
        assertThat(result.created()).isTrue();
        return result.leadId();
    }

    private JsonNode repair(boolean dryRun) throws Exception {
        return data(mvc.perform(post("/api/admin/repair/leads-missing-tasks").param("dryRun", String.valueOf(dryRun))
                .with(as(superAdmin)))
            .andExpect(status().isOk()));
    }

    private Long lead(String status, User assignee) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName("Lid " + System.nanoTime())
            .phone("+998901112233").status(status)
            .assignedUser(assignee != null ? userRepository.findById(assignee.getId()).orElseThrow() : null)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .converted(false).build()).getId());
    }

    private Long task(Long leadId, User assignee, TaskStatus status, boolean auto) {
        return inTx(() -> taskRepository.save(Task.builder().title("Vazifa")
            .dueAt(LocalDateTime.now().plusDays(1)).status(status)
            .result(status == TaskStatus.DONE ? "ok" : null)
            .assignedTo(userRepository.findById(assignee.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow())
            .autoCreated(auto).build()).getId());
    }

    private List<Task> openTasks(Long leadId) {
        return inTx(() -> {
            List<Task> tasks = taskRepository.findOpenByLeadIds(List.of(leadId));
            tasks.forEach(t -> t.getAssignedTo().getId());
            return tasks;
        });
    }

    private Long assigneeOf(Long leadId) {
        return inTx(() -> {
            Lead l = leadRepository.findById(leadId).orElseThrow();
            return l.getAssignedUser() != null ? l.getAssignedUser().getId() : null;
        });
    }

    private Long assigneeOfTask(Long taskId) {
        return inTx(() -> taskRepository.findById(taskId).orElseThrow().getAssignedTo().getId());
    }

    private Map<String, Object> awaitAudit(String action, String entityType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;          // audit @Async — commit'dan keyin
        while (System.currentTimeMillis() < deadline && jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_type = ?",
                Integer.class, action, entityType) == 0) {
            Thread.sleep(50);
        }
        return jdbc.queryForMap("SELECT summary, details_json FROM audit_logs WHERE action = ? AND entity_type = ?"
            + " ORDER BY id LIMIT 1", action, entityType);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
