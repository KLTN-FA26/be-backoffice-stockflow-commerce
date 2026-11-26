package com.stockflow.identity.internal.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sign-in lifetime each profile ends up with (owner decision TTL-VPS-1126; kltn-docs 00: 8 hours). The shared
 * dev/staging server runs a week; the base file, and so prod and every profile that does not say otherwise, keeps
 * eight hours. Read from the real files, so editing one without the other shows here.
 */
class TokenTtlProfilesTest {

    private static final String KEY = "stockflow.security.token-ttl";

    @Test
    void theBaseFileAndTheProdProfileStayAtEightHours() throws IOException {
        assertThat(ttlIn("application.yml")).isEqualTo(Duration.ofHours(8));
        assertThat(ttlIn("application-prod.yml")).as("prod sets no lifetime of its own, so it inherits the base")
                .isNull();
    }

    @Test
    void theDevProfileSignsInForSevenDays() throws IOException {
        assertThat(ttlIn("application-dev.yml")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void localAndTestKeepTheBaseLifetime() throws IOException {
        assertThat(ttlIn("application-local.yml")).isNull();
        assertThat(ttlIn("application-test.yml")).isNull();
    }

    /** The lifetime a file sets, or null when it does not. */
    private static Duration ttlIn(String file) throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        for (PropertySource<?> document : documents) {
            Object value = document.getProperty(KEY);
            if (value != null) {
                return ApplicationConversionService.getSharedInstance().convert(value, Duration.class);
            }
        }
        return null;
    }
}
