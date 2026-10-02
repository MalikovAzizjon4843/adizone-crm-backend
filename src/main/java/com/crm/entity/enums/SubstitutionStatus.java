package com.crm.entity.enums;

/**
 * "Darsni X o'tdi" belgisi holati (leaves-exams-contracts §2): {@code PLANNED → CONDUCTED} (shu dars
 * davomati birinchi marta saqlanganda), {@code PLANNED/CONDUCTED → CANCELLED} (SA/A).
 */
public enum SubstitutionStatus {
    PLANNED,
    CONDUCTED,
    CANCELLED
}
