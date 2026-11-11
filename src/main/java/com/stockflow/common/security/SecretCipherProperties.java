package com.stockflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key {@link SecretCipher} seals stored secrets with, bound from {@code stockflow.security.secrets}.
 *
 * <p>A deployment without the key still starts: nothing it already does needs one, and refusing to boot over a feature
 * nobody has configured yet would take the whole application down for it. What needs the key - saving a carrier's API
 * key or webhook secret - answers 503 until it is set. A key that is set but malformed is a different matter: that is
 * someone's mistake, and the application refuses to start rather than seal anything with a key nobody meant.</p>
 *
 * @param key the AES-256 key, base64 of exactly 32 bytes ({@code openssl rand -base64 32}); blank when not configured.
 *            Changing it makes every secret sealed with the old one unreadable: they have to be entered again
 */
@ConfigurationProperties(prefix = "stockflow.security.secrets")
public record SecretCipherProperties(String key) {
}
