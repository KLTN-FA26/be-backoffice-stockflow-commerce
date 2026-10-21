package com.stockflow.identity.internal.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The RSA key pair this instance signs tokens with, and the JOSE plumbing built on it.
 *
 * <p>SCRUM-378 (WBS 3.19.7): the resource server expects a real JWKS endpoint at {@code jwk-set-uri}
 * ({@code application.yml}) - this is where the key it fetches comes from.</p>
 *
 * <h2>One key, kept across restarts when a location is configured</h2>
 *
 * <p>The key used to be generated at every start, so every deploy of the dev server signed every user out (E2E QA
 * 2026-10-21). It is now loaded from {@code stockflow.security.jwt.private-key} ({@code JWT_PRIVATE_KEY}, PEM
 * content) or {@code stockflow.security.jwt.key-path} ({@code JWT_PRIVATE_KEY_PATH}, created on first start); with
 * neither, a per-process key as before. {@link JwtSigningKey} has the rules, including why a configured key that
 * cannot be read stops the application instead of falling back.</p>
 *
 * <p>Still not done, on purpose: rotation, or publishing more than one key. A rotation is replacing the file and
 * restarting, which signs everyone out once.</p>
 */
@Configuration(proxyBeanMethods = false)
class JwtKeysConfig {

    @Bean
    RSAKey rsaJwk(@Value("${stockflow.security.jwt.private-key:}") String privateKeyPem,
                  @Value("${stockflow.security.jwt.key-path:}") String keyPath) {
        return JwtSigningKey.resolve(privateKeyPem, keyPath);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey rsaJwk) {
        return new ImmutableJWKSet<>(new JWKSet(rsaJwk));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }
}
