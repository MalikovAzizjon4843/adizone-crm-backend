package com.crm.exception;

import org.springframework.http.HttpStatus;

/** 409 — holat o'zgargan yoki parallel amal (qulf kutish chegarasi). */
public class ConflictException extends CodedException {

    public ConflictException(String code, Object... args) {
        super(HttpStatus.CONFLICT, code, args);
    }
}
