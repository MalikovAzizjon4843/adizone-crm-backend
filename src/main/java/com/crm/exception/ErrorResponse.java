package com.crm.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    /** Mashina o'qiydigan xato kodi (CodedException); boshqa xatolarda yo'q. */
    private String code;
    private Map<String, String> validationErrors;
    /** CodedException tafsiloti (masalan {@code payroll.netChanged} da yangi {@code netSalary}). */
    private Map<String, Object> data;
}
