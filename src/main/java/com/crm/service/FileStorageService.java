package com.crm.service;

import com.crm.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FileStorageService {

    /** Bitta fayl hajmi chegarasi — multipart sozlamasi (4MB) bilan bir xil. */
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Rasm o'lchami chegaralari. 4MB lik siqilgan PNG ichida 20000×20000 piksel
     * bo'lishi mumkin — to'liq dekodlansa GB lab xotira (phase5-audit CH-02).
     * Telefon kamerasi (≤ 50 MP, tomoni ≤ 12000) sig'adi.
     */
    public static final int MAX_IMAGE_SIDE = 12_000;
    public static final long MAX_IMAGE_PIXELS = 50_000_000L;

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

    /**
     * Yuklashga ruxsat etilgan fayllar: kengaytma → shu kengaytma uchun qabul
     * qilinadigan MIME turlari (birinchisi — faylni qaytarishdagi Content-Type).
     *
     * <p>Kengaytma HAM, MIME HAM shu ro'yxatdan bo'lishi va bir-biriga mos
     * kelishi shart. SVG va HTML ataylab yo'q: {@code /api/files/**} ochiq
     * va API domenida beriladi, ular esa brauzerda skript bajaradi.
     * Ovoz: brauzer diktofoni webm (Chrome), ogg (Firefox), m4a (Safari).
     */
    private static final Map<String, List<String>> ALLOWED = Map.ofEntries(
        Map.entry("jpg", List.of("image/jpeg", "image/pjpeg")),
        Map.entry("jpeg", List.of("image/jpeg", "image/pjpeg")),
        Map.entry("png", List.of("image/png")),
        Map.entry("webp", List.of("image/webp")),
        Map.entry("gif", List.of("image/gif")),
        Map.entry("pdf", List.of("application/pdf")),
        Map.entry("doc", List.of("application/msword")),
        Map.entry("docx", List.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
        Map.entry("xls", List.of("application/vnd.ms-excel")),
        Map.entry("xlsx", List.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
        Map.entry("webm", List.of("audio/webm")),
        Map.entry("ogg", List.of("audio/ogg", "application/ogg")),
        Map.entry("mp3", List.of("audio/mpeg", "audio/mp3")),
        Map.entry("m4a", List.of("audio/mp4", "audio/x-m4a", "audio/m4a"))
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * Umumiy yuklashda ({@code POST /api/files/upload}) rasmdan tashqari qabul qilinadigan hujjatlar —
     * uy vazifasi va shartnoma fayllari (phase6-api §4). Mazmun sarlavhasi ham tekshiriladi
     * ({@link #hasDocumentSignature}): kengaytma va MIME ni mijoz yozadi.
     */
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "doc", "docx", "xlsx");

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    /** DOCX/XLSX — ZIP konteyner. */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    /** DOC — OLE2 (Compound File). */
    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /**
     * Fayl nomidagi kengaytma (nuqtasiz, kichik harfda) yoki {@code null}.
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** "image/png; charset=binary" → "image/png". */
    public static String normalizeContentType(String raw) {
        if (raw == null) {
            return null;
        }
        int separator = raw.indexOf(';');
        String value = separator >= 0 ? raw.substring(0, separator) : raw;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** Kengaytma va MIME ikkalasi oq ro'yxatda va bir-biriga mos. */
    public static boolean isAllowed(String extension, String contentType) {
        List<String> types = extension != null ? ALLOWED.get(extension) : null;
        return types != null && types.contains(normalizeContentType(contentType));
    }

    /**
     * Saqlangan faylni qaytarishdagi Content-Type — kengaytmadan, mijoz
     * yuborgan yoki OS taxmin qilgan turdan emas. Ro'yxatda yo'q (eski)
     * fayllar uchun {@code null}.
     */
    public static String contentTypeFor(String fileName) {
        String extension = extensionOf(fileName);
        List<String> types = extension != null ? ALLOWED.get(extension) : null;
        return types != null ? types.get(0) : null;
    }

    /**
     * Rasm, PDF va audio brauzerda ochiladi ({@code inline}), qolgani —
     * yuklab olinadi ({@code attachment}).
     */
    public static boolean isInlineType(String contentType) {
        return contentType != null
            && (contentType.startsWith("image/")
                || contentType.startsWith("audio/")
                || contentType.equals("application/pdf"));
    }

    /**
     * Rasm yuklash tekshiruvi. Qaytaradi — saqlash uchun kengaytma (nuqtasiz).
     */
    public String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fayl bo'sh");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !IMAGE_EXTENSIONS.contains(extension)
                || !isAllowed(extension, file.getContentType())) {
            throw new BadRequestException(
                "Faqat rasm fayllari qabul qilinadi (jpg, jpeg, png, webp, gif)");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("Fayl hajmi 4MB dan oshmasligi kerak");
        }
        int[] size = readImageDimensions(file);
        if (size != null && !isImageSizeAllowed(size[0], size[1])) {
            throw new BadRequestException("Rasm o'lchami juda katta: " + size[0] + "×" + size[1]
                + " (ko'pi bilan " + MAX_IMAGE_SIDE + " piksel tomoni)");
        }
        return extension;
    }

    /**
     * Rasm eni va bo'yi — faqat SARLAVHADAN, piksellar dekodlanmaydi
     * ({@code ImageIO.read} butun rasmni xotiraga ochadi, bu esa "decompression
     * bomb" ga yo'l ochardi). Formatni o'qib bo'lmasa (masalan ImageIO
     * plaginisiz WebP) {@code null} — bu xato emas.
     */
    public static int[] readImageDimensions(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Tomon ham, piksellar soni ham chegarada. */
    public static boolean isImageSizeAllowed(int width, int height) {
        return width > 0 && height > 0
            && width <= MAX_IMAGE_SIDE && height <= MAX_IMAGE_SIDE
            && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    /**
     * Umumiy yuklash ({@code POST /api/files/upload}): rasm — {@link #validateImage} qoidalari bilan,
     * hujjat — pdf, doc, docx, xlsx (≤ 4 MB, kengaytma + MIME + mazmun sarlavhasi mos). Qaytaradi — kengaytma.
     */
    public String validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Fayl bo'sh");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension != null && IMAGE_EXTENSIONS.contains(extension)) {
            return validateImage(file);
        }
        if (extension == null || !DOCUMENT_EXTENSIONS.contains(extension)
                || !isAllowed(extension, file.getContentType())) {
            throw new BadRequestException(
                "Faqat rasm (jpg, jpeg, png, webp, gif) yoki hujjat (pdf, doc, docx, xlsx) qabul qilinadi");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("Fayl hajmi 4MB dan oshmasligi kerak");
        }
        if (!hasDocumentSignature(file, extension)) {
            throw new BadRequestException("Fayl mazmuni ." + extension + " formatiga mos emas");
        }
        return extension;
    }

    /** Birinchi baytlar kengaytmaga mos: pdf — {@code %PDF-}, docx/xlsx — ZIP, doc — OLE2. */
    static boolean hasDocumentSignature(MultipartFile file, String extension) {
        byte[] magic = switch (extension) {
            case "pdf" -> PDF_MAGIC;
            case "docx", "xlsx" -> ZIP_MAGIC;
            case "doc" -> OLE_MAGIC;
            default -> null;
        };
        if (magic == null) {
            return false;
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(magic.length);
            return java.util.Arrays.equals(head, magic);
        } catch (IOException e) {
            return false;
        }
    }

    /** {@link #validateUpload} + saqlash; nom — {@code filename} + haqiqiy kengaytma. */
    public String saveUpload(MultipartFile file, String filename) throws IOException {
        String extension = validateUpload(file);
        String baseName = filename.replaceFirst("\\.[^.]*$", "");
        return save(file, baseName + "." + extension);
    }

    /**
     * Saves under {@code uploadDir} using the given {@code filename} (caller controls uniqueness).
     * Kengaytma haqiqiy fayl turiga almashtiriladi ({@code x.jpg} → {@code x.png}),
     * shunda qaytarishdagi Content-Type to'g'ri bo'ladi.
     */
    public String saveImage(MultipartFile file, String filename) throws IOException {
        String extension = validateImage(file);
        String baseName = filename.replaceFirst("\\.[^.]*$", "");
        return save(file, baseName + "." + extension);
    }

    /**
     * Diskka yozishning o'zi — turini tekshirmaydi.
     *
     * <p>Chat biriktirmalari uchun kerak: u yerda rasmdan tashqari PDF,
     * hujjat va ovoz ham qabul qilinadi; chaqiruvchi avval {@link #isAllowed}
     * bilan tekshiradi. {@link #saveImage} shu metodga tayanadi, ya'ni
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

    /**
     * Faqat yuklash katalogining o'zidagi fayl (pastki katalog yo'q): masalan muzlatilgan shartnoma
     * PDF lari ({@code contracts/}) ochiq {@code GET /api/files/**} orqali berilmaydi (§6.3).
     */
    public Path resolveSafePath(String filename) throws IOException {
        if (filename == null || filename.contains("/") || filename.contains("\\")) {
            return null;
        }
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
