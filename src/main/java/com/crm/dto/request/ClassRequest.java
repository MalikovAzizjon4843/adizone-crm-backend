package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClassRequest {
    @NotBlank(message = "Class name is required")
    private String className;
    private String classCode;
}
