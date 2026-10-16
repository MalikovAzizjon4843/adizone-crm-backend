package com.crm.security;

import com.crm.dto.request.LeadCreateRequest;
import com.crm.entity.Lead;
import com.crm.entity.MetaLeadForm;
import com.crm.entity.MetaLeadFormQuestion;
import com.crm.entity.User;
import com.crm.entity.enums.MetaCrmField;
import com.crm.entity.enums.MetaFormType;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.MetaLeadFormQuestionRepository;
import com.crm.repository.MetaLeadFormRepository;
import com.crm.service.LeadService;
import com.crm.service.MetaFormatDetector;
import com.crm.service.MetaLeadIngestService;
import com.crm.service.MetaLeadPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Meta lid formati (16.10.2026): FORMAT savoli javobidan ONLINE / OFFLINE; tanilmasa, savol yo'q yoki forma UNMAPPED /
 * topilmasa — {@code leads.format} NULL (OFFLINE taxmin qilinmaydi). Sayt lidi ham; qo'lda va import — o'zgarmagan.
 */
class MetaLeadFormatTest extends Phase5ItBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MetaLeadIngestService ingestService;
    @Autowired MetaLeadFormRepository formRepository;
    @Autowired MetaLeadFormQuestionRepository questionRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadService leadService;
    @Autowired ObjectMapper objectMapper;

    // ── sof aniqlovchi ──────────────────────────────────────────────────

    @Test
    void detector_keywords_caseAndApostrophes_ambiguousIsNull() {
        assertThat(MetaFormatDetector.detect("Onlayn", null, null)).isEqualTo("ONLINE");
        assertThat(MetaFormatDetector.detect("ONLINE darslar", null, null)).isEqualTo("ONLINE");
        assertThat(MetaFormatDetector.detect("Онлайн", null, null)).isEqualTo("ONLINE");
        assertThat(MetaFormatDetector.detect("Oflayn", null, null)).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("offline", null, null)).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("Офлайн", null, null)).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("O‘quv markazida", null, null)).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("Markazga borib", null, null)).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("Hali bilmayman", null, null)).isNull();
        assertThat(MetaFormatDetector.detect("onlayn yoki markazda", null, null)).as("ikkalasi — aniqlanmagan").isNull();
        assertThat(MetaFormatDetector.detect(" ", null, null)).isNull();
        // Sozlangan kalit so'zlar standart ro'yxat o'rniga
        assertThat(MetaFormatDetector.detect("Ofisda", null, "ofis, filial")).isEqualTo("OFFLINE");
        assertThat(MetaFormatDetector.detect("Markazda", null, "ofis, filial")).isNull();
        assertThat(MetaFormatDetector.detect("Zoom orqali", "zoom", null)).isEqualTo("ONLINE");
    }

    // ── Meta ingest ─────────────────────────────────────────────────────

    private MetaLeadForm form(MetaFormType type, boolean withFormatQuestion, String defaultFormat, String offlineKeywords) {
        String formId = "f-" + System.nanoTime() + "-" + SEQ.incrementAndGet();
        return inTx(() -> {
            MetaLeadForm f = formRepository.save(MetaLeadForm.builder().formId(formId).name("Kurs formasi")
                .leadType(type).defaultStudyFormat(defaultFormat).formatOfflineKeywords(offlineKeywords).build());
            question(f, "full_name", "To'liq ism", MetaCrmField.FULL_NAME);
            question(f, "phone_number", "Telefon", MetaCrmField.PHONE);
            if (withFormatQuestion) {
                question(f, "talim_shakli", "Qanday o'qimoqchisiz?", MetaCrmField.FORMAT);
            }
            return f;
        });
    }

    private void question(MetaLeadForm f, String key, String label, MetaCrmField field) {
        questionRepository.save(MetaLeadFormQuestion.builder().form(f).questionKey(key).label(label).crmField(field)
            .sortOrder(0).build());
    }

    private Lead ingest(MetaLeadForm form, String formatAnswer) {
        List<MetaLeadPayload.FieldValue> fields = new ArrayList<>(List.of(
            new MetaLeadPayload.FieldValue("full_name", List.of("Meta Lid")),
            new MetaLeadPayload.FieldValue("phone_number",
                List.of("+99890" + String.format("%07d", 1_000_000 + SEQ.incrementAndGet())))));
        if (formatAnswer != null) {
            fields.add(new MetaLeadPayload.FieldValue("talim_shakli", List.of(formatAnswer)));
        }
        var result = inTx(() -> ingestService.ingest(new MetaLeadPayload("lg-" + System.nanoTime(),
            form != null ? form.getFormId() : "unknown-" + System.nanoTime(), null, "ig", null, fields, "{}"), false));
        assertThat(result.created()).isTrue();
        return inTx(() -> leadRepository.findById(result.leadId()).orElseThrow());
    }

    @Test
    void ingest_formatQuestion_onlineOfflineUnrecognized_answerKeptInNote() {
        MetaLeadForm f = form(MetaFormType.STUDENT, true, null, null);

        Lead online = ingest(f, "Onlayn");
        assertThat(online.getFormat()).isEqualTo("ONLINE");
        assertThat(online.getNotes()).contains("Qanday o'qimoqchisiz?: Onlayn");

        Lead offline = ingest(f, "O‘quv markazda");
        assertThat(offline.getFormat()).isEqualTo("OFFLINE");
        assertThat(offline.getNotes()).contains("Qanday o'qimoqchisiz?: O‘quv markazda");

        Lead unknown = ingest(f, "Hali bilmayman");
        assertThat(unknown.getFormat()).as("tanilmadi — aniqlanmagan, OFFLINE emas").isNull();
        assertThat(unknown.getNotes()).contains("Qanday o'qimoqchisiz?: Hali bilmayman");

        assertThat(ingest(f, null).getFormat()).as("javob kelmadi").isNull();
    }

    @Test
    void ingest_noFormatQuestion_unmapped_unknownForm_null_explicitFormDefaultKept() {
        assertThat(ingest(form(MetaFormType.STUDENT, false, null, null), null).getFormat()).isNull();
        assertThat(ingest(form(MetaFormType.UNMAPPED, false, "ONLINE", null), null).getFormat())
            .as("xaritasiz forma — standart ham ishlatilmaydi").isNull();
        assertThat(ingest(null, null).getFormat()).as("forma topilmadi").isNull();

        // Admin formada aniq tanlagan standart — javob tanilmasa zaxira
        MetaLeadForm withDefault = form(MetaFormType.STUDENT, true, "ONLINE", null);
        assertThat(ingest(withDefault, "Hali bilmayman").getFormat()).isEqualTo("ONLINE");
        assertThat(ingest(withDefault, "Oflayn").getFormat()).as("javob ustun").isEqualTo("OFFLINE");
    }

    @Test
    void ingest_formKeywordsOverrideDefaults() {
        MetaLeadForm f = form(MetaFormType.STUDENT, true, null, "ofis, filial");
        assertThat(ingest(f, "Chilonzor filialida").getFormat()).isEqualTo("OFFLINE");
        assertThat(ingest(f, "Markazda").getFormat()).isNull();
        assertThat(ingest(f, "Onlayn").getFormat()).as("ONLINE ro'yxati standart").isEqualTo("ONLINE");
    }

    // ── sayt va qo'lda ──────────────────────────────────────────────────

    @Test
    void websiteLead_withoutFormat_null_staffLead_unchanged() throws Exception {
        mvc.perform(post("/api/leads/public").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sayt Lid\",\"phone\":\"+998907770001\"}"))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/leads/public").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sayt Onlayn\",\"phone\":\"+998907770002\",\"format\":\"online\"}"))
            .andExpect(status().isCreated());
        List<Lead> leads = inTx(() -> leadRepository.findAll());
        assertThat(leads).filteredOn(l -> "Sayt Lid".equals(l.getFullName())).singleElement()
            .satisfies(l -> assertThat(l.getFormat()).isNull());
        assertThat(leads).filteredOn(l -> "Sayt Onlayn".equals(l.getFullName())).singleElement()
            .satisfies(l -> assertThat(l.getFormat()).isEqualTo("ONLINE"));

        // Qo'lda (kanban tez qo'shish) — avvalgidek OFFLINE
        User admin = newUser(UserRole.ADMIN);
        loginAs(admin);
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName("Qo'lda Lid");
        r.setPhone("+998907770003");
        Long id = leadService.createLeadByStaff(r).getId();
        assertThat(inTx(() -> leadRepository.findById(id).orElseThrow().getFormat())).isEqualTo("OFFLINE");
    }
}
