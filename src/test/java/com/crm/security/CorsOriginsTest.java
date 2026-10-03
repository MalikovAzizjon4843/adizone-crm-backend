package com.crm.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS: eski panel (admin.adizone.uz), yangi panel pilot domeni (crm.adizone.uz, docs/ops/deploy-v2.md §5)
 * va Vercel preview — ruxsat; begona origin — rad.
 */
class CorsOriginsTest extends Phase5ItBase {

    private void allowed(String origin) throws Exception {
        mvc.perform(options("/api/leads")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflight_allowsOldAndNewAdminPanels() throws Exception {
        allowed("https://crm.adizone.uz");
        allowed("https://admin.adizone.uz");
        allowed("https://adizone-admin-git-main.vercel.app");
    }

    /** Telegram Mini App (docs/design/miniapp-api.md): admin API ham, app zanjiri ham. */
    @Test
    void preflight_allowsMiniAppOrigin_onBothChains() throws Exception {
        allowed("https://webapp.adizone.uz");
        mvc.perform(options("/api/app/auth")
                .header(HttpHeaders.ORIGIN, "https://webapp.adizone.uz")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://webapp.adizone.uz"));
    }

    @Test
    void preflight_rejectsForeignOrigin() throws Exception {
        mvc.perform(options("/api/leads")
                .header(HttpHeaders.ORIGIN, "https://crm.adizone.uz.evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
