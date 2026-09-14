package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class LeadImportExecuteRequest {

    @NotBlank(message = "{leadImport.importId.required}")
    private String importId;

    /**
     * amoCRM bosqichi -&gt; {@code lead_stages.code}.
     *
     * <p>Qiymat {@code null} bo'lsa shu bosqichdagi qatorlar IMPORT
     * QILINMAYDI. Jadvalda umuman yo'q bosqich ham o'tkazib yuboriladi —
     * "moslashtirilmagan" va "ataylab tashlab ketilgan" bir xil natija
     * beradi, chunki ikkalasida ham qayerga qo'yishni bilmaymiz.
     */
    @NotNull(message = "{leadImport.stageMapping.required}")
    private Map<String, String> stageMapping;

    /** amoCRM mas'uli ismi -&gt; user id. {@code null} = biriktirilmagan. */
    private Map<String, Long> operatorMapping;

    /** true bo'lsa bazada shu telefon bor lidlar o'tkazib yuboriladi. */
    private Boolean skipDuplicates;

    /** Partiya belgisi — keyin ommaviy o'chirish uchun. */
    @NotBlank(message = "{leadImport.importTag.required}")
    private String importTag;
}
