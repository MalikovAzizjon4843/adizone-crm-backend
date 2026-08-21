package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ism-familiyadan taklif qilingan login va uning bo'shligi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsernamePreviewResponse {
    private String username;
    /** true — asosiy shakl bo'sh; false — band bo'lgani uchun raqam qo'shildi. */
    private boolean available;
}
