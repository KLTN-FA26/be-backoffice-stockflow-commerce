package com.stockflow.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.CurrentUserProvider;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

class IdempotencyFilterTest {
    /**
     * Review of #195 (N3): a provider webhook carries its own idempotency and caps its body as it reads it, so this
     * filter neither buffers nor stores it - even with an Idempotency-Key - and the handler reads the stream itself.
     */
    @Test
    void aWebhookIsPassedThroughUnbufferedAndUnstored() throws Exception {
        var store = mock(IdempotencyStore.class);
        var users = mock(CurrentUserProvider.class);
        var request = new MockHttpServletRequest("POST", "/api/v1/notifications/webhooks/resend");
        request.addHeader("Idempotency-Key", "a-client-header-on-a-webhook");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        var handled = new java.util.concurrent.atomic.AtomicReference<jakarta.servlet.ServletRequest>();
        new IdempotencyFilter(store, users, new ObjectMapper(), Clock.systemUTC())
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> handled.set(req));
        assertThat(handled.get()).as("the request as it came, not a buffered copy").isSameAs(request);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void replayPreservesVietnameseJsonBytesAndDoesNotInvokeTheHandler() throws Exception {
        var store = mock(IdempotencyStore.class);
        var users = mock(CurrentUserProvider.class);
        when(users.current()).thenReturn(Optional.empty());
        var request = new MockHttpServletRequest("POST", "/api/v1/purchase-orders");
        request.addHeader("Idempotency-Key", "po-vietnamese-replay");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        String body = "{\"name\":\"Nhà cung cấp\",\"description\":\"Ghế kiểm thử\"}";
        String fingerprint =
                IdempotencyKeys.fingerprint(
                        "POST", request.getRequestURI(), request.getContentAsByteArray());
        when(store.beginIfAbsent(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        Optional.of(
                                new IdempotencyRecord(
                                        "po-vietnamese-replay",
                                        "anonymous",
                                        fingerprint,
                                        IdempotencyRecord.Status.COMPLETED,
                                        201,
                                        body,
                                        Instant.EPOCH,
                                        Instant.MAX)));
        var response = new MockHttpServletResponse();
        new IdempotencyFilter(store, users, new ObjectMapper(), Clock.systemUTC())
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            throw new AssertionError("A replay must not execute the handler");
                        });
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsByteArray())
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(
                        new ObjectMapper()
                                .readTree(response.getContentAsByteArray())
                                .get("name")
                                .asText())
                .isEqualTo("Nhà cung cấp");
    }
}
