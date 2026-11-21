package com.crm.calls;

import com.crm.entity.Lead;
import com.crm.entity.LeadComment;
import com.crm.entity.PhoneCall;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadStageRepository;
import com.crm.repository.TaskRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Natija → lid timeline / bosqich / vazifa (mavjud servislar orqali), CRM'dan qo'ng'iroq so'rovi oqimi, CRM ro'yxatlari
 * ruxsatlari va statistika — docs/design/calls.md §4.5, §6, §7.
 */
class CallResultRequestStatsTest extends CallsItBase {

    @Autowired private LeadStageRepository stageRepository;
    @Autowired private LeadCommentRepository commentRepository;
    @Autowired private TaskRepository taskRepository;

    private org.springframework.test.web.servlet.ResultActions result(String token, Long callId, String json)
            throws Exception {
        return mvc.perform(device(patch("/api/calls/app/calls/" + callId + "/result"), token)
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String due() {
        // TaskService "kelajak"ni tizim soatidan tekshiradi
        return LocalDateTime.now().plusDays(2).withNano(0).toString();
    }

    // ── Natija ───────────────────────────────────────────────────────────

    @Test
    void result_onLead_writesTimeline_changesStage_createsTask() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String caller = phone();
        String body = syncOk(token, item("c1", "IN", caller, 10, 155));
        Long callId = callId(body, 0);
        Long leadId = ((Number) JsonPath.read(body, "$.data.results[0].contact.leadId")).longValue();
        Long contacted = inTx(() -> stageRepository.findByCode("CONTACTED").orElseThrow().getId());
        mvc.perform(device(get("/api/calls/app/lead-stages"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.code == 'CONTACTED')].id").value(contacted.intValue()));

        result(token, callId, "{\"resultCode\":\"talked\",\"note\":\"Ingliz tili, kechki guruh\","
                + "\"leadStageId\":" + contacted + ",\"nextTask\":{\"title\":\"Sinov darsini eslatish\","
                + "\"dueAt\":\"" + due() + "\",\"type\":\"CALL\"}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.timelineWritten").value(true))
            .andExpect(jsonPath("$.data.stageChanged").value(true))
            .andExpect(jsonPath("$.data.taskId").isNumber())
            .andExpect(jsonPath("$.data.call.resultCode").value("TALKED"))
            .andExpect(jsonPath("$.data.call.note").value("Ingliz tili, kechki guruh"))
            .andExpect(jsonPath("$.data.call.resultBy.id").value(sm.getId()));

        Lead lead = inTx(() -> leadRepository.findById(leadId).orElseThrow());
        assertThat(lead.getStatus()).isEqualTo("CONTACTED");
        List<LeadComment> comments = inTx(() -> commentRepository.findAll().stream()
            .filter(c -> c.getLead().getId().equals(leadId)).toList());
        assertThat(comments).anySatisfy(c -> assertThat(c.getText())
            .startsWith("📞 Kiruvchi qo'ng'iroq · 2:35 — Gaplashildi. Ingliz tili, kechki guruh"));
        List<Task> open = inTx(() -> taskRepository.findOpenByLeadIds(List.of(leadId)));
        assertThat(open).anySatisfy(t -> {
            assertThat(t.getTitle()).isEqualTo("Sinov darsini eslatish");
            assertThat(t.getAssignedTo().getId()).isEqualTo(sm.getId());
        });
        PhoneCall call = inTx(() -> callRepository.findById(callId).orElseThrow());
        assertThat(call.getResultAt()).isNotNull();
    }

    @Test
    void result_validation_studentTask_andForeignLeadForbidden() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        String studentPhone = phone();
        Long studentId = student("Jasur", studentPhone);
        Long studentCall = callId(syncOk(token, item("s1", "OUT", studentPhone, 10, 30)), 0);

        result(token, studentCall, "{\"resultCode\":\"MAYBE\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.result.codeInvalid"));
        result(token, studentCall, "{\"resultCode\":\"TALKED\",\"leadStageId\":1}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.result.notLead"));
        result(token, studentCall, "{\"resultCode\":\"CALLBACK\",\"note\":\"To'lovni ertaga qiladi\","
                + "\"nextTask\":{\"title\":\"To'lovni so'rash\",\"dueAt\":\"" + due() + "\"}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.timelineWritten").value(false))
            .andExpect(jsonPath("$.data.call.note").value("To'lovni ertaga qiladi"));
        Task task = inTx(() -> taskRepository.findOpenByStudentIds(List.of(studentId)).get(0));
        assertThat(task.getTitle()).isEqualTo("To'lovni so'rash");

        // Boshqa operatorning lidi: natija saqlanadi, timeline yo'q; bosqich so'ralsa — 403 va hech narsa saqlanmaydi
        User owner = staff(UserRole.SALES_MANAGER);
        String leadPhone = phone();
        Long leadId = lead("Begona lid", leadPhone, owner);
        Long foreignCall = callId(syncOk(token, item("f1", "IN", leadPhone, 5, 20)), 0);
        Long contacted = inTx(() -> stageRepository.findByCode("CONTACTED").orElseThrow().getId());
        result(token, foreignCall, "{\"resultCode\":\"TALKED\",\"leadStageId\":" + contacted + "}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("calls.result.leadForbidden"));
        assertThat(inTx(() -> callRepository.findById(foreignCall).orElseThrow().getResultCode())).isNull();
        result(token, foreignCall, "{\"resultCode\":\"NO_ANSWER\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.timelineWritten").value(false));
        assertThat(inTx(() -> leadRepository.findById(leadId).orElseThrow().getStatus())).isEqualTo("NEW");

        // Begona qo'ng'iroq — 404
        result(register(staff(UserRole.SALES_HEAD)), studentCall, "{\"resultCode\":\"TALKED\"}")
            .andExpect(status().isNotFound());
    }

    // ── CRM'dan qo'ng'iroq so'rovi ───────────────────────────────────────

    @Test
    void callRequest_flow() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String leadPhone = phone();
        Long leadId = lead("So'rov lidi", leadPhone, sm);

        mvc.perform(post("/api/calls/requests").with(as(sm)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadId\":" + leadId + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("calls.request.noDevice"));

        String token = register(sm);
        String created = mvc.perform(post("/api/calls/requests").with(as(sm)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadId\":" + leadId + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.phone").value(leadPhone))
            .andExpect(jsonPath("$.data.contactName").value("So'rov lidi"))
            .andReturn().getResponse().getContentAsString();
        Long requestId = ((Number) JsonPath.read(created, "$.data.id")).longValue();

        mvc.perform(device(get("/api/calls/app/requests/pending"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(requestId));
        String ack = "/api/calls/app/requests/" + requestId + "/ack";
        mvc.perform(device(post(ack), token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERED"));
        mvc.perform(device(post(ack), token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
            .andExpect(status().isOk());
        mvc.perform(device(post(ack), token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DIALED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.dialedAt").isNotEmpty());
        mvc.perform(device(post(ack), token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("calls.request.invalidTransition"));
        mvc.perform(get("/api/calls/requests/" + requestId).with(as(sm)))
            .andExpect(jsonPath("$.data.status").value("DIALED"));

        // Boshqa xodim telefoniga: SM — 403, SALES_HEAD — mumkin; yangisi eskisini bekor qiladi; muddat o'tsa EXPIRED
        User sh = staff(UserRole.SALES_HEAD);
        mvc.perform(post("/api/calls/requests").with(as(staff(UserRole.SALES_MANAGER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone() + "\",\"userId\":" + sm.getId() + "}"))
            .andExpect(status().isForbidden());
        Long r1 = ((Number) JsonPath.read(mvc.perform(post("/api/calls/requests").with(as(sh))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"90 123 45 67\",\"userId\":" + sm.getId() + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.phone").value("+998901234567"))
            .andReturn().getResponse().getContentAsString(), "$.data.id")).longValue();
        Long r2 = ((Number) JsonPath.read(mvc.perform(post("/api/calls/requests").with(as(sm))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+998901112233\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.id")).longValue();
        mvc.perform(get("/api/calls/requests/" + r1).with(as(sh))).andExpect(jsonPath("$.data.status").value("CANCELLED"));

        clock.setDateTime(LocalDateTime.now(clock).plusMinutes(3));
        mvc.perform(device(get("/api/calls/app/requests/pending"), token))
            .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/calls/requests/" + r2).with(as(sm))).andExpect(jsonPath("$.data.status").value("EXPIRED"));
        mvc.perform(device(post("/api/calls/app/requests/" + r2 + "/ack"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DIALED\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/calls/requests").with(as(sm)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"abc\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.phone.invalid"));
    }

    // ── CRM ro'yxatlari va statistika ────────────────────────────────────

    @Test
    void lists_respectRoles() throws Exception {
        User sm1 = staff(UserRole.SALES_MANAGER);
        User sm2 = staff(UserRole.SALES_MANAGER);
        String studentPhone = phone();
        Long studentId = student("Malika", studentPhone);
        String leadPhone = phone();
        Long leadId = lead("Ro'yxat lidi", leadPhone, sm1);
        String t1 = register(sm1);
        String t2 = register(sm2);
        syncOk(t1, item("a", "OUT", leadPhone, 30, 60), item("b", "IN", studentPhone, 20, 10));
        syncOk(t2, item("c", "OUT", studentPhone, 10, 5));

        mvc.perform(get("/api/calls").with(as(sm1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/calls").with(as(sm1)).param("userId", String.valueOf(sm2.getId())))
            .andExpect(jsonPath("$.data.totalElements").value(2));   // SM uchun filtr majburan o'zi
        mvc.perform(get("/api/calls").with(as(staff(UserRole.SALES_HEAD))))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content[0].direction").value("OUT"));
        mvc.perform(get("/api/calls").with(as(staff(UserRole.ADMIN))).param("contactType", "STUDENT")
                .param("direction", "OUT"))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].student.id").value(studentId));
        mvc.perform(get("/api/calls").with(as(staff(UserRole.ADMIN))).param("direction", "SIDEWAYS"))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/leads/" + leadId + "/calls").with(as(sm1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].lead.id").value(leadId));
        mvc.perform(get("/api/leads/" + leadId + "/calls").with(as(sm2))).andExpect(status().isForbidden());
        mvc.perform(get("/api/students/" + studentId + "/calls").with(as(sm2)))
            .andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(get("/api/students/" + studentId + "/calls").with(as(staff(UserRole.SUPER_ADMIN))))
            .andExpect(jsonPath("$.data.content.length()").value(2));
        mvc.perform(get("/api/students/" + studentId + "/calls").with(as(staff(UserRole.TEACHER))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/calls").with(as(staff(UserRole.ACCOUNTANT)))).andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings(value = "unchecked")
    void stats_perUser_withMissedNotCalledBack() throws Exception {
        User sm1 = staff(UserRole.SALES_MANAGER);
        User sm2 = staff(UserRole.SALES_MANAGER);
        String t1 = register(sm1);
        String t2 = register(sm2);
        String x = phone();
        String y = phone();
        String z = phone();
        callsProperties.setAutoLead(false);
        try {
            syncOk(t1,
                item("1", "IN", phone(), 100, 60),
                item("2", "OUT", phone(), 90, 30),
                item("3", "MISSED", x, 80, 0),
                item("4", "MISSED", x, 70, 0),          // o'sha raqam — bitta deb sanaladi
                item("5", "MISSED", y, 60, 0),
                item("6", "REJECTED", z, 50, 0));
            syncOk(t2,
                item("7", "OUT", y, 40, 0),             // y ga boshqa xodim qayta qo'ng'iroq qildi
                item("8", "IN", z, 55, 10));            // z — javobsizdan OLDIN gaplashilgan: hisobga olinmaydi
        } finally {
            callsProperties.setAutoLead(true);
        }

        String body = mvc.perform(get("/api/calls/stats").with(as(staff(UserRole.SALES_HEAD))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String row = "$.data.rows[?(@.userId == " + sm1.getId() + ")]";
        assertThat((List<Integer>) JsonPath.read(body, row + ".total")).containsExactly(6);
        assertThat((List<Integer>) JsonPath.read(body, row + ".incoming")).containsExactly(1);
        assertThat((List<Integer>) JsonPath.read(body, row + ".outgoing")).containsExactly(1);
        assertThat((List<Integer>) JsonPath.read(body, row + ".missed")).containsExactly(3);
        assertThat((List<Integer>) JsonPath.read(body, row + ".rejected")).containsExactly(1);
        assertThat((List<Integer>) JsonPath.read(body, row + ".talkSeconds")).containsExactly(90);
        assertThat((List<Integer>) JsonPath.read(body, row + ".missedNotCalledBack")).containsExactly(2);  // x va z
        assertThat((Integer) JsonPath.read(body, "$.data.totals.total")).isEqualTo(8);

        mvc.perform(get("/api/calls/stats").with(as(sm2)))
            .andExpect(jsonPath("$.data.rows.length()").value(1))
            .andExpect(jsonPath("$.data.rows[0].userId").value(sm2.getId()));
        mvc.perform(get("/api/calls/stats").with(as(sm2)).param("from", "2026-09-20").param("to", "2026-09-10"))
            .andExpect(status().isBadRequest());
    }
}
