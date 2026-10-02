package com.crm.entity.enums;

/**
 * Kassa yozuvining pul yo'nalishi (frontend va eksport uchun hosila, bazada saqlanmaydi):
 * INCOME → IN, EXPENSE → OUT, TRANSFER → chiqim qatori OUT / kirim qatori IN,
 * REVERSAL → asl yozuvga teskari (bekor qilingan to'lov → OUT, bekor qilingan oylik/chiqim → IN).
 */
public enum CashDirection {
    IN,
    OUT;

    public CashDirection opposite() {
        return this == IN ? OUT : IN;
    }
}
