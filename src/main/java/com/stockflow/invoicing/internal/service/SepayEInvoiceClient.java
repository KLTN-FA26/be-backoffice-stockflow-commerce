package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The HTTP side of SePay eInvoice's API v1, and nothing about what an answer means ({@link SepayEInvoiceAnswers}
 * reads them). From SePay's developer documentation (developer.sepay.vn, "eInvoice API"):
 *
 * <pre>
 * POST /v1/token                                  Basic client_id:client_secret → data.access_token, expires_in (24 h)
 * GET  /v1/provider-accounts                      the account's invoicing registrations
 * POST /v1/invoices/create                        an invoice; is_draft false issues it → data.tracking_code
 * POST /v1/invoices/adjust, /v1/invoices/replace  a correction of an issued invoice
 * GET  /v1/invoices/create/check/{tracking_code}  Pending, Success or Failed, with a message - for a create, an
 *                                                adjust and a replace alike
 * GET  /v1/invoices/{reference_code}              the invoice under our reference; 404 when SePay never recorded one
 * GET  /v1/invoices/{reference_code}/download?type=pdf|xml   data.content, base64
 * GET  /v1/provider-accounts/{id}                 one registration, with its templates and series
 * GET  /v1/usage                                  data.quota_remaining: the invoices left in the plan
 * </pre>
 *
 * <p>Every call but the token carries {@code Authorization: Bearer}. The token is kept in memory until shortly before it
 * expires; a call answered 401 asks for a new one and is made once more.</p>
 *
 * <p><b>Nothing secret is logged</b>: not the secret, not the token, not a body.</p>
 */
@Component
class SepayEInvoiceClient {

    static final String SERVICE = "sepay-einvoice";
    /** A token is given up this long before the expiry SePay stated, so none expires on its way. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);
    /** Used when the token's answer states no lifetime: well inside the 24 hours SePay documents. */
    private static final Duration UNSTATED_LIFETIME = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(SepayEInvoiceClient.class);

    /** What came back: the HTTP status and the body as JSON; {@code json} is null when the body was not JSON. */
    record Answer(int status, JsonNode json) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    private final SepayEInvoiceProperties properties;
    private final RestClient api;
    private final RestClient files;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicReference<Token> token = new AtomicReference<>();

    /**
     * @throws IllegalStateException when one call can take as long as the quiet period before a resend, or the lease on
     *                               talking to the provider: a send still waiting for its answer could then be
     *                               overlapped by a second one
     */
    SepayEInvoiceClient(RestClientFactory clients, HttpClientProperties http, SepayEInvoiceProperties properties,
                        ObjectMapper json, Clock clock) {
        Duration longest = longestCall(http.connectTimeout(), properties.readTimeout());
        if (properties.resubmitQuietPeriod().compareTo(longest) <= 0
                || EInvoiceIssuance.PROVIDER_LEASE.compareTo(longest) <= 0) {
            throw new IllegalStateException(("stockflow.invoicing.sepay: one call can take up to %s (four round trips "
                    + "of connect and read timeout); the resubmit quiet period (%s) and the provider lease (%s) must "
                    + "both be longer").formatted(longest, properties.resubmitQuietPeriod(),
                    EInvoiceIssuance.PROVIDER_LEASE));
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(http.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL).build());
        requests.setReadTimeout(properties.readTimeout());
        this.properties = properties;
        this.api = clients.forService(SERVICE, properties.baseUrl()).requestFactory(requests).build();
        this.files = clients.forService(SERVICE + "-files", properties.baseUrl()).requestFactory(requests).build();
        this.json = json;
        this.clock = clock;
    }

    /** The token and the request, and both again when the request is answered 401: four round trips. */
    static Duration longestCall(Duration connectTimeout, Duration readTimeout) {
        return connectTimeout.plus(readTimeout).multipliedBy(4);
    }

    Answer providerAccounts() {
        return authorised("GET", "/v1/provider-accounts?per_page=100", null);
    }

    Answer providerAccount(String id) {
        return authorised("GET", "/v1/provider-accounts/" + segment(id), null);
    }

    Answer usage() {
        return authorised("GET", "/v1/usage", null);
    }

