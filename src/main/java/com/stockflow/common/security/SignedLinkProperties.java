package com.stockflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * What {@link SignedLinks} signs with and where its links point, bound from {@code stockflow.security.links}.
 *
 * @param key              the HMAC-SHA256 key, base64 of at least 32 bytes ({@code openssl rand -base64 32}). Changing
 *                         it ends every link already handed out
 * @param baseUrl          the address the application is reached at from outside ({@code https://api.example.vn}),
 *                         which every link starts with. Blank: no link can be made ({@link SignedLinks#available()} is
 *                         false) and whoever wanted one says so instead
 * @param processKeyAllowed with a base URL and no key, sign with a key made for this process: links then stop working
 *                         at the next restart and on any other instance, which a laptop can live with and a server must
 *                         not - so off unless a profile says so, and the application refuses to start instead
 */
@ConfigurationProperties(prefix = "stockflow.security.links")
public record SignedLinkProperties(String key, String baseUrl, boolean processKeyAllowed) {

    @ConstructorBinding
    public SignedLinkProperties {
    }

    /** A key and an address, no process key allowed. */
    public SignedLinkProperties(String key, String baseUrl) {
        this(key, baseUrl, false);
    }
}
