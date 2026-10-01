package com.crm.billing;

import com.crm.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Billing yozish amallari oldidan: {@code app.billing.enabled=false} bo'lsa
 * 503 {@code billing.maintenance} (cutover oynasi, §9.6). O'qish ochiq qoladi.
 */
@Component
@RequiredArgsConstructor
public class BillingGate {

    private final BillingProperties properties;

    public void requireWritable() {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("billing.maintenance");
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
