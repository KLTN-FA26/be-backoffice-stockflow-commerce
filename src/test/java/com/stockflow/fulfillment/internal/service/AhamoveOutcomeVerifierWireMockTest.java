package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A final outcome Ahamove's unsigned callback reported, asked back of Ahamove's API before it is applied (WF-05 fix plan
 * item 6) - the real gateway and its real HTTP client against a WireMock server standing in for
 * {@code partner-apistg.ahamove.com}: what the API bears out is verified; what it gives otherwise, refuses or does not
 * know is not; and an API that does not answer - an error page, a reset connection, a reply slower than the client
 * waits - is no verdict, so the callback is to be sent again.
 */
class AhamoveOutcomeVerifierWireMockTest {

    private static final String ORDER = "24ABCD";
    private static final String API_KEY = "aha-key-0123456789";
    private static final String ACCOUNT = "84908000000";
    private static final WireMockServer AHAMOVE = new WireMockServer(options().dynamicPort());

    private final SecretCipher cipher = new SecretCipher(new SecretCipherProperties(
            Base64.getEncoder().encodeToString(new byte[32])));
    private final CarrierApiCalls calls = mock(CarrierApiCalls.class);
    private final UUID bookingId = UUID.randomUUID();
    private CarrierJpaEntity carrier;
    private AhamoveOutcomeVerifier verifier;

    @BeforeAll
    static void startAhamove() {
        AHAMOVE.start();
    }

    @AfterAll
    static void stopAhamove() {
        AHAMOVE.stop();
    }

    @BeforeEach
    void aCarrierBookedThroughTheFakeAhamove() {
        AHAMOVE.resetAll();
        verifier = verifier(true);
        carrier = new CarrierJpaEntity(UUID.randomUUID(), "AHA");
        carrier.apply("AHA", "Ahamove", null, null, "ACTIVE");
        carrier.connect("API_BOOKING", true, null, "apikey",
                cipher.seal("webhook-credential-0123456789", CarrierService.context(carrier, "webhook-auth-value")),
                null, "STAGING", ACCOUNT, cipher.seal(API_KEY, CarrierService.context(carrier, "api-key")), Map.of());
        AHAMOVE.stubFor(post(urlEqualTo("/v3/accounts/token"))
                .withRequestBody(matchingJsonPath("$.mobile", equalTo(ACCOUNT)))
                .withRequestBody(matchingJsonPath("$.api_key", equalTo(API_KEY)))
                .willReturn(okJson("{\"token\":\"t1\",\"refresh_token\":\"r\"}")));
    }

    /** A gateway on the real HTTP client, short on patience, and the verifier over it. */
    private AhamoveOutcomeVerifier verifier(boolean verifyOutcomes) {
        AhamoveProperties properties = new AhamoveProperties(AHAMOVE.baseUrl(), AHAMOVE.baseUrl(),
                "Khách hàng muốn hủy đơn", new AhamoveProperties.Webhook(verifyOutcomes, Duration.ofHours(24),
                Duration.ofMinutes(5), List.of()));
        AhamoveGateway gateway = new AhamoveGateway(new RestClientFactory(new HttpClientProperties(
                Duration.ofSeconds(2), Duration.ofMillis(700))), properties, calls, new ObjectMapper());
        return new AhamoveOutcomeVerifier(gateway, new CarrierSecrets(cipher), properties);
    }

    /** Ahamove's order as GET /v3/orders/{id} gives it: the pickup as path[0], then each drop-off with its status. */
    private static String order(String status, String subStatus, String... stopStatuses) {
        StringBuilder path = new StringBuilder("{\"address\":\"1 Lê Lợi\",\"status\":\"\"}");
        for (String stop : stopStatuses) {
            path.append(",{\"address\":\"2 Hai Bà Trưng\",\"status\":\"").append(stop).append("\"}");
        }
        return "{\"_id\":\"" + ORDER + "\",\"status\":\"" + status + "\",\"sub_status\":\"" + subStatus
                + "\",\"service_id\":\"SGN-TRUCK-2000\",\"path\":[" + path + "]}";
    }

