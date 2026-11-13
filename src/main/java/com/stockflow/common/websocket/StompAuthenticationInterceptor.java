package com.stockflow.common.websocket;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.StockflowJwtAuthenticationConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Clock;
import java.util.Optional;

/**
 * Authentication and authorisation of the STOMP socket ({@code /ws}), on the client's inbound channel.
 *
 * <h2>CONNECT: the same token as REST</h2>
 *
 * <p>A browser cannot put an {@code Authorization} header on a WebSocket handshake, so the handshake itself is
 * anonymous ({@code ResourceServerSecurityConfig} permits {@code /ws}) and the token travels in the STOMP CONNECT
 * frame's {@code Authorization: Bearer ...} header instead. It is decoded by the application's own {@link JwtDecoder} -
 * signature, expiry and the live session ({@code SessionValidator}) - and turned into the same authentication REST
 * gets by {@link StockflowJwtAuthenticationConverter}: roles from the token, permissions and scope from the role matrix
 * (ADR-0008). The principal's name is the account id, which is what user destinations ({@code /user/queue/...}) route
 * by.</p>
 *
 * <h2>SUBSCRIBE and SEND: asked every time</h2>
 *
 * <p>A socket can stay open for hours. The permissions are therefore resolved again on every SUBSCRIBE (cheap: the
 * matrix is cached per role and version), so a revoked grant takes effect at the next subscription, and a frame
 * arriving after the token expired is refused: the client reconnects with a fresh one. Whether the caller may read a
 * destination is the owning module's {@link StompDestinationPolicy}; a destination nobody claims is refused, and a
 * client never sends straight to the broker ({@code /topic}, {@code /queue}) - only to {@code /app}. The policies are
 * looked up when a frame arrives, not injected: they are module services, and the broker configuration that holds this
 * interceptor is built before them.</p>
 *
 * <p>When security is switched off ({@code stockflow.security.enabled=false}) there is no decoder, and every CONNECT
 * is refused: the socket serves only signed-in people, and there is nobody to sign in.</p>
 */
@Component
public class StompAuthenticationInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthenticationInterceptor.class);
    private static final String BEARER = "Bearer ";
    private static final String USER_QUEUES = "/user/queue/";
    private static final String APPLICATION = "/app/";

    private final ObjectProvider<JwtDecoder> decoder;
    private final ObjectProvider<StockflowJwtAuthenticationConverter> converter;
    private final CurrentUserProvider users;
    private final ObjectProvider<StompDestinationPolicy> policies;
    private final Clock clock;

    public StompAuthenticationInterceptor(ObjectProvider<JwtDecoder> decoder,
                                          ObjectProvider<StockflowJwtAuthenticationConverter> converter,
                                          CurrentUserProvider users, ObjectProvider<StompDestinationPolicy> policies,
                                          Clock clock) {
        this.decoder = decoder;
        this.converter = converter;
        this.users = users;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
        } else if (command == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            CurrentUser user = caller(accessor.getUser(), true);
            if (destination == null || !(destination.startsWith(USER_QUEUES)
                    || policyFor(destination).map(p -> p.maySubscribe(user, destination)).orElse(false))) {
                throw new MessageDeliveryException("Not allowed to subscribe to " + destination);
            }
        } else if (command == StompCommand.SEND) {
            String destination = accessor.getDestination();
            CurrentUser user = caller(accessor.getUser(), false);
            if (destination == null || !destination.startsWith(APPLICATION)
                    || !policyFor(destination).map(p -> p.maySend(user, destination)).orElse(false)) {
                throw new MessageDeliveryException("Not allowed to send to " + destination);
            }
        }
        return message;
    }

    private AbstractAuthenticationToken authenticate(String header) {
        JwtDecoder jwtDecoder = decoder.getIfAvailable();
        StockflowJwtAuthenticationConverter authorities = converter.getIfAvailable();
        if (jwtDecoder == null || authorities == null) {
            log.warn("STOMP CONNECT refused: security is switched off, so nobody can sign in to the socket");
            throw new MessageDeliveryException("Authentication is not available");
        }
        if (header == null || !header.startsWith(BEARER) || header.length() == BEARER.length()) {
            throw new MessageDeliveryException("A bearer token is required to connect");
        }
        try {
            return authorities.convert(jwtDecoder.decode(header.substring(BEARER.length()).strip()));
        } catch (JwtException invalid) {
            throw new MessageDeliveryException("The token is not valid");
        }
    }

    /**
     * The signed-in caller of a frame, refused when the socket never authenticated or its token has expired since.
     *
     * @param refresh whether to resolve the permissions again from the role matrix (ADR-0008)
     */
    private CurrentUser caller(Principal principal, boolean refresh) {
        if (!(principal instanceof JwtAuthenticationToken token)) {
            throw new MessageDeliveryException("Not authenticated");
        }
        Jwt jwt = token.getToken();
        if (jwt.getExpiresAt() != null && !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw new MessageDeliveryException("The token has expired; connect again");
        }
        JwtAuthenticationToken current = token;
        StockflowJwtAuthenticationConverter authorities = converter.getIfAvailable();
        if (refresh && authorities != null && authorities.convert(jwt) instanceof JwtAuthenticationToken fresh) {
            current = fresh;
        }
        return users.of(current).orElseThrow(() -> new MessageDeliveryException("Not authenticated"));
    }

    private Optional<StompDestinationPolicy> policyFor(String destination) {
        return policies.orderedStream().filter(policy -> policy.claims(destination)).findFirst();
    }
}
