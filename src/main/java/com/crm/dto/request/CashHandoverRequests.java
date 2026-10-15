package com.crm.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/** Kunlik topshirish so'rovlari (docs/design/cash-and-books.md §1.2.3). */
public final class CashHandoverRequests {

    private CashHandoverRequests() {
    }

    /** {@code POST /api/cash-registers/{id}/handovers} — admin. */
    @Data
    public static class Submit {
        /** Admin sanab topshirayotgan naqd (razmen kassada qoladi). */
        private BigDecimal declaredCash;
        private String note;
        /** Preview'dagi {@code previewHash}: orada yangi yozuv tushgan bo'lsa — 409. */
        private String previewHash;
    }

    /** {@code POST /api/cash-handovers/{id}/accept} — SA. */
    @Data
    public static class Accept {
        /** Direktor sanagan naqd — shu summa Direktor kassasiga o'tadi. */
        private BigDecimal countedCash;
        /** Naqdsiz usullar bo'yicha haqiqiy summa (terminal Z-hisobot, Click/Payme kabineti): usul → summa. */
        private Map<String, BigDecimal> actuals;
        private String diffNote;
        /** Direktor kassasi; ko'rsatilmasa — yagona faol DIRECTOR kassa. */
        private Long targetCashRegisterId;
    }

    /** {@code POST /api/cash-handovers/{id}/reject} — SA. */
    @Data
    public static class Reject {
        private String reason;
    }
}
