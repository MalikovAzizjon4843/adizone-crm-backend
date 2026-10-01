package com.crm.dto.request;

import lombok.Data;

@Data
public class FreezeStudentRequest {
    /** Billing v2 (§6.7): faqat tanlangan yozilma; bir nechta faol guruhda majburiy. */
    private Long groupId;
    /** Default — bugun; {@code ≤ bugun}, 30 kundan eski emas, oxirgi billable davomatdan oldin emas (§13 #2). */
    private java.time.LocalDate freezeDate;
    private String reason;
    private String note;
}
