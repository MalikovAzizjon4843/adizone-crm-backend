package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Import tahlili — hech narsa saqlanmaydi.
 *
 * <p>{@code importId} ikkinchi qadamda ({@code /execute}) ishlatiladi:
 * fayl vaqtincha saqlanadi va qayta yuklanmaydi.
 *
 * <p>{@code sourceStages} va {@code operators} — foydalanuvchi shu
 * ro'yxatlar asosida moslashtirish jadvalini to'ldiradi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadImportPreviewResponse {

    private String importId;
    private String fileName;
    /** Fayl shu vaqtdan keyin o'chiriladi. */
    private LocalDateTime expiresAt;

    private int totalRows;
    private int validPhones;
    private int invalidPhones;
    /** Fayl ichidagi takrorlanuvchi telefonlar (birinchisi hisobga olinmaydi). */
    private int duplicatesInFile;
    /** Bazada allaqachon mavjud telefonlar. */
    private int duplicatesInDb;

    private List<NameCount> sourceStages;
    private List<NameCount> operators;

    /**
     * Import uchun YOPIQ bosqich kodlari — {@code kind = CONVERTED}
     * bo'lgan faol bosqichlar.
     *
     * <p>Import lidni to'g'ridan-to'g'ri yozadi va {@code updateStatus}
     * dagi taqiqdan o'tmaydi, shuning uchun bunday xaritalash lidni
     * yetim holatda ({@code student_id} bo'sh, lekin konvert ustunida)
     * qoldirardi. Frontend shu kodlarni moslashtirish ro'yxatidan
     * chiqarib tashlaydi; {@code /execute} esa baribir rad etadi.
     */
    private List<String> blockedStages;

    /** Birinchi 5 qator — o'girilgan holda, tekshirib ko'rish uchun. */
    private List<SampleRow> sampleRows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NameCount {
        private String name;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SampleRow {
        private int row;
        private String fullName;
        private String phone;
        private boolean phoneValid;
        private String stage;
        private String operator;
        private String source;
        private String format;
        private LocalDateTime createdAt;
        private String notes;
        private int noteCount;
    }
}
