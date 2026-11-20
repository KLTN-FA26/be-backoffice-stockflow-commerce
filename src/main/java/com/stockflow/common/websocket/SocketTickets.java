package com.stockflow.common.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.common.security.StockflowJwtAuthenticationConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Single-use, 30-second tickets that open the STOMP socket for a caller who cannot hand the socket a bearer token.
 *
 * <h2>Why tickets</h2>
 *
 * <p>The back office keeps its access token in an HttpOnly cookie behind its own server (a Next.js BFF), so the
 * browser's script never sees the token and cannot put it in the CONNECT frame. It can, however, ask the API through
 * the BFF for a ticket ({@code POST /api/v1/chat/socket-tickets}, which the BFF signs with the cookie's token) and put
 * the ticket in the CONNECT frame's {@code ticket} header. Never in the URL: a URL is logged by every proxy on the
 * way.</p>
 *
 * <h2>What a ticket holds, and why not the token itself</h2>
 *
 * <p>A ticket is 32 bytes from {@link SecureRandom}, URL-safe base64, and the key of one Redis entry
 * ({@code ws-ticket:<ticket>}, TTL 30 s) holding the claims of the token that asked for it - exactly the claims the
 * socket reads: subject, session id, user name, roles, issue and expiry instants, token id. The access token itself is
 * not stored, so a copy of Redis holds no credential that lives longer than 30 seconds. Redeeming reads the entry with
 * {@code GETDEL}, so a ticket opens at most one socket, and a second use, an expired ticket and an unknown one look the
 * same: absent. The ticket is spent before the session is checked, so a failure of the session store at that moment
 * costs the client its ticket: it is refused as unavailable and asks for a new one - fail closed, never a second
 * use.</p>
 *
 * <h2>A ticket grants no more, and lives no longer, than the token that asked for it</h2>
 *
 * <p>The redeemed claims are rebuilt into a {@link Jwt} and go through the same checks a bearer token goes through on
 * the socket, minus the signature (they never left the server): its expiry against the clock, the live session through
 * the very {@link SessionValidator} the decoder uses (a logout between issue and connect ends the ticket), and
 * {@link StockflowJwtAuthenticationConverter} for the roles' permissions and scope, read from the role matrix at
 * connect time (ADR-0008). The socket then carries the token's own expiry, so it is refused at the next frame once the
 * token would have been - as a bearer-opened socket is. A claim the socket does not read is not copied, so it cannot
 * widen anything.</p>
 */
@Component
public class SocketTickets {

    /** Seconds a ticket may wait for its CONNECT. Also what the issuing endpoint tells the client. */
    public static final int TTL_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(SocketTickets.class);
    private static final String KEY_PREFIX = "ws-ticket:";
    private static final int TICKET_BYTES = 32;
    /** 32 bytes in unpadded URL-safe base64: anything else is refused before Redis is asked. */
    private static final Pattern TICKET_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** The rebuilt token's stand-in value: it was never a signed token, and the ticket is not kept anywhere. */
    private static final String REBUILT_TOKEN_VALUE = "ws-ticket";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public SocketTickets(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * A new ticket for the caller holding {@code token}.
     *
     * @param token the caller's decoded access token (already checked by the filter chain); null when security is
     *              switched off and nobody is signed in, which is refused as unauthenticated
     */
    public SocketTicket issue(Jwt token) {
        if (token == null || token.getSubject() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "A socket ticket needs a signed-in caller");
        }
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String grant;
        try {
            grant = json.writeValueAsString(Grant.of(token));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A socket ticket's claims could not be written", impossible);
        }
        redis.opsForValue().set(KEY_PREFIX + ticket, grant, Duration.ofSeconds(TTL_SECONDS));
        log.debug("Socket ticket issued for subject {}", token.getSubject());
        return new SocketTicket(ticket, TTL_SECONDS);
    }

    /**
     * Takes the ticket out of Redis and rebuilds the token it was issued for, unchecked: the caller checks expiry and
     * session. Empty for a ticket that is malformed, unknown, expired or already used - one answer for all of them.
     *
     * @throws org.springframework.dao.DataAccessException when Redis cannot be read; not the same as "unknown"
     */
    Optional<Jwt> redeem(String ticket) {
        if (ticket == null || !TICKET_SHAPE.matcher(ticket).matches()) {
            return Optional.empty();
        }
        String grant = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        if (grant == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(grant, Grant.class).toJwt());
        } catch (JsonProcessingException | RuntimeException unreadable) {
            log.warn("A socket ticket's stored claims could not be read; refusing it ({})",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** What the issuing endpoint returns: the ticket and how many seconds it stays valid. */
    public record SocketTicket(String ticket, int expiresIn) {
    }

    /** The claims a socket reads, as stored under a ticket. Instants are epoch seconds, as in a JWT. */
    record Grant(String subject, String sessionId, String username, List<String> roles, Long issuedAt,
                 Long expiresAt, String tokenId) {

        static Grant of(Jwt token) {
            return new Grant(token.getSubject(), token.getClaimAsString(SessionValidator.SESSION_CLAIM),
                    token.getClaimAsString("preferred_username"),
                    token.getClaimAsStringList(StockflowJwtAuthenticationConverter.ROLES_CLAIM),
                    epochSeconds(token.getIssuedAt()), epochSeconds(token.getExpiresAt()), token.getId());
        }

        Jwt toJwt() {
            // A JWT must have a header; "none" says what is true of this one - it was rebuilt, not verified.
            Jwt.Builder jwt = Jwt.withTokenValue(REBUILT_TOKEN_VALUE).header("alg", "none").subject(subject)
                    .claim(StockflowJwtAuthenticationConverter.ROLES_CLAIM, roles == null ? List.of() : roles);
            if (sessionId != null) {
                jwt.claim(SessionValidator.SESSION_CLAIM, sessionId);
            }
            if (username != null) {
                jwt.claim("preferred_username", username);
            }
            if (issuedAt != null) {
                jwt.issuedAt(Instant.ofEpochSecond(issuedAt));
            }
            if (expiresAt != null) {
                jwt.expiresAt(Instant.ofEpochSecond(expiresAt));
            }
            if (tokenId != null) {
                jwt.jti(tokenId);
            }
            return jwt.build();
        }

        private static Long epochSeconds(Instant instant) {
            return instant == null ? null : instant.getEpochSecond();
        }
    }
}
