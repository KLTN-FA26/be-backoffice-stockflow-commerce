package com.stockflow.identity.internal.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * The RSA key pair this instance signs staff tokens with, and the JOSE plumbing built on it.
 *
 * <p>SCRUM-378 (WBS 3.19.7): the resource server already expects a real JWKS endpoint at
 * {@code jwk-set-uri} ({@code application.yml}) — this is where the key it will fetch comes from.
 *
 * <h2>One key, generated at startup, kept for the process lifetime</h2>
 *
 * <p>Deliberately minimal, per this story's own scope note ("the minimum to issue and validate a
 * real staff token"): a fresh 2048-bit RSA key is generated every time the application starts,
 * which is exactly right for local development and this sprint's demo — every token this instance
 * issues remains valid until it restarts, and {@link com.stockflow.identity.internal.controller.JwksController}
 * always publishes the matching public key.</p>
 *
 * <p><b>What this does not do, on purpose:</b> persist the private key, rotate it, or support more
 * than one signing instance behind a load balancer — a restart or a second instance invalidates
 * every previously issued token. That is an acceptable gap for a single-instance sprint demo and
 * an explicit follow-up before a real multi-instance deployment (load the key from a mounted
 * secret instead of generating one, the same "externalize for prod" pattern
 * {@code JWT_JWK_SET_URI} already uses elsewhere in {@code application.yml}).</p>
 */
@Configuration(proxyBeanMethods = false)
class JwtKeysConfig {

    @Bean
    RSAKey rsaJwk() {
        KeyPair keyPair = generateRsaKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey rsaJwk) {
        return new ImmutableJWKSet<>(new JWKSet(rsaJwk));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            // RSA is a mandatory algorithm for every JCE provider - this cannot happen in practice.
            throw new IllegalStateException("JVM does not support RSA key generation", ex);
        }
    }
}
