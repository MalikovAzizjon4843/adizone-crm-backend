package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadCommentRequest {

    @NotBlank(message = "{leadComment.text.required}")
    private String text;
}
