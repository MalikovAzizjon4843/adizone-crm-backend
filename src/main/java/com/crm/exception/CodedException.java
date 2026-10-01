package com.crm.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Xato kodi + HTTP status. Matn {@code messages*.properties} dan
 * {@code GlobalExceptionHandler} da tarjima qilinadi, kod esa javobda
 * {@code code} maydonida qaytadi — frontend matnga emas, kodga tayanadi
 * (masalan {@code payment.plan.changed}, {@code concurrency.busy}).
 */
@Getter
public class CodedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public CodedException(HttpStatus status, String code, Object... args) {
        super(code);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public static CodedException badRequest(String code, Object... args) {
        return new CodedException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static CodedException notFound(String code, Object... args) {
        return new CodedException(HttpStatus.NOT_FOUND, code, args);
    }

    public static CodedException forbidden(String code, Object... args) {
        return new CodedException(HttpStatus.FORBIDDEN, code, args);
    }
}
