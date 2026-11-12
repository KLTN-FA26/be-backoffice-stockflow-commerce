package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.storage.Checksums;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP side of Viettel SInvoice's API v2, and nothing about what an answer means ({@link ViettelSInvoiceAnswers}
 * reads them). The calls are the ones Viettel's public description ("Tài liệu mô tả webservice hóa đơn điện tử",
 * SInvoice V2) and Odoo's {@code l10n_vn_edi_viettel} both show:
 *
 * <pre>
 * POST /auth/login                                                        {"username", "password"} → access_token, expires_in
 * POST {api}/InvoiceAPI/InvoiceWS/createInvoice/{supplierTaxCode}          JSON
 * POST {api}/InvoiceAPI/InvoiceWS/searchInvoiceByTransactionUuid           form: supplierTaxCode, transactionUuid
 * POST {api}/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile        JSON: supplierTaxCode, templateCode, invoiceNo,
 *                                                                          transactionUuid, fileType (ZIP | PDF)
 * {api} = /services/einvoiceapplication/api
 * </pre>
 *
 * <p>Every call but the log-in carries the token as the cookie {@code access_token}. A token is kept in memory until
 * shortly before it expires; a call answered 401 asks for a new one and is made once more.</p>
 *
 * <p><b>Nothing secret is logged</b>: not the password, not the token, not a body. The log line of a call is its path
 * (the shared interceptor drops the query string), and the form of the look-up goes in the body, never in the URL.</p>
 */
@Component
class ViettelSInvoiceClient {

    static final String SERVICE = "viettel-sinvoice";
    private static final String API = "/services/einvoiceapplication/api";
    /** A token is given up this long before the expiry Viettel stated, so none expires on its way. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);
    /** Used when the log-in states no lifetime: the five minutes Odoo's module records for SInvoice tokens, less a margin. */
    private static final Duration UNSTATED_LIFETIME = Duration.ofMinutes(4);

    private static final Logger log = LoggerFactory.getLogger(ViettelSInvoiceClient.class);

    /** The account a call is made with; the password is clear only here, for the length of a call. */
    record Account(boolean production, String username, String password) {

        @Override
        public String toString() {
            // A record prints its components: this one must never print the password.
            return "Account[" + (production ? "PRODUCTION" : "SANDBOX") + "]";
        }
    }

    /** What came back: the HTTP status and the body as JSON; {@code json} is null when the body was not JSON. */
    record Answer(int status, JsonNode json) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    private final RestClient sandbox;
    private final RestClient production;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    ViettelSInvoiceClient(RestClientFactory clients, HttpClientProperties http, ViettelSInvoiceProperties properties,
                          ObjectMapper json, Clock clock) {
        this.sandbox = client(clients, http, properties.sandboxUrl(), properties.readTimeout());
        this.production = client(clients, http, properties.productionUrl(), properties.readTimeout());
        this.json = json;
        this.clock = clock;
    }

