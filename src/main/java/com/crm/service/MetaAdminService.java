package com.crm.service;

import com.crm.config.MetaProperties;
import com.crm.dto.request.MetaFormSettingsRequest;
import com.crm.dto.request.MetaQuestionMappingRequest;
import com.crm.dto.response.MetaFormDetailDto;
import com.crm.dto.response.MetaFormDto;
import com.crm.dto.response.MetaQuestionDto;
import com.crm.dto.response.MetaStatusDto;
import com.crm.dto.response.MetaSyncResultDto;
import com.crm.dto.response.MetaWebhookEventDto;
import com.crm.dto.response.PageResponse;
import com.crm.entity.MetaLeadForm;
import com.crm.entity.MetaLeadFormQuestion;
import com.crm.entity.MetaWebhookEvent;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.MetaFormType;
import com.crm.entity.enums.StudyFormat;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.MetaLeadFormQuestionRepository;
import com.crm.repository.MetaLeadFormRepository;
import com.crm.repository.MetaWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Meta integratsiyasini sozlash — controller ortidagi mantiq.
 *
 * <p>Validatsiya shu yerda markazlashgan: bosqich kodi {@code lead_stages}
 * da bo'lishi, manba {@code MarketingSource} da bo'lishi va vazifa
 * savoli AYNAN shu formada bo'lishi tekshiriladi. Tekshirilmagan sozlama
 * bir oydan keyin "nega lidlar noto'g'ri ustunda?" degan savol bo'lib
 * qaytadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaAdminService {

    private static final int MAX_PAGE_SIZE = 200;

    private final MetaProperties properties;
    private final MetaLeadFormRepository formRepository;
    private final MetaLeadFormQuestionRepository questionRepository;
    private final MetaWebhookEventRepository eventRepository;
    private final MetaFormSyncService formSyncService;
    private final MetaGraphClient graphClient;
    private final LeadStageService leadStageService;
    private final ObjectMapper objectMapper;

    // -- Formalar ---------------------------------------------------------

    public MetaSyncResultDto sync() {
        MetaFormSyncService.SyncResult result = formSyncService.syncForms();
        return MetaSyncResultDto.builder()
            .total(result.total())
            .created(result.created())
            .updated(result.updated())
            .questionsCreated(result.questionsCreated())
            .questionsUpdated(result.questionsUpdated())
            .warnings(result.warnings())
            .build();
    }

    @Transactional(readOnly = true)
    public List<MetaFormDto> listForms() {
        List<MetaLeadForm> forms = formRepository.findAllByOrderByLeadsCountDescIdAsc();

        // Savollar soni bitta GROUP BY bilan — formalar o'nlab bo'lishi
        // mumkin va har biriga alohida COUNT yuborish N+1 bo'lardi.
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : questionRepository.countGroupedByForm()) {
            counts.put((Long) row[0], (Long) row[1]);
        }

        return forms.stream()
            .map(form -> toDto(form,
                counts.getOrDefault(form.getId(), 0L),
                questionRepository.countUnmapped(form.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public MetaFormDetailDto getForm(String formId) {
        MetaLeadForm form = requireForm(formId);
        List<MetaQuestionDto> questions = questionRepository
            .findByForm_IdOrderBySortOrderAscIdAsc(form.getId())
            .stream()
            .map(this::toDto)
            .toList();
        long unmapped = questions.stream().filter(q -> q.getCrmField() == null).count();
        return MetaFormDetailDto.builder()
            .form(toDto(form, questions.size(), unmapped))
            .questions(questions)
            .build();
    }

    /**
     * Sozlamalarni yangilaydi.
     *
     * <p>null qoldirilgan maydon TEGILMAYDI, bo'sh matn esa tozalaydi.
     * Shu farq tufayli frontend faqat o'zgargan maydonni yuborishi mumkin.
     */
    @Transactional
    public MetaFormDetailDto updateForm(String formId, MetaFormSettingsRequest request) {
        MetaLeadForm form = requireForm(formId);

        if (request.getLeadType() != null) {
            form.setLeadType(request.getLeadType());
        }
        if (request.getDefaultStageCode() != null) {
            form.setDefaultStageCode(blankToNull(request.getDefaultStageCode()) == null
                ? null
                // Bosqich mavjudligi VA faolligi shu yerda tekshiriladi:
                // yaroqsiz kod bilan saqlansa, lid har safar NEW ga tushib
                // qolardi va sabab hech qayerda ko'rinmasdi.
                : leadStageService.requireActiveCode(request.getDefaultStageCode()));
        }
        if (request.getDefaultStudyFormat() != null) {
            form.setDefaultStudyFormat(parseStudyFormat(request.getDefaultStudyFormat()));
        }
        if (request.getDefaultSource() != null) {
            form.setDefaultSource(parseSource(request.getDefaultSource()));
        }
        if (request.getAutoCreateTask() != null) {
            form.setAutoCreateTask(request.getAutoCreateTask());
        }
        if (request.getTaskTimeQuestionKey() != null) {
            form.setTaskTimeQuestionKey(
                parseTaskQuestionKey(form, request.getTaskTimeQuestionKey()));
        }
        if (request.getActive() != null) {
            form.setActive(request.getActive());
        }

        // Vazifa yoqilgan, lekin vaqt savoli ko'rsatilmagan — sozlama
        // jimgina ishlamay turardi. Yaxshisi darhol aytamiz.
        if (Boolean.TRUE.equals(form.getAutoCreateTask())
                && blankToNull(form.getTaskTimeQuestionKey()) == null) {
            throw new BadRequestException(
                "autoCreateTask yoqilgan bo'lsa taskTimeQuestionKey majburiy");
        }

        formRepository.save(form);
        return getForm(formId);
    }

    /** {@code PUT /forms/{formId}/mapping} — savollarni CRM maydonlariga bog'laydi. */
    @Transactional
    public MetaFormDetailDto updateMapping(
            String formId, List<MetaQuestionMappingRequest> mappings) {
        MetaLeadForm form = requireForm(formId);
        if (mappings == null || mappings.isEmpty()) {
            throw new BadRequestException("Mapping ro'yxati bo'sh");
        }

        for (MetaQuestionMappingRequest row : mappings) {
            MetaLeadFormQuestion question = questionRepository
                .findByForm_IdAndQuestionKey(form.getId(), row.getQuestionKey())
                .orElseThrow(() -> new BadRequestException(
                    "Bu formada '" + row.getQuestionKey() + "' savoli yo'q. "
                        + "Kalitni AYNAN GET /api/meta/forms/" + formId
                        + " qaytargan holda yuboring."));
            question.setCrmField(row.getCrmField());
            questionRepository.save(question);
        }
        return getForm(formId);
    }

    // -- Eventlar ---------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<MetaWebhookEventDto> listEvents(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);

        Page<MetaWebhookEvent> events = status != null && !status.isBlank()
            ? eventRepository.findByStatusOrderByIdDesc(
                status.trim().toUpperCase(Locale.ROOT), pageable)
            : eventRepository.findAllByOrderByIdDesc(pageable);

        // formId → nom: ro'yxatda "972...487" emas, forma nomi ko'rinsin.
        Map<String, String> formNames = new HashMap<>();
        for (MetaLeadForm form : formRepository.findAll()) {
            formNames.put(form.getFormId(), form.getName());
        }

        return PageResponse.<MetaWebhookEventDto>builder()
            .content(events.getContent().stream()
                .map(event -> toDto(event, formNames.get(event.getFormId())))
                .toList())
            .pageNumber(events.getNumber())
            .pageSize(events.getSize())
            .totalElements(events.getTotalElements())
            .totalPages(events.getTotalPages())
            .last(events.isLast())
            .build();
    }

    /**
     * Eventni navbatga qaytaradi.
     *
     * <p>{@code attempts = 0} — FAILED bo'lgan event yana 5 marta urinish
     * huquqini oladi. Odatda sabab tashqarida bo'lgan (token tugagan,
     * Graph javob bermagan) va u tuzatilgandan keyin qayta urinish
     * muvaffaqiyatli bo'ladi.
     */
    @Transactional
    public MetaWebhookEventDto retryEvent(Long id) {
        MetaWebhookEvent event = eventRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("MetaWebhookEvent", id));
        event.setStatus(MetaWebhookEvent.STATUS_PENDING);
        event.setAttempts(0);
        event.setErrorMessage(null);
        event.setProcessedAt(null);
        eventRepository.save(event);
        log.info("Meta: event #{} (leadgen={}) navbatga qaytarildi", id, event.getLeadgenId());
        return toDto(event, null);
    }

    // -- Holat va obuna ---------------------------------------------------

    @Transactional(readOnly = true)
    public MetaStatusDto status() {
        long formsTotal = formRepository.count();
        long unmapped = formRepository.findAll().stream()
            .filter(f -> f.getLeadType() == MetaFormType.UNMAPPED)
            .count();

        return MetaStatusDto.builder()
            .enabled(properties.isEnabled())
            .apiVersion(properties.getApiVersion())
            .pageId(properties.getPageId())
            .systemTokenConfigured(notBlank(properties.getSystemUserToken()))
            .appSecretConfigured(notBlank(properties.getAppSecret()))
            .verifyTokenConfigured(notBlank(properties.getVerifyToken()))
            .taskAssigneeConfigured(properties.getTaskAssigneeUserId() != null)
            // Sir bor + bayroq yoqilgan bo'lsagina imzo haqiqatan
            // tekshiriladi - MetaWebhookController.signatureAccepted
            // bilan bir xil shart.
            .signatureVerified(
                properties.isVerifySignature() && notBlank(properties.getAppSecret()))
            .pageTokenCached(graphClient.hasCachedPageToken())
            .lastSyncedAt(formRepository.findLastSyncedAt().orElse(null))
            .formsTotal(formsTotal)
            .formsUnmapped(unmapped)
            .eventsPending(eventRepository.countByStatus(MetaWebhookEvent.STATUS_PENDING))
            .eventsFailed(eventRepository.countByStatus(MetaWebhookEvent.STATUS_FAILED))
            .eventsProcessed(eventRepository.countByStatus(MetaWebhookEvent.STATUS_PROCESSED))
            .eventsSkipped(eventRepository.countByStatus(MetaWebhookEvent.STATUS_SKIPPED))
            .build();
    }

    public JsonNode subscribe() {
        JsonNode result = graphClient.subscribePage();
        log.info("Meta: sahifa leadgen ga obuna qilindi — {}", result);
        return result;
    }

    public JsonNode subscriptions() {
        return graphClient.getSubscriptions();
    }

    // -- Mapping yordamchilari --------------------------------------------

    private MetaLeadForm requireForm(String formId) {
        return formRepository.findByFormId(formId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Meta formasi topilmadi: " + formId));
    }

    private MetaFormDto toDto(MetaLeadForm form, long questions, long unmapped) {
        return MetaFormDto.builder()
            .id(form.getId())
            .formId(form.getFormId())
            .pageId(form.getPageId())
            .name(form.getName())
            .status(form.getStatus())
            .locale(form.getLocale())
            .leadsCount(form.getLeadsCount())
            .leadType(form.getLeadType())
            .defaultStageCode(form.getDefaultStageCode())
            .defaultStudyFormat(form.getDefaultStudyFormat())
            .defaultSource(form.getDefaultSource())
            .autoCreateTask(form.getAutoCreateTask())
            .taskTimeQuestionKey(form.getTaskTimeQuestionKey())
            .active(form.getActive())
            .syncedAt(form.getSyncedAt())
            .questionsCount(questions)
            .unmappedQuestionsCount(unmapped)
            .build();
    }

    private MetaQuestionDto toDto(MetaLeadFormQuestion question) {
        return MetaQuestionDto.builder()
            .id(question.getId())
            .questionKey(question.getQuestionKey())
            .label(question.getLabel())
            .type(question.getType())
            .options(parseOptions(question.getOptionsJson()))
            .crmField(question.getCrmField())
            .sortOrder(question.getSortOrder())
            .build();
    }

    private MetaWebhookEventDto toDto(MetaWebhookEvent event, String formName) {
        return MetaWebhookEventDto.builder()
            .id(event.getId())
            .leadgenId(event.getLeadgenId())
            .formId(event.getFormId())
            .formName(formName)
            .pageId(event.getPageId())
            .adId(event.getAdId())
            .createdTimeMs(event.getCreatedTimeMs())
            .status(event.getStatus())
            .attempts(event.getAttempts())
            .errorMessage(event.getErrorMessage())
            .leadId(event.getLeadId())
            .receivedAt(event.getReceivedAt())
            .processedAt(event.getProcessedAt())
            .build();
    }

    /** Buzuq JSON sozlash sahifasini yiqitmasin — bo'sh lug'at qaytadi. */
    private Map<String, String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, String> map = new LinkedHashMap<>();
            JsonNode node = objectMapper.readTree(json);
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            return map;
        } catch (Exception e) {
            log.warn("Meta: options_json o'qilmadi: {}", e.getMessage());
            return null;
        }
    }

    private static String parseStudyFormat(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        StudyFormat parsed = StudyFormat.parseOrNull(value);
        if (parsed == null) {
            throw new BadRequestException(
                "defaultStudyFormat faqat ONLINE yoki OFFLINE bo'ladi: " + raw);
        }
        return parsed.name();
    }

    private static String parseSource(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return MarketingSource.valueOf(value.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noma'lum marketing manbasi: " + raw);
        }
    }

    /**
     * Vazifa vaqti savoli AYNAN shu formada bo'lishi kerak.
     *
     * <p>Tekshirilmasa, matn xatosi (masalan oxirgi {@code _} tushib
     * qolgani) sozlamada jimgina saqlanardi va vazifa hech qachon
     * yaratilmasdi — sababi esa hech qayerda ko'rinmasdi.
     */
    private String parseTaskQuestionKey(MetaLeadForm form, String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        questionRepository.findByForm_IdAndQuestionKey(form.getId(), value)
            .orElseThrow(() -> new BadRequestException(
                "Bu formada '" + value + "' savoli yo'q. Kalitni GET /api/meta/forms/"
                    + form.getFormId() + " qaytargan holda, xom ko'chirib yuboring."));
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
