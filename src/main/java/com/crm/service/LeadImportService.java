package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.LeadImportExecuteRequest;
import com.crm.dto.response.LeadImportPreviewResponse;
import com.crm.dto.response.LeadImportResult;
import com.crm.entity.Lead;
import com.crm.entity.LeadNote;
import com.crm.entity.User;
import com.crm.exception.BadRequestException;
import com.crm.repository.LeadNoteRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.UserRepository;
import com.crm.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * amoCRM Excel eksportidan lidlarni import qilish — ikki qadamli.
 *
 * <p>1. {@link #preview} faylni o'qiydi, sanaydi va vaqtincha saqlaydi.
 * Bazaga hech narsa yozilmaydi. Foydalanuvchi javobdagi bosqich va
 * operator ro'yxatlariga qarab moslashtirishni tuzadi.
 *
 * <p>2. {@link #execute} o'sha faylni moslashtirish bilan qayta o'qiydi
 * va yozadi.
 *
 * <p>Excel o'qish {@code ImportService} naqshi bo'yicha: bir xil POI
 * {@code WorkbookFactory}, bir xil sarlavha indeksi va katak o'qish
 * yordamchilari — ular paket ichida ochilgan, ikkinchi nusxa yozilmadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadImportService {

    // ── amoCRM eksportidagi ustun sarlavhalari ──────────────────────
    private static final String H_FULL_NAME = "Основной контакт";
    private static final String H_WORK_PHONE = "Рабочий телефон (контакт)";
    private static final String H_MOBILE_PHONE = "Мобильный телефон (контакт)";
    private static final String H_OPERATOR = "Ответственный";
    private static final String H_STAGE = "Этап сделки";
    private static final String H_CREATED_AT = "Дата создания";
    private static final String H_TAGS = "Теги сделки";
    private static final String H_FORMAT = "Online yoki Offline (контакт)";

    /** Bular bitta matn bo'lib {@code Lead.notes} ga yoziladi. */
    private static final String[] EXTRA_NOTE_HEADERS = {
        "Qachon bog'lansak bo'ladi (контакт)",
        "Qachon boshlamoqchi (контакт)",
        "Toshkentdanmi (контакт)"
    };

    /** Har biri alohida {@code LeadNote} bo'ladi. */
    private static final int NOTE_COLUMNS = 5;
    private static final String NOTE_HEADER_PREFIX = "Примечание ";

    private static final DateTimeFormatter[] CREATED_AT_FORMATS = {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    };
    private static final DateTimeFormatter DATE_ONLY =
        DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final int PHONE_MAX = 50;
    private static final String PHONE_UNKNOWN = "—";
    private static final int SAMPLE_ROWS = 5;
    private static final Duration TTL = Duration.ofHours(1);
    private static final String TEMP_DIR_NAME = "adizone-lead-import";

    private final LeadRepository leadRepository;
    private final LeadNoteRepository leadNoteRepository;
    private final UserRepository userRepository;
    private final LeadStageService leadStageService;
    private final LeadAccessService leadAccessService;
    private final Messages messages;
    private final PlatformTransactionManager transactionManager;

    /**
     * Kutayotgan importlar. Xotirada: import bir martalik admin amali va
     * ilova qayta ishga tushsa ro'yxat yo'qoladi — fayl diskda qoladi va
     * tozalovchi uni o'zi o'chiradi. Bir nechta instansiyada ishlatilsa
     * {@code /execute} boshqa instansiyaga tushib qolishi mumkin.
     */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String id, Path path, String fileName, Instant createdAt) {
    }

    /** Bitta qatordan o'qilgan, hali saqlanmagan ma'lumot. */
    private record ParsedRow(int rowNum, String fullName, String phone, String phoneDigits,
                             String stage, String operator, String source, String format,
                             LocalDateTime createdAt, String notes, List<String> noteTexts) {
        boolean phoneValid() {
            return phoneDigits != null;
        }
    }

    // ── 1-qadam: tahlil ─────────────────────────────────────────────

    public LeadImportPreviewResponse preview(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(messages.get("leadImport.file.empty"));
        }

        Path stored = storeTemp(file);
        String importId = stored.getFileName().toString().replace(".xlsx", "");
        Pending entry = new Pending(importId, stored, file.getOriginalFilename(), Instant.now());
        pending.put(importId, entry);

        List<ParsedRow> rows = readRows(stored);

        Set<String> seen = new HashSet<>();
        int duplicatesInFile = 0;
        int validPhones = 0;
        for (ParsedRow row : rows) {
            if (row.phoneValid()) {
                validPhones++;
                if (!seen.add(row.phoneDigits())) {
                    duplicatesInFile++;
                }
            }
        }

        Set<String> existing = existingPhoneDigits();
        int duplicatesInDb = (int) rows.stream()
            .filter(ParsedRow::phoneValid)
            .map(ParsedRow::phoneDigits)
            .distinct()
            .filter(existing::contains)
            .count();

        return LeadImportPreviewResponse.builder()
            .importId(importId)
            .fileName(entry.fileName())
            .expiresAt(LocalDateTime.ofInstant(entry.createdAt().plus(TTL), ZoneId.systemDefault()))
            .totalRows(rows.size())
            .validPhones(validPhones)
            .invalidPhones(rows.size() - validPhones)
            .duplicatesInFile(duplicatesInFile)
            .duplicatesInDb(duplicatesInDb)
            .sourceStages(countBy(rows, ParsedRow::stage))
            .operators(countBy(rows, ParsedRow::operator))
            .blockedStages(leadStageService.activeConvertedCodes())
            .sampleRows(sampleRows(rows))
            .build();
    }

    // ── 2-qadam: bajarish ───────────────────────────────────────────

    @Audited(action = AuditAction.IMPORT, entity = "Lead",
        summary = "'Lidlar import qilindi: ' + #result.created + '/' + #result.totalRows",
        label = "#result.importBatch")
    public LeadImportResult execute(LeadImportExecuteRequest request) {
        Pending entry = pending.get(request.getImportId());
        if (entry == null || !Files.exists(entry.path())) {
            throw new BadRequestException(messages.get("leadImport.expired"));
        }

        User current = leadAccessService.getCurrentUserOrThrow();
        String batch = ImportService.truncate(request.getImportTag().trim(), 50);
        boolean skipDuplicates = Boolean.TRUE.equals(request.getSkipDuplicates());

        Map<String, String> stageMapping = normalizeKeys(request.getStageMapping());
        // Terilgan kodlar haqiqatan mavjudligini OLDINDAN tekshiramiz —
        // aks holda 4000 qator noto'g'ri bosqich bilan yozilib ketardi.
        stageMapping.values().stream()
            .filter(code -> code != null && !code.isBlank())
            .distinct()
            .forEach(leadStageService::requireActiveCode);
        // Xom map: xabarda amoCRM bosqichi operator ko'rgan ko'rinishda chiqsin
        requireNoConvertedMapping(request.getStageMapping());
        Map<String, Long> operatorMapping = normalizeKeys(request.getOperatorMapping());
        Map<Long, User> operators = loadOperators(operatorMapping.values());

        List<ParsedRow> rows = readRows(entry.path());
        Set<String> knownPhones = skipDuplicates ? existingPhoneDigits() : new HashSet<>();

        List<LeadImportResult.RowError> errors = new ArrayList<>();
        List<LeadImportResult.RowError> warnings = new ArrayList<>();
        int created = 0;
        int skipped = 0;
        int failed = 0;
        int notes = 0;

        // HAR QATOR O'Z TRANZAKSIYASIDA. Avval partiya darajasida edi va bu
        // PostgreSQL'da ishlamasdi: bitta INSERT yiqilgach seans 25P02
        // ("current transaction is aborted") holatiga tushadi va qolgan 499
        // qator ham, commit ham yiqilardi. try/catch buni ushlay olmaydi —
        // tranzaksiya allaqachon o'lik. 4000 ta alohida tranzaksiya sekinroq,
        // lekin import bir martalik amal va bitta buzuq qator faqat o'zini
        // yo'qotadi.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (ParsedRow row : rows) {
            RowOutcome outcome;
            try {
                outcome = tx.execute(status -> saveRow(
                    row, stageMapping, operatorMapping, operators,
                    knownPhones, skipDuplicates, batch, current));
            } catch (Exception e) {
                outcome = null;
                log.warn("Import: {}-qator yiqildi: {}", row.rowNum(), rootMessage(e));
                errors.add(new LeadImportResult.RowError(row.rowNum(), rootMessage(e)));
            }
            if (outcome == null) {
                failed++;
                continue;
            }
            if (outcome.skipped()) {
                skipped++;
                continue;
            }
            created++;
            notes += outcome.notes();
            if (outcome.warning() != null) {
                warnings.add(new LeadImportResult.RowError(row.rowNum(), outcome.warning()));
            }
        }

        log.info("Lid importi tugadi: batch={} jami={} yaratildi={} o'tkazildi={} xato={}",
            batch, rows.size(), created, skipped, failed);

        return LeadImportResult.builder()
            .importBatch(batch)
            .totalRows(rows.size())
            .created(created)
            .skipped(skipped)
            .failed(failed)
            .notesCreated(notes)
            .errors(errors)
            .warnings(warnings)
            .build();
    }

    /** Bitta qator natijasi. {@code skipped} bo'lsa lid yaratilmagan. */
    private record RowOutcome(boolean skipped, int notes, String warning) {
        static RowOutcome skip() {
            return new RowOutcome(true, 0, null);
        }
    }

    /**
     * Bitta qatorni yozadi. Chaqiruvchi uni alohida tranzaksiyada bajaradi,
     * shuning uchun bu yerda try/catch YO'Q: istisno tashqariga chiqib
     * o'sha qatorning tranzaksiyasini rollback qiladi va qolganlariga
     * tegmaydi.
     */
    private RowOutcome saveRow(ParsedRow row,
                               Map<String, String> stageMapping,
                               Map<String, Long> operatorMapping,
                               Map<Long, User> operators,
                               Set<String> knownPhones,
                               boolean skipDuplicates,
                               String batch,
                               User current) {
        String stageCode = stageMapping.get(normalizeKey(row.stage()));
        if (stageCode == null || stageCode.isBlank()) {
            return RowOutcome.skip();
        }
        if (ImportService.isBlank(row.fullName())) {
            return RowOutcome.skip();
        }
        if (skipDuplicates && row.phoneValid() && knownPhones.contains(row.phoneDigits())) {
            return RowOutcome.skip();
        }

        Long operatorId = operatorMapping.get(normalizeKey(row.operator()));
        User assignee = operatorId != null ? operators.get(operatorId) : null;

        Lead lead = Lead.builder()
            .fullName(ImportService.truncate(row.fullName(), 255))
            // Telefon tanilmasa ham lid yaratiladi — row.phone() hech qachon
            // null emas, xom qiymat yoki "—" bo'ladi.
            .phone(row.phone())
            .format(row.format())
            .source(row.source())
            .notes(row.notes())
            .status(stageCode)
            .converted(false)
            .createdBy(current)
            .assignedUser(assignee)
            .assignedAt(assignee != null ? LocalDateTime.now() : null)
            .importBatch(batch)
            .build();
        Lead saved = leadRepository.save(lead);

        // @PrePersist createdAt ni hozirgi vaqtga qo'yadi — amoCRM sanasini
        // undan KEYIN yozamiz, aks holda yo'qoladi. updatedAt tegilmaydi:
        // @PreUpdate uni baribir now() qiladi va u "import qachon bo'ldi"
        // degan ma'noni saqlaydi.
        if (row.createdAt() != null) {
            saved.setCreatedAt(row.createdAt());
            leadRepository.save(saved);
        }

        int notes = 0;
        for (String text : row.noteTexts()) {
            leadNoteRepository.save(LeadNote.builder()
                .lead(saved)
                .text(text)
                .createdBy(current)
                .build());
            notes++;
        }

        if (row.phoneValid()) {
            knownPhones.add(row.phoneDigits());
        }

        String warning = row.phoneValid()
            ? null
            : messages.get("leadImport.phone.raw", row.phone());
        return new RowOutcome(false, notes, warning);
    }

    /** Hibernate istisnolari o'ralgan bo'ladi — eng ichkisi tushunarliroq. */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null ? ImportService.truncate(msg, 300) : t.getClass().getSimpleName();
    }

    // ── 3-qadam: partiyani o'chirish ────────────────────────────────

    @Audited(action = AuditAction.DELETE, entity = "Lead",
        summary = "'Import partiyasi o''chirildi: ' + #importBatch")
    public long deleteBatch(String importBatch) {
        List<Lead> leads = leadRepository.findByImportBatch(importBatch);
        if (leads.isEmpty()) {
            throw new BadRequestException(messages.get("leadImport.batch.notFound", importBatch));
        }
        // Izohlar lidga FK bilan bog'langan — avval ular.
        for (Lead lead : leads) {
            leadNoteRepository.deleteAll(leadNoteRepository.findByLead_IdOrderByCreatedAtDesc(lead.getId()));
        }
        leadRepository.deleteAll(leads);
        log.info("Import partiyasi o'chirildi: {} ({} ta lid)", importBatch, leads.size());
        return leads.size();
    }

    public long countBatch(String importBatch) {
        return leadRepository.countByImportBatch(importBatch);
    }

    // ── Faylni o'qish ───────────────────────────────────────────────

    private List<ParsedRow> readRows(Path path) {
        List<ParsedRow> rows = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BadRequestException(messages.get("leadImport.header.missing"));
            }
            Map<String, Integer> col = ImportService.buildHeaderIndex(headerRow);
            requireColumn(col, H_FULL_NAME);
            requireColumn(col, H_STAGE);

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row, col)) {
                    continue;
                }
                rows.add(parseRow(row, col, r + 1));
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new BadRequestException(messages.get("leadImport.file.unreadable", e.getMessage()));
        }
        return rows;
    }

    private ParsedRow parseRow(Row row, Map<String, Integer> col, int rowNum) {
        String work = ImportService.cellByHeader(row, col, H_WORK_PHONE);
        String mobile = ImportService.cellByHeader(row, col, H_MOBILE_PHONE);
        String rawPhone = !ImportService.isBlank(work) ? work : mobile;

        String tags = ImportService.cellByHeader(row, col, H_TAGS);

        List<String> noteTexts = new ArrayList<>();
        for (int i = 1; i <= NOTE_COLUMNS; i++) {
            String text = ImportService.cellByHeader(row, col, NOTE_HEADER_PREFIX + i);
            if (!ImportService.isBlank(text)) {
                noteTexts.add(text.trim());
            }
        }

        return new ParsedRow(
            rowNum,
            ImportService.cellByHeader(row, col, H_FULL_NAME),
            storablePhone(rawPhone),
            PhoneUtils.canonicalDigits(rawPhone),
            trimOrEmpty(ImportService.cellByHeader(row, col, H_STAGE)),
            trimOrEmpty(ImportService.cellByHeader(row, col, H_OPERATOR)),
            sourceFromTags(tags),
            formatOf(ImportService.cellByHeader(row, col, H_FORMAT)),
            parseCreatedAt(row, col),
            buildNotes(row, col, tags),
            noteTexts);
    }

    /**
     * Manba teglardan aniqlanadi. Kurs teglari ("turk tili" va h.k.)
     * e'tiborsiz qoladi — ular manba emas.
     */
    private static String sourceFromTags(String tags) {
        if (ImportService.isBlank(tags)) {
            return "OTHER";
        }
        String lower = tags.toLowerCase(Locale.ROOT);
        if (lower.contains("sayt")) {
            return "WEBSITE";
        }
        if (lower.contains("fb") || lower.contains("target") || lower.contains("facebook")) {
            return "INSTAGRAM";
        }
        return "OTHER";
    }

    /**
     * Saqlanadigan qiymat. {@code leads.phone} NOT NULL, foydalanuvchi esa
     * buzuq telefonli lidlarni ham import qilishni xohladi — shuning uchun
     * tanilmagan qiymat XOM holicha (50 belgigacha) saqlanadi, mutlaqo bo'sh
     * bo'lsa "—" yoziladi. Natijada hech qachon null qaytmaydi.
     */
    static String storablePhone(String raw) {
        String canonical = PhoneUtils.canonical(raw);
        if (canonical != null) {
            return canonical;
        }
        String fallback = ImportService.truncate(raw, PHONE_MAX);
        return ImportService.isBlank(fallback) ? PHONE_UNKNOWN : fallback;
    }

    private static String formatOf(String raw) {
        if (ImportService.isBlank(raw)) {
            return "OFFLINE";
        }
        return raw.toLowerCase(Locale.ROOT).contains("online") ? "ONLINE" : "OFFLINE";
    }

    /** Qo'shimcha anketa maydonlari va teglar bitta matnga yig'iladi. */
    private static String buildNotes(Row row, Map<String, Integer> col, String tags) {
        StringBuilder sb = new StringBuilder();
        for (String header : EXTRA_NOTE_HEADERS) {
            String value = ImportService.cellByHeader(row, col, header);
            if (!ImportService.isBlank(value)) {
                sb.append(header.replace(" (контакт)", "")).append(": ")
                  .append(value.trim()).append('\n');
            }
        }
        if (!ImportService.isBlank(tags)) {
            sb.append("Teglar: ").append(tags.trim());
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * "Дата создания" — amoCRM uni matn sifatida beradi
     * ({@code dd.MM.yyyy HH:mm:ss}), lekin Excel uni sana katagiga
     * aylantirib qo'yishi ham mumkin. Ikkalasi ham qo'llab-quvvatlanadi.
     *
     * <p>{@code ImportService.getCellString} bu yerda yaramaydi: u sana
     * katagidan faqat kunni oladi va VAQTNI TASHLAB YUBORADI.
     */
    private static LocalDateTime parseCreatedAt(Row row, Map<String, Integer> col) {
        Integer idx = col.get(ImportService.normalizeHeaderKey(H_CREATED_AT));
        if (idx == null) {
            return null;
        }
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            java.util.Date d = cell.getDateCellValue();
            return d == null ? null
                : LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        }
        String raw = ImportService.getCellStringFromCell(cell);
        if (ImportService.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        for (DateTimeFormatter fmt : CREATED_AT_FORMATS) {
            try {
                return LocalDateTime.parse(text, fmt);
            } catch (Exception ignored) {
                // keyingi shaklni sinaymiz
            }
        }
        try {
            return java.time.LocalDate.parse(text, DATE_ONLY).atStartOfDay();
        } catch (Exception e) {
            log.debug("Sana o'qilmadi: {}", text);
            return null;
        }
    }

    private static boolean isRowEmpty(Row row, Map<String, Integer> col) {
        return ImportService.isBlank(ImportService.cellByHeader(row, col, H_FULL_NAME))
            && ImportService.isBlank(ImportService.cellByHeader(row, col, H_WORK_PHONE))
            && ImportService.isBlank(ImportService.cellByHeader(row, col, H_MOBILE_PHONE));
    }

    private void requireColumn(Map<String, Integer> col, String header) {
        if (!col.containsKey(ImportService.normalizeHeaderKey(header))) {
            throw new BadRequestException(messages.get("leadImport.column.missing", header));
        }
    }

    // ── Vaqtincha fayl ──────────────────────────────────────────────

    private Path storeTemp(MultipartFile file) {
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + ".xlsx");
            file.transferTo(target.toFile());
            return target;
        } catch (IOException e) {
            throw new BadRequestException(messages.get("leadImport.file.unreadable", e.getMessage()));
        }
    }

    /** Bir soatdan oshgan vaqtincha fayllarni o'chiradi. */
    @Scheduled(fixedDelay = 15 * 60 * 1000L)
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        pending.values().removeIf(entry -> {
            if (entry.createdAt().isAfter(cutoff)) {
                return false;
            }
            deleteQuietly(entry.path());
            return true;
        });

        // Ilova qayta ishga tushgan bo'lsa ro'yxat bo'sh, fayllar esa
        // diskda qolgan — ularni ham tozalaymiz.
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> isOlderThan(p, cutoff))
                .forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Vaqtincha import papkasini tozalab bo'lmadi: {}", e.getMessage());
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Vaqtincha faylni o'chirib bo'lmadi: {}", path);
        }
    }

    // ── Yordamchilar ────────────────────────────────────────────────

    private Set<String> existingPhoneDigits() {
        Set<String> digits = new HashSet<>();
        for (String phone : leadRepository.findAllPhones()) {
            String d = PhoneUtils.canonicalDigits(phone);
            if (d != null) {
                digits.add(d);
            }
        }
        return digits;
    }

    private Map<Long, User> loadOperators(java.util.Collection<Long> ids) {
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> map = new java.util.HashMap<>();
        for (User user : userRepository.findAllById(clean)) {
            if (!LeadAccessService.canBeOperator(user.getRole())) {
                throw new BadRequestException(
                    messages.get("leadImport.operator.invalid", user.getUsername()));
            }
            map.put(user.getId(), user);
        }
        return map;
    }

    /**
     * {@code kind = CONVERTED} bosqichiga xaritalashni rad etadi — bitta
     * qator ham yozilmasdan oldin.
     *
     * <p>Import lidni {@code Lead.builder().status(...)} bilan
     * to'g'ridan-to'g'ri yozadi, ya'ni {@code LeadService.updateStatus}
     * dagi "konvert bosqichiga qo'lda o'tib bo'lmaydi" taqiqidan
     * o'tmaydi. Shunday xaritalash bilan minglab lid yetim holatda —
     * {@code student_id} bo'sh, lekin konvert ustunida — tushardi va
     * KPI ni ham, kanban sanog'ini ham buzardi. O'quvchi faqat
     * konvertatsiya orqali yaratiladi.
     */
    private void requireNoConvertedMapping(Map<String, String> rawStageMapping) {
        if (rawStageMapping == null || rawStageMapping.isEmpty()) {
            return;
        }
        String blocked = rawStageMapping.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isBlank())
            .filter(e -> leadStageService.isConverted(e.getValue()))
            .map(e -> trimOrEmpty(e.getKey()) + " -> " + e.getValue().trim())
            .sorted()
            .collect(Collectors.joining("; "));
        if (!blocked.isEmpty()) {
            throw new BadRequestException(messages.get("leadImport.stage.converted", blocked));
        }
    }

    private static <V> Map<String, V> normalizeKeys(Map<String, V> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, V> map = new java.util.HashMap<>();
        raw.forEach((key, value) -> map.put(normalizeKey(key), value));
        return map;
    }

    /** Excel'dagi ortiqcha probel va registr moslashtirishni buzmasin. */
    private static String normalizeKey(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trimOrEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static List<LeadImportPreviewResponse.NameCount> countBy(
            List<ParsedRow> rows, java.util.function.Function<ParsedRow, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            String value = key.apply(row);
            counts.merge(ImportService.isBlank(value) ? "—" : value, 1L, Long::sum);
        }
        List<LeadImportPreviewResponse.NameCount> list = new ArrayList<>(counts.size());
        counts.forEach((name, count) ->
            list.add(new LeadImportPreviewResponse.NameCount(name, count)));
        list.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        return list;
    }

    private static List<LeadImportPreviewResponse.SampleRow> sampleRows(List<ParsedRow> rows) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<LeadImportPreviewResponse.SampleRow> list = new ArrayList<>();
        for (ParsedRow row : rows.subList(0, Math.min(SAMPLE_ROWS, rows.size()))) {
            list.add(LeadImportPreviewResponse.SampleRow.builder()
                .row(row.rowNum())
                .fullName(row.fullName())
                .phone(row.phone())
                .phoneValid(row.phoneValid())
                .stage(row.stage())
                .operator(row.operator())
                .source(row.source())
                .format(row.format())
                .createdAt(row.createdAt())
                .notes(row.notes())
                .noteCount(row.noteTexts().size())
                .build());
        }
        return list;
    }
}
