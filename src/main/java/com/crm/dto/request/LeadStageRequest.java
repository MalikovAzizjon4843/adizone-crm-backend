package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Bosqich yaratish va tahrirlash uchun yagona shakl.
 *
 * <p>{@code code} ataylab YO'Q: yaratishda {@code nameUz} dan avtomatik
 * hosil qilinadi, tahrirlashda esa umuman o'zgarmaydi — bazadagi lidlar
 * o'sha matnni saqlaydi.
 *
 * <p>{@code kind} ham ataylab yo'q: yangi bosqich doim OPEN bo'ladi va
 * mavjud bosqichning turi o'zgartirilmaydi. Aks holda ikkita CONVERTED
 * bosqich paydo bo'lib, konvert oqimi qaysi biriga tushishini bilmay
 * qolardi.
 */
@Data
public class LeadStageRequest {

    @NotBlank(message = "{leadStage.nameUz.required}")
    private String nameUz;

    @NotBlank(message = "{leadStage.nameRu.required}")
    private String nameRu;

    @NotBlank(message = "{leadStage.nameEn.required}")
    private String nameEn;

    /** secondary | info | warning | success | danger */
    @NotBlank(message = "{leadStage.color.required}")
    private String color;

    /** Berilmasa yangi bosqich ro'yxat oxiriga qo'yiladi. */
    private Integer sortOrder;

    private Boolean isActive;
}
