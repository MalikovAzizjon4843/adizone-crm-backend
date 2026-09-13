package com.crm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskCompleteRequest {

    /**
     * Bajarilish natijasi — MAJBURIY. amoCRM'da ham vazifani natija
     * yozmasdan yopish mumkin emas: aks holda lenta "vazifa bajarildi"
     * degan bo'sh yozuvlar bilan to'lib ketadi.
     */
    @NotBlank(message = "{task.result.required}")
    private String result;

    /**
     * Darhol yaratiladigan keyingi vazifa — ixtiyoriy. Berilmasa faqat
     * joriy vazifa yopiladi (avvalgi xulq).
     *
     * <p>Zanjirning uzilishi aynan shu yerda sodir bo'ladi: operator
     * vazifani yopadi va yangisini rejalashtirmaydi, lid esa "vazifasiz"
     * ro'yxatiga tushib qoladi. amoCRM bajarish oynasidayoq keyingisini
     * so'raydi — shuning uchun ikkalasi bitta so'rovda.
     *
     * <p>{@code @Valid} kaskadi faqat obyekt berilganda ishlaydi, ya'ni
     * ichki {@code @NotNull} lar ixtiyoriylikni buzmaydi.
     */
    @Valid
    private NextTask nextTask;

    @Data
    public static class NextTask {

        /** CALL, MEETING, MESSAGE, OTHER. Berilmasa CALL. */
        private String type;

        /** Bo'sh bo'lsa tur nomi olinadi ("Qo'ng'iroq", "Uchrashuv", ...). */
        private String title;

        @NotNull(message = "{task.dueAt.required}")
        private LocalDateTime dueAt;

        /** true bo'lsa dueAt shu kunning 23:59 ga keltiriladi. */
        private Boolean allDay;
    }
}
