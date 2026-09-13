package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LeadNoteRequest {

    @NotBlank(message = "{leadNote.text.required}")
    private String text;
}
