package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
import com.stockflow.common.websocket.StompAuthenticationInterceptor;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Socket tickets (BE-12): {@code POST /api/v1/chat/socket-tickets} issues a single-use, 30-second ticket that opens the
 * STOMP socket in place of a bearer token in the CONNECT frame.
 *
 * <ul>
 *   <li>a ticket opens the socket as the account that asked for it, with the permissions of its roles from the role
 *       matrix (ADR-0008) - through the interceptor, and end to end over a real socket;</li>
 *   <li>a second use, an expired ticket (its Redis entry gone), a token expired since, a session ended since and a
 *       malformed ticket are all refused with {@code WS_TICKET_INVALID}, which reaches the client's ERROR frame;</li>
 *   <li>the bearer CONNECT is unchanged; the endpoint needs {@code chat-conversations:READ}.</li>
 * </ul>
 *
 * <p>The same context as {@link ChatStompIntegrationTest} (a real port, the secured test properties, only token
 * decoding stubbed), so the two share it.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class ChatSocketTicketIntegrationTest {

    private static final long WAIT_SECONDS = 10;
    private static final String TICKETS = "/api/v1/chat/socket-tickets";

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired StompAuthenticationInterceptor interceptor;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
    }

    @Test
    @DisplayName("a ticket connects as the account with its roles' permissions, once; it lives 30 s and is not cached")
    void aTicketConnectsOnce() throws Exception {
        UUID agent = new StaffFixtures(jdbc, tx, entityManager).staff("SALES_STAFF");
        String bearer = TestTokens.bearer(decoder, agent, Role.SALES_STAFF);

        String body = mvc.perform(post(TICKETS).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = json.readTree(body).path("data");
        String ticket = data.path("ticket").asText();
        assertThat(data.path("expiresIn").asInt()).isEqualTo(30);
        assertThat(ticket).matches("[A-Za-z0-9_-]{43}");
        Long ttl = redis.getExpire("ws-ticket:" + ticket);
        assertThat(ttl).isBetween(1L, 30L);
        assertThat(redis.opsForValue().get("ws-ticket:" + ticket)).as("the token itself is not stored")
                .doesNotContain(bearer.substring("Bearer ".length()));

        Principal user = connectWith(StompAuthenticationInterceptor.TICKET_HEADER, ticket);
        assertThat(user.getName()).isEqualTo(agent.toString());
        assertThat(((Authentication) user).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("chat-conversations:READ", "SALES_STAFF");

        assertRefused(ticket, "a second use");
    }

    @Test
    @DisplayName("refused with WS_TICKET_INVALID: expired, unknown, malformed, the token expired since, the session ended since")
    void refusals() throws Exception {
        UUID agent = new StaffFixtures(jdbc, tx, entityManager).staff("SALES_STAFF");

        String expired = issue(TestTokens.bearer(decoder, agent, Role.SALES_STAFF));
        redis.delete("ws-ticket:" + expired);
        assertRefused(expired, "a ticket past its 30 s (its entry gone)");
        assertRefused("A".repeat(43), "an unknown ticket");
        assertRefused("../*", "a malformed ticket");

        Instant now = Instant.now();
        String lapsed = issue(TestTokens.bearer(decoder, agent,
                Map.of("iat", now.minusSeconds(900), "exp", now.minusSeconds(1)), Role.SALES_STAFF.authority()));
        assertRefused(lapsed, "the token that asked for it has expired since");

        UUID session = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_session (id, user_id, issued_at, expires_at, version, created_at)
                VALUES (?, ?, NOW(), NOW() + INTERVAL '1 hour', 0, NOW())""", session, agent));
        String bearer = TestTokens.bearer(decoder, agent, Map.of("sid", session.toString()),
                Role.SALES_STAFF.authority());
        String first = issue(bearer);
        String second = issue(bearer);
        assertThat(connectWith(StompAuthenticationInterceptor.TICKET_HEADER, first).getName())
                .as("the session is live").isEqualTo(agent.toString());
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE identity.user_session SET revoked_at = NOW(), revoked_reason = 'LOGOUT' WHERE id = ?""",
                session));
        assertRefused(second, "the session was logged out after the ticket was issued");
    }

    @Test
    @DisplayName("the bearer CONNECT is unchanged; the endpoint needs chat-conversations:READ")
    void bearerAndGuard() throws Exception {
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        UUID agent = people.staff("SALES_STAFF");
        Principal user = connectWith("Authorization", TestTokens.bearer(decoder, agent, Role.SALES_STAFF));
        assertThat(user.getName()).isEqualTo(agent.toString());

        UUID picker = people.staff("WAREHOUSE_STAFF");
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        http.asRole(Role.WAREHOUSE_STAFF, picker, post(TICKETS)).andExpect(status().isForbidden());
        http.anonymous(post(TICKETS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("over a real socket: a ticket opens it and subscribes; a used one gets an ERROR frame saying WS_TICKET_INVALID")
    void overARealSocket() throws Exception {
        UUID agent = new StaffFixtures(jdbc, tx, entityManager).staff("SALES_STAFF");
        String ticket = issue(TestTokens.bearer(decoder, agent, Role.SALES_STAFF));

        Frames opened = new Frames();
        StompHeaders headers = new StompHeaders();
        headers.add(StompAuthenticationInterceptor.TICKET_HEADER, ticket);
        StompSession session = client().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                headers, opened).get(WAIT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        session.subscribe("/topic/chat.inbox", opened);
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(session.isConnected()).as("the subscription to the staff inbox was accepted").isTrue();
        assertThat(opened.error).as("no ERROR frame").isNotDone();

        Frames reused = new Frames();
        try {
            sessions.add(client().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                    reused).get(WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (Exception refused) {
            // The CONNECTED frame never comes; the ERROR frame was handed to the handler first.
        }
        assertThat(reused.error.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("WS_TICKET_INVALID");
    }

    private String issue(String bearer) throws Exception {
        String body = mvc.perform(post(TICKETS).header("Authorization", bearer)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data").path("ticket").asText();
    }

    /** A CONNECT frame with this one header, through the interceptor; the principal it set. */
    private Principal connectWith(String name, String value) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader(name, value);
        accessor.setLeaveMutable(true);
        Message<byte[]> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        interceptor.preSend(connect, null);
        assertThat(accessor.getUser()).isNotNull();
        return accessor.getUser();
    }

    private void assertRefused(String ticket, String why) {
        assertThatThrownBy(() -> connectWith(StompAuthenticationInterceptor.TICKET_HEADER, ticket)).as(why)
                .isInstanceOf(MessageDeliveryException.class).hasMessage("WS_TICKET_INVALID");
    }

    private WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(json);
        client.setMessageConverter(converter);
        return client;
    }

    /** The {@code message} header of the first ERROR frame a session received. */
    private static final class Frames extends StompSessionHandlerAdapter {

        private final CompletableFuture<String> error = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String message = headers.getFirst("message");
            if (message != null) {
                error.complete(message);
            }
        }
    }
}
