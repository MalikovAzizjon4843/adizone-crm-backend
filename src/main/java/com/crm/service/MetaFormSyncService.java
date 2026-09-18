package com.crm.service;

import com.crm.config.MetaProperties;
import com.crm.entity.MetaLeadForm;
import com.crm.entity.MetaLeadFormQuestion;
import com.crm.entity.enums.MetaCrmField;
import com.crm.entity.enums.MetaFormType;
import com.crm.repository.MetaLeadFormQuestionRepository;
import com.crm.repository.MetaLeadFormRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Meta dagi formalarni va ularning savollarini bazaga ko'chiradi.
 *
 * <p><b>Eng muhim qoida: sozlamalar ustiga yozilmaydi.</b> Sinxronizatsiya
 * faqat Meta ga tegishli maydonlarni yangilaydi ({@code name},
 * {@code status}, {@code locale}, {@code leadsCount}). Operator qo'ygan
 * {@code leadType}, {@code defaultStageCode}, {@code defaultStudyFormat},
 * {@code defaultSource}, {@code autoCreateTask}, {@code taskTimeQuestionKey},
 * {@code active} va savollarning {@code crmField} i TEGILMAYDI. Aks holda
 * har sinxronizatsiyadan keyin butun sozlashni qaytadan qilish kerak
 * bo'lardi.
 *
 * <p>Startup da avtomatik ishlamaydi — faqat
 * {@code POST /api/meta/forms/sync} orqali va lid qayta ishlashda forma
 * topilmaganda.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaFormSyncService {

    private final MetaProperties properties;
    private final MetaGraphClient graphClient;
    private final MetaLeadFormRepository formRepository;
    private final MetaLeadFormQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    /** Sinxronizatsiya hisoboti — controller shu bilan javob beradi. */
    public record SyncResult(
        int total, int created, int updated, int questionsCreated, int questionsUpdated,
        List<String> warnings) {
    }

    @Transactional
    public SyncResult syncForms() {
        List<JsonNode> forms = graphClient.getForms();
        log.info("Meta: Graph dan {} ta forma keldi", forms.size());

        int created = 0;
        int updated = 0;
        int qCreated = 0;
        int qUpdated = 0;
        java.util.List<String> warnings = new java.util.ArrayList<>();
        Instant now = Instant.now();

        for (JsonNode node : forms) {
            String formId = node.path("id").asText(null);
            if (formId == null || formId.isBlank()) {
                continue;
            }
            String status = node.path("status").asText(null);

            MetaLeadForm form = formRepository.findByFormId(formId).orElse(null);
            boolean isNew = form == null;
            if (isNew) {
                form = MetaLeadForm.builder()
                    .formId(formId)
                    .pageId(properties.getPageId())
                    // Yangi forma HECH QACHON o'zidan ishga tushmaydi: uni
                    // avval odam ko'rib chiqishi va turini qo'yishi kerak.
                    .leadType(MetaFormType.UNMAPPED)
                    .active(false)
                    .build();
                created++;
            } else {
                updated++;
            }

            // Faqat Meta ga tegishli maydonlar.
            form.setName(trim(node.path("name").asText(null), 255));
            form.setStatus(trim(status, 32));
            form.setLocale(trim(node.path("locale").asText(null), 16));
            form.setLeadsCount(node.path("leads_count").isMissingNode()
                ? form.getLeadsCount()
                : node.path("leads_count").asInt(0));
            form.setSyncedAt(now);

            // Meta da arxivlangan formani biz ham o'chiramiz. Teskarisi YO'Q:
            // ACTIVE bo'lib qolgani bizning bayroqni o'z-o'zidan yoqmaydi.
            if (isArchived(status)) {
                form.setActive(false);
            }

            form = formRepository.save(form);

            try {
                int[] counts = syncQuestions(form);
                qCreated += counts[0];
                qUpdated += counts[1];
            } catch (MetaApiException e) {
                // Bitta formaning savollari kelmasa qolganlari to'xtamasin.
                log.warn("Meta: '{}' ({}) formasining savollari olinmadi: {}",
                    form.getName(), formId, e.getMessage());
                warnings.add(formId + ": " + e.getMessage());
            }
        }

        log.info("Meta: sinxronizatsiya tugadi - formalar {} yangi / {} yangilandi, "
                + "savollar {} yangi / {} yangilandi",
            created, updated, qCreated, qUpdated);
        return new SyncResult(forms.size(), created, updated, qCreated, qUpdated, warnings);
    }

    /**
     * Bitta formaning savollari.
     *
     * @return {@code [yangi, yangilangan]}
     */
    private int[] syncQuestions(MetaLeadForm form) {
        JsonNode detail = graphClient.getFormQuestions(form.getFormId());
        JsonNode questions = detail.path("questions");
        if (!questions.isArray()) {
            return new int[]{0, 0};
        }

        int created = 0;
        int updated = 0;
        int order = 0;
        for (JsonNode q : questions) {
            // XOM kalit. Trim ham qilinmaydi: Meta kalitning oxiriga "_"
            // qo'shishi mumkin va field_data da ham AYNAN shunday keladi.
            String key = q.path("key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }

            MetaLeadFormQuestion row = questionRepository
                .findByForm_IdAndQuestionKey(form.getId(), key)
                .orElse(null);
            boolean isNew = row == null;
            if (isNew) {
                row = MetaLeadFormQuestion.builder()
                    .form(form)
                    .questionKey(key)
                    .build();
            }

            row.setLabel(trim(q.path("label").asText(null), 512));
            row.setType(trim(q.path("type").asText(null), 64));
            row.setOptionsJson(optionsJson(q.path("options")));
            row.setSortOrder(order++);

            // Mapping FAQAT bo'sh bo'lsa to'ldiriladi. Operator qo'lda
            // qo'ygan qiymat taxmin bilan almashtirilmaydi - u aynan
            // taxmin xato bo'lgani uchun qo'yilgan bo'lishi mumkin.
            if (row.getCrmField() == null) {
                row.setCrmField(guessField(key));
            }

            questionRepository.save(row);
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }
        return new int[]{created, updated};
    }

    /**
     * Variantlarni {@code {optionKey: label}} lug'atiga yig'adi.
     *
     * <p>Meta massiv beradi ({@code [{key, value}, ...]}), bizga esa
     * javobdagi kalitni odam o'qiydigan matnga aylantirish uchun lug'at
     * kerak.
     */
    private String optionsJson(JsonNode options) {
        if (options == null || !options.isArray() || options.isEmpty()) {
            return null;
        }
        ObjectNode map = objectMapper.createObjectNode();
        for (JsonNode option : options) {
            String key = option.path("key").asText(null);
            String value = option.path("value").asText(null);
            if (key != null && !key.isBlank()) {
                map.put(key, value != null ? value : key);
            }
        }
        if (map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Meta: variantlar JSON ga yozilmadi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Yangi savol uchun CRM maydonini taxmin qiladi.
     *
     * <p><b>{@code phone_number} birlamchi, {@code telefon_raqamingiz}
     * zaxira.</b> Birinchisi Meta ning standart savoli: raqam doim
     * {@code +998...} shaklida va Facebook profilidan avtomatik to'ldiriladi.
     * Ikkinchisi qo'lda yozilgan savol va odam unga kodsiz ham, xato ham
     * yozishi mumkin.
     *
     * <p>Tanilmagan savol NOTE ga tushadi, IGNORE ga emas: javob izohda
     * ko'rinib tursin, operator uni o'qisin. Keraksizini keyin qo'lda
     * IGNORE qilish mumkin, yo'qolgan javobni esa qaytarib bo'lmaydi.
     */
    static MetaCrmField guessField(String questionKey) {
        String key = questionKey.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "full_name" -> MetaCrmField.FULL_NAME;
            case "first_name", "ismingiz" -> MetaCrmField.FIRST_NAME;
            case "last_name", "familyangiz" -> MetaCrmField.LAST_NAME;
            case "phone_number" -> MetaCrmField.PHONE;
            case "telefon_raqamingiz" -> MetaCrmField.PHONE_ALT;
            default -> MetaCrmField.NOTE;
        };
    }

    static boolean isArchived(String status) {
        return status != null && "ARCHIVED".equalsIgnoreCase(status.trim());
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
