package com.stockflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The browser origins allowed to call the API, bound from {@code stockflow.security.cors.*}.
 *
 * <p>Configuration rather than code because the answer depends on where a frontend is deployed,
 * not on the application: a laptop, a shared dev server and production each have their own. As a
 * literal in the security config, every new frontend host was a code change and a release.</p>
 *
 * <p>Patterns ({@code CorsConfiguration#setAllowedOriginPatterns}), so {@code http://localhost:*}
 * covers every dev-server port and {@code https://*.example.com} every subdomain. A deployment sets
 * {@code STOCKFLOW_SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS} to a comma-separated list.</p>
 *
 * <h2>What is refused at startup, and why</h2>
 *
 * <p>A misconfigured origin never produces an error the server can see - the browser blocks the
 * response and the frontend reports "CORS error" on every call. So the two mistakes that are
 * certain to be wrong fail the application's startup instead:</p>
 * <ul>
 *   <li><b>A wildcard host</b> ({@code *}, {@code https://*}). Credentials are allowed, so this would
 *       let any website make authenticated calls with a visitor's credentials.</li>
 *   <li><b>A pattern that cannot match an Origin header</b>, which is always
 *       {@code scheme://host[:port]}: no scheme, a trailing slash or a path matches nothing.</li>
 * </ul>
 *
 * @param allowedOriginPatterns origin patterns; blank entries and surrounding spaces are dropped
 */
@ConfigurationProperties(prefix = "stockflow.security.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:*", "https://*.stockflow.vn"}) List<String> allowedOriginPatterns
) {

    public CorsProperties {
        List<String> patterns = allowedOriginPatterns == null ? List.of()
                : allowedOriginPatterns.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException(
                    "stockflow.security.cors.allowed-origin-patterns must list at least one origin");
        }
        patterns.forEach(CorsProperties::requireUsable);
        allowedOriginPatterns = patterns;
    }

    private static void requireUsable(String pattern) {
        int schemeEnd = pattern.indexOf("://");
        String scheme = schemeEnd < 0 ? "" : pattern.substring(0, schemeEnd);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid(pattern, "it must start with http:// or https://");
        }
        String host = pattern.substring(schemeEnd + 3);
        if (host.contains("/")) {
            throw invalid(pattern, "an Origin has no path or trailing slash");
        }
        if (host.isEmpty() || host.startsWith("*:") || host.equals("*")) {
            throw invalid(pattern, "a wildcard host would admit every website");
        }
    }

    private static IllegalArgumentException invalid(String pattern, String reason) {
        return new IllegalArgumentException(
                "stockflow.security.cors.allowed-origin-patterns: '" + pattern + "' - " + reason);
    }
}
