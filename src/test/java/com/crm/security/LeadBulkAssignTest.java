package com.crm.security;

import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lid mas'uli (buyurtmachi 11.10.2026): o'zgartirish faqat SUPER_ADMIN va SALES_HEAD — bitta lid ({@code PATCH .../assign})
 * va ommaviy ({@code POST /api/leads/bulk-assign}); ommaviy biriktirish yagona biriktirish mantig'ini qayta ishlatadi.
 */
class LeadBulkAssignTest extends Phase5ItBase {

    @Autowired LeadRepository leadRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired LeadAssignmentRepository assignmentRepository;
    @Autowired ObjectMapper objectMapper;

    private User superAdmin;
    private User head;
    private User admin;
    private User manager;
    private User target;

    @BeforeEach
    void setUp() {
        superAdmin = newUser(UserRole.SUPER_ADMIN);
        head = newUser(UserRole.SALES_HEAD);
        admin = newUser(UserRole.ADMIN);
        manager = newUser(UserRole.SALES_MANAGER);
        target = newUser(UserRole.SALES_MANAGER);
    }

    private Long lead(String status, User assignee) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName("Lid " + System.nanoTime())
            .phone("+998901112233").status(status)
            .assignedUser(assignee != null ? userRepository.findById(assignee.getId()).orElseThrow() : null)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .converted(false).build()).getId());
    }

    private Long task(Long leadId, User assignee, boolean auto) {
        return inTx(() -> taskRepository.save(Task.builder().title("Vazifa")
            .dueAt(LocalDateTime.now().plusDays(1)).status(TaskStatus.OPEN)
            .assignedTo(userRepository.findById(assignee.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow())
            .autoCreated(auto).build()).getId());
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

    private ResultActions bulk(User actor, List<Long> leadIds, Long userId) throws Exception {
        String ids = leadIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mvc.perform(post("/api/leads/bulk-assign").with(as(actor)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"leadIds\":[" + ids + "],\"userId\":" + userId + "}"));
    }

    private ResultActions single(User actor, Long leadId, Long userId) throws Exception {
        return mvc.perform(patch("/api/leads/" + leadId + "/assign").with(as(actor))
            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + userId + "}"));
    }

    // ── Ruxsatlar: ikki qatlam (URL + @PreAuthorize) ────────────────────

    @Test
    void permissions_onlySuperAdminAndSalesHead() throws Exception {
        Long leadId = lead("NEW", null);
        single(admin, leadId, target.getId()).andExpect(status().isForbidden());
        single(manager, leadId, target.getId()).andExpect(status().isForbidden());
        single(manager, leadId, null).andExpect(status().isForbidden());           // olib tashlash ham
        assertThat(assigneeOf(leadId)).isNull();
        single(superAdmin, leadId, target.getId()).andExpect(status().isOk());
        single(head, leadId, manager.getId()).andExpect(status().isOk());
        assertThat(assigneeOf(leadId)).isEqualTo(manager.getId());
        single(head, leadId, null).andExpect(status().isOk());
        assertThat(assigneeOf(leadId)).isNull();

        bulk(admin, List.of(leadId), target.getId()).andExpect(status().isForbidden());
        bulk(manager, List.of(leadId), target.getId()).andExpect(status().isForbidden());
        assertThat(assigneeOf(leadId)).isNull();
        bulk(superAdmin, List.of(leadId), target.getId()).andExpect(status().isOk());
        bulk(head, List.of(leadId), manager.getId()).andExpect(status().isOk());
        assertThat(assigneeOf(leadId)).isEqualTo(manager.getId());

        // Boshqa lid ruxsatlariga tegilmagan: ADMIN va SM lidlarni ko'radi
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/leads/" + leadId)
                .with(as(admin))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/leads/operators")
                .with(as(admin))).andExpect(status().isOk());
    }

    // ── Natija: assigned / skipped / failed ─────────────────────────────

    @Test
    void bulkAssign_result_assignedSkippedFailed_closedStagesToo_historyRecorded() throws Exception {
        Long unassigned = lead("NEW", null);
        Long fromOther = lead("CONTACTED", manager);
        Long already = lead("CONTACTED", target);
        Long converted = lead("CONVERTED", manager);
        Long rejected = lead("REJECTED", null);
        long missing = 9_999_999L;

        JsonNode data = objectMapper.readTree(bulk(head,
                List.of(unassigned, fromOther, already, converted, rejected, missing, unassigned), target.getId())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("data");

        assertThat(data.get("requested").asInt()).isEqualTo(6);                     // takror bitta sanaladi
        assertThat(data.get("assigned").asInt()).isEqualTo(4);
        assertThat(data.get("skipped").asInt()).isEqualTo(1);
        assertThat(data.get("failed")).hasSize(1);
        assertThat(data.get("failed").get(0).get("leadId").asLong()).isEqualTo(missing);
        assertThat(data.get("failed").get(0).get("reason").asText()).isEqualTo("lead.notFound");

        for (Long id : List.of(unassigned, fromOther, already, converted, rejected)) {
            assertThat(assigneeOf(id)).as("lid " + id).isEqualTo(target.getId());
        }
        // Tayinlash tarixi — yagona biriktirish mantig'idan (LeadFunnelTracker.onAssigned)
        List<LeadAssignment> history = inTx(() -> assignmentRepository.findAll()).stream()
            .filter(a -> a.getLeadId().equals(fromOther)).toList();
        assertThat(history).anySatisfy(a -> {
            assertThat(a.getUserId()).isEqualTo(target.getId());
            assertThat(a.getUnassignedAt()).isNull();
        });
        // Allaqachon shu xodimda — tegilmagan (yangi tarix qatori yo'q)
        assertThat(inTx(() -> assignmentRepository.findAll()).stream()
            .filter(a -> a.getLeadId().equals(already)).count()).isZero();
    }

    @Test
    void bulkAssign_movesOpenAutoTasks_manualStay() throws Exception {
        Long leadId = lead("CONTACTED", manager);
        Long auto = task(leadId, manager, true);
        Long manual = task(leadId, manager, false);

        bulk(superAdmin, List.of(leadId), target.getId()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assigned").value(1));

        assertThat(assigneeOfTask(auto)).isEqualTo(target.getId());
        assertThat(assigneeOfTask(manual)).isEqualTo(manager.getId());
    }

    // ── Validatsiya ─────────────────────────────────────────────────────

    @Test
    void bulkAssign_invalidUser_400() throws Exception {
        Long leadId = lead("NEW", null);
        User inactive = newUser(UserRole.SALES_MANAGER);
        inTx(() -> {
            User u = userRepository.findById(inactive.getId()).orElseThrow();
            u.setIsActive(false);
            userRepository.save(u);
        });
        User accountant = newUser(UserRole.ACCOUNTANT);

        for (Long bad : List.of(inactive.getId(), accountant.getId(), 9_999_999L)) {
            bulk(superAdmin, List.of(leadId), bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("lead.bulkAssign.userInvalid"));
        }
        bulk(superAdmin, List.of(leadId), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.bulkAssign.userRequired"));
        assertThat(assigneeOf(leadId)).isNull();
    }

    @Test
    void bulkAssign_sizeLimits_400() throws Exception {
        List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
        bulk(head, tooMany, target.getId())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.bulkAssign.size"));
        bulk(head, List.of(), target.getId())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("lead.bulkAssign.size"));
        // Aynan 200 — chegara ichida (lidlar yo'q — hammasi failed, lekin 400 emas)
        bulk(head, LongStream.rangeClosed(9_000_001L, 9_000_200L).boxed().toList(), target.getId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requested").value(200))
            .andExpect(jsonPath("$.data.failed.length()").value(200));
    }
}
