package com.crm.entity.enums;

public enum LeadStatus {
    NEW,
    DAY_1_WORKED,
    DAY_2_WORKED,
    DAY_3_WORKED,
    DAY_4_WORKED,
    ONLINE_ENROLLED,
    OFFLINE_ENROLLED,
    ONLINE_PAID,
    OFFLINE_PAID,
    CONVERTED,
    REJECTED;

    public static LeadStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return NEW;
        }
        String normalized = value.trim().toUpperCase();
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return fromLegacy(normalized);
        }
    }

    public static LeadStatus fromLegacy(String legacyStatus) {
        if (legacyStatus == null || legacyStatus.isBlank()) {
            return NEW;
        }
        return switch (legacyStatus.trim().toUpperCase()) {
            case "CONTACTED" -> DAY_1_WORKED;
            case "IN_PROGRESS" -> DAY_2_WORKED;
            case "INTERESTED" -> DAY_3_WORKED;
            case "ENROLLED" -> CONVERTED;
            default -> NEW;
        };
    }
}
