package com.crm.billing;

import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.StudentGroup;

import java.math.BigDecimal;

/**
 * Yozilma narxi — FAQAT yozilmadan (docs/design/billing-v2.md §3.4, §3.5).
 *
 * <pre>
 * fee(sg) = sg.monthlyPriceOverride (> 0) → group.course.monthlyPrice (> 0) → 0
 * c(sg)   = Money.uzs(fee × (100 − d) / 100),   d = sg.discountPercentage ?? 0
 * </pre>
 * {@code student.monthlyFee} fallback'i YO'Q — u endi faqat hosila yig'indi (§8).
 */
public final class EnrollmentPricing {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private EnrollmentPricing() {
    }

    public static BigDecimal monthlyFee(StudentGroup sg) {
        if (sg.getMonthlyPriceOverride() != null && sg.getMonthlyPriceOverride().signum() > 0) {
            return sg.getMonthlyPriceOverride();
        }
        Course course = course(sg);
        if (course != null && course.getMonthlyPrice() != null && course.getMonthlyPrice().signum() > 0) {
            return course.getMonthlyPrice();
        }
        return BigDecimal.ZERO;
    }

    /** {@code d}: null → 0; 0..100 dan tashqari — {@code student.discount.invalid}. */
    public static BigDecimal discount(StudentGroup sg) {
        return validDiscount(sg.getDiscountPercentage());
    }

    public static BigDecimal validDiscount(BigDecimal d) {
        BigDecimal value = d != null ? d : BigDecimal.ZERO;
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("student.discount.invalid");
        }
        return value;
    }

    /** Yozilma yaratish/tahrirlashda: null → 0, 0..100 dan tashqari — 400 {@code student.discount.invalid}. */
    public static BigDecimal requestDiscount(BigDecimal d) {
        try {
            return validDiscount(d);
        } catch (IllegalArgumentException e) {
            throw com.crm.exception.CodedException.badRequest("student.discount.invalid");
        }
    }

    /**
     * §9.5: individual narx faqat kurs narxidan farq qilsa saqlanadi; aks holda {@code null} —
     * kurs narxi o'zgarsa yozilma keyingi davrdan yangi narxni oladi.
     */
    public static BigDecimal explicitOverride(BigDecimal requested, Course course) {
        if (requested == null || requested.signum() <= 0) {
            return null;
        }
        if (course != null && course.getMonthlyPrice() != null && Money.eq(requested, course.getMonthlyPrice())) {
            return null;
        }
        return requested;
    }

    /** MONTHLY davr summasi {@code c(sg)} (joriy narx bilan). */
    public static BigDecimal effectiveMonthlyFee(StudentGroup sg) {
        return Money.discounted(monthlyFee(sg), discount(sg));
    }

    /**
     * PER_LESSON dars narxi (chegirmasiz): sg.lessonPrice → course.lessonPrice → 0.
     * Eski "oylik / oyiga darslar" taxmini olib tashlangan — u kasrli narx berardi.
     */
    public static BigDecimal lessonPrice(StudentGroup sg) {
        if (sg.getLessonPrice() != null && sg.getLessonPrice().signum() > 0) {
            return sg.getLessonPrice();
        }
        Course course = course(sg);
        if (course != null && course.getLessonPrice() != null && course.getLessonPrice().signum() > 0) {
            return course.getLessonPrice();
        }
        return BigDecimal.ZERO;
    }

    /** PER_LESSON chegirmali dars narxi {@code l(sg)}. */
    public static BigDecimal effectiveLessonPrice(StudentGroup sg) {
        return Money.discounted(lessonPrice(sg), discount(sg));
    }

    private static Course course(StudentGroup sg) {
        Group g = sg.getGroup();
        return g != null ? g.getCourse() : null;
    }
}
