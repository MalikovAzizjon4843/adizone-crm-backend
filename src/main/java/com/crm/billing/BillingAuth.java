package com.crm.billing;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

/** Billing qoidalaridagi rol tekshiruvlari (controller {@code @PreAuthorize} dan tashqari). */
public final class BillingAuth {

    private BillingAuth() {
    }

    public static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (Arrays.stream(roles).anyMatch(r -> name.equals("ROLE_" + r))) {
                return true;
            }
        }
        return false;
    }
}
