package com.stockflow.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    private static CorsConfiguration configFor(String... patterns) {
        var source = new CorsConfig().corsConfigurationSource(new CorsProperties(List.of(patterns)));
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/orders"));
    }

    @Test
    void allowsTheConfiguredOriginsAndNothingElse() {
        var config = configFor("http://localhost:*", "https://*.example.com");

        assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(config.checkOrigin("https://fe.example.com")).isEqualTo("https://fe.example.com");
        assertThat(config.checkOrigin("https://evil.com")).isNull();
        // A subdomain wildcard does not cover the apex, nor a look-alike suffix.
        assertThat(config.checkOrigin("https://example.com")).isNull();
        assertThat(config.checkOrigin("https://fe.example.com.evil.com")).isNull();
    }

    @Test
    void appliesToEveryPath() {
        var source = new CorsConfig().corsConfigurationSource(
                new CorsProperties(List.of("http://localhost:*")));

        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("GET", "/actuator/info")))
                .isNotNull();
    }

    @Test
    void keepsTheHeadersTheClientsDependOn() {
        var config = configFor("http://localhost:*");

        assertThat(config.getAllowedHeaders()).contains("Authorization", "Idempotency-Key");
        assertThat(config.getExposedHeaders()).contains("X-Correlation-Id", "Retry-After");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    void trimsAndDropsBlankEntries() {
        // How a comma-separated environment variable with stray spaces arrives.
        var properties = new CorsProperties(List.of(" http://localhost:* ", "", "https://a.example.com"));

        assertThat(properties.allowedOriginPatterns())
                .containsExactly("http://localhost:*", "https://a.example.com");
    }

    @Test
    void refusesAnEmptyList() {
        assertThatThrownBy(() -> new CorsProperties(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-origin-patterns");
    }

    @Test
    void refusesAWildcardThatWouldAdmitEverySite() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAWildcardOverAPublicSuffix() {
        // One mistyped label away from the intended pattern, and every site under the suffix
        // could call the API with credentials.
        for (String pattern : List.of("https://*.com", "https://*.vn", "https://*.io.vn",
                "https://*.com.vn", "https://*.id.vn", "http://*.co.uk:*")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(pattern)))
                    .as(pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public suffix");
        }
    }

    @Test
    void acceptsAWildcardUnderARegistrableDomain() {
        var properties = new CorsProperties(List.of("https://*.stockflow.vn",
                "https://*.hoaiphuong.io.vn", "https://*.example.com:8443", "http://localhost:*"));

        assertThat(properties.allowedOriginPatterns()).hasSize(4);
    }

    @Test
    void refusesAPatternThatCanNeverMatchAnOrigin() {
        // An Origin header is always scheme://host[:port] - no scheme or a path matches nothing,
        // and the browser's only report of that is a CORS error on every call.
        assertThatThrownBy(() -> new CorsProperties(List.of("fe.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://fe.example.com/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://fe.example.com/app")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
