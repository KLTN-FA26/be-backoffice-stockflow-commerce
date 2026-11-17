package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chat's socket end to end on a real port: STOMP over WebSocket at {@code /ws}, the JWT in the CONNECT frame
 * decoded by the application's decoder and resolved against the role matrix (ADR-0008), subscriptions checked against
 * the conversation, and the events of REST writes pushed after commit.
 *
 * <ul>
 *   <li>a customer subscribed to their conversation receives the agent's answer, and never the internal note written
 *       just before it (16 BR-07) - the note goes to the staff topic, where the agent receives it;</li>
 *   <li>a customer may not subscribe to the staff topic of their own conversation, nor to another customer's
 *       conversation; a CONNECT without a token is refused.</li>
 * </ul>
 *
 * <p>Its own application context (a real port), with the secured test properties. Only token decoding is stubbed.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class ChatStompIntegrationTest {

    private static final long WAIT_SECONDS = 10;

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

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
    @DisplayName("STOMP round trip: the customer gets the answer and never the note; the agent gets the note on the staff topic")
    void roundTrip() throws Exception {
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        UUID agent = people.staff("SALES_STAFF");
        SecuredMvc http = new SecuredMvc(mvc, decoder);

        String opened = http.asCustomer(account, post("/api/v1/chat/me/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Hoi gia\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID conversation = UUID.fromString(json.readTree(opened).path("data").path("id").asText());
        http.asRole(Role.SALES_STAFF, agent, post("/api/v1/chat/conversations/" + conversation + "/claim"))
                .andExpect(status().isOk());

        Events customerSide = new Events();
        StompSession customerSession = connect(TestTokens.bearer(decoder, account, Role.CUSTOMER), customerSide);
        customerSession.subscribe("/topic/chat.conversations." + conversation, customerSide);
        customerSession.subscribe("/user/queue/chat", customerSide);
        Events staffSide = new Events();
        StompSession staffSession = connect(TestTokens.bearer(decoder, agent, Role.SALES_STAFF), staffSide);
        staffSession.subscribe("/topic/chat.conversations." + conversation + ".staff", staffSide);
        // SUBSCRIBE frames are handled on the broker's inbound executor: let them land before the first push.
        TimeUnit.SECONDS.sleep(1);

        String note = "Ghi chu noi bo cho dong nghiep";
        http.asRole(Role.SALES_STAFF, agent, post("/api/v1/chat/conversations/" + conversation + "/notes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + note + "\"}"))
                .andExpect(status().isCreated());
        http.asRole(Role.SALES_STAFF, agent, post("/api/v1/chat/conversations/" + conversation + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Chao anh\"}"))
                .andExpect(status().isCreated());

        JsonNode answer = customerSide.awaitMessage("Chao anh");
        assertThat(answer.path("type").asText()).isEqualTo("message.created");
        assertThat(answer.path("data").path("visibility").asText()).isEqualTo("CUSTOMER");
        JsonNode staffNote = staffSide.awaitMessage(note);
        assertThat(staffNote.path("data").path("kind").asText()).isEqualTo("INTERNAL_NOTE");
        // Give anything that would leak the time to arrive, then look at everything the customer got.
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(customerSide.all()).noneMatch(event -> event.toString().contains(note))
                .noneMatch(event -> event.toString().contains("INTERNAL_NOTE"));
    }

    @Test
    @DisplayName("subscriptions are checked: not the staff topic, not another customer's conversation; no token, no socket")
    void subscriptionsAreChecked() throws Exception {
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        UUID strangerAccount = people.staff("CUSTOMER");
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?",
                strangerAccount, stranger));
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        String opened = http.asCustomer(account, post("/api/v1/chat/me/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID conversation = UUID.fromString(json.readTree(opened).path("data").path("id").asText());

        Events own = new Events();
        StompSession session = connect(TestTokens.bearer(decoder, account, Role.CUSTOMER), own);
        session.subscribe("/topic/chat.conversations." + conversation + ".staff", own);
        assertThat(own.awaitRefusal()).as("a customer on the staff topic of their own conversation").isTrue();

        Events other = new Events();
        StompSession strangers = connect(TestTokens.bearer(decoder, strangerAccount, Role.CUSTOMER), other);
        strangers.subscribe("/topic/chat.conversations." + conversation, other);
        assertThat(other.awaitRefusal()).as("another customer's conversation").isTrue();

        Events anonymous = new Events();
        CompletableFuture<StompSession> refused = client().connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), new StompHeaders(), anonymous);
        boolean failed;
        try {
            sessions.add(refused.get(WAIT_SECONDS, TimeUnit.SECONDS));
            failed = anonymous.awaitRefusal();
        } catch (Exception connectRefused) {
            failed = true;
        }
        assertThat(failed).as("a CONNECT without a token").isTrue();
    }

    private StompSession connect(String bearer, Events handler) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", bearer);
        StompSession session = client().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                headers, handler).get(WAIT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(json);
        client.setMessageConverter(converter);
        return client;
    }

    /** What one session received: the events, and whether the server refused it something. */
    private static final class Events extends StompSessionHandlerAdapter {

        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final List<JsonNode> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CompletableFuture<Boolean> refused = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof JsonNode node) {
                received.add(node);
                seen.add(node);
            }
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            refused.complete(true);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            refused.complete(true);
        }

        JsonNode awaitMessage(String body) throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < until) {
                JsonNode event = received.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && body.equals(event.path("data").path("body").asText(null))) {
                    return event;
                }
            }
            throw new AssertionError("no message '" + body + "' arrived; got " + seen);
        }

        boolean awaitRefusal() {
            try {
                return refused.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception none) {
                return false;
            }
        }

        List<JsonNode> all() {
            return List.copyOf(seen);
        }
    }
}
