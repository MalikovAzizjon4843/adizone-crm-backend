package com.crm.service;

import com.crm.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class FileStorageService {

    private static final long MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Saqlangan fayl URL'ining boshi. {@code public} — chat kelgan
     * {@code fileUrl} aynan shu backend bergan yo'lmi, shuni tekshiradi.
     */
    public static final String URL_PREFIX = "/api/files/";

    private final String uploadDir;
    private final String baseUrl;

    public FileStorageService(
            @Value("${app.upload.dir:/opt/crm/uploads}") String uploadDir,
            @Value("${app.base-url:https://api.adizone.uz}") String baseUrl) {
        this.uploadDir = uploadDir;
        this.baseUrl = baseUrl;
    }

    public void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fayl bo'sh");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BadRequestException("Faqat rasm fayllari qabul qilinadi");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("Fayl hajmi 4MB dan oshmasligi kerak");
        }
    }

    /**
     * Saves under {@code uploadDir} using the given {@code filename} (caller controls uniqueness).
     */
    public String saveImage(MultipartFile file, String filename) throws IOException {
        validateImage(file);
        return save(file, filename);
    }

    /**
     * Diskka yozishning o'zi — turini tekshirmaydi.
     *
     * <p>Chat biriktirmalari uchun kerak: u yerda rasmdan tashqari PDF,
     * hujjat va arxiv ham qabul qilinadi, ruxsat etilgan turlar ro'yxati
     * esa chaqiruvchida. {@link #saveImage} shu metodga tayanadi, ya'ni
     * saqlash mantig'i bitta joyda qoladi.
     */
    public String save(MultipartFile file, String filename) throws IOException {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path target = uploadPath.resolve(filename).normalize();
        if (!target.startsWith(uploadPath)) {
            throw new BadRequestException("Noto'g'ri fayl nomi");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return URL_PREFIX + filename;
    }

    public Path resolveSafePath(String filename) throws IOException {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path filePath = uploadPath.resolve(filename).normalize();
        if (!filePath.startsWith(uploadPath)) {
            return null;
        }
        return filePath;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
