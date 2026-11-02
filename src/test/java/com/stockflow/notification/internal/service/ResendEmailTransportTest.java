package com.stockflow.notification.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Every answer Resend documents, against a mock server: never the real API. What each status means for a retry is the
 * transport's contract with the deliveries above it.
 */
class ResendEmailTransportTest {

    private static final String KEY = "re_TEST_secret_key_123";
    private static final String BASE = "https://api.resend.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC);

    private final Logger logs = (Logger) LoggerFactory.getLogger("com.stockflow");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previous;
    private MockRestServiceServer server;

    @BeforeEach
    void captureLogs() {
        previous = logs.getLevel();
        logs.setLevel(Level.TRACE);
        appender.start();
        logs.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logs.detachAppender(appender);
        logs.setLevel(previous);
    }

    static EmailProperties properties(String from, String key, int requestsPerSecond) {
        return new EmailProperties(EmailProvider.RESEND, from, null, Locale.forLanguageTag("vi"), "StockFlow",
                new EmailProperties.Customer(true, 10, Duration.ofMinutes(1), Duration.ofHours(6)),
                new EmailProperties.Resend(key, BASE, null, Duration.ofMinutes(5), requestsPerSecond, 4));
    }

    private ResendEmailTransport transport(int requestsPerSecond) {
        var factory = mock(RestClientFactory.class);
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        when(factory.forService(ResendEmailTransport.SERVICE, BASE)).thenReturn(builder);
        return new ResendEmailTransport(factory, properties("StockFlow <no-reply@stockflow.vn>", KEY,
                requestsPerSecond), CLOCK);
    }

    private static Email email(String to) {
        return new Email("StockFlow <no-reply@stockflow.vn>", List.of(to), null, "Đơn hàng SO-1 đã được tiếp nhận",
                "Secret plain body", "<p>Secret html body</p>", "customer-mail:order.placed:o-1", "order_placed");
    }

