package com.crm.audit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.audit")
@Getter
@Setter
public class AuditProperties {

    /** false bo'lsa aspect umuman yuklanmaydi (@ConditionalOnProperty). */
    private boolean enabled = true;

    /** Shu kundan eski loglar kechasi o'chiriladi. 0 yoki manfiy — hech qachon o'chirilmaydi. */
    private int retentionDays = 90;
}
