package com.crm.dto.response;

import java.util.List;

/**
 * Ommaviy biriktirish natijasi: {@code requested} — takrorsiz lidlar soni, {@code assigned} — biriktirilgan,
 * {@code skipped} — allaqachon shu xodimda bo'lgani (tegilmagan), {@code failed} — yiqilganlari sababi bilan.
 */
public record LeadBulkAssignResponse(int requested, int assigned, int skipped, List<Failed> failed) {

    /** {@code reason} — xato kodi ({@code lead.notFound}, servis kodi) yoki xabar. */
    public record Failed(Long leadId, String reason) {
    }
}
