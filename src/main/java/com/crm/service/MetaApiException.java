package com.crm.service;

/**
 * Graph API qaytargan xato yoki unga bora olmaslik.
 *
 * <p>{@link #code} — Meta ning {@code error.code} qiymati. Kod bo'yicha
 * qaror qabul qilinadigan yagona holat: <b>190</b> (token yaroqsiz) —
 * unda sahifa tokeni keshi tozalanadi va keyingi siklda qaytadan olinadi.
 */
public class MetaApiException extends RuntimeException {

    /** Token yaroqsiz / muddati tugagan / huquq olib tashlangan. */
    public static final int CODE_INVALID_TOKEN = 190;

    private final int code;
    private final int httpStatus;

    public MetaApiException(String message, int code, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public MetaApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
        this.httpStatus = 0;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isInvalidToken() {
        return code == CODE_INVALID_TOKEN;
    }
}
