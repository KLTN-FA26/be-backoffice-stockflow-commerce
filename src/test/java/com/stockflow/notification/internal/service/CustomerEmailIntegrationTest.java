package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.notification.internal.domain.WebhookSignature;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-352 end to end on PostgreSQL: a shipment event commits, the listener runs after the commit, the customer's
 * address is read through {@code CustomerDirectory} (the customer module's adapter, on a real row), the message is
 * rendered and POSTed to Resend's API - a stub here, never the real one - and the outcome lands in
 * {@code notification.delivery_log}. Then what goes wrong: a duplicate event, a refused recipient, a Resend outage
 * that the retry job recovers from, a customer with no address. Then Resend's signed delivery report.
 *
 * <p>The stub sits under the transport's {@code RestClient} as its request factory ({@link ResendStub}), so the real
 * transport builds the real request - headers, JSON, status handling - and no socket is opened: the JDK's HTTP client
 * and server both need a loopback selector pipe that some sandboxed build machines refuse.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(PostgresContainer.class)
@TestPropertySource(properties = {
        "stockflow.notification.email.provider=resend",
        "stockflow.notification.email.from=StockFlow <no-reply@stockflow.vn>",
        "stockflow.notification.email.reply-to=sales@stockflow.vn",
        "stockflow.notification.email.resend.api-key=" + CustomerEmailIntegrationTest.KEY,
        "stockflow.notification.email.resend.base-url=https://api.resend.test",
        "stockflow.notification.email.resend.webhook-secret=" + CustomerEmailIntegrationTest.SECRET,
        "stockflow.notification.email.resend.requests-per-second=1000"})
class CustomerEmailIntegrationTest {

    static final String KEY = "re_integration_test_key";
    static final String SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC1zaWduaW5nLXNlY3JldA==";
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final ResendStub RESEND = new ResendStub();

    /** The application's client factory, except that Resend's client talks to the stub. */
    @TestBean RestClientFactory restClientFactory;

    static RestClientFactory restClientFactory() {
        return new RestClientFactory(new HttpClientProperties(Duration.ofSeconds(3), Duration.ofSeconds(10))) {
            @Override
            public RestClient.Builder forService(String serviceName, String baseUrl) {
                return ResendEmailTransport.SERVICE.equals(serviceName)
                        ? RestClient.builder().baseUrl(baseUrl).requestFactory(RESEND)
                        : super.forService(serviceName, baseUrl);
            }
        };
    }

    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerEmailRetry retry;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private UUID customer;
    private String address;

    @BeforeEach
    void customer() {
        customer = Identifiers.newId();
        address = "buyer-" + customer + "@cup-maker.vn";
        // In a transaction: the pool hands out connections with auto-commit off, and rolls back what is not committed.
        write("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, NOW())""", customer, "Công ty <b>Kiểm Thử</b> & Co", address);
        RESEND.script.clear();
    }

    private void write(String sql, Object... arguments) {
        transactions.executeWithoutResult(tx -> jdbc.update(sql, arguments));
    }

    private ShipmentStatusChanged handedOver(UUID customerId) {
        return new ShipmentStatusChanged(Identifiers.newId(), Identifiers.newId(), "SO-IT-1-S1", Identifiers.newId(),
                "SO-IT-1", customerId, Identifiers.newId(), "PARCEL", "READY_TO_DISPATCH", "HANDED_OVER", null, 2,
                "Giao Hàng Nhanh", "LD-IT-1", null, Instant.parse("2026-10-24T07:05:00Z"));
    }

    private void publish(Object event) {
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }

    private static String reference(ShipmentStatusChanged event) {
        return "customer-mail:shipment.handed-over:" + event.eventId();
    }

    private List<Map<String, Object>> rows(String reference) {
        return jdbc.queryForList("""
                SELECT status, error, terminal, recipient, provider, provider_message_id, next_attempt_at,
                       external_reference, attempt_number, provider_status
                FROM notification.delivery_log WHERE operation_reference = ? ORDER BY created_at, id""", reference);
    }

    private long pending(ShipmentStatusChanged event) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + event.eventId() + "%");
    }

    private List<ResendStub.Recorded> requests(String reference) {
        return RESEND.requests.stream().filter(r -> reference.equals(r.headers().get("idempotency-key"))).toList();
    }

    @Test
    @DisplayName("handed to the carrier: one POST to Resend - key, idempotency key, Vietnamese, escaped - recorded SENT")
    void sentOnceThroughResend() throws Exception {
        ShipmentStatusChanged event = handedOver(customer);
        publish(event);
        String reference = reference(event);
        Await.until("the customer e-mail is recorded", ASYNC, () -> !rows(reference).isEmpty());

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SENT");
            assertThat(row.get("recipient")).isEqualTo(address);
            assertThat(row.get("provider")).isEqualTo("RESEND");
            assertThat(row.get("external_reference")).isEqualTo(reference);
            assertThat((String) row.get("provider_message_id")).startsWith("stub-");
        });
        Await.until("the publication is complete", ASYNC, () -> pending(event) == 0);

        ResendStub.Recorded request = requests(reference).getFirst();
        assertThat(request.uri()).isEqualTo("https://api.resend.test/emails");
        assertThat(request.headers().get("authorization")).isEqualTo("Bearer " + KEY);
        JsonNode body = json.readTree(request.body());
        assertThat(body.at("/from").asText()).isEqualTo("StockFlow <no-reply@stockflow.vn>");
        assertThat(body.at("/to/0").asText()).isEqualTo(address);
        assertThat(body.at("/reply_to").asText()).isEqualTo("sales@stockflow.vn");
        assertThat(body.at("/subject").asText()).isEqualTo("Đơn hàng SO-IT-1 đã được bàn giao cho đơn vị vận chuyển");
        assertThat(body.at("/tags/0/value").asText()).isEqualTo("shipment_handed-over");
        assertThat(body.at("/html").asText()).contains("Công ty &lt;b&gt;Kiểm Thử&lt;/b&gt; &amp; Co", "Giao Hàng Nhanh",
                "14:05 24/10/2026").doesNotContain("<b>Kiểm Thử</b>");
        assertThat(body.at("/text").asText()).contains("Kính gửi Công ty <b>Kiểm Thử</b> & Co,",
                "Đơn vị vận chuyển: Giao Hàng Nhanh");

        // The same event again - a redelivery, a restart's republication: found sent, not sent twice.
        publish(event);
        Await.until("the duplicate publication is complete", ASYNC, () -> pending(event) == 0);
        assertThat(requests(reference)).hasSize(1);
        assertThat(rows(reference)).hasSize(1);
    }

    @Test
    @DisplayName("a return's step (SCRUM-243) reaches the customer the same way: once, in Vietnamese, named by its RMA")
    void returnStepIsMailed() throws Exception {
        UUID returnId = Identifiers.newId();
        var event = new com.stockflow.contracts.ReturnClosed(returnId, "RMA-IT-1", Identifiers.newId(), "SO-IT-1",
                customer, "EXPIRED", false, null, Instant.parse("2026-10-25T07:05:00Z"));
        publish(event);
        String reference = "customer-mail:return.expired:" + returnId;
        Await.until("the return e-mail is recorded", ASYNC, () -> !rows(reference).isEmpty());

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SENT");
            assertThat(row.get("recipient")).isEqualTo(address);
        });
        Await.until("the return publication is complete", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + returnId + "%") == 0);
        assertThat(requests(reference)).hasSize(1);
        JsonNode body = json.readTree(requests(reference).getFirst().body());
        assertThat(body.at("/subject").asText()).isEqualTo("Yêu cầu đổi trả RMA-IT-1 đã hết hiệu lực");
        assertThat(body.at("/text").asText()).contains("Mã yêu cầu đổi trả: RMA-IT-1", "Mã đơn hàng: SO-IT-1",
                "kèm mã vận đơn");
        assertThat(body.at("/tags/0/value").asText()).isEqualTo("return_expired");
    }

    @Test
    @DisplayName("422 from Resend: recorded as a final failure, the event completes, nothing is retried")
    void refusedRecipient() {
        RESEND.script.add(new ResendStub.Response(422,
                "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to` field\"}", Map.of()));
        ShipmentStatusChanged event = handedOver(customer);
        publish(event);
        String reference = reference(event);
        Await.until("the refusal is recorded", ASYNC, () -> !rows(reference).isEmpty());
        Await.until("the publication is complete", ASYNC, () -> pending(event) == 0);

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error")).isEqualTo("RESEND_REJECTED_422");
            assertThat(row.get("terminal")).isEqualTo(true);
            assertThat(row.get("next_attempt_at")).isNull();
        });
    }

    @Test
    @DisplayName("Resend down: a failed attempt waits its back-off, the retry job sends it once due, with the same key")
    void outageIsRetried() {
        RESEND.script.add(new ResendStub.Response(503, "{\"name\":\"internal_server_error\"}", Map.of()));
        ShipmentStatusChanged event = handedOver(customer);
        publish(event);
        String reference = reference(event);
        Await.until("the failure is recorded", ASYNC, () -> !rows(reference).isEmpty());

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error")).isEqualTo("RESEND_UNAVAILABLE");
            assertThat(row.get("terminal")).isEqualTo(false);
            assertThat(row.get("next_attempt_at")).isNotNull();
        });
        assertThat(pending(event)).as("kept for the retry").isEqualTo(1);

        // Inside its back-off: the job passes it over.
        write("UPDATE event_publication SET publication_date = publication_date - interval '5 minutes'"
                + " WHERE serialized_event LIKE ?", "%" + event.eventId() + "%");
        retry.retry();
        assertThat(requests(reference)).hasSize(1);

        // Due: sent, with the key the first attempt used, and the publication completes.
        write("UPDATE notification.delivery_log SET next_attempt_at = now() - interval '1 second'"
                + " WHERE operation_reference = ?", reference);
        // The job holds its ShedLock for at least 30 s after a run; a second run inside that is skipped, by design.
        // Expire it rather than delete it (ShedLock remembers the row exists and only updates it afterwards), in UTC
        // like ShedLock's own database-time columns.
        write("UPDATE platform.shedlock SET lock_until = timezone('utc', now()) - interval '1 second'"
                + " WHERE name = 'notification.retryCustomerEmails'");
        retry.retry();
        Await.until("the retry is recorded", ASYNC, () -> rows(reference).size() == 2);
        Await.until("the publication is complete", ASYNC, () -> pending(event) == 0);
        assertThat(rows(reference)).extracting(row -> row.get("status")).containsExactly("FAILED", "SENT");
        assertThat(rows(reference).get(1).get("attempt_number")).isEqualTo(2);
        assertThat(requests(reference)).hasSize(2);
    }

    @Test
    @DisplayName("a customer that does not exist: skipped with the reason, the event completes, Resend never called")
    void unknownCustomerIsSkipped() {
        ShipmentStatusChanged event = handedOver(Identifiers.newId());
        publish(event);
        String reference = reference(event);
        Await.until("the skip is recorded", ASYNC, () -> !rows(reference).isEmpty());
        Await.until("the publication is complete", ASYNC, () -> pending(event) == 0);

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SKIPPED");
            assertThat(row.get("error")).isEqualTo("CUSTOMER_NOT_FOUND");
            assertThat(row.get("terminal")).isEqualTo(true);
            assertThat((String) row.get("recipient")).startsWith("customer:");
        });
        assertThat(requests(reference)).isEmpty();
    }

    @Test
    @DisplayName("Resend's signed report lands on the message; an unsigned or oversized one is refused and leaks nothing")
    void deliveryWebhook() throws Exception {
        ShipmentStatusChanged event = handedOver(customer);
        publish(event);
        String reference = reference(event);
        Await.until("the customer e-mail is sent", ASYNC,
                () -> rows(reference).stream().anyMatch(row -> "SENT".equals(row.get("status"))));
        String messageId = (String) rows(reference).getFirst().get("provider_message_id");

        byte[] report = ("{\"type\":\"email.delivered\",\"created_at\":\"2026-10-24T08:00:00.000Z\",\"data\":"
                + "{\"email_id\":\"" + messageId + "\",\"to\":[\"" + address + "\"]}}").getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        String signature = new WebhookSignature(SECRET, Duration.ofMinutes(5)).sign("msg_it_1", now, report);

        mvc.perform(post("/api/v1/notifications/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", "msg_it_1").header("svix-timestamp", Long.toString(now.getEpochSecond()))
                        .header("svix-signature", signature).content(report))
                .andExpect(status().isNoContent());
        assertThat(rows(reference).getFirst().get("provider_status")).isEqualTo("DELIVERED");

        String refusal = mvc.perform(post("/api/v1/notifications/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).doesNotContain(messageId, address, reference);

        // One byte over the controller's 64 KiB: refused before it is read for meaning.
        mvc.perform(post("/api/v1/notifications/webhooks/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[64 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_PAYLOAD_TOO_LARGE"));
    }

    /**
     * Resend's {@code POST /emails} as the transport's request factory: records each request it is handed and answers
     * from a script, else 200 with a fresh id.
     */
    static final class ResendStub implements ClientHttpRequestFactory {

        record Recorded(String uri, Map<String, String> headers, String body) {
        }

        record Response(int status, String body, Map<String, String> headers) {
        }

        final List<Recorded> requests = new CopyOnWriteArrayList<>();
        final Deque<Response> script = new ConcurrentLinkedDeque<>();

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    Map<String, String> headers = new HashMap<>();
                    getHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
                    requests.add(new Recorded(uri.toString(), headers, getBodyAsString(StandardCharsets.UTF_8)));
                    Response next = script.pollFirst();
                    if (next == null) {
                        next = new Response(200, "{\"id\":\"stub-" + UUID.randomUUID() + "\"}", Map.of());
                    }
                    var response = new MockClientHttpResponse(next.body().getBytes(StandardCharsets.UTF_8),
                            HttpStatusCode.valueOf(next.status()));
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    next.headers().forEach(response.getHeaders()::add);
                    return response;
                }
            };
        }
    }
}
