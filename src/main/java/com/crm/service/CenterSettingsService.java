package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.request.CenterSettingsRequest;
import com.crm.dto.response.CenterSettingsDto;
import com.crm.entity.Setting;
import com.crm.exception.CodedException;
import com.crm.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Markaz rekvizitlari — {@code settings} jadvalidagi {@code center.*} kalitlari
 * (leaves-exams-contracts §5). O'qish keshlanadi, yozishda (commit dan keyin ham) bekor qilinadi.
 *
 * <p>Boshlang'ich qiymatlar — V63 (prod). Kodda default yo'q: bo'sh rekvizit shartnomada
 * {@code ________} bo'lib chiqadi va {@code missing} ro'yxatida ko'rinadi.
 */
@Service
@RequiredArgsConstructor
public class CenterSettingsService {

    public static final String PREFIX = "center.";

    /** Barcha kalitlar (tartib — javob va audit tartibi). */
    public static final List<String> FIELDS = List.of(
        "legalName", "legalNameRu", "shortName", "inn", "address", "phone",
        "bankName", "bankAccount", "bankMfo", "directorName", "contractCity",
        "licenseInfo", "supportPhone");

    /** Shartnoma uchun majburiy rekvizitlar — bo'sh bo'lsa {@code missing}. */
    public static final List<String> REQUIRED = List.of(
        "legalName", "inn", "address", "phone", "bankName", "bankAccount", "bankMfo", "directorName");

    private static final int MAX_LENGTH = 500;
    private static final Map<String, Pattern> FORMATS = Map.of(
        "inn", Pattern.compile("\\d{9}"),
        "bankAccount", Pattern.compile("\\d{20}"),
        "bankMfo", Pattern.compile("\\d{5}"));

    private final SettingRepository settingRepository;
    private final TeacherAccessService teacherAccessService;
    private final Clock billingClock;

    private volatile Map<String, String> cache;

    /** Joriy qiymatlar: maydon nomi → qiymat (yo'q yoki bo'sh — {@code ""}). */
    @Transactional(readOnly = true)
    public Map<String, String> values() {
        Map<String, String> snapshot = cache;
        if (snapshot == null) {
            Map<String, String> loaded = new LinkedHashMap<>();
            FIELDS.forEach(f -> loaded.put(f, ""));
            for (Setting s : settingRepository.findBySettingKeyStartingWith(PREFIX)) {
                String field = s.getSettingKey().substring(PREFIX.length());
                if (loaded.containsKey(field)) {
                    loaded.put(field, s.getSettingValue() != null ? s.getSettingValue().trim() : "");
                }
            }
            snapshot = Collections.unmodifiableMap(loaded);
            cache = snapshot;
        }
        return snapshot;
    }

    /** Bo'sh majburiy rekvizitlar. */
    public List<String> missing() {
        Map<String, String> v = values();
        return REQUIRED.stream().filter(f -> v.get(f).isBlank()).toList();
    }

    @Transactional(readOnly = true)
    public CenterSettingsDto get() {
        return toDto(values());
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Settings", summary = "'Markaz rekvizitlari yangilandi'")
    public CenterSettingsDto update(CenterSettingsRequest request) {
        if (!request.getUnknownFields().isEmpty()) {
            throw CodedException.badRequest("settings.center.field.unknown", String.join(", ", request.getUnknownFields()));
        }
        Map<String, String> provided = request.provided();
        Map<String, String> normalized = new LinkedHashMap<>();
        provided.forEach((field, raw) -> normalized.put(field, validate(field, raw)));

        Long actorId = teacherAccessService.getCurrentUserOrThrow().getId();
        LocalDateTime now = LocalDateTime.now(billingClock);
        evict();
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            String key = PREFIX + e.getKey();
            Setting s = settingRepository.findBySettingKey(key).orElseGet(() -> Setting.builder().settingKey(key).build());
            String old = s.getSettingValue() != null ? s.getSettingValue().trim() : "";
            if (old.equals(e.getValue()) && s.getId() != null) {
                continue;
            }
            AuditContext.change(key, old, e.getValue());
            s.setSettingValue(e.getValue());
            s.setUpdatedBy(actorId);
            s.setUpdatedAt(now);
            settingRepository.save(s);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    evict();
                }
            });
        }
        settingRepository.flush();
        cache = null;
        return toDto(reload());
    }

    public void evict() {
        cache = null;
    }

    private Map<String, String> reload() {
        evict();
        return values();
    }

    private static String validate(String field, String raw) {
        String value = raw.trim();
        if (value.length() > MAX_LENGTH) {
            throw CodedException.badRequest("settings.center.tooLong", field, MAX_LENGTH);
        }
        Pattern format = FORMATS.get(field);
        if (format != null && !value.isEmpty()) {
            String digits = value.replace(" ", "");
            if (!format.matcher(digits).matches()) {
                throw CodedException.badRequest("settings.center." + field + ".invalid");
            }
            return digits;
        }
        return value;
    }

    private static CenterSettingsDto toDto(Map<String, String> v) {
        return CenterSettingsDto.builder()
            .legalName(v.get("legalName"))
            .legalNameRu(v.get("legalNameRu"))
            .shortName(v.get("shortName"))
            .inn(v.get("inn"))
            .address(v.get("address"))
            .phone(v.get("phone"))
            .bankName(v.get("bankName"))
            .bankAccount(v.get("bankAccount"))
            .bankMfo(v.get("bankMfo"))
            .directorName(v.get("directorName"))
            .contractCity(v.get("contractCity"))
            .licenseInfo(v.get("licenseInfo"))
            .supportPhone(v.get("supportPhone"))
            .missing(REQUIRED.stream().filter(f -> v.get(f).isBlank()).toList())
            .build();
    }
}
