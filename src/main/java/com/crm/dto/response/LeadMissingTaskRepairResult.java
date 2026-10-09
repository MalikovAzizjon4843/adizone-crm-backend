package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code POST /api/admin/repair/leads-missing-tasks} javobi. {@code dryRun = true} da {@code created} va
 * {@code assignedToDefault} — "yaratilardi / biriktirilardi" (hech narsa yozilmagan).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadMissingTaskRepairResult {
    private boolean dryRun;
    /** Yaratilgan (yaratiladigan) vazifalar muddati — bugun 18:00 yoki ertaga 10:00 (Asia/Tashkent). */
    private LocalDateTime dueAt;
    /** {@code requires_task} bosqichdagi, ochiq vazifasi yo'q lidlar. */
    private long total;
    private long created;
    /** Shulardan mas'ulsiz bo'lib, standart mas'ulga biriktirilgan (biriktiriladigan) lidlar. */
    private long assignedToDefault;
    /** Mas'ul ham, standart mas'ul ham yo'q — o'tkazib yuborilgan. */
    private long skippedNoAssignee;
    /** Tekshiruv bilan tuzatish orasida vazifa paydo bo'lgan yoki bosqich o'zgargan lidlar. */
    private long skippedAlreadyOk;
    /** Xato bilan to'xtagan lidlar (har lid alohida tranzaksiyada — qolganlariga ta'sir qilmaydi). */
    private long failed;
    private List<StageRow> byStage;
    /** {@code "lead #id: xabar"} — ko'pi bilan 50 ta. */
    private List<String> errors;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageRow {
        private String status;
        private String statusLabel;
        private long total;
        private long created;
        private long assignedToDefault;
        private long skippedNoAssignee;
    }
}
