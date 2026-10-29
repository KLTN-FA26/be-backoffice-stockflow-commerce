package com.stockflow.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.CurrentUserProvider;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stream of server-sent events is passed through untouched (LOOP-6): wrapped, it would reach
 * the client empty and be stored as an empty 200 for the whole retention window. Every other
 * request with a key is still guarded.
 */
class IdempotencyFilterStreamTest {

    private static final String KEY = "stream-key-0001";

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final IdempotencyFilter filter =
            new IdempotencyFilter(store, users, new ObjectMapper(), Clock.systemUTC());

    @Test
    @DisplayName("a request accepting text/event-stream reaches the handler unwrapped, claims "
            + "nothing")
    void anEventStreamIsLeftAlone() throws Exception {
        MockHttpServletRequest request =
                post("/api/v1/assistant/conversations/c1/messages/stream", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(res));

        assertThat(seen.get()).as("the handler writes to the real response").isSameAs(response);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a stream is known by its path too: a client may accept anything and still "
            + "reach it")
    void aStreamPathIsLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> seen = new AtomicReference<>();

        filter.doFilter(post("/api/v1/assistant/conversations/c1/messages/stream", "*/*"), response,
                (req, res) -> seen.set(res));
        filter.doFilter(post("/api/v1/assistant/conversations/c1/messages/stream", null), response,
                (req, res) -> seen.set(res));

        assertThat(seen.get()).isSameAs(response);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a path that merely ends in the letters, and a JSON request, are still guarded "
            + "and stored")
    void otherRequestsAreStillGuarded() throws Exception {
        when(users.current()).thenReturn(Optional.empty());
        when(store.beginIfAbsent(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/api/v1/shipping/upstream", "application/json"), response,
                (req, res) -> {
                    ((HttpServletResponse) res).setStatus(201);
                    res.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
                });

        verify(store).complete(eq(KEY), eq("anonymous"), eq(201), eq("{}"), any());
        assertThat(response.getContentAsString()).isEqualTo("{}");
    }

    @Test
    @DisplayName("a JSON request that also accepts an event stream keeps its protection")
    void acceptingJsonTooIsStillGuarded() throws Exception {
        when(users.current()).thenReturn(Optional.empty());
        when(store.beginIfAbsent(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        AtomicReference<ServletResponse> seen = new AtomicReference<>();

        filter.doFilter(post("/api/v1/purchase-orders", "application/json, text/event-stream"),
                new MockHttpServletResponse(), (req, res) -> seen.set(res));

        verify(store).beginIfAbsent(eq(KEY), eq("anonymous"), anyString(), any(), any());
        assertThat(seen.get()).as("the response is wrapped so it can be stored")
                .isNotInstanceOf(MockHttpServletResponse.class);
    }

    @Test
    @DisplayName("a handler that answers asynchronously is not stored, and its claim is released")
    void anAsynchronousAnswerIsNotStored() throws Exception {
        when(users.current()).thenReturn(Optional.empty());
        when(store.beginIfAbsent(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        MockHttpServletRequest request = post("/api/v1/purchase-orders", "application/json");
        request.setAsyncSupported(true);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync());

        verify(store).abandon(KEY, "anonymous");
        verify(store, never()).complete(anyString(), anyString(), anyInt(), any(), any());
    }

    private static MockHttpServletRequest post(String path, String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader(IdempotencyKeys.HEADER, KEY);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        request.setContentType("application/json");
        request.setContent("{\"text\":\"Xin chào\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
