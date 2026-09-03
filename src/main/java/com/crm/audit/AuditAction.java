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

    private AuditAction() {
    }
}
