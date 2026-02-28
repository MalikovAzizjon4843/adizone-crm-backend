package com.crm.dto.response;

import com.crm.entity.enums.StageKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadStageResponse {
    private Long id;
    private UUID uuid;
    /** O'zgarmas texnik kalit — lidlar shu matnni saqlaydi. */
    private String code;
    private String nameUz;
    private String nameRu;
    private String nameEn;
    private String color;
    private Integer sortOrder;
    private StageKind kind;
    /** Shu bosqichga o'tishda summa majburiymi. */
    private Boolean requiresAmount;
    private Boolean isActive;
    /** CONVERTED va REJECTED o'chirilmaydi — frontend tugmani shunga qarab yashiradi. */
    private boolean deletable;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
