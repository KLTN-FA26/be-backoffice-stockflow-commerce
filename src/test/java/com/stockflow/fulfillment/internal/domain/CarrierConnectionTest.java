package com.stockflow.fulfillment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 09 §4.1 step 2, BR-21: what each kind of carrier connection needs, and §4.3 step 3 / E22: a carrier's
 * wording of a failed delivery, mapped or recorded as OTHER with the carrier's words.
 */
class CarrierConnectionTest {

    private static Iterable<String> fields(CarrierConnection connection) {
        return connection.violations().stream().map(CarrierConnection.Violation::field).toList();
    }

    @Test
    @DisplayName("a manual carrier needs nothing, and cannot have webhooks enabled")
    void manual() {
        assertThat(CarrierConnection.manual().violations()).isEmpty();
        assertThat(fields(new CarrierConnection(CarrierConnectionType.MANUAL, true, false, null, false, null, null,
                false))).containsExactly("webhookEnabled");
    }

    @Test
    @DisplayName("a carrier that signs its events needs the secret they are signed with")
    void webhookLoad() {
        assertThat(fields(new CarrierConnection(CarrierConnectionType.WEBHOOK_LOAD, true, false, null, false, null,
                null, false))).containsExactly("webhookSecret");
        assertThat(new CarrierConnection(CarrierConnectionType.WEBHOOK_LOAD, false, true, null, false, null, null,
                false).violations()).isEmpty();
    }

    @Test
    @DisplayName("a carrier the system books with needs the header credential, the environment, account and key")
    void apiBooking() {
        assertThat(fields(new CarrierConnection(CarrierConnectionType.API_BOOKING, true, false, null, false, null,
                null, false))).containsExactlyInAnyOrder("webhookAuthHeader", "webhookAuthValue", "apiEnvironment",
                "apiAccount", "apiKey");
        assertThat(fields(new CarrierConnection(CarrierConnectionType.API_BOOKING, true, false, "api key", true,
                CarrierApiEnvironment.STAGING, "84 90", true))).containsExactlyInAnyOrder("webhookAuthHeader",
                "apiAccount");
        assertThat(new CarrierConnection(CarrierConnectionType.API_BOOKING, true, false, "apikey", true,
                CarrierApiEnvironment.STAGING, "84908000000", true).violations()).isEmpty();
    }

    @Test
    @DisplayName("only MANUAL sends no webhooks")
    void whoSendsWebhooks() {
        assertThat(CarrierConnectionType.MANUAL.sendsWebhooks()).isFalse();
        assertThat(CarrierConnectionType.WEBHOOK_LOAD.sendsWebhooks()).isTrue();
        assertThat(CarrierConnectionType.API_BOOKING.sendsWebhooks()).isTrue();
    }

    @Test
    @DisplayName("a mapped wording gives its reason whatever its case and spacing, and keeps the carrier's words")
    void mappedReason() {
        FailureReasonMap map = FailureReasonMap.of(Map.of("Khách không nghe máy", DeliveryFailureReason.CUSTOMER_ABSENT,
                "Sai địa chỉ", DeliveryFailureReason.WRONG_ADDRESS));

        assertThat(map.resolve("  khách KHÔNG   nghe máy ")).isEqualTo(new FailureReasonMap.Resolution(
                DeliveryFailureReason.CUSTOMER_ABSENT, "khách KHÔNG   nghe máy"));
        assertThat(map.resolve("Sai địa chỉ").reason()).isEqualTo(DeliveryFailureReason.WRONG_ADDRESS);
        // The same words decomposed (NFD, combining marks) and with a non-breaking space still match.
        String decomposed = java.text.Normalizer.normalize("Sai\u00A0địa chỉ", java.text.Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("Sai địa chỉ");
        assertThat(map.resolve(decomposed).reason()).isEqualTo(DeliveryFailureReason.WRONG_ADDRESS);
    }

    @Test
    @DisplayName("wording missing from the table, or none at all, is OTHER with the carrier's words as the note (E22)")
    void unmappedReasonIsOther() {
        FailureReasonMap map = FailureReasonMap.of(Map.of("Sai địa chỉ", DeliveryFailureReason.WRONG_ADDRESS));

        assertThat(map.resolve("Xe hỏng giữa đường")).isEqualTo(new FailureReasonMap.Resolution(
                DeliveryFailureReason.OTHER, "Xe hỏng giữa đường"));
        assertThat(map.resolve(null)).isEqualTo(new FailureReasonMap.Resolution(DeliveryFailureReason.OTHER, null));
        assertThat(FailureReasonMap.empty().resolve(" ")).isEqualTo(new FailureReasonMap.Resolution(
                DeliveryFailureReason.OTHER, null));
    }

    @Test
    @DisplayName("a table with a blank, over-long or repeated wording, no reason, or over 100 entries is refused")
    void malformedTables() {
        assertThatThrownBy(() -> FailureReasonMap.of(Map.of(" ", DeliveryFailureReason.LOST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureReasonMap.of(Map.of("x".repeat(201), DeliveryFailureReason.LOST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureReasonMap.of(Map.of("Lost", DeliveryFailureReason.LOST,
                " LOST ", DeliveryFailureReason.OTHER))).hasMessageContaining("twice");
        java.util.Map<String, DeliveryFailureReason> none = new java.util.HashMap<>();
        none.put("Lost", null);
        assertThatThrownBy(() -> FailureReasonMap.of(none)).hasMessageContaining("no internal reason");
        java.util.Map<String, DeliveryFailureReason> many = new java.util.HashMap<>();
        for (int i = 0; i <= FailureReasonMap.MAX_ENTRIES; i++) {
            many.put("reason " + i, DeliveryFailureReason.OTHER);
        }
        assertThatThrownBy(() -> FailureReasonMap.of(many)).hasMessageContaining("100");
        assertThat(FailureReasonMap.of(null).entries()).isEmpty();
    }
}
