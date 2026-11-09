package com.stockflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What {@link SignedLinks} signs with and where its links point, bound from {@code stockflow.security.links}.
 *
 * @param key     the HMAC-SHA256 key, base64 of at least 32 bytes ({@code openssl rand -base64 32}). Blank: a key made
 *                for this process only - links stop working at the next restart, which a laptop and the tests can live
 *                with and a server should not. Changing it ends every link already handed out
 * @param baseUrl the address the application is reached at from outside ({@code https://api.example.vn}), which every
 *                link starts with. Blank: no link can be made ({@link SignedLinks#available()} is false) and whoever
 *                wanted one says so instead
 */
@ConfigurationProperties(prefix = "stockflow.security.links")
public record SignedLinkProperties(String key, String baseUrl) {
}
