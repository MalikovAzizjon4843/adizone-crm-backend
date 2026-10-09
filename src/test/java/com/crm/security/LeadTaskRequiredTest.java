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
import com.crm.repository.TaskRepository;
import com.crm.service.LeadStageService;
import com.crm.service.MetaLeadIngestService;
import com.crm.service.MetaLeadPayload;
import com.crm.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lid bosqichida vazifa majburiyligi (lead_stages.requires_task, V79): a) bosqichni o'zgartirish,
 * b) vazifani bajarish, c) bekor qilish / o'chirish — oxirgi ochiq vazifa bo'lsa {@code nextTask} shart;
 * avtomatik lidlar bloklanmaydi (vazifa o'zi yaratiladi); CONVERTED/REJECTED da qoida yo'q;
 * ro'yxat DTO va {@code /stats/without-task}.
 *
 * <p>{@code lead_stages} {@code fixtures.wipe()} da tozalanmaydi — har test oxirida bayroqlar qaytariladi.
 */
class LeadTaskRequiredTest extends Phase5ItBase {

    private static final List<String> TOUCHED = List.of("NEW", "CONTACTED", "REJECTED", "CONVERTED_OFFLINE");

    @Autowired LeadRepository leadRepository;
    @Autowired LeadStageRepository leadStageRepository;
    @Autowired LeadStageService leadStageService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskService taskService;
    @Autowired MetaLeadIngestService metaLeadIngestService;
    @Autowired MetaProperties metaProperties;
    @Autowired ObjectMapper objectMapper;

    private User admin;
    private User manager;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
        manager = newUser(UserRole.SALES_MANAGER);
        requireTask("CONTACTED", true);
    }

    @AfterEach
    void restoreStages() {
        TOUCHED.forEach(code -> requireTask(code, false));
        metaProperties.setTaskAssigneeUserId(null);
    }

    // ── a) bosqichni o'zgartirish ──────────────────────────────────────

    @Test
    void statusChange_toRequiredStage_withoutOpenTask_needsNextTask() throws Exception {
        Long leadId = lead("NEW", manager);

        changeStatus(leadId, "{\"status\":\"CONTACTED\"}", manager)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.task.required"));
        assertThat(leadStatus(leadId)).isEqualTo("NEW");

        // nextTask maydonlari majburiy (validatsiya)
        changeStatus(leadId, "{\"status\":\"CONTACTED\",\"nextTask\":{\"title\":\"\"}}", manager)
            .andExpect(status().isBadRequest());

        String due = LocalDateTime.now().plusDays(1).withNano(0).toString();
        changeStatus(leadId, "{\"status\":\"CONTACTED\",\"nextTask\":{\"title\":\"Qayta qo'ng'iroq\",\"dueAt\":\"" + due
                + "\",\"assigneeId\":" + manager.getId() + ",\"comment\":\"Kechqurun\"}}", manager)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONTACTED"))
            .andExpect(jsonPath("$.data.openTaskCount").value(1))
            .andExpect(jsonPath("$.data.taskMissing").value(false))
            .andExpect(jsonPath("$.data.nextTaskTitle").value("Qayta qo'ng'iroq"));

        List<Task> open = openTasks(leadId);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getDescription()).isEqualTo("Kechqurun");
        assertThat(open.get(0).getAssignedTo().getId()).isEqualTo(manager.getId());
    }

    @Test
    void statusChange_leadWithOpenTask_orNonRequiredStage_orSameStage_isFree() throws Exception {
        Long withTask = lead("NEW", manager);
        task(withTask, manager);
        changeStatus(withTask, "{\"status\":\"CONTACTED\"}", manager).andExpect(status().isOk());

        // Bayroqsiz bosqich — erkin
        Long plain = lead("NEW", manager);
        changeStatus(plain, "{\"status\":\"VISITED_TRIAL\"}", manager).andExpect(status().isOk());

        // Eski lid allaqachon bosqichda, vazifasiz — shu bosqichga qayta saqlash bloklanmaydi (retroaktiv emas)
        Long legacy = lead("CONTACTED", manager);
        changeStatus(legacy, "{\"status\":\"CONTACTED\",\"amount\":100000}", manager).andExpect(status().isOk());
        mvc.perform(get("/api/leads/" + legacy).with(as(manager)))
            .andExpect(jsonPath("$.data.taskMissing").value(true))
            .andExpect(jsonPath("$.data.openTaskCount").value(0));
    }

    @Test
    void rejectedAndConvertedStages_areExempt() throws Exception {
        requireTask("REJECTED", true);
        requireTask("CONVERTED_OFFLINE", true);
        assertThat(leadStageService.requiresTask("REJECTED")).isFalse();
        assertThat(leadStageService.requiresTask("CONVERTED_OFFLINE")).isFalse();
        assertThat(leadStageService.requiresTask("CONTACTED")).isTrue();

        Long leadId = lead("NEW", manager);
        changeStatus(leadId, "{\"status\":\"REJECTED\"}", manager).andExpect(status().isOk());

        // Konvert bosqichidagi lidning oxirgi vazifasini yopish ham erkin
        Long converted = lead("CONVERTED_OFFLINE", manager);
        Long t = task(converted, manager);
        complete(t, "{\"result\":\"Tayyor\"}", manager).andExpect(status().isOk());
        mvc.perform(get("/api/leads/" + converted).with(as(manager)))
            .andExpect(jsonPath("$.data.taskMissing").value(false));
    }

    // ── b) vazifani bajarish ───────────────────────────────────────────

    @Test
    void complete_lastOpenTask_needsNextTask() throws Exception {
        Long leadId = lead("CONTACTED", manager);
        Long t = task(leadId, manager);

        complete(t, "{\"result\":\"Gaplashildi\"}", manager)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.task.required"));
        assertThat(taskRepository.findById(t).orElseThrow().getStatus()).isEqualTo(TaskStatus.OPEN);

        String due = LocalDateTime.now().plusDays(2).withNano(0).toString();
        complete(t, "{\"result\":\"Gaplashildi\",\"nextTask\":{\"title\":\"Uchrashuv\",\"dueAt\":\"" + due
                + "\",\"assigneeId\":" + manager.getId() + ",\"comment\":\"Ofisda\"}}", manager)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leadHasOpenTask").value(true))
            .andExpect(jsonPath("$.data.nextTask.title").value("Uchrashuv"))
            .andExpect(jsonPath("$.data.nextTask.description").value("Ofisda"));
        assertThat(openTasks(leadId)).hasSize(1);
    }

    @Test
    void complete_withAnotherOpenTask_isFree() throws Exception {
        Long leadId = lead("CONTACTED", manager);
        Long first = task(leadId, manager);
        task(leadId, manager);
        complete(first, "{\"result\":\"Bo'ldi\"}", manager)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leadHasOpenTask").value(true));
    }

    // ── c) bekor qilish va o'chirish ───────────────────────────────────

    @Test
    void cancel_lastOpenTask_needsNextTask() throws Exception {
        Long leadId = lead("CONTACTED", manager);
        Long t = task(leadId, manager);

        mvc.perform(patch("/api/tasks/" + t + "/cancel").with(as(manager)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.task.required"));

        String due = LocalDateTime.now().plusDays(1).withNano(0).toString();
        mvc.perform(patch("/api/tasks/" + t + "/cancel").with(as(manager))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Raqam o'zgardi\",\"nextTask\":{\"title\":\"Yangi raqamga\",\"dueAt\":\"" + due
                    + "\",\"assigneeId\":" + manager.getId() + "}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.result").value("Raqam o'zgardi"));
        List<Task> open = openTasks(leadId);
        assertThat(open).extracting(Task::getTitle).containsExactly("Yangi raqamga");

        // Lidsiz vazifa — erkin bekor qilinadi
        Long standalone = inTx(() -> taskRepository.save(Task.builder().title("Hisobot")
            .dueAt(LocalDateTime.now().plusDays(1)).status(TaskStatus.OPEN)
            .assignedTo(userRepository.findById(manager.getId()).orElseThrow())
            .createdBy(userRepository.findById(manager.getId()).orElseThrow()).build()).getId());
        mvc.perform(patch("/api/tasks/" + standalone + "/cancel").with(as(manager))).andExpect(status().isOk());
    }

    @Test
    void delete_lastOpenTask_needsNextTask_closedTaskIsFree() throws Exception {
        Long leadId = lead("CONTACTED", manager);
        Long t = task(leadId, manager);

        mvc.perform(delete("/api/tasks/" + t).with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.task.required"));
        assertThat(taskRepository.existsById(t)).isTrue();

        String due = LocalDateTime.now().plusDays(1).withNano(0).toString();
        mvc.perform(delete("/api/tasks/" + t).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nextTask\":{\"title\":\"Boshqa vazifa\",\"dueAt\":\"" + due
                    + "\",\"assigneeId\":" + manager.getId() + "}}"))
            .andExpect(status().isOk());
        assertThat(taskRepository.existsById(t)).isFalse();
        List<Task> open = openTasks(leadId);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getAssignedTo().getId()).isEqualTo(manager.getId());

        // Bajarilgan vazifani o'chirish ochiq vazifalar sonini o'zgartirmaydi — erkin
        Long done = inTx(() -> taskRepository.save(Task.builder().title("Eski").dueAt(LocalDateTime.now())
            .status(TaskStatus.DONE).result("ok")
            .assignedTo(userRepository.findById(manager.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow()).build()).getId());
        mvc.perform(delete("/api/tasks/" + done).with(as(admin))).andExpect(status().isOk());
    }

    // ── avtomatik kirgan lidlar ────────────────────────────────────────

    @Test
    void autoLeads_areNotBlocked_initialTaskCreatedForAssignee() throws Exception {
        requireTask("NEW", true);

        // Kanbandagi tez qo'shish (SALES_MANAGER o'ziga oladi) — vazifa operatorga, +15 daqiqa
        JsonNode created = data(mvc.perform(post("/api/leads").with(as(manager))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Tez lid\",\"phone\":\"+998901234567\"}"))
            .andExpect(status().isCreated()));
        long staffLead = created.get("id").asLong();
        assertThat(created.get("openTaskCount").asLong()).isEqualTo(1);
        Task initial = openTasks(staffLead).get(0);
        assertThat(initial.getTitle()).isEqualTo("Yangi lid: bog'lanish");
        assertThat(initial.getAssignedTo().getId()).isEqualTo(manager.getId());
        assertThat(Duration.between(LocalDateTime.now(), initial.getDueAt()).toMinutes()).isBetween(13L, 15L);

        // Ochiq forma — lid yaratiladi (bloklanmaydi); mas'ul yo'q va tasks.assigned_to NOT NULL — vazifasiz
        JsonNode pub = data(mvc.perform(post("/api/leads/public")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sayt lidi\",\"phone\":\"+998901234568\"}"))
            .andExpect(status().isCreated()));
        assertThat(pub.get("openTaskCount").asLong()).isZero();
        assertThat(pub.get("taskMissing").asBoolean()).isTrue();

        // Import yo'li (operatori bor lid) — xuddi shu servis
        Long imported = lead("NEW", manager);
        inTx(() -> taskService.createInitialTaskIfRequired(leadRepository.findById(imported).orElseThrow(), null));
        assertThat(openTasks(imported)).extracting(Task::getTitle).containsExactly("Yangi lid: bog'lanish");
        // Takroriy chaqiruv ikkinchi vazifa yaratmaydi
        inTx(() -> taskService.createInitialTaskIfRequired(leadRepository.findById(imported).orElseThrow(), null));
        assertThat(openTasks(imported)).hasSize(1);
    }

    @Test
    void metaLead_initialTask_goesToConfiguredAssignee() {
        requireTask("NEW", true);
        metaProperties.setTaskAssigneeUserId(manager.getId());

        var result = inTx(() -> metaLeadIngestService.ingest(new MetaLeadPayload("lg-" + System.nanoTime(),
            null, null, "ig", null,
            List.of(new MetaLeadPayload.FieldValue("full_name", List.of("Meta Lid")),
                new MetaLeadPayload.FieldValue("phone_number", List.of("+998905550011"))),
            "{}"), false));
        assertThat(result.created()).isTrue();
        List<Task> open = openTasks(result.leadId());
        assertThat(open).extracting(Task::getTitle).containsExactly("Yangi lid: bog'lanish");
        assertThat(open.get(0).getAssignedTo().getId()).isEqualTo(manager.getId());

        // Sozlanmagan bo'lsa — lid baribir yaratiladi, vazifasiz
        metaProperties.setTaskAssigneeUserId(null);
        var second = inTx(() -> metaLeadIngestService.ingest(new MetaLeadPayload("lg-" + System.nanoTime(),
            null, null, "fb", null,
            List.of(new MetaLeadPayload.FieldValue("full_name", List.of("Meta Ikki")),
                new MetaLeadPayload.FieldValue("phone_number", List.of("+998905550022"))),
            "{}"), false));
        assertThat(second.created()).isTrue();
        assertThat(openTasks(second.leadId())).isEmpty();
    }

    // ── ro'yxat DTO va statistika ──────────────────────────────────────

    @Test
    void list_andWithoutTaskStats() throws Exception {
        Long missing = lead("CONTACTED", manager);
        Long ok = lead("CONTACTED", manager);
        task(ok, manager);
        task(ok, manager);
        Long plain = lead("NEW", manager);
        Long otherManagers = lead("CONTACTED", newUser(UserRole.SALES_MANAGER));
        lead("REJECTED", manager);

        JsonNode list = data(mvc.perform(get("/api/leads").param("size", "50").with(as(admin))).andExpect(status().isOk()));
        for (JsonNode row : list.get("content")) {
            long id = row.get("id").asLong();
            if (id == missing) {
                assertThat(row.get("taskMissing").asBoolean()).isTrue();
                assertThat(row.get("openTaskCount").asLong()).isZero();
                assertThat(row.get("nextTaskDueAt").isNull()).isTrue();
            } else if (id == ok) {
                assertThat(row.get("taskMissing").asBoolean()).isFalse();
                assertThat(row.get("openTaskCount").asLong()).isEqualTo(2);
                assertThat(row.get("nextTaskDueAt").isNull()).isFalse();
            } else if (id == plain) {
                assertThat(row.get("taskMissing").asBoolean()).isFalse();   // NEW vazifa talab qilmaydi
            }
        }

        JsonNode stats = data(mvc.perform(get("/api/leads/stats/without-task").with(as(admin)))
            .andExpect(status().isOk()));
        assertThat(stats.get("total").asLong()).isEqualTo(3);           // missing, plain, otherManagers (REJECTED yopiq)
        assertThat(stats.get("requiredTotal").asLong()).isEqualTo(2);   // CONTACTED dagilar
        assertThat(stageCount(stats, "CONTACTED")).isEqualTo(2);
        assertThat(stageCount(stats, "NEW")).isEqualTo(1);
        assertThat(stats.get("leads").get("totalElements").asLong()).isEqualTo(3);

        JsonNode required = data(mvc.perform(get("/api/leads/stats/without-task")
                .param("requiredOnly", "true").param("status", "CONTACTED").with(as(admin)))
            .andExpect(status().isOk()));
        assertThat(required.get("total").asLong()).isEqualTo(2);
        assertThat(required.get("byStage")).allMatch(s -> s.get("requiresTask").asBoolean());
        assertThat(required.get("leads").get("totalElements").asLong()).isEqualTo(2);

        // SALES_MANAGER — faqat o'zinikilar
        JsonNode mine = data(mvc.perform(get("/api/leads/stats/without-task").with(as(manager)))
            .andExpect(status().isOk()));
        assertThat(mine.get("total").asLong()).isEqualTo(2);
        assertThat(mine.get("leads").get("content")).noneMatch(l -> l.get("id").asLong() == otherManagers);
    }

    @Test
    void stageCrud_exposesRequiresTask() throws Exception {
        Long id = leadStageRepository.findByCode("CONTACTED").orElseThrow().getId();
        mvc.perform(get("/api/lead-stages").with(as(manager)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.code == 'CONTACTED')].requiresTask").value(true))
            .andExpect(jsonPath("$.data[?(@.code == 'NEW')].requiresTask").value(false));
        LeadStage stage = leadStageRepository.findById(id).orElseThrow();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/lead-stages/" + id)
                .with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameUz\":\"" + stage.getNameUz() + "\",\"nameRu\":\"" + stage.getNameRu()
                    + "\",\"nameEn\":\"" + stage.getNameEn() + "\",\"color\":\"" + stage.getColor()
                    + "\",\"requiresTask\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requiresTask").value(false));
        assertThat(leadStageService.requiresTask("CONTACTED")).isFalse();
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
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private Long lead(String status, User assignee) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName("Lid " + System.nanoTime())
            .phone("+998901112233").status(status)
            .assignedUser(assignee != null ? userRepository.findById(assignee.getId()).orElseThrow() : null)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .converted(false).build()).getId());
    }

    private Long task(Long leadId, User assignee) {
        return inTx(() -> taskRepository.save(Task.builder().title("Qo'ng'iroq")
            .dueAt(LocalDateTime.now().plusDays(1)).status(TaskStatus.OPEN)
            .assignedTo(userRepository.findById(assignee.getId()).orElseThrow())
            .createdBy(userRepository.findById(assignee.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow()).build()).getId());
    }

    private List<Task> openTasks(Long leadId) {
        return inTx(() -> {
            List<Task> tasks = taskRepository.findOpenByLeadIds(List.of(leadId));
            tasks.forEach(t -> t.getAssignedTo().getId());
            return tasks;
        });
    }

    private String leadStatus(Long leadId) {
        return leadRepository.findById(leadId).orElseThrow().getStatus();
    }

    private ResultActions changeStatus(Long leadId, String body, User as) throws Exception {
        return mvc.perform(patch("/api/leads/" + leadId + "/status").with(as(as))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions complete(Long taskId, String body, User as) throws Exception {
        return mvc.perform(patch("/api/tasks/" + taskId + "/complete").with(as(as))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static long stageCount(JsonNode stats, String code) {
        for (JsonNode s : stats.get("byStage")) {
            if (code.equals(s.get("status").asText())) {
                return s.get("count").asLong();
            }
        }
        return -1;
    }
}
