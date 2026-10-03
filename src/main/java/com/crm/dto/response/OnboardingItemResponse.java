package com.crm.dto.response;

import java.time.LocalDateTime;

/** Ko'rilgan onboarding turi: kalit va birinchi marta belgilangan payt. */
public record OnboardingItemResponse(String key, LocalDateTime seenAt) {
}
