package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.LeadStageRequest;
import com.crm.dto.response.LeadStageResponse;
import com.crm.entity.LeadStage;
import com.crm.entity.enums.StageKind;
import com.crm.entity.enums.StudyFormat;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Voronka bosqichlarini boshqarish. Nomi, rangi va tartibi buyurtmachiniki;
 * {@code code} va {@code kind} — kodniki.
 *
 * <p>{@code Lead.status} shu jadvaldagi {@code code} ni saqlaydi. Bosqichlar
 * kam va kamdan-kam o'zgargani uchun ular xotirada keshlanadi — aks holda
 * har bir lid tekshiruvi alohida SQL yuborardi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadStageService {

    /** Frontend {@code LEAD_STATUS_COLOR} da tanigan qiymatlar. */
    private static final Set<String> ALLOWED_COLORS =
        Set.of("secondary", "info", "warning", "success", "danger");

    private static final int CODE_MAX = 50;
    private static final String CODE_FALLBACK = "STAGE";

    private final LeadStageRepository leadStageRepository;
    private final LeadRepository leadRepository;
    private final Messages messages;

    /**
     * {@code code -> bosqich}. Birinchi murojaatda yuklanadi va har qanday
     * yozuvdan keyin bekor qilinadi.
     *
     * <p>{@code volatile} + to'liq almashtirish: o'qish qulfsiz, yozuv esa
     * admin CRUD, ya'ni juda kam. Yozuv tranzaksiyasi commit bo'lgunicha
     * boshqa oqim eski suratni ko'rishi mumkin — bosqich nomlari uchun bu
     * zararsiz va keyingi murojaatda o'zi tuzaladi.
     */
    private volatile Map<String, Snapshot> cache;

    /** Keshdagi bitta bosqich — entity emas, sessiyaga bog'liq bo'lmasin. */
    private record Snapshot(String code, String nameUz, String nameRu, String nameEn,
                            StageKind kind, boolean active, int sortOrder) {
    }

    @Transactional(readOnly = true)
    public List<LeadStageResponse> getAll() {
        return leadStageRepository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "LeadStage",
        summary = "'Yangi bosqich: ' + #result.nameUz",
        entityId = "#result.id",
        label = "#result.code")
    public LeadStageResponse create(LeadStageRequest request) {
        String color = requireColor(request.getColor());
        // Yangi bosqich doim OPEN: yakuniy turlar bittadan bo'lishi kerak.
        LeadStage stage = LeadStage.builder()
            .code(generateCode(request.getNameUz()))
            .nameUz(request.getNameUz().trim())
            .nameRu(request.getNameRu().trim())
            .nameEn(request.getNameEn().trim())
            .color(color)
            .sortOrder(request.getSortOrder() != null
                ? request.getSortOrder()
                : leadStageRepository.findMaxSortOrder() + 1)
            .kind(StageKind.OPEN)
            .isActive(request.getIsActive() == null || request.getIsActive())
            .build();
        LeadStage saved = leadStageRepository.save(stage);
        invalidateCache();
        return toResponse(saved);
    }

    /**
     * Nom, rang, tartib va faollikni o'zgartiradi.
     *
     * <p>{@code code} va {@code kind} so'rovdan umuman o'qilmaydi — DTO da
     * ular yo'q, ya'ni tasodifan ham o'zgarib ketmaydi.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "LeadStage",
        summary = "'Bosqich tahrirlandi: ' + #result.nameUz",
        entityId = "#result.id",
        label = "#result.code")
    public LeadStageResponse update(Long id, LeadStageRequest request) {
        LeadStage stage = getOrThrow(id);
        String color = requireColor(request.getColor());

        AuditContext.change("nameUz", stage.getNameUz(), request.getNameUz().trim());
        AuditContext.change("color", stage.getColor(), color);

        stage.setNameUz(request.getNameUz().trim());
        stage.setNameRu(request.getNameRu().trim());
        stage.setNameEn(request.getNameEn().trim());
        stage.setColor(color);
        if (request.getSortOrder() != null) {
            stage.setSortOrder(request.getSortOrder());
        }
        if (request.getIsActive() != null) {
            stage.setIsActive(request.getIsActive());
        }
        LeadStage saved = leadStageRepository.save(stage);
        invalidateCache();
        return toResponse(saved);
    }

    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "LeadStage", entityId = "#id")
    public void delete(Long id) {
        LeadStage stage = getOrThrow(id);

        // Yakuniy bosqichlarga kod tayanadi — ularsiz konvert va rad etish
        // oqimlari qayerga borishini bilmay qoladi.
        if (stage.getKind().isFinal()) {
            throw new BadRequestException(messages.get("leadStage.delete.systemStage"));
        }

        long leads = leadRepository.countByStatusCode(stage.getCode());
        if (leads > 0) {
            throw new BadRequestException(messages.get("leadStage.delete.hasLeads", leads));
        }

        AuditContext.label(stage.getCode());
        AuditContext.summary("Bosqich o'chirildi: " + stage.getNameUz());
        leadStageRepository.delete(stage);
        invalidateCache();
    }

    /**
     * Tartibni qayta yozadi. Ro'yxatdagi o'rin yangi {@code sortOrder} ga
     * aylanadi, ya'ni frontend faqat id larni to'g'ri tartibda yuboradi.
     *
     * <p>Ro'yxatda kelmagan bosqich tegilmaydi — u o'z tartibida qoladi va
     * yangilangalaridan keyinga tushadi.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "LeadStage",
        summary = "'Bosqichlar tartibi o''zgartirildi'")
    public List<LeadStageResponse> reorder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new BadRequestException(messages.get("leadStage.reorder.empty"));
        }
        int position = 1;
        for (Long id : orderedIds) {
            LeadStage stage = getOrThrow(id);
            stage.setSortOrder(position++);
            leadStageRepository.save(stage);
        }
        invalidateCache();
        return getAll();
    }

    // ── Lid oqimi uchun API (keshdan o'qiydi) ──────────────────────────

    /**
     * Kodni tekshiradi va normallashtirilgan shaklda qaytaradi.
     *
     * <p>Bosqich topilmasa yoki o'chirilgan bo'lsa 400. Bu {@code Lead.status}
     * ga yoziladigan yagona yo'l — validatsiya shu yerda markazlashgan.
     */
    public String requireActiveCode(String rawCode) {
        String code = normalize(rawCode);
        if (code == null) {
            throw new BadRequestException(messages.get("leadStage.code.required"));
        }
        Snapshot stage = cache().get(code);
        if (stage == null) {
            throw new BadRequestException(messages.get("leadStage.code.unknown", rawCode));
        }
        if (!stage.active()) {
            throw new BadRequestException(messages.get("leadStage.code.inactive", rawCode));
        }
        return stage.code();
    }

    /** Yopiq bosqich kodlari — avvalgi {@code LeadStatus.closed()} o'rniga. */
    public Set<String> closedCodes() {
        return cache().values().stream()
            .filter(st -> st.kind().isFinal())
            .map(Snapshot::code)
            .collect(Collectors.toSet());
    }

    /**
     * Konvert bosqichlari — endi ULARDAN BIR NECHTASI bo'lishi mumkin
     * (online va oflayn o'quvchi uchun alohida). "Konvertatsiya bo'ldimi"
     * degan har qanday tekshiruv shu to'plamga qarashi kerak, bitta kodga
     * emas.
     */
    public Set<String> convertedCodes() {
        return codesOfKind(StageKind.CONVERTED);
    }

    /**
     * O'qish formatiga mos konvert bosqichi.
     *
     * <p>Avval {@code CONVERTED_ONLINE} / {@code CONVERTED_OFFLINE} qidiriladi.
     * Topilmasa (buyurtmachi o'chirgan yoki nomini boshqacha qilgan) —
     * {@code kind = CONVERTED} bo'lgan birinchi bosqich olinadi va ogohlantirish
     * yoziladi. Konvertatsiya bosqich sozlamasi tufayli to'xtab qolmasligi kerak.
     */
    public String convertedCodeFor(StudyFormat format) {
        String preferred = format == StudyFormat.ONLINE
            ? "CONVERTED_ONLINE"
            : "CONVERTED_OFFLINE";

        Snapshot stage = cache().get(preferred);
        if (stage != null && stage.kind() == StageKind.CONVERTED) {
            return stage.code();
        }

        String fallback = requireSystemCode(StageKind.CONVERTED);
        log.warn("{} bosqichi topilmadi (format={}) — lid {} ga tushadi",
            preferred, format, fallback);
        return fallback;
    }

    /** Rad etilgan bosqich kodi — dashboard hisoblari uchun. */
    public String rejectedCode() {
        return requireSystemCode(StageKind.REJECTED);
    }

    /** Lid shu bosqichlarning birida bo'lsa — allaqachon konvert qilingan. */
    public boolean isConverted(String code) {
        String key = normalize(code);
        return key != null && convertedCodes().contains(key);
    }

    public boolean isClosed(String code) {
        Snapshot stage = cache().get(normalize(code));
        return stage != null && stage.kind().isFinal();
    }

    /** Bosqichlar {@code sortOrder} tartibida — statistika ustunlari uchun. */
    public List<String> orderedCodes() {
        return cache().values().stream()
            .sorted(java.util.Comparator.comparingInt(Snapshot::sortOrder))
            .map(Snapshot::code)
            .collect(Collectors.toList());
    }

    /**
     * Joriy so'rov tili bo'yicha nom. Til {@code Accept-Language} dan
     * ({@code LocaleContextHolder}), {@code Messages} bilan bir manba.
     *
     * <p>Noma'lum kod uchun kodning o'zi qaytadi — "—" emas: bazada nima
     * turgani ko'rinib tursin.
     */
    public String label(String code) {
        String key = normalize(code);
        if (key == null) {
            return null;
        }
        Snapshot stage = cache().get(key);
        if (stage == null) {
            return code;
        }
        return switch (LocaleContextHolder.getLocale().getLanguage()) {
            case "ru" -> stage.nameRu();
            case "en" -> stage.nameEn();
            default -> stage.nameUz();
        };
    }

    // ── Kesh ────────────────────────────────────────────────────────────

    private Map<String, Snapshot> cache() {
        Map<String, Snapshot> snapshot = cache;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cache;
                if (snapshot == null) {
                    snapshot = load();
                    cache = snapshot;
                }
            }
        }
        return snapshot;
    }

    private Map<String, Snapshot> load() {
        Map<String, Snapshot> loaded = new LinkedHashMap<>();
        for (LeadStage stage : leadStageRepository.findAllByOrderBySortOrderAscIdAsc()) {
            loaded.put(stage.getCode(), new Snapshot(
                stage.getCode(), stage.getNameUz(), stage.getNameRu(), stage.getNameEn(),
                stage.getKind(), Boolean.TRUE.equals(stage.getIsActive()),
                stage.getSortOrder() != null ? stage.getSortOrder() : 0));
        }
        log.debug("lead_stages keshi yuklandi: {} ta bosqich", loaded.size());
        return loaded;
    }

    private void invalidateCache() {
        cache = null;
    }

    private Set<String> codesOfKind(StageKind kind) {
        return cache().values().stream()
            .filter(st -> st.kind() == kind)
            .map(Snapshot::code)
            .collect(Collectors.toSet());
    }

    private String requireSystemCode(StageKind kind) {
        return cache().values().stream()
            .filter(st -> st.kind() == kind)
            .map(Snapshot::code)
            .findFirst()
            .orElseThrow(() -> new BadRequestException(
                messages.get("leadStage.systemStage.missing", kind.name())));
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    // ── Yordamchilar ────────────────────────────────────────────────────

    private LeadStage getOrThrow(Long id) {
        return leadStageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("LeadStage", id));
    }

    private String requireColor(String raw) {
        String color = raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_COLORS.contains(color)) {
            throw new BadRequestException(messages.get("leadStage.color.invalid", raw));
        }
        return color;
    }

    /**
     * {@code nameUz} dan texnik kalit yasaydi: "Sinov darsi" -&gt; SINOV_DARSI.
     *
     * <p>Apostrof tushiriladi ({@code o'quvchi} -&gt; OQUVCHI), diakritika
     * normallashtiriladi, qolgan har qanday belgi pastki chiziqqa aylanadi.
     * Lotin bo'lmagan yozuvdan (masalan kirill) hech narsa qolmasa
     * {@code STAGE} ishlatiladi.
     *
     * <p>Dublikat bo'lsa oxiriga raqam qo'shiladi: SINOV_DARSI_2,
     * SINOV_DARSI_3 ...
     */
    private String generateCode(String nameUz) {
        String base = slugify(nameUz);
        if (!leadStageRepository.existsByCode(base)) {
            return base;
        }
        // Raqam qo'shilganda ham 50 belgidan oshmasin
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = "_" + suffix;
            String head = base.length() + tail.length() > CODE_MAX
                ? base.substring(0, CODE_MAX - tail.length())
                : base;
            String candidate = head + tail;
            if (!leadStageRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new BadRequestException(messages.get("leadStage.code.exhausted", base));
    }

    private static String slugify(String raw) {
        String value = raw != null ? raw.trim() : "";
        // Tutuq belgilari va o'/g' apostroflari kodda qolmasin
        value = value.replace("'", "").replace("‘", "").replace("’", "")
                     .replace("`", "").replace("ʻ", "").replace("ʼ", "");
        value = Normalizer.normalize(value, Normalizer.Form.NFD)
                          .replaceAll("\\p{M}", "");
        value = value.toUpperCase(Locale.ROOT)
                     .replaceAll("[^A-Z0-9]+", "_")
                     .replaceAll("^_+|_+$", "");
        if (value.isEmpty()) {
            return CODE_FALLBACK;
        }
        return value.length() > CODE_MAX ? value.substring(0, CODE_MAX) : value;
    }

    private LeadStageResponse toResponse(LeadStage stage) {
        return LeadStageResponse.builder()
            .id(stage.getId())
            .uuid(stage.getUuid())
            .code(stage.getCode())
            .nameUz(stage.getNameUz())
            .nameRu(stage.getNameRu())
            .nameEn(stage.getNameEn())
            .color(stage.getColor())
            .sortOrder(stage.getSortOrder())
            .kind(stage.getKind())
            .isActive(stage.getIsActive())
            .deletable(!stage.getKind().isFinal())
            .createdAt(stage.getCreatedAt())
            .updatedAt(stage.getUpdatedAt())
            .build();
    }
}
