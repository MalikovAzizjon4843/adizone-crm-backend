package com.crm.dto.response;

import com.crm.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET/PUT /api/settings/leads/default-assignee}. Sozlanmagan bo'lsa barcha maydonlar null/false.
 * {@code valid = false} — sozlangan foydalanuvchi endi nofaol yoki roli mos emas: lidlarga biriktirilmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadDefaultAssigneeDto {
    private Long userId;
    private String fullName;
    private UserRole role;
    private boolean active;
    private boolean valid;
}
