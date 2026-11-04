package com.stockflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Set;

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
 *   <li><b>A wildcard over a public suffix</b> ({@code https://*.com}, {@code https://*.io.vn}): the
 *       same exposure, limited only to everyone who registered a name under that suffix - and the
 *       likeliest slip, since {@code *.io.vn} is one dropped label away from {@code *.mysite.io.vn}.
 *       Checked against a single label plus {@link #SHARED_SUFFIXES}, not the full Public Suffix
 *       List: it catches the mistakes plausible for this project, not every possible one.</li>
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

    /**
     * Second-level suffixes anyone can register under: Vietnam's (where this is deployed) and the
     * few common elsewhere. A wildcard directly over one of these admits strangers' sites.
     */
    private static final Set<String> SHARED_SUFFIXES = Set.of(
            "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn", "int.vn", "ac.vn", "biz.vn",
            "info.vn", "name.vn", "pro.vn", "health.vn", "id.vn", "io.vn",
            "co.uk", "org.uk", "com.au", "co.jp", "com.cn", "com.sg", "co.kr");

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
        if (host.startsWith("*.")) {
            int port = host.lastIndexOf(':');
            String suffix = (port < 0 ? host.substring(2) : host.substring(2, port)).toLowerCase();
            if (!suffix.contains(".") || SHARED_SUFFIXES.contains(suffix)) {
                throw invalid(pattern, "'*." + suffix + "' is a wildcard over a public suffix, which "
                        + "would admit every site registered under it");
            }
        }
    }

    private static IllegalArgumentException invalid(String pattern, String reason) {
        return new IllegalArgumentException(
                "stockflow.security.cors.allowed-origin-patterns: '" + pattern + "' - " + reason);
    }
}
