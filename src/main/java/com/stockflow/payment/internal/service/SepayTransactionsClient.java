package com.stockflow.payment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.payment.internal.domain.BankTransfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The HTTP side of SePay's API v2 transaction list (developer.sepay.vn, "API v2 / Giao dịch / Danh sách"), for the
 * nightly catch-up; nothing about what a transfer means.
 *
 * <pre>
 * GET {base}/transactions?transfer_type=in&amp;transaction_date_from=..&amp;transaction_date_to=..
 *                         &amp;transaction_date_sort=asc&amp;timestamp_format=iso8601&amp;page=N&amp;per_page=100
 * Authorization: Bearer &lt;token&gt;
 * → { "status": "success", "data": [ { "id": "&lt;uuid&gt;", "transaction_date": "...", "account_number": "...",
 *       "transfer_type": "in", "amount_in": 500000, "transaction_content": "...", "reference_number": "...",
 *       "code": null, ... } ], "meta": { "pagination": { "has_more": true, ... } } }
 * </pre>
 *
 * <p>The live base is {@code https://userapi.sepay.vn/v2}; a token made in SePay's Test mode works only on
 * {@code https://userapi-sandbox.sepay.vn/v2}, which is why v2 and not v1 ({@code my.sepay.vn/userapi}, which no Test
 * token reaches). The API allows three calls a second; a run reads a page at a time with a pause between. A v2 id is a
 * UUID, not the webhook's number, so a transfer is told apart from the webhook's copy by its bank reference
 * ({@link BankTransfer#dedupeKey}).</p>
 *
 * <p><b>Nothing secret is logged</b>: not the token, not a body.</p>
 */
@Component
class SepayTransactionsClient {

    static final String SERVICE = "sepay-bank-feed";
    static final int PER_PAGE = 100;
    private static final Logger log = LoggerFactory.getLogger(SepayTransactionsClient.class);

    /** One page of incoming transfers, and whether another follows. */
    record Page(List<BankTransfer> transfers, boolean hasMore) {
    }

    /** SePay did not answer, or answered something other than a page. */
    static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    private final BankFeedProperties.Api config;
    private final RestClient api;
    private final ObjectMapper json;

    SepayTransactionsClient(RestClientFactory clients, HttpClientProperties http, BankFeedProperties properties,
                            ObjectMapper json) {
        this.config = properties.api();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(http.connectTimeout()).build());
        requests.setReadTimeout(config.readTimeout());
        this.api = clients.forService(SERVICE, config.baseUrl()).requestFactory(requests).build();
        this.json = json;
    }

    boolean configured() {
        return config.configured();
    }

    /**
     * Page {@code page} (from 1) of the incoming transfers booked from {@code from} to {@code to}, oldest first.
     *
     * @throws Unavailable when SePay did not answer with a page
     */
    Page incoming(Instant from, Instant to, int page) {
        String body;
        try {
            // Built by the client's own URI factory, so the dates' spaces are encoded once.
            body = api.get().uri(b -> b.path("/transactions")
                            .queryParam("transfer_type", "in")
                            .queryParam("transaction_date_from", vietnam(from))
                            .queryParam("transaction_date_to", vietnam(to))
                            .queryParam("transaction_date_sort", "asc")
                            .queryParam("timestamp_format", "iso8601")
                            .queryParam("page", page)
                            .queryParam("per_page", PER_PAGE)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.token().strip())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException failed) {
            throw new Unavailable("SePay's transaction list did not answer (" + failed.getClass().getSimpleName()
                    + ")");
        }
        return page(body);
    }

    /** One page of SePay's answer, the incoming transfers only; a row it cannot read is logged and left out. */
    Page page(String body) {
        JsonNode root;
        try {
            root = body == null ? null : json.readTree(body);
        } catch (IOException notJson) {
            throw new Unavailable("SePay's transaction list answered something other than JSON");
        }
        if (root == null || !root.path("data").isArray()) {
            throw new Unavailable("SePay's transaction list answered without data");
        }
        List<BankTransfer> transfers = new ArrayList<>();
        for (JsonNode row : root.get("data")) {
            transfer(row).ifPresent(transfers::add);
        }
        boolean more = root.path("meta").path("pagination").path("has_more").asBoolean(false);
        return new Page(transfers, more);
    }

    private static Optional<BankTransfer> transfer(JsonNode row) {
        String id = SepayWebhook.text(row, "id");
        if (id == null || id.isBlank() || !"in".equalsIgnoreCase(SepayWebhook.text(row, "transfer_type"))) {
            return Optional.empty();
        }
        var amount = SepayWebhook.amount(row.get("amount_in"));
        Optional<Instant> at = when(SepayWebhook.text(row, "transaction_date"));
        if (amount == null || at.isEmpty()) {
            log.warn("SePay transaction {} left out: no amount in or no date", id);
            return Optional.empty();
        }
        try {
            return Optional.of(new BankTransfer("v2:" + id.strip(), SepayWebhook.text(row, "account_number"), amount,
                    SepayWebhook.text(row, "transaction_content"), SepayWebhook.text(row, "reference_number"),
                    SepayWebhook.text(row, "code"), at.get(), row.toString()));
        } catch (IllegalArgumentException invalid) {
            log.warn("SePay transaction {} left out: {}", id, invalid.getMessage());
            return Optional.empty();
        }
    }

    /** ISO 8601 with its offset ({@code timestamp_format=iso8601}), or SePay's plain Vietnam time. */
    private static Optional<Instant> when(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.strip()).toInstant());
        } catch (DateTimeParseException notIso) {
            return SepayWebhook.vietnamTime(value);
        }
    }

    private static String vietnam(Instant at) {
        return SepayWebhook.DATE_TIME.format(at.atZone(InstallmentService.BUSINESS_ZONE));
    }
}
