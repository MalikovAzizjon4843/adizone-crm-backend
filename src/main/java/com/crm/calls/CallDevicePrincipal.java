package com.crm.calls;

import java.security.Principal;

/**
 * Qurilma tokeni bilan kirgan ilova. {@link #getName()} — xodim logini: mavjud servislar ({@code LeadAccessService},
 * {@code TaskService}) joriy foydalanuvchini {@code Authentication.getName()} orqali topadi va shu xodim nomidan,
 * uning roli bo'yicha cheklovlar bilan ishlaydi.
 */
public record CallDevicePrincipal(Long deviceId, Long userId, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
