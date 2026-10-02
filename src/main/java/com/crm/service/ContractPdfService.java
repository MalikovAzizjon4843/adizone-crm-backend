package com.crm.service;

import com.crm.entity.Contract;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.CodedException;
import com.crm.repository.ParentRepository;
import com.crm.util.ContractHtml;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shartnoma PDF va chop etish ko'rinishi (leaves-exams-contracts §6.3).
 *
 * <ul>
 *   <li>Dvigatel — OpenHTMLtoPDF (PDFBox), kirish — XHTML ({@code templates/contract-layout.html} +
 *       {@link ContractHtml} bilan tozalangan shartnoma matni);</li>
 *   <li>shriftlar — faqat {@code fonts/} dagi Noto (subset bilan joylanadi): o'zbek lotini
 *       {@code ʻ ʼ}, kirill {@code ў қ ғ ҳ}, {@code № « » — –} qamraladi;</li>
 *   <li>DRAFT — har so'rovda qayta yaratiladi; SIGNED/ACCEPTED — imzo paytida bir marta yozilib
 *       ({@code app.contracts.pdf-dir}), keyin shu fayl beriladi (rekvizit o'zgarsa ham imzolangan
 *       nusxa o'zgarmaydi). Katalog {@code /api/files} dan tashqarida: fayl faqat endpoint orqali.</li>
 * </ul>
 */
@Service
@Slf4j
public class ContractPdfService {

    /** PDF + fayl nomi; {@code frozen} — diskdagi muzlatilgan nusxa. */
    public record Pdf(byte[] bytes, String fileName, boolean frozen) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String SANS = "Noto Sans";
    private static final String SERIF = "Noto Serif";
    private static final Set<ContractStatus> FROZEN = Set.of(ContractStatus.SIGNED, ContractStatus.ACCEPTED);

    private final CenterSettingsService centerSettingsService;
    private final ParentRepository parentRepository;
    private final Path pdfDir;
    private final String layout;

    public ContractPdfService(CenterSettingsService centerSettingsService,
                              ParentRepository parentRepository,
                              @Value("${app.contracts.pdf-dir:${app.upload.dir:/opt/crm/uploads}/contracts}") String pdfDir) {
        this.centerSettingsService = centerSettingsService;
        this.parentRepository = parentRepository;
        this.pdfDir = Path.of(pdfDir).toAbsolutePath().normalize();
        this.layout = readResource("templates/contract-layout.html");
    }

    /** SIGNED/ACCEPTED: muzlatilgan nusxa (yo'q bo'lsa — hozir muzlatiladi); boshqalar — yangi. */
    public Pdf pdf(Contract c) {
        String name = fileName(c);
        if (c.getPdfFile() != null) {
            Path file = resolve(c.getPdfFile());
            if (Files.isRegularFile(file)) {
                byte[] bytes = read(file);
                if (c.getPdfSha256() != null && !c.getPdfSha256().equals(sha256(bytes))) {
                    log.warn("Shartnoma PDF xeshi mos emas: contract={}, file={}", c.getId(), file);
                }
                return new Pdf(bytes, name, true);
            }
            log.warn("Muzlatilgan shartnoma PDF topilmadi, qayta yaratiladi: contract={}, file={}", c.getId(), file);
        }
        if (FROZEN.contains(c.getStatus())) {
            freeze(c);
            return new Pdf(read(resolve(c.getPdfFile())), name, true);
        }
        return new Pdf(render(c), name, false);
    }

    /** Imzo/qabul paytida: PDF yaratiladi, diskka yoziladi, {@code pdfFile} va xesh entity ga qo'yiladi. */
    public void freeze(Contract c) {
        byte[] bytes = render(c);
        String relative = c.getUuid() + ".pdf";
        Path target = resolve(relative);
        try {
            Files.createDirectories(pdfDir);
            Path tmp = Files.createTempFile(pdfDir, "contract-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw CodedException.badRequest("contract.pdf.failed", e.getMessage());
        }
        c.setPdfFile(relative);
        c.setPdfSha256(sha256(bytes));
    }

    public byte[] render(Contract c) {
        String html = html(c);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.withProducer("Adizone CRM");
            font(builder, "fonts/NotoSerif-Regular.ttf", SERIF, 400, BaseRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/NotoSerif-Bold.ttf", SERIF, 700, BaseRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/NotoSerif-Italic.ttf", SERIF, 400, BaseRendererBuilder.FontStyle.ITALIC);
            font(builder, "fonts/NotoSans-Regular.ttf", SANS, 400, BaseRendererBuilder.FontStyle.NORMAL);
            font(builder, "fonts/NotoSans-Bold.ttf", SANS, 700, BaseRendererBuilder.FontStyle.NORMAL);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.error("Shartnoma PDF yaratilmadi: contract={}", c.getId(), e);
            throw CodedException.badRequest("contract.pdf.failed", e.getMessage());
        }
    }

    /** Chop etish ko'rinishi va PDF manbasi — bir xil XHTML. */
    public String html(Contract c) {
        Map<String, String> center = centerSettingsService.values();
        Student s = c.getStudent();
        Parent parent = parentRepository.findByStudentId(s.getId()).stream().findFirst().orElse(null);

        Map<String, String> v = new LinkedHashMap<>();
        center.forEach((k, value) -> v.put(k, value.isBlank() ? ContractPlaceholders.BLANK : value));
        v.put("legalNameRu", center.get("legalNameRu"));
        v.put("licenseInfo", center.get("licenseInfo"));
        v.put("contractNumber", c.getContractNumber());
        v.put("contractDate", c.getContractDate() != null ? c.getContractDate().format(DATE) : "");
        v.put("typeLabel", c.getType() == ContractType.OFFER ? "(ommaviy oferta)" : "(o'quv xizmatlari ko'rsatish to'g'risida)");
        v.put("studentName", (nz(s.getFirstName()) + " " + nz(s.getLastName())).trim());
        v.put("studentPhone", nz(s.getPhone()));
        v.put("parentName", parent != null ? nz(parent.getFullName()) : "");
        v.put("parentPhone", parent != null ? nz(parent.getPhone()) : nz(s.getParentPhone()));

        String content = ContractHtml.sanitize(c.getRenderedContent());
        StringBuilder out = new StringBuilder(layout.length() + content.length() + 512);
        int pos = 0;
        while (true) {
            int start = layout.indexOf("@@", pos);
            if (start < 0) {
                break;
            }
            int end = layout.indexOf("@@", start + 2);
            if (end < 0) {
                break;
            }
            out.append(layout, pos, start);
            String key = layout.substring(start + 2, end);
            switch (key) {
                case "content" -> out.append(content);
                case "priceBlock" -> out.append(priceBlock(c));
                default -> out.append(ContractHtml.escape(v.getOrDefault(key, "")));
            }
            pos = end + 2;
        }
        out.append(layout, pos, layout.length());
        return out.toString();
    }

    /** Narx snapshot'i (D10): kurs narxi, chegirma, yakuniy summa — shablondan qat'i nazar. */
    private static String priceBlock(Contract c) {
        if (c.getFinalAmount() == null) {
            return "";
        }
        String unit = c.getPaymentType() == PaymentType.PER_LESSON ? " (bir dars)" : " (oyiga)";
        List<String[]> rows = List.of(
            new String[]{"Kurs narxi" + unit, ContractService.formatAmount(c.getListPrice()) + " so'm"},
            new String[]{"Chegirma", (c.getDiscountPercent() != null ? c.getDiscountPercent().stripTrailingZeros().toPlainString() : "0")
                + "% — " + ContractService.formatAmount(c.getDiscountAmount()) + " so'm"},
            new String[]{"Yakuniy summa" + unit, ContractService.formatAmount(c.getFinalAmount()) + " so'm"},
            new String[]{"To'lov boshlanishi", c.getStartDate() != null ? c.getStartDate().format(DATE) : ""});
        StringBuilder sb = new StringBuilder("<div class=\"price\"><table>");
        for (String[] r : rows) {
            sb.append("<tr><td>").append(ContractHtml.escape(r[0])).append("</td><td><b>")
                .append(ContractHtml.escape(r[1])).append("</b></td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    private static void font(PdfRendererBuilder builder, String path, String family, int weight,
                             BaseRendererBuilder.FontStyle style) {
        builder.useFont(() -> {
            try {
                return new ClassPathResource(path).getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, family, weight, style, true);
    }

    private Path resolve(String relative) {
        Path p = pdfDir.resolve(relative).normalize();
        if (!p.startsWith(pdfDir)) {
            throw new IllegalStateException("PDF yo'li katalogdan tashqarida: " + relative);
        }
        return p;
    }

    private static String fileName(Contract c) {
        return (c.getContractNumber() != null ? c.getContractNumber() : "contract-" + c.getId()) + ".pdf";
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Resurs topilmadi: " + path, e);
        }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