    private String logged() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private EmailDeliveryException refusedWith(HttpStatus status, String name, HttpHeaders headers) {
        var transport = transport(1000);
        server.expect(requestTo(BASE + "/emails")).andRespond(withStatus(status).headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"statusCode\":" + status.value() + ",\"name\":\"" + name
                        + "\",\"message\":\"The to field has buyer@cup-maker.vn which is invalid\"}"));
        try {
            transport.send(email("buyer@cup-maker.vn"));
        } catch (EmailDeliveryException refused) {
            server.verify();
            return refused;
        }
        throw new AssertionError("Resend answered " + status + " and the transport accepted it");
    }

    @Test
    @DisplayName("accepted: POST /emails with the key, the idempotency key and Resend's body; the receipt carries its id")
    void accepted() {
        var transport = transport(1000);
        server.expect(requestTo(BASE + "/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andExpect(header("Idempotency-Key", "customer-mail:order.placed:o-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("StockFlow <no-reply@stockflow.vn>"))
                .andExpect(jsonPath("$.to[0]").value("buyer@cup-maker.vn"))
                .andExpect(jsonPath("$.subject").value("Đơn hàng SO-1 đã được tiếp nhận"))
                .andExpect(jsonPath("$.text").value("Secret plain body"))
                .andExpect(jsonPath("$.html").value("<p>Secret html body</p>"))
                .andExpect(jsonPath("$.reply_to").doesNotExist())
                .andExpect(jsonPath("$.tags[0].name").value("category"))
                .andExpect(jsonPath("$.tags[0].value").value("order_placed"))
                .andRespond(withSuccess("{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}",
                        MediaType.APPLICATION_JSON));

        var receipt = transport.send(email("buyer@cup-maker.vn"));

        server.verify();
        assertThat(receipt.provider()).isEqualTo(EmailProvider.RESEND);
        assertThat(receipt.messageId()).isEqualTo("4ef9a417-02e9-4d39-ad75-9611e0fcc33c");
    }

    @Test
    @DisplayName("401 and 403: the key or the sender domain is wrong - misconfigured, not retried, ERROR without the key")
    void badKey() {
        EmailDeliveryException unauthorized = refusedWith(HttpStatus.UNAUTHORIZED, "missing_api_key", new HttpHeaders());
        assertThat(unauthorized.kind()).isEqualTo(EmailDeliveryException.Kind.MISCONFIGURED);
        assertThat(unauthorized.code()).isEqualTo("RESEND_UNAUTHORIZED");
        EmailDeliveryException forbidden = refusedWith(HttpStatus.FORBIDDEN, "validation_error", new HttpHeaders());
        assertThat(forbidden.kind()).isEqualTo(EmailDeliveryException.Kind.MISCONFIGURED);
        assertThat(forbidden.code()).isEqualTo("RESEND_FORBIDDEN");
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(logged()).doesNotContain(KEY, "re_TEST");
    }

    @Test
    @DisplayName("422: this message is refused (an invalid recipient) - recorded as such, never retried")
    void invalidRecipient() {
        EmailDeliveryException refused = refusedWith(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                new HttpHeaders());
        assertThat(refused.kind()).isEqualTo(EmailDeliveryException.Kind.REJECTED);
        assertThat(refused.code()).isEqualTo("RESEND_REJECTED_422");
        assertThat(logged()).as("Resend's message quotes the address; it is never logged")
                .doesNotContain("buyer@cup-maker.vn", "which is invalid");
    }

    @Test
    @DisplayName("422 invalid_from_address is the configuration's fault: misconfigured")
    void invalidSender() {
        EmailDeliveryException refused = refusedWith(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_from_address",
                new HttpHeaders());
        assertThat(refused.kind()).isEqualTo(EmailDeliveryException.Kind.MISCONFIGURED);
        assertThat(refused.code()).isEqualTo("RESEND_SENDER_REJECTED");
    }

    @Test
    @DisplayName("429: rate limit or quota - retried, not before Retry-After (seconds or Resend's ratelimit-reset)")
    void rateLimited() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        EmailDeliveryException limited = refusedWith(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", headers);
        assertThat(limited.retryable()).isTrue();
        assertThat(limited.code()).isEqualTo("RESEND_RATE_LIMITED");
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(30));

        var reset = new HttpHeaders();
        reset.set("ratelimit-reset", "2");
        assertThat(refusedWith(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", reset).retryAfter())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(refusedWith(HttpStatus.TOO_MANY_REQUESTS, "daily_quota_exceeded", new HttpHeaders()).retryAfter())
                .isNull();
    }

    @Test
    @DisplayName("Retry-After as an HTTP date is read against the clock; a date in the past asks for nothing")
    void retryAfterAsDate() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "Sat, 24 Oct 2026 08:02:00 GMT");
        assertThat(ResendEmailTransport.retryAfter(headers, CLOCK.instant())).isEqualTo(Duration.ofMinutes(2));
        headers.set(HttpHeaders.RETRY_AFTER, "Sat, 24 Oct 2026 07:00:00 GMT");
        assertThat(ResendEmailTransport.retryAfter(headers, CLOCK.instant())).isNull();
        headers.set(HttpHeaders.RETRY_AFTER, "999999999");
        assertThat(ResendEmailTransport.retryAfter(headers, CLOCK.instant())).isEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("5xx: Resend failed - retried")
    void serverError() {
        EmailDeliveryException failed = refusedWith(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error",
                new HttpHeaders());
        assertThat(failed.retryable()).isTrue();
        assertThat(failed.code()).isEqualTo("RESEND_UNAVAILABLE");
    }

    @Test
    @DisplayName("409: a key in flight is retried; a key reused with another message is never sent again")
    void idempotencyConflicts() {
        assertThat(refusedWith(HttpStatus.CONFLICT, "concurrent_idempotent_requests", new HttpHeaders()).retryable())
                .isTrue();
        EmailDeliveryException reused = refusedWith(HttpStatus.CONFLICT, "invalid_idempotent_request",
                new HttpHeaders());
        assertThat(reused.kind()).isEqualTo(EmailDeliveryException.Kind.REJECTED);
        assertThat(reused.code()).isEqualTo("RESEND_IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("an answer that is not JSON is judged by its status alone")
    void unreadableErrorBody() {
        var transport = transport(1000);
        server.expect(requestTo(BASE + "/emails")).andRespond(withRawStatus(502).body("<html>Bad gateway</html>"));
        assertThatThrownBy(() -> transport.send(email("buyer@cup-maker.vn")))
                .isInstanceOfSatisfying(EmailDeliveryException.class, failed -> {
                    assertThat(failed.retryable()).isTrue();
                    assertThat(failed.code()).isEqualTo("RESEND_UNAVAILABLE");
                });
    }

    @Test
    @DisplayName("a timeout or no connection is retried, with its own code")
    void unreachable() {
        var timedOut = transport(1000);
        server.expect(requestTo(BASE + "/emails")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });
        assertThatThrownBy(() -> timedOut.send(email("buyer@cup-maker.vn")))
                .isInstanceOfSatisfying(EmailDeliveryException.class, failed -> {
                    assertThat(failed.retryable()).isTrue();
                    assertThat(failed.code()).isEqualTo("RESEND_TIMEOUT");
                });
        var refused = transport(1000);
        server.expect(requestTo(BASE + "/emails")).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });
        assertThatThrownBy(() -> refused.send(email("buyer@cup-maker.vn")))
                .isInstanceOfSatisfying(EmailDeliveryException.class, failed -> {
                    assertThat(failed.retryable()).isTrue();
                    assertThat(failed.code()).isEqualTo("RESEND_UNREACHABLE");
                });
    }

    @Test
    @DisplayName("a recipient on a reserved domain (demo data's example.com) never reaches Resend")
    void reservedDomainNeverSent() {
        var transport = transport(1000);
        assertThatThrownBy(() -> transport.send(email("customer-1@example.com")))
                .isInstanceOfSatisfying(EmailDeliveryException.class, refused -> {
                    assertThat(refused.kind()).isEqualTo(EmailDeliveryException.Kind.REJECTED);
                    assertThat(refused.code()).isEqualTo("RECIPIENT_RESERVED_DOMAIN");
                });
        server.verify();
    }

    @Test
    @DisplayName("staff mail to a reserved and a real address goes to the real one only")
    void reservedRecipientsAreDropped() {
        var transport = transport(1000);
        server.expect(requestTo(BASE + "/emails"))
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.to[0]").value("planner@cup-maker.vn"))
                .andRespond(withSuccess("{\"id\":\"e-2\"}", MediaType.APPLICATION_JSON));
        transport.send(new Email("no-reply@stockflow.vn", List.of("demo@example.com", "planner@cup-maker.vn"), null,
                "s", "t", null, null, null));
        server.verify();
    }

    @Test
    @DisplayName("no key, no sender, or a sender no one can verify: the application does not start")
    void failsFastOnConfiguration() {
        var factory = mock(RestClientFactory.class);
        assertThatThrownBy(() -> new ResendEmailTransport(factory,
                properties("StockFlow <no-reply@stockflow.vn>", null, 2), CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendEmailTransport(factory, properties("", KEY, 2), CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_FROM");
        assertThatThrownBy(() -> new ResendEmailTransport(factory, properties("noreply@stockflow.local", KEY, 2),
                CLOCK)).isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_FROM");
        assertThatThrownBy(() -> new ResendEmailTransport(factory, properties("StockFlow <a@example.com>", KEY, 2),
                CLOCK)).isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_FROM");
        assertThatThrownBy(() -> new ResendEmailTransport(factory, properties("not an address", KEY, 2), CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_FROM");
    }

    @Test
    @DisplayName("the key, subjects, bodies and addresses never reach the log, on success or failure")
    void nothingSensitiveIsLogged() {
        var transport = transport(1000);
        server.expect(requestTo(BASE + "/emails")).andRespond(withSuccess("{\"id\":\"e-3\"}",
                MediaType.APPLICATION_JSON));
        transport.send(email("buyer@cup-maker.vn"));
        refusedWith(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", new HttpHeaders());
        assertThat(logged()).doesNotContain(KEY, "Secret", "buyer@cup-maker.vn", "SO-1");
    }

    @Test
    @DisplayName("each instance keeps to its requests per second instead of bursting into 429s")
    void paced() {
        var transport = transport(5);
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(BASE + "/emails")).andRespond(withSuccess("{\"id\":\"e-" + i + "\"}",
                    MediaType.APPLICATION_JSON));
        }
        long started = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            transport.send(email("buyer@cup-maker.vn"));
        }
        // Three sends at five per second: the third waits for its slot, 2 x 200 ms after the first.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(350));
        server.verify();
    }
}
