package com.crm.miniapp;

import com.crm.entity.AppIdentity;

/**
 * {@code /api/app/**} so'rovining egasi — tekshirilgan app JWT dan (docs/design/telegram-platform.md §3.3).
 * Xodim {@code CrmUserDetails} bilan aralashmaydi: {@code ROLE_APP} faqat app zanjirida beriladi.
 *
 * @param staffUserId o'qituvchi rejimi (§11.4) — faqat user hozir ham faol TEACHER bo'lsa ({@link AppJwtFilter});
 *                    aks holda null
 */
public record AppPrincipal(Long identityId, AppIdentity.Kind kind, Long staffUserId) {

    public AppPrincipal(Long identityId, AppIdentity.Kind kind) {
        this(identityId, kind, null);
    }

    public boolean isTeacher() {
        return staffUserId != null;
    }
}
