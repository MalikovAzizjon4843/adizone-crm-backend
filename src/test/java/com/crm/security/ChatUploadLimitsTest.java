package com.crm.security;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CH-02 (P0): chat upload'da rasm o'lchami SARLAVHADAN, diskka yozishdan va har qanday
 * dekodlashdan oldin tekshiriladi. Avval {@code ImageIO.read} butun rasmni xotiraga ochardi —
 * 4MB ichidagi 20000×20000 PNG ("decompression bomb") JVM ni OOM qilardi.
 */
class ChatUploadLimitsTest extends Phase5ItBase {

    @Value("${app.upload.dir:/opt/crm/uploads}")
    String uploadDir;

    /** Faqat imzo + IHDR (+ IEND): o'lcham sarlavhada, piksel ma'lumoti yo'q — bir necha bayt. */
    static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height)
            .put((byte) 1)   // bit depth
            .put((byte) 0)   // grayscale
            .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    static byte[] realPng(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private long storedFiles() throws IOException {
        Path dir = Path.of(uploadDir).toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void headerDimensions_readWithoutDecoding() throws IOException {
        assertThat(FileStorageService.readImageDimensions(
            new MockMultipartFile("file", "a.png", "image/png", realPng(3, 2)))).containsExactly(3, 2);
        assertThat(FileStorageService.readImageDimensions(
            new MockMultipartFile("file", "b.png", "image/png", pngHeaderOnly(20_000, 20_000))))
            .containsExactly(20_000, 20_000);
        assertThat(FileStorageService.isImageSizeAllowed(4_000, 3_000)).isTrue();
        assertThat(FileStorageService.isImageSizeAllowed(20_000, 20_000)).isFalse();
        assertThat(FileStorageService.isImageSizeAllowed(12_000, 12_000)).isFalse(); // 144 MP
        // Rasm emas (masalan WebP plaginisiz) — o'lcham noma'lum, xato emas
        assertThat(FileStorageService.readImageDimensions(
            new MockMultipartFile("file", "c.png", "image/png", new byte[]{1, 2, 3}))).isNull();
    }

    @Test
    void decompressionBomb_rejectedWith400_beforeSaving() throws Exception {
        User staff = newUser(UserRole.TEACHER);
        byte[] bomb = pngHeaderOnly(20_000, 20_000);
        assertThat(bomb.length).isLessThan(100);
        long before = storedFiles();

        mvc.perform(multipart("/api/chat/upload")
                .file(new MockMultipartFile("file", "bomb.png", "image/png", bomb))
                .with(as(staff)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("20000")));

        assertThat(storedFiles()).isEqualTo(before);
    }

    @Test
    void oversizedFile_rejectedWith400() throws Exception {
        User staff = newUser(UserRole.ADMIN);
        byte[] big = new byte[(int) FileStorageService.MAX_BYTES + 1];
        mvc.perform(multipart("/api/chat/upload")
                .file(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                .with(as(staff)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void profilePhotoValidation_rejectsBombToo() {
        FileStorageService storage = new FileStorageService(uploadDir, "http://localhost");
        assertThatThrownBy(() -> storage.validateImage(
                new MockMultipartFile("file", "bomb.png", "image/png", pngHeaderOnly(30_000, 100))))
            .isInstanceOf(BadRequestException.class);
    }
}
