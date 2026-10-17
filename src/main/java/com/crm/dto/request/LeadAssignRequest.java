package com.crm.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class LeadAssignRequest {
    private Long userId;

    /**
     * Ichki: ommaviy biriktirish ({@code LeadBulkAssignService}) har lid uchun bildirishnoma yubormaydi — oxirida
     * bitta yig'ma "N ta lid biriktirildi". JSON'dan o'qilmaydi.
     */
    @JsonIgnore
    private boolean suppressNotification;
}
