package com.crm.billing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arxitektura qoidalari — docs/design/billing-v2.md §12.1. Manba matni
 * skanerlanadi (ArchUnit qo'shilmagan): qoida buzilsa fayl:qator bilan yiqiladi.
 */
class BillingArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/com/crm");
    private static final Path BILLING = MAIN.resolve("billing");

    /** I2: SG/o'quvchi balansini faqat LedgerService o'zgartiradi. Kassa va DTO balanslari boshqa narsa. */
    @Test
    void setBalance_onlyInLedgerService() throws IOException {
        Pattern call = Pattern.compile("(\\w+)\\s*\\.\\s*setBalance\\(");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            if (file.endsWith("LedgerService.java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = call.matcher(lines.get(i));
                while (m.find()) {
                    String receiver = m.group(1);
                    if (!receiver.equals("register") && !receiver.equals("dto")) {
                        violations.add(MAIN.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(violations).as("setBalance faqat LedgerService da").isEmpty();
    }

    /** I7: o'quvchi/yozilma holatini faqat BillingSnapshotService yozadi. */
    @Test
    void setPaymentStatus_onlyInSnapshotService() throws IOException {
        assertThat(grep(MAIN, "\\.setPaymentStatus\\(", "BillingSnapshotService.java"))
            .as("setPaymentStatus faqat BillingSnapshotService da").isEmpty();
    }

    /** §1.3: billing paketida yaxlitlash faqat Money da. */
    @Test
    void roundingMode_onlyInMoney() throws IOException {
        assertThat(grep(BILLING, "RoundingMode", "Money.java"))
            .as("RoundingMode billing paketida faqat Money da").isEmpty();
    }

    /** §1.3: billing kodida double taqiqlangan. */
    @Test
    void noDoubleInBilling() throws IOException {
        assertThat(grep(BILLING, "\\bdouble\\b|\\bDouble\\b|doubleValue\\(", null))
            .as("double billing paketida").isEmpty();
    }

    static List<String> grep(Path root, String regex, String exceptFile) throws IOException {
        Pattern p = Pattern.compile(regex);
        List<String> hits = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            if (exceptFile != null && file.endsWith(exceptFile)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
                if (code.trim().startsWith("*") || code.trim().startsWith("/*")) {
                    continue;
                }
                if (p.matcher(code).find()) {
                    hits.add(root.relativize(file) + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        return hits;
    }

    static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
