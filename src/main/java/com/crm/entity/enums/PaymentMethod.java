package com.crm.entity.enums;

/**
 * Loyihadagi YAGONA to'lov usuli ro'yxati.
 * Payment, CashTransaction va Payroll shu ro'yxatdan foydalanadi.
 */
public enum PaymentMethod {
    CASH("Naqd", "💵"),
    CARD("Karta", "💳"),
    CLICK("Click", "📱"),
    PAYME("Payme", "🔷"),
    UZUM("Uzum", "🟠"),
    TERMINAL("Terminal", "🖥️"),
    BANK("Bank o'tkazmasi", "🏦"),
    CASH_AND_CARD("Naqd + Karta", "💵💳"),
    OTHER("Boshqa", "❓");

    private final String label;
    private final String icon;

    PaymentMethod(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
