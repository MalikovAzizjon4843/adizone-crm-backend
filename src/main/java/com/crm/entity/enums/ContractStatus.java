package com.crm.entity.enums;

/**
 * Shartnoma holati (leaves-exams-contracts §6.2): {@code DRAFT → SIGNED} (OFFLINE) yoki
 * {@code DRAFT → ACCEPTED} (OFFER); {@code DRAFT/SIGNED/ACCEPTED → CANCELLED} (sabab bilan).
 */
public enum ContractStatus {
    DRAFT,
    SIGNED,
    ACCEPTED,
    CANCELLED
}
