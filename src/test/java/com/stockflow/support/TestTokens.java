package com.stockflow.support;

import com.stockflow.common.security.Role;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Builds the {@code Authorization} header value for a test that runs with {@code stockflow.security.enabled=true}.
 *
 * <p>Only token <em>decoding</em> is stubbed (the test class declares a {@code @MockitoBean JwtDecoder}); the
 * filter chain, the authority converter, the permission aspect and the data scope all run for real. The token
 * carries a subject and a {@code roles} claim and nothing else, because that is all the real converter reads:
 * permissions and scope come from the role matrix in the database.</p>
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** {@code "Bearer <token>"} for this account with these roles. */
    public static String bearer(JwtDecoder decoder, UUID user, String... roles) {
        return bearer(decoder, user, Map.of(), roles);
    }

    /** As above, plus extra claims (for a test that checks the converter ignores a claim it must ignore). */
    public static String bearer(JwtDecoder decoder, UUID user, Map<String, Object> extraClaims, String... roles) {
        String value = UUID.randomUUID().toString();
        Jwt.Builder jwt = Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(roles));
        extraClaims.forEach(jwt::claim);
        when(decoder.decode(value)).thenReturn(jwt.build());
        return "Bearer " + value;
    }

    public static String bearer(JwtDecoder decoder, UUID user, Role... roles) {
        return bearer(decoder, user, java.util.Arrays.stream(roles).map(Role::authority).toArray(String[]::new));
    }
}
