package com.crm.dto.response;

import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashCategoryDto {
    private String code;
    private CashDirection direction;
    private CashCategoryScope scope;
    private String name;
    private boolean active;
    private int sortOrder;
}
