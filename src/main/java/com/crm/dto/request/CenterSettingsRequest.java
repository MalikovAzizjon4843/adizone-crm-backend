package com.crm.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PUT /api/settings/center} — qisman yangilash (leaves-exams-contracts §5.2): {@code null} —
 * o'zgarmaydi, {@code ""} — tozalanadi. Noma'lum maydon jimgina tashlanmaydi — 400.
 */
@Data
public class CenterSettingsRequest {

    private String legalName;
    private String legalNameRu;
    private String shortName;
    /** STIR — 9 raqam. */
    private String inn;
    private String address;
    private String phone;
    private String bankName;
    /** Hisob raqami (h/r) — 20 raqam. */
    private String bankAccount;
    /** MFO — 5 raqam. */
    private String bankMfo;
    private String directorName;
    private String contractCity;
    private String licenseInfo;
    /** Telegram Mini App dagi yordam telefoni. */
    private String supportPhone;

    @JsonIgnore
    private final List<String> unknownFields = new ArrayList<>();

    @JsonAnySetter
    public void unknownField(String name, Object value) {
        unknownFields.add(name);
    }

    /** Berilgan (null bo'lmagan) maydonlar: nom → qiymat. */
    public Map<String, String> provided() {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "legalName", legalName);
        put(out, "legalNameRu", legalNameRu);
        put(out, "shortName", shortName);
        put(out, "inn", inn);
        put(out, "address", address);
        put(out, "phone", phone);
        put(out, "bankName", bankName);
        put(out, "bankAccount", bankAccount);
        put(out, "bankMfo", bankMfo);
        put(out, "directorName", directorName);
        put(out, "contractCity", contractCity);
        put(out, "licenseInfo", licenseInfo);
        put(out, "supportPhone", supportPhone);
        return out;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
