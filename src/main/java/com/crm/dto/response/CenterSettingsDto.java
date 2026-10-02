package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Markaz rekvizitlari ({@code center.*} sozlamalari) — leaves-exams-contracts §5. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CenterSettingsDto {
    private String legalName;
    private String legalNameRu;
    private String shortName;
    private String inn;
    private String address;
    private String phone;
    private String bankName;
    private String bankAccount;
    private String bankMfo;
    private String directorName;
    private String contractCity;
    private String licenseInfo;
    private String supportPhone;
    /** Shartnoma uchun majburiy, lekin bo'sh rekvizitlar (PDF da {@code ________}). */
    private List<String> missing;
}