    /**
     * Where a submission SePay took in stands, asked at {@code /v1/invoices/create/check/{tracking_code}} whatever the
     * submission was (WF-05 BE-61). SePay's tracking page gives that one path as "dùng chung cho cả ba luồng", and the
     * {@code tracking_url} an adjust or a replace answers with points to it; {@code /adjust/check/} and
     * {@code /replace/check/} answer 404 with an empty body every time (sandbox run X-4, 23/11/2026, 20 calls each).
     * Asked there, an adjustment's or a replacement's tracking said nothing, so a {@code Failed} was never seen and a
     * correction SePay was still processing - its look-up not showing it yet - was taken for "never received" and sent
     * again, which SePay's error page warns can make a second invoice.
     *
     * @param reference {@code <create|adjust|replace>/<tracking_code>}, as {@link SepayEInvoiceAnswers#trackingReference}
     *                  wrote it; the kind is kept in the call log for people reading it, and rows written before this
     *                  fix name {@code adjust} or {@code replace} - both are still read
     * @throws IllegalArgumentException for anything else: nothing read back from a log goes into a path unchecked
     */
    Answer tracking(String reference) {
        int slash = reference == null ? -1 : reference.indexOf('/');
        String kind = slash < 0 ? "" : reference.substring(0, slash);
        String code = slash < 0 ? "" : reference.substring(slash + 1);
        if (!java.util.Set.of("create", "adjust", "replace").contains(kind) || !code.matches("[0-9A-Za-z-]{1,64}")) {
            throw new IllegalArgumentException("Not a SePay tracking reference");
        }
        return authorised("GET", "/v1/invoices/create/check/" + segment(code), null);
    }

    Answer create(String body) {
        return authorised("POST", "/v1/invoices/create", body);
    }

    Answer adjust(String body) {
        return authorised("POST", "/v1/invoices/adjust", body);
    }

    Answer replace(String body) {
        return authorised("POST", "/v1/invoices/replace", body);
    }

    Answer invoice(String referenceCode) {
        return authorised("GET", "/v1/invoices/" + segment(referenceCode), null);
    }

    /** @param type {@code pdf} or {@code xml} */
    Answer download(String referenceCode, String type) {
        return authorised("GET", "/v1/invoices/" + segment(referenceCode) + "/download?type=" + type, null);
    }

    /**
     * A file at an address SePay's answer gave ({@code pdf_url}, {@code xml_url}), fetched without the token: it is
     * the invoicing portal's, not SePay's API. Only {@code https}.
     *
     * @return the bytes; null when the address does not hand a file over
     */
    byte[] fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return null;
        }
        try {
            return files.get().uri(uri).exchange((req, res) -> res.getStatusCode().is2xxSuccessful()
                    ? res.getBody().readAllBytes() : null);
        } catch (RestClientException noAnswer) {
            log.warn("SePay eInvoice's file address gave no answer: {}", noAnswer.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ the calls

    /** A call with the token; on 401 a new token is asked for and the call made once more. */
    private Answer authorised(String method, String path, String body) {
        Token current = token();
        Answer answer = call(method, path, body, current.value());
        if (answer.status() == 401) {
            token.compareAndSet(current, null);
            answer = call(method, path, body, token().value());
        }
        return answer;
    }

    /** The kept token, or a new one. */
    private Token token() {
        Token kept = token.get();
        if (kept != null && clock.instant().isBefore(kept.expiresAt())) {
            return kept;
        }
        Token fresh = signIn();
        token.set(fresh);
        return fresh;
    }

    private Token signIn() {
        if (!properties.configured()) {
            throw new ProviderUnavailableException("SePay eInvoice has no client id and secret configured");
        }
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        Answer answer = send("POST", "/v1/token", null, "Basic " + basic);
        JsonNode data = answer.json() == null ? null : answer.json().get("data");
        JsonNode value = data == null ? null : data.get("access_token");
        if (answer.status() >= 400 || value == null || !value.isTextual() || value.asText().isBlank()) {
            // The key is wrong, revoked, or for the other environment: nothing about any invoice is learnt.
            log.warn("SePay eInvoice did not sign the client in (HTTP {})", answer.status());
            throw new ProviderUnavailableException("SePay eInvoice did not sign the client in");
        }
        JsonNode expiresIn = data.get("expires_in");
        Duration lifetime = expiresIn != null && expiresIn.canConvertToLong()
                && Duration.ofSeconds(expiresIn.asLong()).compareTo(EXPIRY_MARGIN.multipliedBy(2)) > 0
                ? Duration.ofSeconds(expiresIn.asLong()).minus(EXPIRY_MARGIN) : UNSTATED_LIFETIME;
        return new Token(value.asText(), clock.instant().plus(lifetime));
    }

    private Answer call(String method, String path, String body, String bearer) {
        return send(method, path, body, "Bearer " + bearer);
    }

    private Answer send(String method, String path, String body, String authorization) {
        try {
            RestClient.RequestHeadersSpec<?> request;
            if ("GET".equals(method)) {
                request = api.get().uri(path);
            } else {
                RestClient.RequestBodySpec post = api.post().uri(path);
                request = body == null ? post : post.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange((req, res) -> new Answer(res.getStatusCode().value(),
                            parse(res.getBody().readAllBytes())));
        } catch (RestClientException noAnswer) {
            // The class only: a message can quote the request line, and nothing of a request belongs in a log.
            log.warn("SePay eInvoice gave no answer: {}", noAnswer.getClass().getSimpleName());
            throw new ProviderUnavailableException("SePay eInvoice did not answer");
        }
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return json.readTree(bytes);
        } catch (IOException notJson) {
            return null;
        }
    }

    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
