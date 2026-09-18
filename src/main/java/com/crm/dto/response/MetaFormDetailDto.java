package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Forma + uning savollari va mapping i. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFormDetailDto {

    private MetaFormDto form;

    private List<MetaQuestionDto> questions;
}
