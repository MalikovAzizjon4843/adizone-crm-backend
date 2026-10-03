package com.crm.miniapp;

import com.crm.entity.AppIdentity;

/**
 * {@code /api/app/**} so'rovining egasi — tekshirilgan app JWT dan (docs/design/telegram-platform.md §3.3).
 * Xodim {@code CrmUserDetails} bilan aralashmaydi: {@code ROLE_APP} faqat app zanjirida beriladi.
 */
public record AppPrincipal(Long identityId, AppIdentity.Kind kind) {
}
