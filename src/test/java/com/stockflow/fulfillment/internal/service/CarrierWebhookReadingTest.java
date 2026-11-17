package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading carriers' webhooks (3-5; kltn-docs 09 §4.5): Ahamove's callback by its documented shape and the mapping of
 * 09's table, the system's WEBHOOK_LOAD format, the credential checks, and what the event log keeps of a body.
 */
class CarrierWebhookReadingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Nested
    @DisplayName("Ahamove's callback")
    class Ahamove {

        @Test
        @DisplayName("a driver taking the order: the driver's name and phone, at accept_time (or board_time once boarded)")
        void accepted() throws Exception {
            CarrierReport plain = AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD\",\"status\":\"ACCEPTED\","
                    + "\"sub_status\":\"\",\"accept_time\":1800000000.25,\"board_time\":0,\"supplier_name\":\" Tài \","
                    + "\"supplier_id\":\"84901111111\"}"));
            CarrierReport boarded = AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD\",\"status\":\"ACCEPTED\","
                    + "\"sub_status\":\"BOARDED\",\"accept_time\":1800000000,\"board_time\":1800000500}"));

            assertThat(plain.kind()).isEqualTo(CarrierEventKind.DRIVER_ASSIGNED);
            assertThat(plain.reference()).isEqualTo("24ABCD");
            assertThat(plain.stop()).isNull();
            assertThat(plain.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L, 250_000_000L));
            assertThat(plain.driverName()).isEqualTo("Tài");
            assertThat(plain.driverPhone()).isEqualTo("84901111111");
            assertThat(boarded.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_500L));
        }

        @Test
        @DisplayName("each order status to 09's table; a status with no time of its own carries none")
        void orderStatuses() throws Exception {
            assertThat(read("ASSIGNING", "", "").kind()).isEqualTo(CarrierEventKind.DRIVER_RELEASED);
            assertThat(read("ASSIGNING", "", "").reportedAt()).isNull();
            CarrierReport pickedUp = read("IN PROCESS", "", ",\"pickup_time\":1800000700");
            assertThat(pickedUp.kind()).isEqualTo(CarrierEventKind.PICKED_UP);
            assertThat(pickedUp.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_700L));
            assertThat(read("IN PROCESS", "COMPLETING", ",\"pickup_time\":1800000700").kind())
                    .isEqualTo(CarrierEventKind.IN_TRANSIT);
            assertThat(read("COMPLETED", "IN_RETURN", "").kind()).isEqualTo(CarrierEventKind.RETURNING);
            assertThat(read("COMPLETED", "", ",\"complete_time\":1800009000").kind())
                    .isEqualTo(CarrierEventKind.ORDER_COMPLETED);
            assertThat(read("IDLE", "", "").kind()).isEqualTo(CarrierEventKind.OTHER);
            CarrierReport cancelled = read("CANCELLED", "", ",\"cancel_time\":1800000900,"
                    + "\"cancel_comment\":\"Auto cancel, no driver accepted\"");
            assertThat(cancelled.kind()).isEqualTo(CarrierEventKind.ORDER_CANCELLED);
            assertThat(cancelled.failureWording()).isEqualTo("Auto cancel, no driver accepted");
            assertThat(cancelled.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_900L));
            // Cancelled by the partner itself - this system: its own cancel echoed back.
            assertThat(read("CANCELLED", "", ",\"cancel_by_user\":true,\"cancel_time\":1800000900").kind())
                    .isEqualTo(CarrierEventKind.CANCELLED_ON_REQUEST);
            // A driver giving it back carries the acceptance it undoes, when the order still has it.
            assertThat(read("ASSIGNING", "", ",\"accept_time\":1800000100").reportedAt())
                    .isEqualTo(Instant.ofEpochSecond(1_800_000_100L));
        }

        @Test
        @DisplayName("a completed order's drop-offs: each with an outcome, as its own callback would read")
        void completedStops() throws Exception {
            java.util.List<CarrierReport> stops = AhamoveWebhookReader.stops(json("{\"_id\":\"24ABCD\","
                    + "\"status\":\"COMPLETED\",\"path\":[{\"status\":\"\"},{\"status\":\"COMPLETED\","
                    + "\"complete_time\":1800003000},{\"status\":\"\"},{\"status\":\"FAILED\","
                    + "\"fail_comment\":\"Sai địa chỉ\"}]}"), "24ABCD");

            assertThat(stops).extracting(CarrierReport::stop).containsExactly(1, 3);
            assertThat(stops).extracting(CarrierReport::kind).containsExactly(CarrierEventKind.DELIVERED,
                    CarrierEventKind.DELIVERY_FAILED);
            assertThat(stops.get(1).failureWording()).isEqualTo("Sai địa chỉ");
        }

        @Test
        @DisplayName("returned: the latest return_time of the stops, else complete_time")
        void returned() throws Exception {
            CarrierReport report = AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD\",\"status\":\"COMPLETED\","
                    + "\"sub_status\":\"RETURNED\",\"complete_time\":1800001000,\"path\":[{\"return_time\":0},"
                    + "{\"return_time\":1800002000},{\"return_time\":1800003000}]}"));
            CarrierReport noStopTime = read("COMPLETED", "RETURNED", ",\"complete_time\":1800001000");

            assertThat(report.kind()).isEqualTo(CarrierEventKind.RETURNED);
            assertThat(report.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_003_000L));
            assertThat(noStopTime.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_001_000L));
        }

        @Test
        @DisplayName("a drop-off's callback ({order}-{k}): its own status, time and reason from path[k]")
        void dropOffs() throws Exception {
            CarrierReport delivered = AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD-2\",\"status\":\"IN PROCESS\","
                    + "\"path\":[{},{\"status\":\"COMPLETED\",\"complete_time\":1800003000},"
                    + "{\"status\":\"FAILED\",\"fail_time\":1800004000,\"fail_comment\":\"Khách vắng\","
                    + "\"tracking_number\":\"SO-1-S2\"}]}"));
            CarrierReport first = AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD-1\",\"status\":\"IN PROCESS\","
                    + "\"path\":[{},{\"status\":\"COMPLETED\",\"complete_time\":1800003000}]}"));

            assertThat(delivered.kind()).isEqualTo(CarrierEventKind.DELIVERY_FAILED);
            assertThat(delivered.reference()).isEqualTo("24ABCD");
            assertThat(delivered.stop()).isEqualTo(2);
            assertThat(delivered.shipmentCode()).isEqualTo("SO-1-S2");
            assertThat(delivered.failureWording()).isEqualTo("Khách vắng");
            assertThat(delivered.reportedAt()).isEqualTo(Instant.ofEpochSecond(1_800_004_000L));
            assertThat(first.kind()).isEqualTo(CarrierEventKind.DELIVERED);
            assertThat(first.stop()).isOne();
        }

        @Test
        @DisplayName("unreadable: no object, no _id, a drop-off the path does not have")
        void unreadable() {
            assertThatThrownBy(() -> AhamoveWebhookReader.read(json("[]")))
                    .isInstanceOf(CarrierWebhookUnreadable.class);
            assertThatThrownBy(() -> AhamoveWebhookReader.read(json("{\"status\":\"ACCEPTED\"}")))
                    .isInstanceOf(CarrierWebhookUnreadable.class).hasMessageContaining("_id");
            assertThatThrownBy(() -> AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD-3\",\"path\":[{},{}]}")))
                    .isInstanceOf(CarrierWebhookUnreadable.class).hasMessageContaining("drop-off 3");
        }

        @Test
        @DisplayName("times: 0, negative, unreadable or absurd is not set; a numeric string is read")
        void times() throws Exception {
            assertThat(AhamoveWebhookReader.time(json("{\"t\":0}"), "t")).isNull();
            assertThat(AhamoveWebhookReader.time(json("{\"t\":-5}"), "t")).isNull();
            assertThat(AhamoveWebhookReader.time(json("{\"t\":\"soon\"}"), "t")).isNull();
            assertThat(AhamoveWebhookReader.time(json("{\"t\":1e15}"), "t")).isNull();
            assertThat(AhamoveWebhookReader.time(json("{\"t\":\"1800000000\"}"), "t"))
                    .isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        }

        private CarrierReport read(String status, String sub, String fields) throws Exception {
            return AhamoveWebhookReader.read(json("{\"_id\":\"24ABCD\",\"status\":\"" + status + "\",\"sub_status\":\""
                    + sub + "\"" + fields + "}"));
        }
    }

    @Nested
    @DisplayName("a WEBHOOK_LOAD carrier's event")
    class PerLoad {

        @Test
        @DisplayName("the load code, the type, the time with its offset; the rest as given")
        void reads() throws Exception {
            CarrierReport report = LoadWebhookReader.read(json("{\"loadCode\":\"LD-1\",\"type\":\"delivery_failed\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00+07:00\",\"shipmentCode\":\"SO-1-S1\","
                    + "\"failureReason\":\"Khách không nhận\"}"));

            assertThat(report.kind()).isEqualTo(CarrierEventKind.DELIVERY_FAILED);
            assertThat(report.reference()).isEqualTo("LD-1");
            assertThat(report.shipmentCode()).isEqualTo("SO-1-S1");
            assertThat(report.failureWording()).isEqualTo("Khách không nhận");
            assertThat(report.reportedAt()).isEqualTo(Instant.parse("2026-11-20T01:00:00Z"));
        }

        @Test
        @DisplayName("refused: a missing field, an unknown type, a time without an offset, a waybill event without a "
                + "number, a field longer than the load holds")
        void refuses() {
            assertThatThrownBy(() -> LoadWebhookReader.read(json("{\"type\":\"PICKED_UP\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00Z\"}"))).hasMessageContaining("loadCode");
            assertThatThrownBy(() -> LoadWebhookReader.read(json("{\"loadCode\":\"LD-1\",\"type\":\"TELEPORTED\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00Z\"}"))).hasMessageContaining("type");
            assertThatThrownBy(() -> LoadWebhookReader.read(json("{\"loadCode\":\"LD-1\",\"type\":\"PICKED_UP\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00\"}"))).hasMessageContaining("reportedAt");
            assertThatThrownBy(() -> LoadWebhookReader.read(json("{\"loadCode\":\"LD-1\",\"type\":\"WAYBILL\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00Z\"}"))).hasMessageContaining("waybillNo");
            assertThatThrownBy(() -> LoadWebhookReader.read(json("{\"loadCode\":\"LD-1\",\"type\":\"WAYBILL\","
                    + "\"reportedAt\":\"2026-11-20T08:00:00Z\",\"waybillNo\":\"W\",\"vehiclePlate\":\""
                    + "X".repeat(21) + "\"}"))).hasMessageContaining("vehiclePlate");
        }
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("a header holds when it carries exactly the configured value")
        void header() {
            assertThat(WebhookCredentials.headerHolds("webhook-credential-0123456789", " webhook-credential-0123456789 "))
                    .isTrue();
            assertThat(WebhookCredentials.headerHolds("webhook-credential-0123456789", "webhook-credential-012345678"))
                    .isFalse();
            assertThat(WebhookCredentials.headerHolds("webhook-credential-0123456789", null)).isFalse();
            assertThat(WebhookCredentials.headerHolds(null, "anything")).isFalse();
        }

        @Test
        @DisplayName("a signature holds for the raw bytes signed with the secret, bare or as sha256=, in either case")
        void signature() {
            byte[] body = "{\"loadCode\":\"LD-1\"}".getBytes(StandardCharsets.UTF_8);
            String signed = WebhookCredentials.sign("secret-0123456789", body);

            assertThat(signed).startsWith("sha256=");
            assertThat(WebhookCredentials.signatureHolds("secret-0123456789", body, signed)).isTrue();
            assertThat(WebhookCredentials.signatureHolds("secret-0123456789", body,
                    signed.substring("sha256=".length()).toUpperCase())).isTrue();
            assertThat(WebhookCredentials.signatureHolds("secret-0123456789",
                    "{\"loadCode\": \"LD-1\"}".getBytes(StandardCharsets.UTF_8), signed)).isFalse();
            assertThat(WebhookCredentials.signatureHolds("another-secret-0123", body, signed)).isFalse();
            assertThat(WebhookCredentials.signatureHolds("secret-0123456789", body, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the event log keeps")
    class Payload {

        @Test
        @DisplayName("a credential the carrier echoes is masked at any depth; the rest is kept")
        void masksCredentials() {
            String stored = CarrierWebhookPayload.stored(("{\"_id\":\"24ABCD\",\"api_key\":\"aha-key-0123456789\","
                    + "\"path\":[{\"Token\":\"t-1\",\"address\":\"1 Lê Lợi\"}]}").getBytes(StandardCharsets.UTF_8), JSON);

            assertThat(stored).doesNotContain("aha-key-0123456789").doesNotContain("t-1").contains("24ABCD")
                    .contains("1 Lê Lợi").contains("\"api_key\":\"***\"");
        }

        @Test
        @DisplayName("a body that is not JSON is masked by pattern; a NUL is replaced; a body with nothing secret is kept "
                + "as it came")
        void otherBodies() {
            String broken = CarrierWebhookPayload.stored("{\"api_key\":\"k-1\", oops".getBytes(StandardCharsets.UTF_8),
                    JSON);
            String plain = "{ \"loadCode\" : \"LD-1\" }";

            assertThat(broken).doesNotContain("k-1").contains("***");
            assertThat(CarrierWebhookPayload.stored("a\0b".getBytes(StandardCharsets.UTF_8), JSON)).isEqualTo("a�b");
            assertThat(CarrierWebhookPayload.stored(plain.getBytes(StandardCharsets.UTF_8), JSON)).isEqualTo(plain);
        }

        @Test
        @DisplayName("a refused call is kept as its size, digest and first characters, the echoed key masked")
        void summary() {
            String body = "{\"api_key\":\"aha-key-0123456789\",\"pad\":\"" + "x".repeat(1000) + "\"}";
            String kept = CarrierWebhookPayload.summary(body.getBytes(StandardCharsets.UTF_8), JSON);

            assertThat(kept).startsWith("[refused: " + body.length() + " bytes, sha256 ").doesNotContain("aha-key")
                    .endsWith("...");
            assertThat(kept.length()).isLessThan(400);
        }

        @Test
        @DisplayName("a repeat is told by the carrier's time, or - with none - by the body")
        void dedupeKey() {
            UUID carrier = UUID.randomUUID();
            CarrierReport at = CarrierReport.of(CarrierEventKind.PICKED_UP, "24ABCD", Instant.ofEpochSecond(1));
            CarrierReport none = CarrierReport.of(CarrierEventKind.DRIVER_RELEASED, "24ABCD", null);
            byte[] one = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            byte[] two = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);

            assertThat(CarrierEventApplier.dedupeKey(carrier, at, one)).isEqualTo(CarrierEventApplier.dedupeKey(carrier,
                    at, two)).hasSize(64);
            assertThat(CarrierEventApplier.dedupeKey(carrier, none, one)).isNotEqualTo(CarrierEventApplier.dedupeKey(
                    carrier, none, two));
            assertThat(CarrierEventApplier.dedupeKey(carrier, at, one)).isNotEqualTo(CarrierEventApplier.dedupeKey(
                    UUID.randomUUID(), at, one));
        }
    }
}