    private static void ahamoveGives(String body) {
        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).withHeader("Authorization", equalTo("Bearer t1"))
                .willReturn(okJson(body)));
    }

    private static CarrierReport stop(CarrierEventKind kind, int stop) {
        return new CarrierReport(kind, ORDER, stop, "SO-1-S" + stop, Instant.parse("2026-11-12T03:00:00Z"), null,
                null, null, null, kind == CarrierEventKind.DELIVERY_FAILED ? "Khách vắng" : null);
    }

    private CarrierEventApplier.Matched matched(CarrierReport report, CarrierReport... stops) {
        return new CarrierEventApplier.Matched(report, UUID.randomUUID(), "LD-1", UUID.randomUUID(), bookingId, ORDER,
                report.stop() == null ? null : UUID.randomUUID(), "dedupe", List.of(stops), null);
    }

    @Test
    @DisplayName("a drop-off the callback reports delivered is verified when Ahamove's API gives it COMPLETED - asked "
            + "once, with the carrier's own token, and logged as a STATUS call")
    void aDeliveredDropOffIsBorneOut() {
        ahamoveGives(order("IN PROCESS", "", "COMPLETED", ""));
        CarrierEventApplier.Matched delivered = matched(stop(CarrierEventKind.DELIVERED, 1));

        assertThat(verifier.concerns(delivered)).isTrue();
        assertThat(verifier.verify(carrier, delivered)).isNull();

        AHAMOVE.verify(1, postRequestedFor(urlEqualTo("/v3/accounts/token")));
        AHAMOVE.verify(1, getRequestedFor(urlEqualTo("/v3/orders/" + ORDER))
                .withHeader("Authorization", equalTo("Bearer t1")));
        verify(calls).record(eq(carrier.getId()), eq(bookingId), eq("STATUS"), eq(CarrierApiCalls.Outcome.OK), eq(200),
                isNull(), any(), isNull(), anyLong());
    }

    @Test
    @DisplayName("a drop-off reported delivered while Ahamove's API gives it failed, or nothing yet, is not verified - "
            + "the note says what each side gave")
    void aDeliveredDropOffTheApiDoesNotBearOut() {
        ahamoveGives(order("IN PROCESS", "", "FAILED", ""));
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 1))))
                .contains("drop-off 1").contains(ORDER).contains("COMPLETED").contains("FAILED");
        // The second drop-off is still under way; a third the order never had.
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 2)))).contains("says nothing");
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 3)))).contains("says nothing");
    }

    @Test
    @DisplayName("a failed drop-off is verified by FAILED, in whatever case Ahamove writes it, and not by COMPLETED")
    void aFailedDropOff() {
        ahamoveGives(order("In Process", "", "failed"));
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERY_FAILED, 1)))).isNull();

        ahamoveGives(order("IN PROCESS", "", "COMPLETED"));
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERY_FAILED, 1))))
                .contains("FAILED").contains("COMPLETED");
    }

    @Test
    @DisplayName("an order the callback reports cancelled is verified only when Ahamove's API gives it CANCELLED - a "
            + "forged cancel of an order a driver is on is not")
    void aCancelledOrder() {
        CarrierEventApplier.Matched cancelled = matched(new CarrierReport(CarrierEventKind.ORDER_CANCELLED, ORDER,
                null, null, Instant.parse("2026-11-12T03:00:00Z"), null, null, null, null, "No driver"));
        ahamoveGives(order("CANCELLED", ""));
        assertThat(verifier.verify(carrier, cancelled)).isNull();

        ahamoveGives(order("ACCEPTED", ""));
        assertThat(verifier.verify(carrier, cancelled)).contains("CANCELLED").contains("ACCEPTED");
    }

    @Test
    @DisplayName("a completed order's callback is verified with the outcomes of the drop-offs it carries, the order "
            + "read from inside Ahamove's 'order' wrapper too; one drop-off differing is enough to refuse it")
    void aCompletedOrderAndItsDropOffs() {
        CarrierReport completed = CarrierReport.of(CarrierEventKind.ORDER_COMPLETED, ORDER,
                Instant.parse("2026-11-12T03:00:00Z"));
        CarrierEventApplier.Matched carrying = matched(completed, stop(CarrierEventKind.DELIVERED, 1),
                stop(CarrierEventKind.DELIVERY_FAILED, 2));

        ahamoveGives("{\"order\":" + order("COMPLETED", "", "COMPLETED", "FAILED") + "}");
        assertThat(verifier.concerns(carrying)).isTrue();
        assertThat(verifier.verify(carrier, carrying)).isNull();

        ahamoveGives(order("COMPLETED", "", "COMPLETED", "COMPLETED"));
        assertThat(verifier.verify(carrier, carrying)).contains("drop-off 2");
        ahamoveGives(order("IN PROCESS", "", "COMPLETED", "FAILED"));
        assertThat(verifier.verify(carrier, carrying)).contains("COMPLETED").contains("IN PROCESS");
        // A completed order carrying no drop-off's outcome changes nothing: it is not asked back.
        assertThat(verifier.concerns(matched(completed))).isFalse();
    }

    @Test
    @DisplayName("goods reported back are verified by a completed order that Ahamove's API gives as returned")
    void aReturnedOrder() {
        CarrierEventApplier.Matched returned = matched(CarrierReport.of(CarrierEventKind.RETURNED, ORDER,
                Instant.parse("2026-11-12T03:00:00Z")));
        ahamoveGives(order("COMPLETED", "RETURNED", "FAILED"));
        assertThat(verifier.verify(carrier, returned)).isNull();

        ahamoveGives(order("COMPLETED", "IN_RETURN", "FAILED"));
        assertThat(verifier.verify(carrier, returned)).contains("returned").contains("IN_RETURN");
        // Review F1: an order completed in the ordinary way - no sub-status at all - is not the goods back. A forged
        // "returned" for a completed order whose failed drop-off is still with the driver must not pass.
        ahamoveGives(order("COMPLETED", "", "COMPLETED", "FAILED"));
        assertThat(verifier.verify(carrier, returned)).contains("returned").contains("nothing of a return");
        ahamoveGives("{\"_id\":\"" + ORDER + "\",\"status\":\"COMPLETED\",\"path\":[{},{\"status\":\"FAILED\"}]}");
        assertThat(verifier.verify(carrier, returned)).contains("nothing of a return");
        ahamoveGives(order("IN PROCESS", "", "FAILED"));
        assertThat(verifier.verify(carrier, returned)).contains("COMPLETED").contains("IN PROCESS");
    }

    @Test
    @DisplayName("an order Ahamove's API does not know, or refuses to show, is not verified")
    void anOrderTheApiDoesNotKnowOrRefuses() {
        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{\"code\":\"ORDER_NOT_FOUND\"}")));
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 1))))
                .contains("knows no order " + ORDER);

        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"NOT_PERMITTED\",\"title\":\"Not your order\"}")));
        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 1))))
                .contains("refused").contains("NOT_PERMITTED");
    }

    @Test
    @DisplayName("a token Ahamove no longer takes is asked for again and the order read with the new one")
    void anExpiredTokenIsRenewed() {
        AHAMOVE.resetAll();
        AHAMOVE.stubFor(post(urlEqualTo("/v3/accounts/token")).inScenario("token").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"token\":\"stale\"}")).willSetStateTo("renewed"));
        AHAMOVE.stubFor(post(urlEqualTo("/v3/accounts/token")).inScenario("token").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"token\":\"fresh\"}")));
        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).withHeader("Authorization", equalTo("Bearer stale"))
                .willReturn(aResponse().withStatus(401)));
        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).withHeader("Authorization", equalTo("Bearer fresh"))
                .willReturn(okJson(order("IN PROCESS", "", "COMPLETED"))));

        assertThat(verifier.verify(carrier, matched(stop(CarrierEventKind.DELIVERED, 1)))).isNull();
        AHAMOVE.verify(2, postRequestedFor(urlEqualTo("/v3/accounts/token")));
        AHAMOVE.verify(2, getRequestedFor(urlEqualTo("/v3/orders/" + ORDER)));
    }

    @Test
    @DisplayName("an API that does not answer - an error page, a reset connection, a reply slower than the client "
            + "waits - is no verdict: the callback is to be sent again")
    void anApiThatDoesNotAnswerIsNoVerdict() {
        CarrierEventApplier.Matched delivered = matched(stop(CarrierEventKind.DELIVERED, 1));

        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(502)
                .withBody("<html>Bad Gateway</html>")));
        assertThatThrownBy(() -> verifier.verify(carrier, delivered)).isInstanceOf(CarrierWebhookRetry.class)
                .hasMessageContaining(ORDER).hasMessageContaining("send it again");

        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> verifier.verify(carrier, delivered)).isInstanceOf(CarrierWebhookRetry.class);

        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(okJson(order("IN PROCESS", "", "COMPLETED"))
                .withFixedDelay(2_000)));
        assertThatThrownBy(() -> verifier.verify(carrier, delivered)).isInstanceOf(CarrierWebhookRetry.class);
        verify(calls, atLeastOnce()).record(eq(carrier.getId()), eq(bookingId), eq("STATUS"),
                eq(CarrierApiCalls.Outcome.NO_ANSWER), isNull(), isNull(), isNull(), any(), anyLong());
    }

    @Test
    @DisplayName("review F2: told to come back later (429, 408, 425), or answered 200 with nothing that reads as an "
            + "order, Ahamove has said nothing about the order - no verdict, the callback is to be sent again")
    void aPassingRefusalOrAnUnreadableAnswerIsNoVerdict() {
        CarrierEventApplier.Matched delivered = matched(stop(CarrierEventKind.DELIVERED, 1));

        for (int status : new int[] {429, 408, 425}) {
            AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"TOO_MANY_REQUESTS\",\"title\":\"Slow down\"}")));
            assertThatThrownBy(() -> verifier.verify(carrier, delivered)).as("HTTP " + status)
                    .isInstanceOf(CarrierWebhookRetry.class).hasMessageContaining("send it again");
        }
        for (String body : new String[] {"", "   ", "<html>maintenance</html>", "[]", "null", "\"ok\""}) {
            AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody(body)));
            assertThatThrownBy(() -> verifier.verify(carrier, delivered)).as("body '" + body + "'")
                    .isInstanceOf(CarrierWebhookRetry.class);
        }
        // A refusal that is a verdict stays one, and so does "no such order".
        AHAMOVE.stubFor(get(urlEqualTo("/v3/orders/" + ORDER)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"code\":\"INVALID_ORDER\"}")));
        assertThat(verifier.verify(carrier, delivered)).contains("refused");
    }

    @Test
    @DisplayName("only final outcomes are asked back: a milestone, the driver's name and our own cancel echoed are not; "
            + "switched off, nothing is")
    void onlyFinalOutcomesAreAskedBack() {
        Instant at = Instant.parse("2026-11-12T03:00:00Z");
        for (CarrierEventKind kind : List.of(CarrierEventKind.DRIVER_ASSIGNED, CarrierEventKind.DRIVER_RELEASED,
                CarrierEventKind.PICKED_UP, CarrierEventKind.IN_TRANSIT, CarrierEventKind.RETURNING,
                CarrierEventKind.CANCELLED_ON_REQUEST, CarrierEventKind.OTHER)) {
            assertThat(verifier.concerns(matched(CarrierReport.of(kind, ORDER, at)))).as(kind.name()).isFalse();
        }
        for (CarrierEventKind kind : List.of(CarrierEventKind.ORDER_CANCELLED, CarrierEventKind.RETURNED)) {
            assertThat(verifier.concerns(matched(CarrierReport.of(kind, ORDER, at)))).as(kind.name()).isTrue();
        }
        assertThat(verifier.concerns(matched(stop(CarrierEventKind.DELIVERY_FAILED, 1)))).isTrue();

        assertThat(verifier(false).concerns(matched(stop(CarrierEventKind.DELIVERED, 1)))).isFalse();
        AHAMOVE.verify(0, getRequestedFor(urlEqualTo("/v3/orders/" + ORDER)));
    }

    @Test
    @DisplayName("a carrier whose API key is gone cannot be asked: the outcome is not verified, and Ahamove is not "
            + "called")
    void aCarrierThatCannotBeAsked() {
        CarrierJpaEntity keyless = new CarrierJpaEntity(UUID.randomUUID(), "OLD");
        keyless.apply("OLD", "Ahamove", null, null, "ACTIVE");

        assertThat(verifier.verify(keyless, matched(stop(CarrierEventKind.DELIVERED, 1))))
                .contains("could not be asked").contains(ORDER);
        AHAMOVE.verify(0, postRequestedFor(urlEqualTo("/v3/accounts/token")));
    }
}
