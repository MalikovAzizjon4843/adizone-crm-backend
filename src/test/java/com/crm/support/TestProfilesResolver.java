package com.crm.support;

import org.springframework.test.context.ActiveProfilesResolver;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Test profillari: doim {@code test} + {@code -Dspring.profiles.active} da berilganlari.
 *
 * <p>Nega kerak: {@code @ActiveProfiles("test")} Spring Boot'da
 * {@code spring.profiles.active} system property'sidan USTUN turadi — shu sababli
 * {@code mvn test -Dspring.profiles.active=pgtest} pgtest profilini yoqmasdi.
 * Bu resolver ikkalasini qo'shadi; tashqi profil keyin keladi va {@code test} ni
 * ustidan yozadi (masalan pgtest datasource'i H2 o'rniga).
 *
 * <ul>
 *   <li>{@code mvn test} — H2 (default)</li>
 *   <li>{@code mvn test -Dspring.profiles.active=pgtest} — lokal PostgreSQL
 *       ({@code application-pgtest.yml})</li>
 * </ul>
 */
public class TestProfilesResolver implements ActiveProfilesResolver {

    @Override
    public String[] resolve(Class<?> testClass) {
        Set<String> profiles = new LinkedHashSet<>();
        profiles.add("test");
        String extra = System.getProperty("spring.profiles.active");
        if (extra != null) {
            Arrays.stream(extra.split(","))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .forEach(profiles::add);
        }
        return profiles.toArray(String[]::new);
    }
}
