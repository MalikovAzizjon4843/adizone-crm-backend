package com.crm.service;

import com.crm.config.MetaProperties;
import com.crm.entity.Lead;
import com.crm.entity.LeadNote;
import com.crm.entity.MetaLeadForm;
import com.crm.entity.MetaLeadFormQuestion;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.MetaCrmField;
import com.crm.entity.enums.MetaFormType;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TaskType;
import com.crm.repository.LeadNoteRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.MetaLeadFormQuestionRepository;
import com.crm.repository.MetaLeadFormRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import com.crm.util.PhoneUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Meta lidini CRM lidiga aylantiradigan YAGONA joy.
 *
 * <p>Webhook oqimi ({@link MetaLeadProcessingService}) ham, backfill
 * ({@link MetaBackfillService}) ham shu yerga keladi. Mantiq ikki nusxada
 * bo'lsa ular albatta bir-biridan ajralib ketardi — masalan dublikat
 * qoidasi faqat bir oqimda yangilanardi va eski lidlarni tortib olish
 * kanbanni ikki barobar to'ldirib yuborardi.
 *
 * <p><b>Tranzaksiya bu yerda OCHILMAYDI.</b> Chaqiruvchi har lidni o'z
 * {@code REQUIRES_NEW} tranzaksiyasida o'raydi: PostgreSQL da bitta xato
 * butun tranzaksiyani {@code 25P02} ga tushiradi va undan keyingi har
 * qanday so'rov rad etiladi — ya'ni sikl ichidagi try/catch yordam
 * bermaydi.
 *
 * <p><b>Lid yo'qolmaydi.</b> Forma topilmasa ham, telefon buzuq bo'lsa
 * ham, savol mapping i sozlanmagan bo'lsa ham lid YARATILADI va xom
 * qiymat izohga tushadi. Odam keyin tuzatadi; yo'qolgan lidni esa hech
 * kim qaytarib bera olmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaLeadIngestService {

    /** Ism kelmaganda shu prefiks + telefon oxirgi 4 raqami ishlatiladi. */
    private static final String FALLBACK_NAME = "Meta lid";

    /** Takroriy murojaat izohining boshi — qidirishda shu bo'yicha topiladi. */
    public static final String REPEAT_NOTE = "Meta'dan takroriy murojaat";

    private static final String UNKNOWN_FORM_NOTE =
        "DIQQAT: noma'lum forma — sozlamalar qo'llanmadi, lidni qo'lda tekshiring";

    /** Telefon tanilmaganda {@code leads.phone} ga shu tushadi. */
    private static final String NO_PHONE = "—";

    /** "Kun davomida" vazifa shu vaqtga keltiriladi — {@code TaskService} bilan bir xil. */
    private static final LocalTime ALL_DAY_DUE = LocalTime.of(23, 59);

    private final MetaProperties properties;
    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final MetaLeadFormRepository formRepository;
    private final MetaLeadFormQuestionRepository questionRepository;
    private final LeadStageService leadStageService;
    private final ObjectMapper objectMapper;

    public enum Outcome {
        /** Yangi lid yaratildi. */
        CREATED,
        /** Shu {@code leadgen_id} allaqachon lid bo'lgan — hech narsa qilinmadi. */
        DUPLICATE_LEADGEN,
        /** Shu telefon bilan yaqinda ochiq lid bor — mavjudiga izoh qo'shildi. */
        DUPLICATE_PHONE,
        /** Forma turi lid yaratishni talab qilmaydi (IGNORE yoki HR). */
        SKIPPED
    }

    public record IngestResult(Outcome outcome, Long leadId, String message) {

        public boolean created() {
            return outcome == Outcome.CREATED;
        }

        public boolean duplicate() {
            return outcome == Outcome.DUPLICATE_LEADGEN || outcome == Outcome.DUPLICATE_PHONE;
        }
    }

    /**
     * Bitta Meta lidini yozadi.
     *
     * @param dryRun true bo'lsa hech narsa saqlanmaydi — natija faqat
     *               "nima bo'lardi" degan javob beradi (backfill statistikasi)
     */
    public IngestResult ingest(MetaLeadPayload payload, boolean dryRun) {
        if (payload.leadgenId() == null || payload.leadgenId().isBlank()) {
            throw new IllegalArgumentException("leadgen_id bo'sh — lid ajratib bo'lmadi");
        }

        // 1-dublikat: leadgen_id. Eng arzon tekshiruv va u bazadagi UNIQUE
        // indeks bilan bir xil qoidaga tayanadi.
        Lead existing = leadRepository.findByMetaLeadgenId(payload.leadgenId()).orElse(null);
        if (existing != null) {
            return new IngestResult(Outcome.DUPLICATE_LEADGEN, existing.getId(),
                "Lid allaqachon mavjud (#" + existing.getId() + ")");
        }

        MetaLeadForm form = payload.formId() != null
            ? formRepository.findByFormId(payload.formId()).orElse(null)
            : null;

        // Forma turi lid yaratishni talab qilmasa — shu yerda to'xtaymiz.
        // Xom JSON event qatorida allaqachon saqlangan, ya'ni HR arizasi
        // keyin alohida jadvalga ko'chirilganda hech narsa yo'qolmaydi.
        if (form != null) {
            MetaFormType type = form.getLeadType();
            if (type == MetaFormType.IGNORE) {
                return new IngestResult(Outcome.SKIPPED, null,
                    "Forma IGNORE deb belgilangan");
            }
            if (type == MetaFormType.HR) {
                return new IngestResult(Outcome.SKIPPED, null,
                    "HR formasi — xom JSON saqlandi, lid yaratilmadi");
            }
        }

        Extracted data = extract(payload, form);

        // 2-dublikat: telefon. Faqat TANILGAN raqam bo'yicha — xom matnni
        // solishtirish "—" li lidlarni bir-biriga yopishtirib yuborardi.
        if (data.canonicalPhone() != null) {
            LocalDateTime since = LocalDateTime.now()
                .minusDays(Math.max(properties.getDuplicateWindowDays(), 1));
            List<Lead> recent = leadRepository.findRecentOpenByPhone(
                data.canonicalPhone(), since, leadStageService.closedCodes());
            if (!recent.isEmpty()) {
                Lead target = recent.get(0);
                if (!dryRun) {
                    leadNoteRepository.save(LeadNote.builder()
                        .lead(target)
                        .text(repeatNoteText(payload, form, data))
                        .build());
                }
                return new IngestResult(Outcome.DUPLICATE_PHONE, target.getId(),
                    "Yaqinda ochilgan lid bor (#" + target.getId() + "), izoh qo'shildi");
            }
        }

        if (dryRun) {
            return new IngestResult(Outcome.CREATED, null, "dryRun — saqlanmadi");
        }

        Lead lead = Lead.builder()
            .fullName(data.fullName())
            .phone(data.storedPhone())
            .format(resolveFormat(form))
            .source(resolveSource(payload, form))
            .status(resolveStage(form))
            .notes(data.noteText())
            // Biriktirilmagan: Meta lidi kanbanning "Biriktirilmagan"
            // ustuniga tushadi va uni operator o'zi oladi. Avtomatik
            // taqsimot ataylab yo'q — kim qachon ishlashini boshqarish
            // sotuv bo'limining ishi.
            .assignedUser(null)
            .createdBy(null)
            .converted(false)
            .metaLeadgenId(payload.leadgenId())
            .metaFormId(payload.formId())
            .metaRawJson(payload.rawJson())
            .build();

        Lead saved = leadRepository.save(lead);

        // @PrePersist createdAt ni now() qiladi — Meta bergan sanani undan
        // KEYIN yozamiz, aks holda "qachon murojaat qilgan" ma'lumoti
        // yo'qoladi. Bu LeadImportService dagi bilan bir xil naqsh.
        if (payload.createdTime() != null) {
            saved.setCreatedAt(LocalDateTime.ofInstant(
                payload.createdTime(), ZoneId.systemDefault()));
            saved = leadRepository.save(saved);
        }

        createCallTaskIfNeeded(saved, form, data);

        log.info("Meta: lid #{} yaratildi (leadgen={}, forma={}, bosqich={})",
            saved.getId(), payload.leadgenId(), payload.formId(), saved.getStatus());
        return new IngestResult(Outcome.CREATED, saved.getId(),
            form == null ? "Forma topilmadi — lid NEW bosqichida yaratildi" : null);
    }

    // -- Ajratish ---------------------------------------------------------

    /** {@code field_data} dan chiqarilgan, lidga yozishga tayyor qiymatlar. */
    private record Extracted(
        String fullName,
        /** Kanonik telefon yoki null — dublikat tekshiruvi faqat shunga tayanadi. */
        String canonicalPhone,
        /** {@code leads.phone} ga tushadigan qiymat: kanonik, xom yoki "—". */
        String storedPhone,
        /** Vazifa sarlavhasiga tushadigan vaqt oralig'i, yoki null. */
        String callTime,
        /** Lid kartasidagi izoh matni, yoki null. */
        String noteText) {
    }

    private Extracted extract(MetaLeadPayload payload, MetaLeadForm form) {
        Map<String, MetaLeadFormQuestion> questions = form != null
            ? questionsByKey(form.getId())
            : Map.of();

        String fullName = null;
        String firstName = null;
        String lastName = null;
        String rawPhone = null;
        String rawPhoneAlt = null;
        String callTime = null;
        List<String> noteLines = new ArrayList<>();

        for (MetaLeadPayload.FieldValue field : payload.fields()) {
            MetaLeadFormQuestion question = questions.get(field.key());
            MetaCrmField target = question != null && question.getCrmField() != null
                ? question.getCrmField()
                // Savol bazada yo'q (forma hali sinxronlanmagan yoki Meta da
                // yangi savol qo'shilgan) — taxmin bilan ishlaymiz, javobni
                // tashlab yubormaymiz.
                : MetaFormSyncService.guessField(field.key());

            if (target == MetaCrmField.IGNORE) {
                continue;
            }

            String value = translate(field.first(), question);
            if (value == null) {
                continue;
            }

            switch (target) {
                case FULL_NAME -> fullName = value;
                case FIRST_NAME -> firstName = value;
                case LAST_NAME -> lastName = value;
                case PHONE -> rawPhone = value;
                case PHONE_ALT -> rawPhoneAlt = value;
                case PREFERRED_CALL_TIME -> {
                    callTime = value;
                    noteLines.add(labelOf(question, field.key()) + ": " + value);
                }
                case PLANNED_START, PURPOSE, NOTE ->
                    noteLines.add(labelOf(question, field.key()) + ": " + value);
                default -> { }
            }
        }

        // phone_number BIRLAMCHI: u Meta ning standart savoli va raqam doim
        // to'liq shaklda keladi. telefon_raqamingiz — qo'lda yozilgan savol,
        // odam unga kodsiz ("901234567") yoki umuman matn yozishi mumkin.
        String canonicalPrimary = PhoneUtils.canonical(rawPhone);
        String canonicalAlt = PhoneUtils.canonical(rawPhoneAlt);
        String canonical = canonicalPrimary != null ? canonicalPrimary : canonicalAlt;

        // Ikkalasi ham tanildi va ular HAR XIL — ikkinchisi yo'qolmasin.
        // Bir xil bo'lsa izohga yozish shovqin bo'lardi.
        if (canonicalPrimary != null && canonicalAlt != null
                && !canonicalPrimary.equals(canonicalAlt)) {
            noteLines.add("Qo'shimcha raqam: " + canonicalAlt);
        }

        String storedPhone = canonical;
        if (storedPhone == null) {
            // Xom qiymat saqlanadi: operator odam nima yozganini ko'rsin.
            String raw = rawPhone != null ? rawPhone : rawPhoneAlt;
            storedPhone = raw != null ? raw : NO_PHONE;
            if (raw != null) {
                noteLines.add("Telefon tanilmadi, xom qiymat: " + raw);
            } else {
                noteLines.add("Telefon kelmadi");
            }
        }

        if (form == null) {
            noteLines.add(0, UNKNOWN_FORM_NOTE + " (form_id=" + payload.formId() + ")");
        } else if (form.getLeadType() == MetaFormType.UNMAPPED) {
            noteLines.add(0, "Forma turi hali sozlanmagan (UNMAPPED)");
        }

        return new Extracted(
            resolveName(fullName, firstName, lastName, canonical),
            canonical,
            truncate(storedPhone, 50),
            callTime,
            noteLines.isEmpty() ? null : String.join("\n", noteLines));
    }

    /**
     * Variant javobini odam o'qiydigan matnga aylantiradi.
     *
     * <p>{@code field_data} variantlarda KALIT ni qaytaradi
     * ({@code ha_qulay_borib_o'qiy_olaman_}), foydalanuvchi ko'rgan matnni
     * emas. Lug'atda topilmasa xom kalit qoladi: tushunarsiz bo'lsa ham,
     * javobsiz qolgandan yaxshiroq.
     */
    private String translate(String value, MetaLeadFormQuestion question) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (question == null || question.getOptionsJson() == null) {
            return value;
        }
        try {
            JsonNode options = objectMapper.readTree(question.getOptionsJson());
            JsonNode label = options.get(value);
            if (label != null && label.isTextual() && !label.asText().isBlank()) {
                return label.asText();
            }
        } catch (Exception e) {
            log.warn("Meta: '{}' savolining variantlari o'qilmadi: {}",
                question.getQuestionKey(), e.getMessage());
        }
        return value;
    }

    /**
     * Lid nomi.
     *
     * <p>Tartib FIRST keyin LAST — o'zbekcha murojaat shakli shunday
     * ("Manzura Karimova"), Meta esa ikkala savolni alohida beradi.
     * Hech narsa kelmasa telefonning oxirgi 4 raqami bilan ajratamiz:
     * kanbanda o'nlab "Meta lid" bir xil ko'rinib qolmasin.
     */
    private static String resolveName(
            String fullName, String firstName, String lastName, String phone) {
        String name = null;
        if (fullName != null && !fullName.isBlank()) {
            name = fullName;
        } else if (firstName != null || lastName != null) {
            name = ((firstName != null ? firstName.trim() : "")
                + " " + (lastName != null ? lastName.trim() : "")).trim();
        }
        if (name == null || name.isBlank()) {
            String digits = phone != null ? phone.replaceAll("[^0-9]", "") : "";
            name = digits.length() >= 4
                ? FALLBACK_NAME + " " + digits.substring(digits.length() - 4)
                : FALLBACK_NAME;
            return truncate(name, 255);
        }
        return truncate(capitalize(name), 255);
    }

    /** "manzura karimova" → "Manzura Karimova". */
    static String capitalize(String value) {
        String[] parts = value.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // -- Sozlamalardan kelib chiqadigan qiymatlar -------------------------

    /**
     * Bosqich kodi. Forma sozlamasidagi kod bazada yo'q yoki o'chirilgan
     * bo'lsa {@code NEW} ga tushadi — istisno tashlanmaydi, chunki
     * sozlamadagi xato tufayli lid yo'qolmasligi kerak.
     */
    private String resolveStage(MetaLeadForm form) {
        String code = form != null ? form.getDefaultStageCode() : null;
        if (code == null || code.isBlank()) {
            return Lead.DEFAULT_STATUS;
        }
        try {
            return leadStageService.requireActiveCode(code);
        } catch (RuntimeException e) {
            log.warn("Meta: forma {} dagi bosqich '{}' yaroqsiz ({}), lid {} da yaratiladi",
                form.getFormId(), code, e.getMessage(), Lead.DEFAULT_STATUS);
            return Lead.DEFAULT_STATUS;
        }
    }

    /**
     * Marketing manbasi. Forma sozlamasi ustun; bo'lmasa platformadan:
     * {@code ig} → INSTAGRAM, {@code fb} → FACEBOOK. Platforma ham
     * kelmasa INSTAGRAM — lidlarning aksariyati Instagram dan keladi.
     */
    private static String resolveSource(MetaLeadPayload payload, MetaLeadForm form) {
        if (form != null && form.getDefaultSource() != null
                && !form.getDefaultSource().isBlank()) {
            return form.getDefaultSource().trim().toUpperCase(Locale.ROOT);
        }
        String platform = payload.platform();
        if (platform != null && platform.toLowerCase(Locale.ROOT).startsWith("fb")) {
            return "FACEBOOK";
        }
        return "INSTAGRAM";
    }

    private static String resolveFormat(MetaLeadForm form) {
        String format = form != null ? form.getDefaultStudyFormat() : null;
        if (format == null || format.isBlank()) {
            // null qoldirmaymiz: Lead.@PrePersist uni "OFFLINE" qilib qo'yadi
            // va shu yerda ham aynan shu qiymat bo'lsin, ikki xil yo'l bitta
            // natijaga kelsin.
            return "OFFLINE";
        }
        return format.trim().toUpperCase(Locale.ROOT);
    }

    // -- Yon yozuvlar -----------------------------------------------------

    private String repeatNoteText(
            MetaLeadPayload payload, MetaLeadForm form, Extracted data) {
        StringBuilder sb = new StringBuilder(REPEAT_NOTE);
        sb.append(" (forma: ")
            .append(form != null && form.getName() != null ? form.getName() : payload.formId())
            .append(", leadgen_id: ").append(payload.leadgenId()).append(")");
        if (data.noteText() != null) {
            sb.append('\n').append(data.noteText());
        }
        return sb.toString();
    }

    /**
     * "Qo'ng'iroq qilish — ertalab 9:00-12:00" vazifasi.
     *
     * <p>Mas'ul MAJBURIY ({@code tasks.assigned_to} NOT NULL), Meta lidi esa
     * biriktirilmagan holda tug'iladi. Shuning uchun mas'ul
     * {@code meta.task-assignee-user-id} dan olinadi. U sozlanmagan bo'lsa
     * vazifa yaratilmaydi va log ga ogohlantirish tushadi — lid baribir
     * o'z o'rnida qoladi.
     */
    private void createCallTaskIfNeeded(Lead lead, MetaLeadForm form, Extracted data) {
        if (form == null || !Boolean.TRUE.equals(form.getAutoCreateTask())) {
            return;
        }
        String key = form.getTaskTimeQuestionKey();
        if (key == null || key.isBlank()) {
            return;
        }
        if (data.callTime() == null) {
            log.debug("Meta: lid #{} uchun vazifa yaratilmadi — '{}' savoliga javob yo'q",
                lead.getId(), key);
            return;
        }

        User assignee = resolveTaskAssignee(lead);
        if (assignee == null) {
            log.warn("Meta: lid #{} uchun avtomatik vazifa yaratilmadi — "
                + "meta.task-assignee-user-id sozlanmagan", lead.getId());
            return;
        }

        Task task = Task.builder()
            .title(truncate("Qo'ng'iroq qilish — " + data.callTime(), 255))
            .description("Meta Lead Ads: " + (form.getName() != null ? form.getName() : ""))
            .type(TaskType.CALL)
            .status(TaskStatus.OPEN)
            .dueAt(LocalDate.now().atTime(ALL_DAY_DUE))
            .allDay(true)
            .assignedTo(assignee)
            .createdBy(null)
            .lead(lead)
            .build();
        taskRepository.save(task);
    }

    private User resolveTaskAssignee(Lead lead) {
        if (lead.getAssignedUser() != null) {
            return lead.getAssignedUser();
        }
        Long userId = properties.getTaskAssigneeUserId();
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseGet(() -> {
                log.warn("Meta: meta.task-assignee-user-id={} foydalanuvchi topilmadi "
                    + "yoki faol emas", userId);
                return null;
            });
    }

    private Map<String, MetaLeadFormQuestion> questionsByKey(Long formId) {
        Map<String, MetaLeadFormQuestion> map = new LinkedHashMap<>();
        for (MetaLeadFormQuestion q
                : questionRepository.findByForm_IdOrderBySortOrderAscIdAsc(formId)) {
            map.put(q.getQuestionKey(), q);
        }
        return map;
    }

    private static String labelOf(MetaLeadFormQuestion question, String key) {
        if (question != null && question.getLabel() != null
                && !question.getLabel().isBlank()) {
            return question.getLabel().trim();
        }
        return key;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
