package com.crm.audit;

/** Audit amallarining nomlari — enum emas, chunki ustun VARCHAR va kengaytiriladigan. */
public final class AuditAction {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String EXPORT = "EXPORT";
    public static final String IMPORT = "IMPORT";
    public static final String PAYMENT = "PAYMENT";
    public static final String REPAIR = "REPAIR";

    /** Lid bosqichi o'zgardi — kim, qaysi bosqichdan qaysi bosqichga. */
    public static final String STATUS_CHANGE = "STATUS_CHANGE";

    /** Lid operatorga biriktirildi yoki vazifa mas'uli o'zgardi. */
    public static final String ASSIGN = "ASSIGN";

    /** Lidga izoh yozildi. */
    public static final String COMMENT = "COMMENT";

    /** Vazifa natija bilan yopildi. */
    public static final String TASK_DONE = "TASK_DONE";

    private AuditAction() {
    }
}
