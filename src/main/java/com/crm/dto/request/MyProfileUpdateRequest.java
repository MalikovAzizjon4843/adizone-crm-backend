package com.crm.dto.request;

import com.crm.config.PhoneDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code PUT /api/users/me} — qisman: faqat yuborilgan maydon o'zgaradi. Login va rol bu yerda
 * YO'Q — o'zgartirilmaydi. Ism/familiya yuborilsa bo'sh bo'lmasin (ustun NOT NULL).
 */
@Data
public class MyProfileUpdateRequest {

    @Size(max = 100, message = "{user.firstName.size}")
    private String firstName;

    @Size(max = 100, message = "{user.lastName.size}")
    private String lastName;

    /** Bo'sh satr — telefonni olib tashlash. */
    @Pattern(
        regexp = "^$|^\\+998\\d{9}$|^\\d{9}$",
        message = "{user.phone.pattern}")
    @JsonDeserialize(using = PhoneDeserializer.class)
    private String phone;
}
