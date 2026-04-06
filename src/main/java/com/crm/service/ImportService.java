package com.crm.service;

import com.crm.dto.response.ImportResult;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;

    @Transactional
    public ImportResult importStudents(MultipartFile file) {
        List<Student> students = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int dataRowCount = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();

            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }
                dataRowCount++;
                int rowNum = r + 1;

                try {
                    Student student = new Student();
                    student.setFirstName(truncate(getCellString(row, 0), 100));
                    student.setLastName(truncate(getCellString(row, 1), 100));
                    student.setPhone(normalizePhone(getCellString(row, 2)));
                    student.setParentPhone(normalizePhone(getCellString(row, 3)));
                    student.setAddress(getCellString(row, 4));
                    student.setGender(truncate(getCellString(row, 5), 50));
                    student.setStatus(StudentStatus.ACTIVE);
                    student.setMarketingSource(parseMarketingSource(getCellString(row, 6)));

                    if (student.getFirstName() == null || student.getFirstName().isBlank()) {
                        errors.add("Qator " + rowNum + ": Ism bo'sh bo'lishi mumkin emas");
                        continue;
                    }
                    if (student.getLastName() == null || student.getLastName().isBlank()) {
                        errors.add("Qator " + rowNum + ": Familiya bo'sh bo'lishi mumkin emas");
                        continue;
                    }
                    if (student.getPhone() == null || student.getPhone().isBlank()) {
                        errors.add("Qator " + rowNum + ": Telefon bo'sh bo'lishi mumkin emas");
                        continue;
                    }
                    if (studentRepository.findByPhone(student.getPhone()).isPresent()) {
                        errors.add("Qator " + rowNum + ": Telefon allaqachon ro'yxatda: " + student.getPhone());
                        continue;
                    }

                    students.add(student);
                } catch (Exception e) {
                    log.warn("Student import row {}: {}", rowNum, e.getMessage());
                    errors.add("Qator " + rowNum + ": " + e.getMessage());
                }
            }

            if (!students.isEmpty()) {
                studentRepository.saveAll(students);
            }

        } catch (Exception e) {
            throw new BadRequestException("Excel faylni o'qishda xatolik: " + e.getMessage());
        }

        return ImportResult.builder()
            .totalRows(dataRowCount)
            .imported(students.size())
            .failed(errors.size())
            .errors(errors)
            .build();
    }

    @Transactional
    public ImportResult importTeachers(MultipartFile file) {
        List<Teacher> teachers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
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
                        errors.add("Qator " + rowNum + ": Ism bo'sh");
                        continue;
                    }
                    if (teacher.getLastName() == null || teacher.getLastName().isBlank()) {
                        errors.add("Qator " + rowNum + ": Familiya bo'sh");
                        continue;
                    }
                    if (teacher.getPhone() == null || teacher.getPhone().isBlank()) {
                        errors.add("Qator " + rowNum + ": Telefon bo'sh");
                        continue;
                    }
                    if (teacherRepository.findByPhone(teacher.getPhone()).isPresent()) {
                        errors.add("Qator " + rowNum + ": Telefon allaqachon ro'yxatda: " + teacher.getPhone());
                        continue;
                    }

                    teachers.add(teacher);
                } catch (Exception e) {
                    log.warn("Teacher import row {}: {}", rowNum, e.getMessage());
                    errors.add("Qator " + rowNum + ": " + e.getMessage());
                }
            }

            if (!teachers.isEmpty()) {
                teacherRepository.saveAll(teachers);
            }

        } catch (Exception e) {
            throw new BadRequestException("Excel faylni o'qishda xatolik: " + e.getMessage());
        }

        return ImportResult.builder()
            .totalRows(dataRowCount)
            .imported(teachers.size())
            .failed(errors.size())
            .errors(errors)
            .build();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen);
    }

    /** Spaces olib tashlanadi; DB chegarasiga moslashtiriladi. */
    private static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().replaceAll("\\s+", "").replace("\u00a0", "");
        return truncate(t, 32);
    }

    private static MarketingSource parseMarketingSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return MarketingSource.OTHER;
        }
        try {
            return MarketingSource.valueOf(raw.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return MarketingSource.OTHER;
        }
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
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
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString();
                }
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v)) {
                    yield String.valueOf((long) v);
                }
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> getFormulaStringValue(cell);
            default -> null;
        };
    }

    private String getFormulaStringValue(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v)) {
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
