package com.crm.util;

import com.crm.billing.MigrationPlanner;

import com.crm.entity.Payment;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Migratsiya hisobotlari xlsx ko'rinishida (§9.4 dry-run, §9.7 payments-since). */
public final class MigrationXlsx {

    private MigrationXlsx() {
    }

    public static byte[] dryRun(MigrationPlanner.Report report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = boldStyle(wb);
            Sheet rows = wb.createSheet("SG");
            header(rows, bold, "SG", "O'quvchi", "Guruh", "Toifa", "Tur", "paymentStartDate", "R", "Narx",
                "Davrlar (CHARGED)", "Davrlar (MIGRATED)", "L (ledger)", "sg.balance", "legacyPC", "repairAdj",
                "MIGRATION", "Charges", "Target", "Target − L", "Eski holat", "Eski next", "Eski qarz",
                "Yangi holat", "Yangi qarz", "debtSince", "Yangi next", "Next summa", "Anomaliyalar", "Bloklovchi");
            int i = 1;
            for (MigrationPlanner.SgPlan r : report.rows()) {
                String charged = r.periods().stream()
                    .filter(p -> p.status() == com.crm.entity.enums.BillingPeriodStatus.CHARGED)
                    .map(p -> p.start() + " " + p.amount().toPlainString()).collect(Collectors.joining(", "));
                long migrated = r.periods().stream()
                    .filter(p -> p.status() == com.crm.entity.enums.BillingPeriodStatus.MIGRATED).count();
                row(rows, i++, r.studentGroupId(), r.studentName(), r.groupName(), r.category(), r.paymentType(),
                    r.paymentStartDate(), r.r(), r.fee(), charged, migrated, r.ledgerSum(), r.storedBalance(),
                    r.legacyPeriodCharges(), r.repairAdjustments(), r.migrationAmount(), r.charges(), r.target(),
                    r.delta(), r.oldStatus(), r.oldNextPaymentDate(), r.oldDebt(), r.newStatus(), r.newDebt(),
                    r.newDebtSince(), r.newNextPaymentDate(), r.newNextPaymentAmount(),
                    String.join(",", r.anomalies()), r.blocking() ? "HA" : "");
            }

            Sheet sum = wb.createSheet("Jami");
            MigrationPlanner.Summary s = report.summary();
            int k = 0;
            k = kv(sum, bold, k, "Cutover (T)", report.cutover());
            k = kv(sum, bold, k, "Go-live (G)", report.goLive());
            k = kv(sum, bold, k, "A14: eski payable", report.a14UsePayable());
            k = kv(sum, bold, k, "max(balance_transactions.id)", report.maxTxId());
            k = kv(sum, bold, k, "max(payments.id)", report.maxPaymentId());
            k = kv(sum, bold, k, "Hisobot hash", report.reportHash());
            k = kv(sum, bold, k, "SG jami", s.sgTotal());
            k = kv(sum, bold, k, "Toifalar", s.byCategory());
            k = kv(sum, bold, k, "Anomaliyalar", s.anomalies());
            k = kv(sum, bold, k, "Bloklovchi (apply chetlatadi)", s.blocking());
            k = kv(sum, bold, k, "Allaqachon migratsiya qilingan", s.alreadyMigrated());
            k = kv(sum, bold, k, "Qarzdorlar (eski / yangi)", s.debtorsOld() + " / " + s.debtorsNew());
            k = kv(sum, bold, k, "Σ qarz (eski / yangi)", s.debtOld().toPlainString() + " / " + s.debtNew().toPlainString());
            k = kv(sum, bold, k, "Σ balans (eski / yangi)", s.balanceOld().toPlainString() + " / " + s.balanceNew().toPlainString());
            k = kv(sum, bold, k, "Davrlar CHARGED / MIGRATED", s.periodsCharged() + " / " + s.periodsMigrated());
            k = kv(sum, bold, k, "Yoziladigan PERIOD_CHARGE / MIGRATION", s.chargeEntries() + " / " + s.migrationEntries());
            k++;
            Row h = sum.createRow(k++);
            cell(h, 0, "Holat o'tishi", bold);
            cell(h, 1, "Soni", bold);
            for (Map.Entry<String, Integer> e : s.transitions().entrySet()) {
                Row row = sum.createRow(k++);
                cell(row, 0, e.getKey(), null);
                cell(row, 1, e.getValue(), null);
            }
            for (int c = 0; c < 28; c++) {
                rows.autoSizeColumn(c);
            }
            sum.autoSizeColumn(0);
            sum.autoSizeColumn(1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] payments(List<Payment> payments) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = boldStyle(wb);
            Sheet sheet = wb.createSheet("To'lovlar");
            header(sheet, bold, "ID", "Chek", "O'quvchi ID", "O'quvchi", "Guruh", "Sana", "Summa", "Chegirma",
                "Bonus", "Kassaga", "Usul", "Kassa", "Holat", "Bekor qilingan", "Bekor sababi", "Kiritilgan");
            int i = 1;
            for (Payment p : payments) {
                row(sheet, i++, p.getId(), p.getReceiptNumber(),
                    p.getStudent() != null ? p.getStudent().getId() : null,
                    p.getStudent() != null ? p.getStudent().getFirstName() + " " + p.getStudent().getLastName() : null,
                    p.getGroup() != null ? p.getGroup().getGroupName() : null,
                    p.getPaymentDate(), p.getAmount(), p.getDiscountAmount(), p.getBonusDiscount(), p.getCashAmount(),
                    p.getPaymentMethod(), p.getCashRegister() != null ? p.getCashRegister().getName() : null,
                    p.getStatus(), p.getCancelledAt(), p.getCancelReason(), p.getCreatedAt());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle boldStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void header(Sheet sheet, CellStyle bold, String... titles) {
        Row r = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            cell(r, i, titles[i], bold);
        }
    }

    private static void row(Sheet sheet, int index, Object... values) {
        Row r = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            cell(r, i, values[i], null);
        }
    }

    private static int kv(Sheet sheet, CellStyle bold, int index, String key, Object value) {
        Row r = sheet.createRow(index);
        cell(r, 0, key, bold);
        cell(r, 1, value, null);
        return index + 1;
    }

    private static void cell(Row r, int i, Object value, CellStyle style) {
        Cell c = r.createCell(i);
        if (value instanceof BigDecimal b) {
            c.setCellValue(b.doubleValue());
        } else if (value instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (value != null) {
            c.setCellValue(String.valueOf(value));
        }
        if (style != null) {
            c.setCellStyle(style);
        }
    }
}
