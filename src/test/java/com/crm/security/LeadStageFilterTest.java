package com.crm.security;

import com.crm.entity.Lead;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStageRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/leads?stageId=} — bosqich id lari bo'yicha (ko'p qiymatli, YOKI), SALES_MANAGER doirasi saqlanadi. */
class LeadStageFilterTest extends Phase5ItBase {

    @Autowired LeadRepository leadRepository;
    @Autowired LeadStageRepository stageRepository;

    private Long lead(String status, User assignee) {
        return inTx(() -> leadRepository.save(Lead.builder().fullName("Lid " + System.nanoTime())
            .phone("+998901112233").status(status)
            .assignedUser(assignee != null ? userRepository.findById(assignee.getId()).orElseThrow() : null)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .converted(false).build()).getId());
    }

    private Long stageId(String code) {
        return stageRepository.findByCode(code).orElseThrow().getId();
    }

    private List<Long> ids(String body) {
        List<Number> raw = JsonPath.read(body, "$.data.content[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    @Test
    void stageId_multiValue_bothSyntaxes_andWithStatusAndScope() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        User manager = newUser(UserRole.SALES_MANAGER);
        Long n1 = lead("NEW", manager);
        Long c1 = lead("CONTACTED", manager);
        Long c2 = lead("CONTACTED", null);
        Long r1 = lead("REJECTED", manager);

        String body = mvc.perform(get("/api/leads").with(as(admin))
                .param("stageId", String.valueOf(stageId("NEW")))
                .param("stageId", String.valueOf(stageId("CONTACTED"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).containsExactlyInAnyOrder(n1, c1, c2);

        body = mvc.perform(get("/api/leads").with(as(admin))
                .param("stageId", stageId("CONTACTED") + "," + stageId("REJECTED")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).containsExactlyInAnyOrder(c1, c2, r1);

        // status (kod) bilan birga — VA
        body = mvc.perform(get("/api/leads").with(as(admin))
                .param("stageId", stageId("CONTACTED") + "," + stageId("REJECTED")).param("status", "REJECTED"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).containsExactly(r1);

        // SALES_MANAGER — faqat o'ziniki
        body = mvc.perform(get("/api/leads").with(as(manager)).param("stageId", String.valueOf(stageId("CONTACTED"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).containsExactly(c1);

        // Noma'lum id — hech narsa (bosqich faqat lidsiz bo'lsa o'chiriladi)
        body = mvc.perform(get("/api/leads").with(as(admin)).param("stageId", "987654321"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).isEmpty();

        // Filtrsiz — hammasi
        body = mvc.perform(get("/api/leads").with(as(admin)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).contains(n1, c1, c2, r1);
    }
}
