package com.crm.security;

import com.crm.entity.ContractTemplate;
import com.crm.entity.User;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ErrorResponse;
import com.crm.exception.GlobalExceptionHandler;
import com.crm.repository.ContractTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * X-02: baza cheklovi buzilishi 500 emas — 409/400 kod bilan (phase5-audit §12.1 #8).
 */
class ErrorMappingTest extends Phase5ItBase {

    @Autowired
    GlobalExceptionHandler handler;
    @Autowired
    ContractTemplateRepository templateRepository;

    private ResponseEntity<ErrorResponse> map(String sqlState) {
        return handler.handleDataIntegrity(new DataIntegrityViolationException("x",
            new SQLException("constraint", sqlState)));
    }

    @Test
    void sqlStates_mapToCodes() {
        assertThat(map("23505").getStatusCode().value()).isEqualTo(409);
        assertThat(map("23505").getBody().getCode()).isEqualTo("error.conflict.duplicate");
        assertThat(map("23503").getStatusCode().value()).isEqualTo(409);
        assertThat(map("23503").getBody().getCode()).isEqualTo("error.conflict.reference");
        assertThat(map("23502").getStatusCode().value()).isEqualTo(400);
        assertThat(map("22001").getBody().getCode()).isEqualTo("error.data.invalid");
        // Matnda SQL/cheklov nomi yo'q
        assertThat(map("23505").getBody().getMessage()).doesNotContain("constraint");
    }

    @Test
    void realUniqueViolation_overHttp_is409() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        inTx(() -> {
            ContractTemplate t = new ContractTemplate();
            t.setTitle("Standart");
            t.setType(ContractType.OFFLINE);
            t.setContent("Shartnoma {{contractNumber}}");
            t.setDefault(true);
            return templateRepository.save(t);
        });
        Long student = fixtures.student();
        Long templateId = jdbc.queryForObject("SELECT id FROM contract_templates WHERE is_default = TRUE",
            Long.class);
        // Sequence orqada qolgan (masalan V58 bajarilmagan bazadan ko'chirilgan) — keyingi raqam band
        String taken = "CTR-" + LocalDate.now().getYear() + "-00001";
        jdbc.update("INSERT INTO contracts (uuid, contract_number, student_id, template_id, type, status,"
                + " contract_date) VALUES (?, ?, ?, ?, 'OFFLINE', 'DRAFT', ?)",
            UUID.randomUUID().toString(), taken, student, templateId, LocalDate.now());
        jdbc.execute("ALTER SEQUENCE contract_number_seq RESTART WITH 1");

        mvc.perform(post("/api/contracts/generate").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":" + student + "}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("error.conflict.duplicate"));
    }
}
