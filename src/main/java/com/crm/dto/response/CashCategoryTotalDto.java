package com.crm.dto.response;

import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** {@code GET /api/cash-registers/{id}/by-category}: kategoriya bo'yicha davr yig'indisi (o'z yo'nalishida). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashCategoryTotalDto {
    private String category;
    private String name;
    private CashDirection direction;
    private CashCategoryScope scope;
    /** Kirim − bekor qilingan (kirim kategoriyasi) yoki chiqim − bekor qilingan (chiqim kategoriyasi). */
    private BigDecimal amount;
}
