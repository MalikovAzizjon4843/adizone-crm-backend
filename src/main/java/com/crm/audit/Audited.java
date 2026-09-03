package com.crm.audit;

import java.lang.annotation.*;

/**
 * Metod muvaffaqiyatli TUGAB, tranzaksiya COMMIT bo'lgandan keyin audit yozuvi yaratadi.
 *
 * <p>{@code summary}, {@code entityId}, {@code label} — SpEL ifodalari.
 * Kontekstda mavjud: metod argumentlari (nomi bo'yicha, masalan {@code #request},
 * shuningdek {@code #a0}/{@code #p0} indeks bo'yicha) va {@code #result} (qaytgan qiymat).
 *
 * <pre>
 * &#64;Audited(action = AuditAction.CREATE, entity = "Student",
 *          summary = "'Yangi o''quvchi qo''shildi: ' + #result.fullName",
 *          entityId = "#result.id",
 *          label = "#result.fullName")
 * </pre>
 *
 * <p>Aniq o'zgarishlar ({@code detailsJson}) uchun servis ichida
 * {@link AuditContext#change} yoki {@link AuditDiff} ishlatiladi.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Audited {

    /** @see AuditAction */
    String action();

    /** "Student", "Payment", "Group" ... */
    String entity();

    /** SpEL — o'zbekcha qisqa matn. */
    String summary() default "";

    /** SpEL — Long ga keltiriladi. */
    String entityId() default "";

    /** SpEL — o'qish uchun nom. */
    String label() default "";
}
