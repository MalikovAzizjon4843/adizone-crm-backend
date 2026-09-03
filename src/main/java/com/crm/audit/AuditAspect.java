package com.crm.audit;

import com.crm.entity.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Audited} bilan belgilangan metodlar uchun audit yozuvini tayyorlaydi.
 *
 * <p><b>Faqat COMMIT bo'lgan amallar loglanadi.</b> Metod exception tashlasa yozuv
 * umuman yaratilmaydi; tranzaksiya rollback bo'lsa ham yozilmaydi. Buning uchun
 * ikki holat qo'llab-quvvatlanadi:
 * <ul>
 *   <li>aspect tranzaksiya ICHIDA ishlasa — {@code afterCommit} sinxronizatsiyasi
 *       ro'yxatdan o'tkaziladi;</li>
 *   <li>aspect tranzaksiyadan TASHQARIDA ishlasa (yoki tranzaksiya umuman bo'lmasa) —
 *       {@code proceed()} qaytgani commit bo'lganini bildiradi, darhol yoziladi.</li>
 * </ul>
 * Shu sababli aspect va {@code @Transactional} advice tartibi qanday bo'lishidan
 * qat'i nazar natija bir xil.
 *
 * <p>Foydalanuvchi, IP va User-Agent so'rov oqimida, sinxron olinadi — async oqimda
 * na SecurityContext, na HttpServletRequest mavjud bo'ladi.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditAspect {

    private static final int USER_AGENT_MAX = 255;
    private static final int SUMMARY_MAX = 500;
    private static final int LABEL_MAX = 255;
    private static final int IP_MAX = 45;
    private static final String ROLE_PREFIX = "ROLE_";

    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        AuditContext.clear();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            // Amal bajarilmadi — log ham yozilmaydi
            AuditContext.clear();
            throw t;
        }

        try {
            if (AuditContext.isSkipped()) {
                return result;
            }
            AuditLog draft = buildDraft(pjp, audited, result);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        auditRecorder.record(draft);
                    }
                });
            } else {
                // Tranzaksiya allaqachon yopilgan (yoki umuman bo'lmagan)
                auditRecorder.record(draft);
            }
        } catch (Exception e) {
            // Audit hech qachon asosiy amalni yiqitmaydi
            log.error("Audit yozuvini tayyorlab bo'lmadi: {}", pjp.getSignature(), e);
        } finally {
            AuditContext.clear();
        }

        return result;
    }

    private AuditLog buildDraft(ProceedingJoinPoint pjp, Audited audited, Object result) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();
        Object target = pjp.getTarget();

        String summary = trim(firstNonBlank(
            AuditContext.takeSummary(),
            evalString(audited.summary(), method, args, target, result)), SUMMARY_MAX);
        String label = trim(firstNonBlank(
            AuditContext.takeLabel(),
            evalString(audited.label(), method, args, target, result)), LABEL_MAX);

        Long entityId = AuditContext.takeEntityId();
        if (entityId == null) {
            entityId = toLong(eval(audited.entityId(), method, args, target, result));
        }

        // Servis aktyorni o'zi bergan bo'lsa (login — SecurityContext hali bo'sh)
        // o'sha ustun turadi, aks holda so'rov kontekstidan olinadi.
        Long userId = AuditContext.takeUserId();
        String username = AuditContext.takeUsername();
        String role = AuditContext.takeUserRole();

        if (username == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
                username = auth.getName();
                role = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith(ROLE_PREFIX))
                    .map(a -> a.substring(ROLE_PREFIX.length()))
                    .findFirst()
                    .orElse(null);
            }
        }

        HttpServletRequest request = currentRequest();

        return AuditLog.builder()
            .createdAt(LocalDateTime.now())
            .userId(userId)
            .username(username)
            .userRole(role)
            .action(audited.action())
            .entityType(audited.entity())
            .entityId(entityId)
            .entityLabel(label)
            .summary(summary)
            .detailsJson(buildDetails())
            .ipAddress(request != null ? clientIp(request) : null)
            .userAgent(request != null ? trim(request.getHeader("User-Agent"), USER_AGENT_MAX) : null)
            .build();
    }

    private String buildDetails() {
        List<Map<String, Object>> changes = AuditContext.changes();
        if (changes.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("changes", changes);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Audit detailsJson yozilmadi", e);
            return null;
        }
    }

    private Object eval(String expression, Method method, Object[] args, Object target, Object result) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            MethodBasedEvaluationContext ctx =
                new MethodBasedEvaluationContext(target, method, args, paramNames);
            ctx.setVariable("result", result);
            return parser.parseExpression(expression).getValue(ctx);
        } catch (Exception e) {
            log.warn("Audit SpEL hisoblanmadi: {} ({})", expression, e.getMessage());
            return null;
        }
    }

    private String evalString(String expression, Method method, Object[] args, Object target, Object result) {
        Object v = eval(expression, method, args, target, result);
        return v != null ? String.valueOf(v) : null;
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /**
     * Haqiqiy mijoz IP si. Ilova nginx ortida ishlaydi, shuning uchun
     * {@code getRemoteAddr()} har doim proxy manzilini (127.0.0.1) qaytaradi.
     *
     * <p>Tartib: X-Forwarded-For dagi BIRINCHI manzil (zanjirdagi eng chekka
     * mijoz) -> X-Real-IP -> getRemoteAddr().
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = firstForwardedFor(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) {
            return trim(normalizeIp(forwarded), IP_MAX);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return trim(normalizeIp(realIp.trim()), IP_MAX);
        }
        return trim(normalizeIp(request.getRemoteAddr()), IP_MAX);
    }

    /** "203.0.113.9, 10.0.0.1, 127.0.0.1" -> "203.0.113.9" */
    private static String firstForwardedFor(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        String first = (comma > 0 ? header.substring(0, comma) : header).trim();
        return first.isEmpty() ? null : first;
    }

    /** IPv6 localhost o'qishga noqulay — IPv4 shakliga keltiriladi. */
    private static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        if ("::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)) {
            return "127.0.0.1";
        }
        return v;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