    /** The shared client with Viettel's own read timeout: its answer to a submission is waited for, not cut at 10 s. */
    private static RestClient client(RestClientFactory clients, HttpClientProperties http, String baseUrl,
                                     Duration readTimeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(http.connectTimeout()).build());
        requests.setReadTimeout(readTimeout);
        return clients.forService(SERVICE, baseUrl).requestFactory(requests).build();
    }

    Answer createInvoice(Account account, String supplierTaxCode, String body) {
        return authorised(account, API + "/InvoiceAPI/InvoiceWS/createInvoice/"
                + UriUtils.encodePathSegment(supplierTaxCode, StandardCharsets.UTF_8), MediaType.APPLICATION_JSON, body);
    }

    Answer searchByTransactionUuid(Account account, String supplierTaxCode, String transactionUuid) {
        String form = "supplierTaxCode=" + UriUtils.encode(supplierTaxCode, StandardCharsets.UTF_8)
                + "&transactionUuid=" + UriUtils.encode(transactionUuid, StandardCharsets.UTF_8);
        return authorised(account, API + "/InvoiceAPI/InvoiceWS/searchInvoiceByTransactionUuid",
                MediaType.APPLICATION_FORM_URLENCODED, form);
    }

    /** @param fileType {@code ZIP} (the signed XML, zipped) or {@code PDF} */
    Answer representationFile(Account account, String supplierTaxCode, String templateCode, String invoiceNo,
                              String transactionUuid, String fileType) {
        ObjectNode body = json.createObjectNode();
        body.put("supplierTaxCode", supplierTaxCode);
        body.put("templateCode", templateCode);
        body.put("invoiceNo", invoiceNo);
        body.put("transactionUuid", transactionUuid);
        body.put("fileType", fileType);
        return authorised(account, API + "/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile",
                MediaType.APPLICATION_JSON, body.toString());
    }

    // ------------------------------------------------------------------ the calls

    /** A call with the account's token; on 401 a new token is asked for and the call made once more. */
    private Answer authorised(Account account, String path, MediaType contentType, String body) {
        String key = tokenKey(account);
        Token token = tokenFor(key, account);
        Answer answer = post(account, path, contentType, body, token.value());
        if (answer.status() == 401) {
            tokens.remove(key, token);
            token = tokenFor(key, account);
            answer = post(account, path, contentType, body, token.value());
        }
        return answer;
    }

    /** The kept token, or a new one (not inside the map's compute: the log-in can take seconds). */
    private Token tokenFor(String key, Account account) {
        Token kept = tokens.get(key);
        if (kept != null && clock.instant().isBefore(kept.expiresAt())) {
            return kept;
        }
        Token fresh = logIn(account);
        tokens.put(key, fresh);
        return fresh;
    }

    private Token logIn(Account account) {
        ObjectNode body = json.createObjectNode();
        body.put("username", account.username());
        body.put("password", account.password());
        Answer answer = post(account, "/auth/login", MediaType.APPLICATION_JSON, body.toString(), null);
        JsonNode token = answer.json() == null ? null : answer.json().get("access_token");
        if (answer.status() >= 400 || token == null || !token.isTextual() || token.asText().isBlank()) {
            // The account is wrong, locked or not allowed from this address: nothing about any invoice is learnt.
            log.warn("Viettel SInvoice did not sign the account in (HTTP {})", answer.status());
            throw new ProviderUnavailableException("Viettel SInvoice did not sign the account in");
        }
        JsonNode expiresIn = answer.json().get("expires_in");
        Duration lifetime = expiresIn != null && expiresIn.canConvertToLong() && expiresIn.asLong() > 0
                ? Duration.ofSeconds(expiresIn.asLong()).minus(EXPIRY_MARGIN) : UNSTATED_LIFETIME;
        return new Token(token.asText(), clock.instant().plus(lifetime));
    }

    private Answer post(Account account, String path, MediaType contentType, String body, String token) {
        RestClient client = account.production() ? production : sandbox;
        try {
            RestClient.RequestBodySpec request = client.post().uri(path).accept(MediaType.APPLICATION_JSON)
                    .contentType(contentType);
            if (token != null) {
                request = request.header("Cookie", "access_token=" + token);
            }
            return request.body(body).exchange((req, res) -> {
                byte[] bytes = res.getBody().readAllBytes();
                return new Answer(res.getStatusCode().value(), parse(bytes));
            });
        } catch (RestClientException noAnswer) {
            // The class only: a message can quote the request line, and nothing of a request belongs in a log.
            log.warn("Viettel SInvoice gave no answer: {}", noAnswer.getClass().getSimpleName());
            throw new ProviderUnavailableException("Viettel SInvoice did not answer");
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

    /** The token's cache key: environment, user and a hash of the password - a changed password asks for a new token. */
    private static String tokenKey(Account account) {
        return account.production() + "|" + account.username() + "|"
                + Checksums.sha256Hex(account.password().getBytes(StandardCharsets.UTF_8));
    }
}
