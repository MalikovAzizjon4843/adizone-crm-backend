package com.crm.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Chek raqami — DB sequence ({@code count() + 1} o'rniga), docs/design/billing-v2.md §5.3.
 *
 * <p>Format o'zgarmaydi: {@code RCP-00043}, 99 999 dan keyin 6 xona. Sequence
 * tranzaksiyadan tashqarida ishlaydi — rollback bo'lgan to'lov raqamni "yeydi",
 * bo'shliq bo'lishi me'yor. Parallel to'lovlarda takror raqam bo'lmaydi.
 */
@Service
@RequiredArgsConstructor
public class ReceiptNumberService {

    public static final String SEQUENCE = "payment_receipt_seq";

    private final JdbcTemplate jdbcTemplate;

    public String next() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('" + SEQUENCE + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Sequence qiymat qaytarmadi: " + SEQUENCE);
        }
        return format(value);
    }

    static String format(long value) {
        return "RCP-" + String.format("%05d", value);
    }
}
