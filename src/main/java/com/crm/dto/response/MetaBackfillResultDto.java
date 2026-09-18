package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** {@code POST /api/meta/forms/{formId}/backfill} natijasi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaBackfillResultDto {

    private String formId;

    private String formName;

    /** true bo'lsa hech narsa saqlanmagan. */
    private boolean dryRun;

    /** {@code ?since=} filtri, berilmagan bo'lsa null. */
    private String since;

    /** Graph dan o'qilgan lidlar soni (sana filtridan o'tganlari). */
    private int total;

    /** Yangi yaratilgan (dryRun da — yaratilishi kerak bo'lgan) lidlar. */
    private int created;

    /** Allaqachon mavjud yoki yaqinda kelgan takroriy murojaat. */
    private int duplicates;

    /** Forma turi tufayli o'tkazib yuborilganlar (IGNORE / HR). */
    private int skipped;

    private int errors;

    /** Sana filtridan o'tmagan (juda eski) lidlar. */
    private int outOfRange;

    /** Graph dan nechta sahifa o'qildi — progressni tekshirish uchun. */
    private int pages;

    /** Birinchi 20 ta xato — qolganlari log da. */
    private List<String> errorMessages;
}
