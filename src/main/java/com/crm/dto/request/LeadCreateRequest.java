package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Xodim tomonidan lid yaratish ({@code POST /api/leads}).
 *
 * <p>{@link LeadRequest} dan ataylab MEROS OLMAYDI va uni kengaytirmaydi:
 * o'sha DTO {@code /api/leads/public} da, ya'ni autentifikatsiyasiz
 * endpointda ishlatiladi. {@code status} yoki {@code assignedUserId} ni
 * o'sha shaklga qo'shish anonim chaqiruvchiga bosqich tanlash va operator
 * biriktirish imkonini berardi. Ikki kontrakt — ikki sinf.
 */
@Data
public class LeadCreateRequest {

    @NotBlank(message = "{lead.fullName.required}")
    private String fullName;

    @NotBlank(message = "{lead.phone.required}")
    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;

    @JsonDeserialize(using = PhoneDeserializer.class)
    private String parentPhone;

    private String address;
    private String course;
    private String format;
    private String source;
    private String notes;

    /** Berilmasa NEW. */
    private String status;

    /**
     * Operator. Berilmasa: SALES_MANAGER o'ziga oladi, ADMIN/SUPER_ADMIN da
     * null qoladi (lid "Biriktirilmagan" ustuniga tushadi).
     */
    private Long assignedUserId;
}
