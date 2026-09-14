package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.response.ImportResult;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.BadRequestException;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.StudentImportRowService.RowImportException;
import com.crm.service.StudentImportRowService.StudentRowData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private static final DateTimeFormatter DATE_DD_MM_YYYY =
        DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Xato matnida ko'rsatiladigan mavjud guruhlarning maksimal soni. */
    private static final int MAX_GROUPS_IN_ERROR = 10;

    /** Import orqali o'quvchi qo'shish mumkin bo'lgan guruh statuslari. */
    private static final Set<GroupStatus> ENROLLABLE_GROUP_STATUSES =
        EnumSet.of(GroupStatus.ACTIVE, GroupStatus.FORMING);

    private static final String[] STUDENT_TEMPLATE_HEADERS = {
        "Ism*", "Familiya*", "Telefon*", "Ota-ona telefoni", "Tug'ilgan sana",
        "Jins", "Qayerdan kelgan", "Manzil", "Qabul sanasi", "Izoh",
        "Guruh nomi", "To'lov turi", "Oylik to'lov", "Dars narxi",
        "To'lov boshlanish sanasi", "Sinov darsi",
        "Ota F.I.O", "Ota telefoni", "Ota manzili",
        "Ona F.I.O", "Ona telefoni", "Ona manzili"
    };

    private static final String TEMPLATE_NOTE =
        "MUHIM: 1 va 2-qatorlarni o'zgartirmang. Ma'lumotlarni 3-qatordan boshlab kiriting. "
            + "To'ldirish namunasi uchun 'NAMUNA' varag'iga qarang. "
            + "Qabul raqami avtomatik beriladi.";

    private static final String SAMPLE_SHEET_NOTE =
        "Bu varaq faqat namuna uchun — import qilishda O'QILMAYDI. "
            + "Qatorlarni nusxalab, 'O'quvchilar' varag'iga joylashtiring. "
            + "'Guruh nomi' bazadagi mavjud guruh nomi bilan bir xil bo'lishi shart.";

    private static final String[] GENDER_VALUES = {"MALE", "FEMALE"};
    private static final String[] TRIAL_VALUES = {"HA", "YO'Q"};

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final StudentImportRowService studentImportRowService;

    /** Haqiqiy import — qatorlar bazaga yoziladi. */
    @Audited(action = AuditAction.IMPORT, entity = "Student",
        summary = "'O''quvchilar import qilindi: ' + #result.imported + '/' + #result.totalRows")
    public ImportResult importStudents(MultipartFile file) {
        return process(file, false);
    }

    /** Dry-run: bir xil validatsiya, lekin bazaga hech narsa yozilmaydi. */
    public ImportResult validateStudents(MultipartFile file) {
        return process(file, true);
    }

    private ImportResult process(MultipartFile file, boolean dryRun) {
        List<ImportResult.ImportIssue> errors = new ArrayList<>();
        List<ImportResult.ImportIssue> warnings = new ArrayList<>();
        int dataRowCount = 0;
        int validRows = 0;
        int skippedRows = 0;

        User currentUser = dryRun ? null : resolveCurrentUser();
        RowContext ctx = new RowContext(loadGroupIndex());

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            // Faqat birinchi varaq o'qiladi — "NAMUNA" va "QIYMATLAR" varaqlari e'tiborsiz qoladi.
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();

            Row headerRow = sheet.getRow(1);
            if (headerRow == null) {
                throw new BadRequestException("Excel shablonida 2-qator (sarlavha) topilmadi");
            }
            Map<String, Integer> colIndex = buildHeaderIndex(headerRow);
            requireHeader(colIndex, "Ism");
            requireHeader(colIndex, "Familiya");
            requireHeader(colIndex, "Telefon");

            for (int r = 2; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }
                dataRowCount++;
                int rowNum = r + 1;

                List<String> rowErrors = new ArrayList<>();
                List<String> rowWarnings = new ArrayList<>();
                StudentRowData data;

                try {
                    data = validateStudentRow(row, colIndex, rowNum, ctx, rowErrors, rowWarnings);
                } catch (Exception e) {
                    log.error("Import row {} validation failed", rowNum, e);
                    errors.add(issue(rowNum, humanizeError(rowNum, e)));
                    skippedRows++;
                    continue;
                }

                if (!rowErrors.isEmpty()) {
                    for (String message : rowErrors) {
                        errors.add(issue(rowNum, message));
                    }
                    skippedRows++;
                    continue;
                }

                if (dryRun) {
                    validRows++;
                    addWarnings(warnings, rowNum, rowWarnings);
                    continue;
                }

                try {
                    StudentImportRowService.RowImportOutcome outcome =
                        studentImportRowService.importRow(data, currentUser);
                    validRows++;
                    addWarnings(warnings, rowNum, rowWarnings);
                    addWarnings(warnings, rowNum, outcome.warnings());
                } catch (RowImportException e) {
                    errors.add(issue(rowNum, rowNum + "-qator: " + e.getMessage()));
                    skippedRows++;
                } catch (Exception e) {
                    log.error("Import row {} failed", rowNum, e);
                    errors.add(issue(rowNum, humanizeError(rowNum, e)));
                    skippedRows++;
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Student import file could not be read", e);
            throw new BadRequestException(
                "Excel faylni o'qib bo'lmadi. Fayl .xlsx formatida va shablonga mos ekanini tekshiring.");
        }

        return ImportResult.builder()
            .totalRows(dataRowCount)
            .imported(dryRun ? 0 : validRows)
            .validRows(validRows)
            .skipped(skippedRows)
            .errors(errors)
            .warnings(warnings)
            .build();
    }

    // ------------------------------------------------------------------
    // Qator validatsiyasi (import va dry-run uchun bir xil)
    // ------------------------------------------------------------------

    /** Bitta fayl davomida saqlanadigan holat: guruhlar indeksi, ko'rilgan telefonlar, guruh bandligi. */
    private static final class RowContext {
        private final Map<String, Group> groupIndex;
        private final Map<String, Integer> phoneFirstRow = new HashMap<>();
        private final Map<Long, Long> groupUsage = new HashMap<>();

        private RowContext(Map<String, Group> groupIndex) {
            this.groupIndex = groupIndex;
        }
    }

    private StudentRowData validateStudentRow(Row row,
                                              Map<String, Integer> col,
                                              int rowNum,
                                              RowContext ctx,
                                              List<String> rowErrors,
                                              List<String> rowWarnings) {
        String firstName = truncate(cellByHeader(row, col, "Ism"), 100);
        String lastName = truncate(cellByHeader(row, col, "Familiya"), 100);
        if (isBlank(firstName) || isBlank(lastName)) {
            rowErrors.add(rowNum + "-qator: Ism va Familiya majburiy");
        }

        String phone = validatePhone(row, col, rowNum, ctx, rowErrors);

        String genderRaw = cellByHeader(row, col, "Jins");
        String gender = null;
        if (!isBlank(genderRaw)) {
            gender = parseGender(genderRaw);
            if (gender == null) {
                rowErrors.add(rowNum + "-qator: Jins faqat " + options(GENDER_VALUES)
                    + " bo'lishi mumkin, kiritildi: '" + genderRaw.trim() + "'");
            }
        }

        String sourceRaw = cellByHeader(row, col, "Qayerdan kelgan");
        MarketingSource marketingSource = null;
        if (!isBlank(sourceRaw)) {
            marketingSource = parseMarketingSource(sourceRaw);
            if (marketingSource == null) {
                marketingSource = MarketingSource.OTHER;
                rowWarnings.add(rowNum + "-qator: manba '" + sourceRaw.trim()
                    + "' tanilmadi -> OTHER qilib yozildi. Ruxsat etilganlar: "
                    + enumList(MarketingSource.values()));
            }
        }

        String paymentTypeRaw = cellByHeader(row, col, "To'lov turi");
        PaymentType paymentType = PaymentType.MONTHLY;
        if (!isBlank(paymentTypeRaw)) {
            paymentType = parsePaymentType(paymentTypeRaw);
            if (paymentType == null) {
                rowErrors.add(rowNum + "-qator: To'lov turi faqat "
                    + enumOptions(PaymentType.values()) + " bo'lishi mumkin, kiritildi: '"
                    + paymentTypeRaw.trim() + "'");
            }
        }

        // Guruh narx fallback'i uchun kerak — shuning uchun to'lov summalaridan OLDIN aniqlanadi.
        Group group = resolveGroup(row, col, rowNum, ctx, rowErrors, rowWarnings);
        Long groupId = group != null ? group.getId() : null;

        Cell monthlyCell = cellRaw(row, col, "Oylik to'lov");
        Cell lessonCell = cellRaw(row, col, "Dars narxi");
        BigDecimal monthlyFee = parseAmount(monthlyCell);
        BigDecimal lessonPrice = parseAmount(lessonCell);
        if (paymentType == PaymentType.MONTHLY) {
            if (isUnreadableAmount(monthlyFee, monthlyCell)) {
                rowErrors.add(amountFormatError(rowNum, "Oylik to'lov", monthlyCell));
            } else {
                monthlyFee = resolveFee(monthlyFee, group, Course::getMonthlyPrice,
                    "Oylik to'lov", rowNum, rowErrors, rowWarnings);
            }
        } else if (paymentType == PaymentType.PER_LESSON) {
            if (isUnreadableAmount(lessonPrice, lessonCell)) {
                rowErrors.add(amountFormatError(rowNum, "Dars narxi", lessonCell));
            } else {
                lessonPrice = resolveFee(lessonPrice, group, Course::getLessonPrice,
                    "Dars narxi", rowNum, rowErrors, rowWarnings);
            }
        }

        LocalDate birthDate = parseDateChecked(row, col, "Tug'ilgan sana", rowNum, rowErrors);
        LocalDate admissionDate = parseDateChecked(row, col, "Qabul sanasi", rowNum, rowErrors);
        LocalDate paymentStartDate =
            parseDateChecked(row, col, "To'lov boshlanish sanasi", rowNum, rowErrors);

        return new StudentRowData(
            firstName,
            lastName,
            phone,
            normalizePhone(cellByHeader(row, col, "Ota-ona telefoni")),
            birthDate,
            gender,
            marketingSource,
            cellByHeader(row, col, "Manzil"),
            admissionDate,
            cellByHeader(row, col, "Izoh"),
            cellByHeader(row, col, "Guruh nomi"),
            groupId,
            paymentType,
            monthlyFee,
            lessonPrice,
            paymentStartDate,
            parseTrial(cellByHeader(row, col, "Sinov darsi")),
            truncate(cellByHeader(row, col, "Ota F.I.O"), 200),
            normalizePhone(cellByHeader(row, col, "Ota telefoni")),
            cellByHeader(row, col, "Ota manzili"),
            truncate(cellByHeader(row, col, "Ona F.I.O"), 200),
            normalizePhone(cellByHeader(row, col, "Ona telefoni")),
            cellByHeader(row, col, "Ona manzili")
        );
    }

    private String validatePhone(Row row, Map<String, Integer> col, int rowNum,
                                 RowContext ctx, List<String> rowErrors) {
        String phoneRaw = cellByHeader(row, col, "Telefon");
        String phone = normalizePhone(phoneRaw);
        if (isBlank(phone)) {
            rowErrors.add(rowNum + "-qator: Telefon majburiy");
            return null;
        }

        String digits = phoneDigits(phone);
        if (digits == null) {
            rowErrors.add(rowNum + "-qator: telefon formati noto'g'ri: '" + phoneRaw.trim()
                + "' (namuna: +998901234567)");
            return phone;
        }

        Integer firstRow = ctx.phoneFirstRow.putIfAbsent(digits, rowNum);
        if (firstRow != null) {
            rowErrors.add(rowNum + "-qator: bu telefon faylning " + firstRow
                + "-qatorida ham bor: '" + phone + "'");
            return phone;
        }

        Student existing = studentRepository.findByPhone(phone).orElse(null);
        if (existing != null) {
            rowErrors.add(rowNum + "-qator: bu telefon bilan o'quvchi allaqachon mavjud: "
                + displayName(existing) + " (ID " + existing.getId() + ")");
        }
        return phone;
    }

    /**
     * Guruh nomini indeksdan qidiradi. Nomi ko'rsatilgan-u topilmasa — qator RAD ETILADI
     * (guruhsiz o'quvchi yaratilmaydi). Nomi bo'sh bo'lsa — ruxsat etiladi, ogohlantirish beriladi.
     * ACTIVE va FORMING guruhlarga qo'shish mumkin; COMPLETED/CANCELLED rad etiladi.
     */
    private Group resolveGroup(Row row, Map<String, Integer> col, int rowNum,
                               RowContext ctx, List<String> rowErrors, List<String> rowWarnings) {
        String groupNameRaw = cellByHeader(row, col, "Guruh nomi");
        if (isBlank(groupNameRaw)) {
            rowWarnings.add(rowNum + "-qator: guruh ko'rsatilmagan — o'quvchi guruhsiz yaratildi");
            return null;
        }

        Group group = ctx.groupIndex.get(normalizeGroupName(groupNameRaw));
        if (group == null) {
            rowErrors.add(rowNum + "-qator: '" + groupNameRaw.trim()
                + "' nomli guruh topilmadi. Mavjud guruhlar: " + availableGroupNames(ctx.groupIndex));
            return null;
        }
        if (!ENROLLABLE_GROUP_STATUSES.contains(group.getStatus())) {
            rowErrors.add(rowNum + "-qator: '" + group.getGroupName() + "' guruhi "
                + statusLabel(group.getStatus()) + " (" + group.getStatus()
                + ") — unga o'quvchi qo'shib bo'lmaydi");
            return null;
        }

        long used = ctx.groupUsage.computeIfAbsent(group.getId(),
            studentGroupRepository::countByGroupIdAndIsActiveTrue);
        if (group.getMaxStudents() != null && used >= group.getMaxStudents()) {
            rowErrors.add(rowNum + "-qator: '" + group.getGroupName() + "' guruhi to'lgan ("
                + used + "/" + group.getMaxStudents() + ")");
            return null;
        }
        ctx.groupUsage.put(group.getId(), used + 1);
        return group;
    }

    private static String statusLabel(GroupStatus status) {
        return status == GroupStatus.CANCELLED ? "bekor qilingan" : "tugagan";
    }

    /**
     * To'lov summasini aniqlaydi: 1) ustunda ko'rsatilgan qiymat, 2) guruh kursidagi narx
     * (ogohlantirish bilan), 3) ikkalasi ham yo'q bo'lsa — xato.
     * Dry-run va haqiqiy import bir xil mantiqdan foydalanadi.
     */
    private BigDecimal resolveFee(BigDecimal entered,
                                  Group group,
                                  Function<Course, BigDecimal> courseFee,
                                  String columnLabel,
                                  int rowNum,
                                  List<String> rowErrors,
                                  List<String> rowWarnings) {
        if (isPositive(entered)) {
            return entered;
        }
        if (entered != null) {
            rowErrors.add(rowNum + "-qator: " + columnLabel + " 0 dan katta bo'lishi kerak, kiritildi: "
                + entered.toPlainString());
            return null;
        }

        BigDecimal fromCourse = group != null && group.getCourse() != null
            ? courseFee.apply(group.getCourse())
            : null;
        if (isPositive(fromCourse)) {
            rowWarnings.add(rowNum + "-qator: " + columnLabel
                + " ko'rsatilmagan — kurs narxi olindi: " + formatAmount(fromCourse));
            return fromCourse;
        }

        rowErrors.add(rowNum + "-qator: " + columnLabel + " majburiy (guruh ko'rsatilmagani yoki "
            + "kursda narx belgilanmagani uchun avtomatik olib bo'lmadi)");
        return null;
    }

    /** Katak to'ldirilgan, lekin sonni o'qib bo'lmadi (masalan matn yozilgan). */
    private boolean isUnreadableAmount(BigDecimal parsed, Cell cell) {
        return parsed == null && !isBlank(getCellStringFromCell(cell));
    }

    private String amountFormatError(int rowNum, String columnLabel, Cell cell) {
        return rowNum + "-qator: '" + columnLabel + "' son bo'lishi kerak, kiritildi: '"
            + getCellStringFromCell(cell).trim() + "' (namuna: 800000)";
    }

    /** 800000 -> "800 000 UZS" */
    private static String formatAmount(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        return new DecimalFormat("#,##0.##", symbols).format(value) + " UZS";
    }

    private Map<String, Group> loadGroupIndex() {
        // COMPLETED/CANCELLED guruhlar ham indeksga olinadi — aks holda "guruh topilmadi"
        // degan chalg'ituvchi xato chiqadi. Ular status bo'yicha rad etiladi.
        return groupRepository.findAllWithCourse().stream()
            .filter(g -> g.getGroupName() != null && !g.getGroupName().isBlank())
            .collect(Collectors.toMap(
                g -> normalizeGroupName(g.getGroupName()),
                g -> g,
                (a, b) -> a));
    }

    /** Guruh nomlarini solishtirish uchun normallashtirish: probellar, tirelar, apostroflar, registr. */
    private String normalizeGroupName(String s) {
        if (s == null) {
            return null;
        }
        return s.trim()
            .replaceAll("\\s+", " ")      // ketma-ket probellar -> bitta
            .replace('–', '-')       // en-dash -> defis
            .replace('—', '-')       // em-dash -> defis
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('ʻ', '\'')
            .replace('ʼ', '\'')
            .toLowerCase(Locale.ROOT);
    }

    /** Taklif sifatida faqat o'quvchi qo'shsa bo'ladigan (ACTIVE, FORMING) guruhlar sanaladi. */
    private String availableGroupNames(Map<String, Group> groupIndex) {
        List<String> names = groupIndex.values().stream()
            .filter(g -> ENROLLABLE_GROUP_STATUSES.contains(g.getStatus()))
            .map(Group::getGroupName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        if (names.isEmpty()) {
            return "bazada o'quvchi qo'shsa bo'ladigan guruh yo'q";
        }
        if (names.size() <= MAX_GROUPS_IN_ERROR) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_GROUPS_IN_ERROR))
            + " va yana " + (names.size() - MAX_GROUPS_IN_ERROR) + " ta";
    }

    // ------------------------------------------------------------------
    // Xato matnlarini insonlashtirish
    // ------------------------------------------------------------------

    private String humanizeError(int rowNum, Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = String.valueOf(root.getMessage());

        if (msg.contains("violates check constraint")) {
            return rowNum + "-qator: bazadagi cheklov buzildi — kiritilgan qiymat "
                + "bazada ruxsat etilgan ro'yxatda yo'q. Administratorga murojaat qiling.";
        }
        if (msg.contains("duplicate key") || msg.contains("unique constraint")) {
            return rowNum + "-qator: bunday yozuv allaqachon mavjud (takrorlanuvchi qiymat).";
        }
        if (msg.contains("null value in column")) {
            return rowNum + "-qator: majburiy maydon to'ldirilmagan.";
        }
        if (msg.contains("value too long")) {
            return rowNum + "-qator: qiymat juda uzun.";
        }
        return rowNum + "-qator: kutilmagan xato. Batafsil ma'lumot server logida.";
    }

    // ------------------------------------------------------------------
    // Shablon
    // ------------------------------------------------------------------

    public byte[] buildStudentImportTemplate() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            buildMainSheet(workbook, headerStyle);
            buildSampleSheet(workbook, headerStyle);
            buildAllowedValuesSheet(workbook, headerStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Student import template could not be generated", e);
            throw new BadRequestException("Shablon yaratishda xatolik yuz berdi");
        }
    }

    /** Asosiy varaq: 1-qator izoh, 2-qator sarlavhalar, 3-qatordan boshlab BO'SH. */
    private void buildMainSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("O'quvchilar");
        sheet.createRow(0).createCell(0).setCellValue(TEMPLATE_NOTE);
        writeHeaderRow(sheet, headerStyle);
        sheet.createFreezePane(0, 2);
        autoSizeColumns(sheet, STUDENT_TEMPLATE_HEADERS.length);
    }

    /** Namuna varag'i: import bu varaqni o'qimaydi. */
    private void buildSampleSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("NAMUNA");
        sheet.createRow(0).createCell(0).setCellValue(SAMPLE_SHEET_NOTE);
        writeHeaderRow(sheet, headerStyle);

        writeSampleRow(sheet, 2, new String[]{
            "Aziz", "Karimov", "+998901234567", "+998901234568", "15.05.2010",
            "MALE", "INSTAGRAM", "Toshkent, Chilonzor", "01.08.2026", "Oylik to'lov namunasi",
            "", "MONTHLY", "500000", "", "01.08.2026", "YO'Q",
            "Karimov Akmal", "+998901111111", "Toshkent",
            "Karimova Dilfuza", "+998902222222", "Toshkent"
        });
        writeSampleRow(sheet, 3, new String[]{
            "Aziza", "Rahimova", "+998901234569", "+998901234570", "22.09.2012",
            "FEMALE", "TELEGRAM", "Toshkent, Yunusobod", "05.08.2026", "Dars narxi namunasi",
            "Ingliz tili A1", "PER_LESSON", "", "50000", "05.08.2026", "HA",
            "Rahimov Bobur", "+998903333333", "Toshkent",
            "Rahimova Nodira", "+998904444444", "Toshkent"
        });

        sheet.createFreezePane(0, 2);
        autoSizeColumns(sheet, STUDENT_TEMPLATE_HEADERS.length);
    }

    /** Ruxsat etilgan qiymatlar varag'i — enum ro'yxatlari dinamik yig'iladi. */
    private void buildAllowedValuesSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("QIYMATLAR");

        Row header = sheet.createRow(0);
        Cell columnHeader = header.createCell(0);
        columnHeader.setCellValue("Ustun");
        columnHeader.setCellStyle(headerStyle);
        Cell valueHeader = header.createCell(1);
        valueHeader.setCellValue("Ruxsat etilgan qiymat");
        valueHeader.setCellStyle(headerStyle);

        int rowIdx = 1;
        rowIdx = writeAllowedValues(sheet, rowIdx, "Jins", GENDER_VALUES);
        rowIdx = writeAllowedValues(sheet, rowIdx, "Qayerdan kelgan",
            enumNames(MarketingSource.values()));
        rowIdx = writeAllowedValues(sheet, rowIdx, "To'lov turi", enumNames(PaymentType.values()));
        writeAllowedValues(sheet, rowIdx, "Sinov darsi", TRIAL_VALUES);

        sheet.createFreezePane(0, 1);
        autoSizeColumns(sheet, 2);
    }

    private int writeAllowedValues(Sheet sheet, int startRow, String column, String[] values) {
        int rowIdx = startRow;
        for (String value : values) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(column);
            row.createCell(1).setCellValue(value);
        }
        return rowIdx;
    }

    private void writeHeaderRow(Sheet sheet, CellStyle headerStyle) {
        Row header = sheet.createRow(1);
        for (int i = 0; i < STUDENT_TEMPLATE_HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(STUDENT_TEMPLATE_HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeSampleRow(Sheet sheet, int rowIdx, String[] values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    /**
     * Ustunlarni matn kengligiga moslaydi. Shrift topilmagan muhitlarda
     * autoSizeColumn ishlamasligi mumkin — bunday holda qat'iy kenglikka qaytamiz.
     */
    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            try {
                sheet.autoSizeColumn(i);
                int width = Math.min(Math.max(sheet.getColumnWidth(i) + 512, 3000), 12000);
                sheet.setColumnWidth(i, width);
            } catch (Exception e) {
                sheet.setColumnWidth(i, 4500);
            }
        }
    }

    // ------------------------------------------------------------------
    // O'qituvchilar importi
    // ------------------------------------------------------------------

    @Transactional
    public ImportResult importTeachers(MultipartFile file) {
        List<Teacher> teachers = new ArrayList<>();
        List<ImportResult.ImportIssue> errors = new ArrayList<>();
        int dataRowCount = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            long baseCount = teacherRepository.count();

            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }
                dataRowCount++;
                int rowNum = r + 1;

                try {
                    Teacher teacher = new Teacher();
                    teacher.setFirstName(truncate(getCellString(row, 0), 100));
                    teacher.setLastName(truncate(getCellString(row, 1), 100));
                    teacher.setPhone(normalizePhone(getCellString(row, 2)));
                    teacher.setEmail(truncate(getCellString(row, 3), 255));
                    teacher.setSubjectSpecialization(truncate(getCellString(row, 4), 255));

                    Double salary = getCellNumber(row, 5);
                    if (salary != null) {
                        teacher.setBasicSalary(BigDecimal.valueOf(salary));
                    }

                    teacher.setIsActive(true);

                    String code = "TCH-" + String.format("%05d", baseCount + teachers.size() + 1);
                    teacher.setTeacherCode(code);

                    if (teacher.getFirstName() == null || teacher.getFirstName().isBlank()) {
                        errors.add(issue(rowNum, rowNum + "-qator: Ism bo'sh"));
                        continue;
                    }
                    if (teacher.getLastName() == null || teacher.getLastName().isBlank()) {
                        errors.add(issue(rowNum, rowNum + "-qator: Familiya bo'sh"));
                        continue;
                    }
                    if (teacher.getPhone() == null || teacher.getPhone().isBlank()) {
                        errors.add(issue(rowNum, rowNum + "-qator: Telefon bo'sh"));
                        continue;
                    }
                    if (teacherRepository.findByPhone(teacher.getPhone()).isPresent()) {
                        errors.add(issue(rowNum, rowNum + "-qator: telefon allaqachon ro'yxatda: "
                            + teacher.getPhone()));
                        continue;
                    }

                    teachers.add(teacher);
                } catch (Exception e) {
                    log.error("Teacher import row {} failed", rowNum, e);
                    errors.add(issue(rowNum, humanizeError(rowNum, e)));
                }
            }

            if (!teachers.isEmpty()) {
                teacherRepository.saveAll(teachers);
            }

        } catch (Exception e) {
            log.error("Teacher import file could not be read", e);
            throw new BadRequestException(
                "Excel faylni o'qib bo'lmadi. Fayl .xlsx formatida va shablonga mos ekanini tekshiring.");
        }

        return ImportResult.builder()
            .totalRows(dataRowCount)
            .imported(teachers.size())
            .validRows(teachers.size())
            .skipped(errors.size())
            .errors(errors)
            .warnings(new ArrayList<>())
            .build();
    }

    // ------------------------------------------------------------------
    // Yordamchi metodlar
    // ------------------------------------------------------------------

    private User resolveCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null) {
                return null;
            }
            return userRepository.findByUsername(auth.getName()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void addWarnings(List<ImportResult.ImportIssue> target, int rowNum,
                                    List<String> messages) {
        for (String message : messages) {
            target.add(issue(rowNum, message.startsWith(rowNum + "-qator:")
                ? message
                : rowNum + "-qator: " + message));
        }
    }

    private static ImportResult.ImportIssue issue(int row, String reason) {
        return ImportResult.ImportIssue.builder().row(row).reason(reason).build();
    }

    private static String displayName(Student student) {
        String first = student.getFirstName() != null ? student.getFirstName() : "";
        String last = student.getLastName() != null ? student.getLastName() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "noma'lum" : full;
    }

    /** Paket ichida ochiq: {@code LeadImportService} ham shu yordamchilarni ishlatadi. */
    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private static String enumList(Enum<?>[] values) {
        return String.join(", ", enumNames(values));
    }

    private static String enumOptions(Enum<?>[] values) {
        return String.join(" yoki ", enumNames(values));
    }

    private static String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static String options(String[] values) {
        return String.join(" yoki ", values);
    }

    private static void requireHeader(Map<String, Integer> colIndex, String key) {
        if (!colIndex.containsKey(normalizeHeaderKey(key))) {
            throw new BadRequestException("Majburiy ustun topilmadi: " + key);
        }
    }

    static Map<String, Integer> buildHeaderIndex(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        short last = headerRow.getLastCellNum();
        for (int c = 0; c < last; c++) {
            String raw = getCellString(headerRow, c);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = normalizeHeaderKey(raw);
            map.putIfAbsent(key, c);
        }
        return map;
    }

    static String normalizeHeaderKey(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim()
            .replace('*', ' ')
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('ʻ', '\'')
            .replace('ʼ', '\'')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
        return s;
    }

    static String cellByHeader(Row row, Map<String, Integer> col, String header) {
        Integer idx = col.get(normalizeHeaderKey(header));
        if (idx == null) {
            return null;
        }
        return getCellString(row, idx);
    }

    private Cell cellRaw(Row row, Map<String, Integer> col, String header) {
        Integer idx = col.get(normalizeHeaderKey(header));
        if (idx == null) {
            return null;
        }
        return row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private static String parseGender(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if ("MALE".equals(v) || "FEMALE".equals(v)) {
            return v;
        }
        return null;
    }

    private static MarketingSource parseMarketingSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MarketingSource.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tanilmagan qiymat uchun null qaytaradi — chaqiruvchi buni xato deb belgilaydi. */
    private static PaymentType parsePaymentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentType.MONTHLY;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if ("MONTHLY".equals(v) || "OYLIK".equals(v)) {
            return PaymentType.MONTHLY;
        }
        if ("PER_LESSON".equals(v) || "LESSON".equals(v) || "DARS".equals(v)) {
            return PaymentType.PER_LESSON;
        }
        return null;
    }

    private static Boolean parseTrial(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT)
            .replace('’', '\'')
            .replace('ʻ', '\'');
        return "HA".equals(v) || "YES".equals(v) || "TRUE".equals(v) || "1".equals(v);
    }

    /** Bo'sh katak -> null; to'ldirilgan, lekin parse bo'lmasa -> rowErrors ga tushunarli xato. */
    private LocalDate parseDateChecked(Row row, Map<String, Integer> col, String header,
                                       int rowNum, List<String> rowErrors) {
        Cell cell = cellRaw(row, col, header);
        if (cell == null) {
            return null;
        }
        String raw = getCellStringFromCell(cell);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDate parsed = parseDate(cell);
        if (parsed == null) {
            rowErrors.add(rowNum + "-qator: '" + header + "' noto'g'ri: '" + raw.trim()
                + "' (kerakli format: 15.03.2008)");
        }
        return parsed;
    }

    private LocalDate parseDate(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                java.util.Date d = cell.getDateCellValue();
                if (d == null) {
                    return null;
                }
                return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                // Excel serial date without date format
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    java.util.Date d = DateUtil.getJavaDate(cell.getNumericCellValue());
                    return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
            String s = getCellStringFromCell(cell);
            return parseDateString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDateString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDate.parse(s, DATE_DD_MM_YYYY);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private BigDecimal parseAmount(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.FORMULA
                && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            String s = getCellStringFromCell(cell);
            if (s == null || s.isBlank()) {
                return null;
            }
            String cleaned = s.replace(' ', ' ')
                .replace(" ", "")
                .replace(",", "")
                .trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen);
    }

    /**
     * Formatlash shovqinini olib tashlaydi: probel (oddiy va uzilmas),
     * apostrof, qavs, defis va nuqta.
     *
     * <p>Apostrof muhim — Excel matn katagini {@code '+998...} ko'rinishida
     * beradi va u tozalanmasa raqam yaroqsiz deb sanalardi.
     *
     * <p>Raqamni QAYTA FORMATLAMAYDI: {@code +} va harflar o'z joyida qoladi,
     * ya'ni "Turk tili" kabi matn baribir {@link #phoneDigits} dan o'tmaydi.
     */
    static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String t = stripPhoneNoise(raw);
        if (t.isEmpty()) {
            return null;
        }
        return truncate(t, 32);
    }

    /** Shovqin tozalangandan keyin 9..13 ta RAQAM qolsa — o'shani qaytaradi. */
    static String phoneDigits(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = stripPhoneNoise(raw).replace("+", "");
        return cleaned.matches("\\d{9,13}") ? cleaned : null;
    }

    /** Harf va {@code +} qoladi — shuning uchun matn baribir raqam bo'lib qolmaydi. */
    static String stripPhoneNoise(String raw) {
        return raw.replaceAll("[\\s\u00a0'`\u2018\u2019\u02bb\u02bc()\\-.]", "").trim();
    }

    static String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return getCellStringFromCell(cell);
    }

    static String getCellStringFromCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    java.util.Date d = cell.getDateCellValue();
                    if (d == null) {
                        yield null;
                    }
                    yield d.toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_DD_MM_YYYY);
                }
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    yield String.valueOf((long) v);
                }
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> getFormulaStringValue(cell);
            default -> null;
        };
    }

    static String getFormulaStringValue(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    yield String.valueOf((long) v);
                }
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private Double getCellNumber(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING -> {
                try {
                    String s = cell.getStringCellValue().trim();
                    if (s.isEmpty()) {
                        yield null;
                    }
                    yield Double.parseDouble(s.replace(",", "").replace(" ", ""));
                } catch (Exception e) {
                    yield null;
                }
            }
            case FORMULA -> {
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    yield cell.getNumericCellValue();
                }
                yield null;
            }
            default -> null;
        };
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        int last = Math.max(row.getLastCellNum(), 0);
        for (int c = 0; c <= last; c++) {
            String s = getCellString(row, c);
            if (s != null && !s.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
