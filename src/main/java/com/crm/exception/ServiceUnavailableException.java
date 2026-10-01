package com.crm.exception;

import org.springframework.http.HttpStatus;

/** 503 — masalan billing texnik oynasi ({@code app.billing.enabled=false}). */
public class ServiceUnavailableException extends CodedException {

    public ServiceUnavailableException(String code, Object... args) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, args);
    }
}
