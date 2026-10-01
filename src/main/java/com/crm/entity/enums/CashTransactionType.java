package com.crm.entity.enums;

public enum CashTransactionType {
    INCOME,
    EXPENSE,
    TRANSFER,
    /** Billing v2: bekor qilingan to'lov kirimining teskarisi (§6.4). */
    REVERSAL
}
