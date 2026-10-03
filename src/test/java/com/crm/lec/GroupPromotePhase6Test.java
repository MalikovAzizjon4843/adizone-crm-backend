package com.crm.lec;

import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.StudentGroupRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** phase6-api §5: guruhga ko'chirish — preview, ogohlantirishlar, atomar apply, Idempotency-Key, audit. */
class GroupPromotePhase6Test extends LecItBase {

    @Autowired StudentGroupRepository studentGroupRepository;

    private User admin;
    private Long from;
    private Long target;
    private Long a;
    private Long b;
    private Long frozen;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
        from = fixtures.group(fixtures.course(600_000), GroupStatus.COMPLETED);
        target = fixtures.group(fixtures.course(800_000), GroupStatus.ACTIVE);
        a = fixtures.student();
        b = fixtures.student();
        frozen = fixtures.student();
        fixtures.enrollment(a, from).start(LocalDate.of(2026, 9, 1)).save();
        fixtures.enrollment(b, from).start(LocalDate.of(2026, 9, 1)).override(500_000).save();
        Long fsg = fixtures.enrollment(frozen, from).start(LocalDate.of(2026, 9, 1)).save();
        inTx(() -> {
            var sg = studentGroupRepository.findById(fsg).orElseThrow();
            sg.setFrozenFrom(LocalDate.of(2026, 9, 10));
            studentGroupRepository.save(sg);
        });
    }

    private ResultActions call(User as, String path, String body, String key) throws Exception {
        var req = post("/api/groups/" + from + path).with(as(as)).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private String body(Long... ids) {
        StringBuilder s = new StringBuilder();
        for (Long id : ids) {
            s.append(s.length() == 0 ? "" : ",").append(id);
        }
        return "{\"targetGroupId\":" + target + ",\"studentIds\":[" + s + "],\"note\":\"Keyingi daraja\"}";
    }

    @Test
    void preview_pricesAndWarnings_blockedApplyChangesNothing() throws Exception {
        JsonNode p = data(call(admin, "/promote/preview", body(a, b, frozen), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canApply").value(false))
            .andExpect(jsonPath("$.data.warnings[*].code", hasItem("SOURCE_CLOSED")))
            .andExpect(jsonPath("$.data.targetGroup.freeSeats").value(100))
            .andReturn());
        JsonNode ra = p.get("students").get(0);
        assertThat(ra.get("studentId").asLong()).isEqualTo(a);
        assertThat(ra.get("oldPrice").decimalValue()).isEqualByComparingTo("600000");
        assertThat(ra.get("newPrice").decimalValue()).isEqualByComparingTo("800000");
        assertThat(ra.get("priceDiff").decimalValue()).isEqualByComparingTo("200000");
        assertThat(ra.get("warnings").toString()).contains("PRICE_CHANGED");
        JsonNode rb = p.get("students").get(1);
        assertThat(rb.get("newPrice").decimalValue()).isEqualByComparingTo("500000");   // individual narx saqlanadi
        assertThat(rb.get("warnings")).isEmpty();
        assertThat(p.get("students").get(2).get("warnings").toString()).contains("\"code\":\"FROZEN\",\"blocking\":true");

        call(admin, "/promote", body(a, b, frozen), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("group.promote.blocked"))
            .andExpect(jsonPath("$.data.students[0].studentId").value(frozen.intValue()))
            .andExpect(jsonPath("$.data.students[0].codes[0]").value("FROZEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_groups WHERE group_id = ?", Integer.class, target))
            .isZero();

        // Maqsad guruh to'la; yopiq maqsad
        inTx(() -> {
            var g = groupRepository.findById(target).orElseThrow();
            g.setMaxStudents(1);
            groupRepository.save(g);
        });
        call(admin, "/promote/preview", body(a, b), null)
            .andExpect(jsonPath("$.data.warnings[*].code", hasItem("TARGET_FULL")))
            .andExpect(jsonPath("$.data.canApply").value(false));
        inTx(() -> {
            var g = groupRepository.findById(target).orElseThrow();
            g.setMaxStudents(100);
            g.setStatus(GroupStatus.CANCELLED);
            groupRepository.save(g);
        });
        call(admin, "/promote/preview", body(a), null)
            .andExpect(jsonPath("$.data.warnings[*].code", hasItem("TARGET_CLOSED")));
        // Guruhda yo'q o'quvchi
        Long stranger = fixtures.student();
        call(admin, "/promote/preview", body(stranger), null)
            .andExpect(jsonPath("$.data.students[0].warnings[0].code").value("NOT_IN_GROUP"));
    }

    @Test
    void promote_transfersAll_idempotent_audited_validation() throws Exception {
        JsonNode r = data(call(admin, "/promote", body(b, a), "promo-1")
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("X-Idempotent-Replay"))
            .andExpect(jsonPath("$.data.students.length()").value(2))
            .andExpect(jsonPath("$.data.students[0].studentId").value(a.intValue()))   // id o'sishida
            .andReturn());
        long batchId = r.get("batchId").asLong();
        long newSgA = r.get("students").get(0).get("toStudentGroupId").asLong();
        assertThat(jdbc.queryForObject("SELECT group_id FROM student_groups WHERE id = ?", Long.class, newSgA))
            .isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT is_active FROM student_groups WHERE id = ?", Boolean.class,
            r.get("students").get(0).get("fromStudentGroupId").asLong())).isFalse();
        assertThat(jdbc.queryForObject("SELECT exit_reason_code FROM student_groups WHERE id = ?", String.class,
            r.get("students").get(0).get("fromStudentGroupId").asLong())).isEqualTo("TRANSFERRED");
        assertThat(jdbc.queryForObject("SELECT monthly_price_override FROM student_groups WHERE id = ?",
            java.math.BigDecimal.class, r.get("students").get(1).get("toStudentGroupId").asLong()))
            .isEqualByComparingTo("500000");

        // Takror — o'sha natija, ikkinchi ko'chirish yo'q
        call(admin, "/promote", body(a, b), "promo-1")
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.data.batchId").value((int) batchId))
            .andExpect(jsonPath("$.data.idempotentReplay").value(true));
        call(admin, "/promote", body(a), "promo-1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("group.promote.idempotency.mismatch"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_groups WHERE group_id = ?", Integer.class, target))
            .isEqualTo(2);
        assertThat(awaitAudits("TRANSFER", "Group")).isPositive();

        // Endi a manba guruhda yo'q
        call(admin, "/promote/preview", body(a), null)
            .andExpect(jsonPath("$.data.students[0].warnings[0].code").value("NOT_IN_GROUP"));
        mvc.perform(post("/api/groups/" + target + "/promote").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetGroupId\":" + target + ",\"studentIds\":[" + a + "]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("group.promote.sameGroup"));
        call(admin, "/promote", "{\"targetGroupId\":" + target + ",\"studentIds\":[" + b + "],\"date\":\"2026-09-01\"}", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("group.promote.date.invalid"));
        call(admin, "/promote", "{\"targetGroupId\":" + target + ",\"studentIds\":[]}", null)
            .andExpect(status().isBadRequest());
        call(newUser(UserRole.ACCOUNTANT), "/promote/preview", body(b), null).andExpect(status().isForbidden());
        call(newTeacher().user(), "/promote", body(b), null).andExpect(status().isForbidden());
    }
}
