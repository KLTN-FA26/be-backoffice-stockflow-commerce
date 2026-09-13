package com.stockflow.identity.internal.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public half of {@code JwtKeysConfig}'s signing key, at the exact path
 * {@code application.yml}'s {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} already
 * points to — this is the endpoint that comment has been promising since before this story existed.
 *
 * <p>Deliberately unprefixed ({@code /oauth2/jwks}, not {@code /api/v1/...}) and deliberately not
 * wrapped in {@link com.stockflow.common.api.ApiResponse}: this is a standard JWKS document
 * (RFC 7517), consumed by {@code NimbusJwtDecoder} and any other OAuth2/OIDC client, not by this
 * application's own frontend — wrapping it would make every off-the-shelf JWKS client fail to
 * parse it.</p>
 */
@RestController
@RequestMapping("/oauth2")
@Tag(name = "Auth", description = "Staff sign-in")
class JwksController {

    /** Computed once at startup — the key pair is generated once per process, so the public JWK
     *  set never changes while this instance is running. */
    private final Map<String, Object> jwkSet;

    JwksController(RSAKey rsaJwk) {
        this.jwkSet = new JWKSet(rsaJwk.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/jwks")
    @Operation(summary = "This instance's public signing key, as a standard JWK Set")
    public Map<String, Object> jwks() {
        return jwkSet;
    }
}
