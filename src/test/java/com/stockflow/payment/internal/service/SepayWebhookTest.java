package com.stockflow.payment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part C: SePay's webhook as its documentation gives it - the two credentials, checked on the raw body, and the payload
 * read into a transfer. No network: the calls are built here.
 */
class SepayWebhookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final String SECRET = "test-hmac-secret";
    private static final byte[] BODY = """
            {"id":92704,"gateway":"Vietcombank","transactionDate":"2026-11-26 10:08:33","accountNumber":"0071001234567",
             "subAccount":"","code":null,"content":"HD-20261108-000007-1 DOT1","transferType":"in",
             "description":"CONG TY ABC chuyen tien","transferAmount":660000,"accumulated":0,"referenceCode":"FT26330ABC"}
            """.getBytes(StandardCharsets.UTF_8);

    private static BankFeedProperties.Webhook hmac() {
        return new BankFeedProperties.Webhook(SECRET, null, Duration.ofMinutes(5));
    }

    private static UnaryOperator<String> headers(Map<String, String> values) {
        return name -> values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static UnaryOperator<String> signed(long timestamp, byte[] body) {
        return headers(Map.of(SepayWebhook.SIGNATURE_HEADER,
                "sha256=" + SepayWebhook.sign(SECRET, String.valueOf(timestamp), body),
                SepayWebhook.TIMESTAMP_HEADER, String.valueOf(timestamp)));
    }

    @Test
    @DisplayName("HMAC: the signature of <timestamp>.<raw body> holds; another body, secret or an old timestamp does not")
    void hmacSignature() {
        long now = NOW.getEpochSecond();
        assertThat(SepayWebhook.authentic(hmac(), signed(now, BODY), BODY, NOW)).isTrue();
        byte[] changed = new String(BODY, StandardCharsets.UTF_8).replace("660000", "990000")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(SepayWebhook.authentic(hmac(), signed(now, BODY), changed, NOW)).as("the body changed").isFalse();
        assertThat(SepayWebhook.authentic(hmac(), signed(now - 301, BODY), BODY, NOW)).as("older than 5 minutes")
                .isFalse();
        assertThat(SepayWebhook.authentic(new BankFeedProperties.Webhook("other", null, Duration.ofMinutes(5)),
                signed(now, BODY), BODY, NOW)).as("another secret").isFalse();
        assertThat(SepayWebhook.authentic(hmac(), headers(Map.of()), BODY, NOW)).as("unsigned").isFalse();
        assertThat(SepayWebhook.authentic(hmac(), headers(Map.of("Authorization", "Apikey " + SECRET)), BODY, NOW))
                .as("an API key does not stand in for the signature when HMAC is configured").isFalse();
    }

    @Test
    @DisplayName("API key: Authorization: Apikey <key>, the word as SePay writes it")
    void apiKey() {
        var config = new BankFeedProperties.Webhook(null, "k-123", Duration.ofMinutes(5));
        assertThat(SepayWebhook.authentic(config, headers(Map.of("Authorization", "Apikey k-123")), BODY, NOW)).isTrue();
        assertThat(SepayWebhook.authentic(config, headers(Map.of("Authorization", "Apikey k-124")), BODY, NOW))
                .isFalse();
        assertThat(SepayWebhook.authentic(config, headers(Map.of("Authorization", "Bearer k-123")), BODY, NOW))
                .isFalse();
        assertThat(SepayWebhook.authentic(config, headers(Map.of()), BODY, NOW)).isFalse();
    }

    @Test
    @DisplayName("no credential configured: nothing is authentic")
    void noCredential() {
        var none = new BankFeedProperties.Webhook(null, " ", Duration.ofMinutes(5));
        assertThat(SepayWebhook.authentic(none, headers(Map.of("Authorization", "Apikey ")), BODY, NOW)).isFalse();
    }

    @Test
    @DisplayName("an incoming transfer: its id, account, amount, content, bank reference and Vietnam time")
    void readsAnIncomingTransfer() {
        var reading = SepayWebhook.read(BODY, JSON);
        assertThat(reading).isInstanceOf(SepayWebhook.Incoming.class);
        var transfer = ((SepayWebhook.Incoming) reading).transfer();
        assertThat(transfer.providerTransactionId()).isEqualTo("92704");
        assertThat(transfer.accountNumber()).isEqualTo("0071001234567");
        assertThat(transfer.amount()).isEqualByComparingTo(new BigDecimal("660000"));
        assertThat(transfer.content()).isEqualTo("HD-20261108-000007-1 DOT1");
        assertThat(transfer.bankReference()).isEqualTo("FT26330ABC");
        assertThat(transfer.paymentCode()).isNull();
        assertThat(transfer.transactionAt()).as("10:08:33 in Vietnam").isEqualTo(Instant.parse("2026-11-26T03:08:33Z"));
    }

    @Test
    @DisplayName("money going out is told apart; what is not a transfer is unreadable")
    void outgoingAndUnreadable() {
        assertThat(SepayWebhook.read(new String(BODY, StandardCharsets.UTF_8).replace("\"in\"", "\"out\"")
                .getBytes(StandardCharsets.UTF_8), JSON)).isInstanceOf(SepayWebhook.Outgoing.class);
        assertThat(SepayWebhook.read("not json".getBytes(StandardCharsets.UTF_8), JSON))
                .isInstanceOf(SepayWebhook.Unreadable.class);
        assertThat(SepayWebhook.read("{\"transferType\":\"in\"}".getBytes(StandardCharsets.UTF_8), JSON))
                .isInstanceOf(SepayWebhook.Unreadable.class);
        assertThat(SepayWebhook.read(new String(BODY, StandardCharsets.UTF_8).replace("660000", "0.5")
                .getBytes(StandardCharsets.UTF_8), JSON)).isInstanceOf(SepayWebhook.Unreadable.class);
        assertThat(SepayWebhook.read(new String(BODY, StandardCharsets.UTF_8).replace("2026-11-26 10:08:33", "26/11")
                .getBytes(StandardCharsets.UTF_8), JSON)).isInstanceOf(SepayWebhook.Unreadable.class);
    }
}
