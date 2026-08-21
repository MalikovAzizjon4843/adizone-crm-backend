package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Foydalanuvchini faollashtirish/nofaol qilish. */
@Data
public class UserStatusRequest {
    @NotNull(message = "{userStatus.active.required}")
    private Boolean active;
}
