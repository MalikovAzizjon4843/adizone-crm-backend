package com.crm.security;

import com.crm.entity.Lead;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lid yopiq bosqichga (kind REJECTED/CONVERTED) o'tganda ochiq vazifalari avtomatik bekor qilinadi:
 * {@code CANCELLED}, {@code result = "Lid yopildi"}, kim/qachon ({@code completedBy/completedAt}),
 * bosqich o'zgarishi auditida {@code tasksCancelled}. Bosqich qaytarilsa vazifalar tiklanmaydi.
 */
class LeadClosedTasksTest extends Phase5ItBase {

    @Autowired LeadRepository leadRepository;
    @Autowired TaskRepository taskRepository;

    private User head;
    private User manager;

    @BeforeEach
    void setUp() {
        head = newUser(UserRole.SALES_HEAD);
        manager = newUser(UserRole.SALES_MANAGER);
    }

    private Long lead(String status) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName("Lid Testov")
            .phone("+99893" + String.format("%07d", System.nanoTime() % 10_000_000L)).status(status)
            .assignedUser(userRepository.findById(manager.getId()).orElseThrow())
            .assignedAt(LocalDateTime.now())
            .converted(false).build()).getId());
    }

    private Long task(Long leadId, TaskStatus status, boolean auto) {
        return inTx(() -> taskRepository.save(Task.builder().title("Qo'ng'iroq")
            .dueAt(LocalDateTime.now().plusDays(1)).status(status)
            .result(status == TaskStatus.DONE ? "Gaplashildi" : null)
            .assignedTo(userRepository.findById(manager.getId()).orElseThrow())
            .lead(leadRepository.findById(leadId).orElseThrow())
            .autoCreated(auto).build()).getId());
    }

    private Task load(Long taskId) {
        return inTx(() -> {
            Task t = taskRepository.findById(taskId).orElseThrow();
            if (t.getCompletedBy() != null) {
                t.getCompletedBy().getId();
            }
            return t;
        });
    }

    private ResultActions moveTo(Long leadId, String status) throws Exception {
        return mvc.perform(patch("/api/leads/" + leadId + "/status").with(as(head))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private String lastLeadStatusAuditDetails(Long leadId) throws InterruptedException {
        String sql = "SELECT details_json FROM audit_logs WHERE action = 'STATUS_CHANGE' AND entity_type = 'Lead'"
            + " AND entity_id = ? ORDER BY id DESC";
        long deadline = System.currentTimeMillis() + 5000;          // audit @Async — commit'dan keyin
        while (System.currentTimeMillis() < deadline) {
            List<String> rows = jdbc.queryForList(sql, String.class, leadId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        return null;
    }

    private void assertCancelledByLeadClose(Long taskId) {
        Task t = load(taskId);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(t.getResult()).isEqualTo("Lid yopildi");
        assertThat(t.getCompletedAt()).isNotNull();
        assertThat(t.getCompletedBy()).isNotNull();
        assertThat(t.getCompletedBy().getId()).isEqualTo(head.getId());
    }

    @Test
    void reject_cancelsOpenTasks_keepsDone_auditsIds_revertDoesNotRestore() throws Exception {
        Long leadId = lead("CONTACTED");
        Long manual = task(leadId, TaskStatus.OPEN, false);
        Long auto = task(leadId, TaskStatus.OPEN, true);
        Long done = task(leadId, TaskStatus.DONE, false);

        moveTo(leadId, "REJECTED").andExpect(status().isOk());

        assertCancelledByLeadClose(manual);
        assertCancelledByLeadClose(auto);
        Task doneTask = load(done);
        assertThat(doneTask.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(doneTask.getResult()).isEqualTo("Gaplashildi");
        assertThat(lastLeadStatusAuditDetails(leadId))
            .contains("\"field\":\"tasksCancelled\"")
            .contains(String.valueOf(manual)).contains(String.valueOf(auto));

        // Bosqich qaytarildi — vazifalar tiklanmaydi
        moveTo(leadId, "CONTACTED").andExpect(status().isOk());
        assertThat(load(manual).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(load(auto).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void moveBetweenOpenStages_leavesTasksOpen() throws Exception {
        Long leadId = lead("NEW");
        Long open = task(leadId, TaskStatus.OPEN, false);

        moveTo(leadId, "CONTACTED").andExpect(status().isOk());

        Task t = load(open);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(t.getCompletedAt()).isNull();
        assertThat(lastLeadStatusAuditDetails(leadId)).doesNotContain("tasksCancelled");
    }

    @Test
    void reject_withNextTask_newTaskStaysOpen() throws Exception {
        Long leadId = lead("CONTACTED");
        Long old = task(leadId, TaskStatus.OPEN, false);
        String due = LocalDateTime.now().plusDays(2).withNano(0).toString();

        mvc.perform(patch("/api/leads/" + leadId + "/status").with(as(head))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REJECTED\",\"nextTask\":{\"title\":\"3 oydan keyin qayta qo'ng'iroq\","
                    + "\"dueAt\":\"" + due + "\",\"assigneeId\":" + manager.getId() + "}}"))
            .andExpect(status().isOk());

        assertCancelledByLeadClose(old);
        List<Task> open = inTx(() -> taskRepository.findByLead_IdAndStatus(leadId, TaskStatus.OPEN));
        assertThat(open).extracting(Task::getTitle).containsExactly("3 oydan keyin qayta qo'ng'iroq");
    }

    @Test
    void convertToStudent_cancelsOpenTasks() throws Exception {
        Long leadId = lead("OFFLINE_PAID");
        Long open = task(leadId, TaskStatus.OPEN, false);

        mvc.perform(post("/api/leads/" + leadId + "/convert").with(as(head))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studyFormat\":\"OFFLINE\"}"))
            .andExpect(status().isCreated());

        assertThat(inTx(() -> leadRepository.findById(leadId).orElseThrow().getStatus()))
            .isEqualTo("CONVERTED_OFFLINE");
        assertCancelledByLeadClose(open);
    }
}
