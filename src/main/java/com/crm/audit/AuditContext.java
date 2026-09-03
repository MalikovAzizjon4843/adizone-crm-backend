package com.crm.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servis @Audited metod ichidan aniq o'zgarishlarni qo'shishi uchun thread-local buferi.
 *
 * <p>AOP umumiy holda "eski qiymat" ni bila olmaydi — buning uchun har bir entity
 * turini oldindan yuklab, maydonma-maydon solishtirish kerak bo'lardi. Buning
 * o'rniga servis o'zi biladigan farqni shu yerga yozadi, aspect esa uni
 * {@code detailsJson} ga o'tkazadi.
 *
 * <p>Aspect har doim {@link #clear()} chaqiradi, shuning uchun thread pool da
 * qiymat oldingi so'rovdan qolib ketmaydi.
 */
public final class AuditContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private AuditContext() {
    }

    private static final class Holder {
        final List<Map<String, Object>> changes = new ArrayList<>();
        Long entityId;
        String label;
        String summary;
        boolean skipped;
        Long userId;
        String username;
        String userRole;
    }

    private static Holder holder() {
        Holder h = HOLDER.get();
        if (h == null) {
            h = new Holder();
            HOLDER.set(h);
        }
        return h;
    }

    /**
     * Bitta maydon o'zgarishini qayd qiladi. Eski va yangi qiymat teng bo'lsa
     * hech narsa yozilmaydi; maxfiy maydon nomlari niqoblanadi.
     */
    public static void change(String field, Object oldValue, Object newValue) {
        if (field == null || AuditDiff.equalValues(oldValue, newValue)) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("field", field);
        row.put("old", AuditDiff.maskIfSecret(field, oldValue));
        row.put("new", AuditDiff.maskIfSecret(field, newValue));
        holder().changes.add(row);
    }

    /** SpEL orqali aniqlab bo'lmaydigan hollarda ID ni qo'lda berish. */
    public static void entityId(Long id) {
        holder().entityId = id;
    }

    /** SpEL orqali aniqlab bo'lmaydigan hollarda nomni qo'lda berish. */
    public static void label(String label) {
        holder().label = label;
    }

    /**
     * Amalni bajargan foydalanuvchini QO'LDA belgilash.
     *
     * <p>Odatda aspect uni SecurityContext dan oladi. Lekin login paytida
     * SecurityContext hali bo'sh bo'ladi — {@code AuthService.login} JWT filtridan
     * OLDIN ishlaydi va o'zi hech qachon kontekstni to'ldirmaydi. Shunday hollarda
     * servis kimligini o'zi biladi va shu metod orqali beradi.
     */
    public static void actor(Long userId, String username, String userRole) {
        Holder h = holder();
        h.userId = userId;
        h.username = username;
        h.userRole = userRole;
    }

    /** Annotatsiyadagi summary o'rniga dinamik matn (masalan import natijasi). */
    public static void summary(String summary) {
        holder().summary = summary;
    }

    /**
     * Shu amal uchun log YOZILMASIN. Amalning o'zi kuzatishga arzimasa
     * ishlatiladi — masalan ta'mirlash endpointi dryRun rejimida ishlaganda.
     */
    public static void skip() {
        holder().skipped = true;
    }

    static boolean isSkipped() {
        Holder h = HOLDER.get();
        return h != null && h.skipped;
    }

    static List<Map<String, Object>> changes() {
        Holder h = HOLDER.get();
        return h != null ? h.changes : List.of();
    }

    static Long takeEntityId() {
        Holder h = HOLDER.get();
        return h != null ? h.entityId : null;
    }

    static String takeLabel() {
        Holder h = HOLDER.get();
        return h != null ? h.label : null;
    }

    static String takeSummary() {
        Holder h = HOLDER.get();
        return h != null ? h.summary : null;
    }

    static Long takeUserId() {
        Holder h = HOLDER.get();
        return h != null ? h.userId : null;
    }

    static String takeUsername() {
        Holder h = HOLDER.get();
        return h != null ? h.username : null;
    }

    static String takeUserRole() {
        Holder h = HOLDER.get();
        return h != null ? h.userRole : null;
    }

    static void clear() {
        HOLDER.remove();
    }
}
