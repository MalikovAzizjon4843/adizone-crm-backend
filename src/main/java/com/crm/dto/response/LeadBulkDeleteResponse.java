package com.crm.dto.response;

import java.util.List;

/**
 * {@code POST /api/leads/bulk-delete} javobi: {@code requested} — noyob id lar soni, {@code deleted} — o'chirilganlar,
 * {@code skipped} — o'chirilmaganlar sabab (xato kodi) bilan: {@code lead.delete.notFound}, {@code lead.delete.converted},
 * {@code lead.delete.hasPayment}, {@code lead.delete.blocked}.
 */
public record LeadBulkDeleteResponse(int requested, int deletedCount, List<Long> deleted, List<Skipped> skipped) {

    public record Skipped(Long leadId, String reason) {
    }
}
