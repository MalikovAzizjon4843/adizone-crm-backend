package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ImportResult;
import com.crm.exception.BadRequestException;
import com.crm.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping("/students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ImportResult>> importStudents(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("Fayl bo'sh");
        }
        return ResponseEntity.ok(ApiResponse.success(
            "Import tugadi",
            importService.importStudents(file)));
    }

    @PostMapping("/teachers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<ImportResult>> importTeachers(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("Fayl bo'sh");
        }
        return ResponseEntity.ok(ApiResponse.success(
            "Import tugadi",
            importService.importTeachers(file)));
    }

    @GetMapping("/template/students")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> downloadStudentTemplate() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("O'quvchilar");

        Row header = sheet.createRow(0);
        String[] headers = {
            "Ism*", "Familiya*", "Telefon*",
            "Ota-ona telefoni", "Manzil",
            "Jins (MALE/FEMALE)",
            "Manba (INSTAGRAM/TELEGRAM/YOUTUBE/REFERRAL/OFFLINE/OTHER)"
        };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            sheet.setColumnWidth(i, 5000);
        }

        Row sample = sheet.createRow(1);
        sample.createCell(0).setCellValue("Aziz");
        sample.createCell(1).setCellValue("Karimov");
        sample.createCell(2).setCellValue("+998901234567");
        sample.createCell(3).setCellValue("+998901234568");
        sample.createCell(4).setCellValue("Toshkent");
        sample.createCell(5).setCellValue("MALE");
        sample.createCell(6).setCellValue("INSTAGRAM");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=students_template.xlsx")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(out.toByteArray());
    }

    @GetMapping("/template/teachers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<byte[]> downloadTeacherTemplate() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("O'qituvchilar");

        Row header = sheet.createRow(0);
        String[] headers = {
            "Ism*", "Familiya*", "Telefon*",
            "Email", "Fan/Ixtisoslik", "Maosh (UZS)"
        };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            sheet.setColumnWidth(i, 5000);
        }

        Row sample = sheet.createRow(1);
        sample.createCell(0).setCellValue("Malika");
        sample.createCell(1).setCellValue("Yusupova");
        sample.createCell(2).setCellValue("+998901234567");
        sample.createCell(3).setCellValue("malika@gmail.com");
        sample.createCell(4).setCellValue("Ingliz tili");
        sample.createCell(5).setCellValue(3000000);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=teachers_template.xlsx")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(out.toByteArray());
    }
}
