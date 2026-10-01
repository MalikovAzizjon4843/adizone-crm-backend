package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.exception.BadRequestException;
import com.crm.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadFile(
            @RequestParam("file") MultipartFile file) {
        try {
            // Kengaytmani saveImage oq ro'yxat bo'yicha o'zi qo'shadi
            String fileUrl = fileStorageService.saveImage(file, UUID.randomUUID().toString());
            String filename = fileUrl.substring(FileStorageService.URL_PREFIX.length());
            Map<String, String> response = new HashMap<>();
            response.put("url", fileUrl);
            response.put("filename", filename);
            return ResponseEntity.ok(ApiResponse.success("Fayl yuklandi", response));
        } catch (Exception e) {
            if (e instanceof BadRequestException) {
                throw (BadRequestException) e;
            }
            throw new BadRequestException("Fayl yuklashda xatolik: " + e.getMessage());
        }
    }

    /**
     * Fayl berish. Ataylab OCHIQ (SecurityConfig: {@code GET /api/files/**}
     * permitAll): {@code <img src>} va {@code <audio src>} Authorization
     * sarlavhasini yubora olmaydi. Himoya — taxmin qilib bo'lmaydigan UUID nom.
     *
     * <p>Content-Type faqat kengaytmadan (oq ro'yxat) olinadi, {@code nosniff}
     * brauzerni undan chetga chiqishdan to'xtatadi; rasm/PDF/audio bo'lmagan
     * hamma narsa {@code attachment} — sahifa sifatida ochilmaydi.
     */
    @GetMapping("/{filename}")
    public ResponseEntity<Resource> getFile(@PathVariable(name = "filename") String filename) {
        try {
            Path filePath = fileStorageService.resolveSafePath(filename);
            if (filePath == null || !Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = FileStorageService.contentTypeFor(filename);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            ContentDisposition disposition = (FileStorageService.isInlineType(contentType)
                    ? ContentDisposition.inline()
                    : ContentDisposition.attachment())
                .filename(filename)
                .build();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
