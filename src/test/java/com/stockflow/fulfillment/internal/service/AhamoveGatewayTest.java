package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Ahamove's partner API v3 as the gateway calls it (3-4; developers.ahamove.com): the token asked for once and asked
 * again on a 401, the order placed with the warehouse first and each shipment a drop-off, a refusal carrying Ahamove's
 * code, no answer told apart from a refusal, a cancel, the truck services of a city - and every call logged, a token
 * call without its key.
 */
class AhamoveGatewayTest {

    private static final String STAGING = "https://partner-apistg.ahamove.com";
    private static final String KEY = "aha-key-0123456789";

    private final CarrierApiCalls calls = mock(CarrierApiCalls.class);
    private MockRestServiceServer server;
    private AhamoveGateway gateway;
    private CarrierBookingGateway.Caller caller;

    @BeforeEach
    void aGatewayOnAMockServer() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClientFactory clients = new RestClientFactory(new HttpClientProperties(Duration.ofSeconds(1),
                Duration.ofSeconds(1))) {
            @Override
            public RestClient.Builder forService(String serviceName, String baseUrl) {
                return builder.baseUrl(baseUrl);
            }
        };
        gateway = new AhamoveGateway(clients, new AhamoveProperties(STAGING, STAGING, "Khách hàng muốn hủy đơn", null),
                calls, new ObjectMapper());
        caller = new CarrierBookingGateway.Caller(UUID.randomUUID(), UUID.randomUUID(), CarrierApiEnvironment.STAGING,
                "84908000000", KEY);
    }

    private void token(String token) {
        server.expect(requestTo(STAGING + "/v3/accounts/token")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.mobile").value("84908000000")).andExpect(jsonPath("$.api_key").value(KEY))
                .andRespond(withSuccess("{\"token\":\"" + token + "\",\"refresh_token\":\"r\"}",
                        MediaType.APPLICATION_JSON));
    }

    private static CarrierBookingGateway.Order order() {
        return new CarrierBookingGateway.Order("SGN-TRUCK-1000", Instant.ofEpochSecond(1_800_000_000L), List.of(
                new CarrierBookingGateway.Stop("1 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh", "Kho HCM", "0909000111", null),
                new CarrierBookingGateway.Stop("2 Hai Bà Trưng, Quận 1, Hồ Chí Minh", "Lan", "0912345678",
                        "SO-1-S1")), "LD-20261109-0001");
    }

    @Test
    @DisplayName("an order: the token asked for once, then the route with the warehouse first, the business paying")
    void placesAnOrder() {
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t1"))
                .andExpect(jsonPath("$.order_time").value(1_800_000_000L))
                .andExpect(jsonPath("$.service_id").value("SGN-TRUCK-1000"))
                .andExpect(jsonPath("$.payment_method").value("BALANCE"))
                .andExpect(jsonPath("$.remarks").value("LD-20261109-0001"))
                .andExpect(jsonPath("$.requests").isEmpty())
                .andExpect(jsonPath("$.path[0].address").value("1 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh"))
                .andExpect(jsonPath("$.path[0].tracking_number").doesNotExist())
                .andExpect(jsonPath("$.path[1].tracking_number").value("SO-1-S1"))
                .andExpect(jsonPath("$.path[1].mobile").value("0912345678"))
                .andRespond(withSuccess("{\"order_id\":\"24ABCD\",\"status\":\"ASSIGNING\","
                        + "\"shared_link\":\"https://expressstg.ahamove.com/s/24ABCD\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/orders")).andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("{\"order_id\":\"24ABCE\",\"order\":{\"_id\":\"24ABCE\"}}",
                        MediaType.APPLICATION_JSON));

        CarrierBookingGateway.Placed placed = gateway.place(caller, order());
        CarrierBookingGateway.Placed again = gateway.place(caller, order());

        assertThat(placed).isEqualTo(new CarrierBookingGateway.Placed("24ABCD",
                "https://expressstg.ahamove.com/s/24ABCD"));
        assertThat(again.orderId()).isEqualTo("24ABCE");
        server.verify();
        // The token call is logged without its key or its token.
        verify(calls).record(eq(caller.carrierId()), eq(caller.bookingId()), eq("TOKEN"),
                eq(CarrierApiCalls.Outcome.OK), eq(200), isNull(), isNull(), isNull(), anyLong());
    }

    @Test
    @DisplayName("a 401 asks for a new token once and makes the call again")
    void renewsAStaleToken() {
        token("old");
        server.expect(requestTo(STAGING + "/v3/orders")).andExpect(header("Authorization", "Bearer old"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"code\":\"NOT_AUTHORIZED\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        token("new");
        server.expect(requestTo(STAGING + "/v3/orders")).andExpect(header("Authorization", "Bearer new"))
                .andRespond(withSuccess("{\"order_id\":\"24ABCD\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.place(caller, order()).orderId()).isEqualTo("24ABCD");
        server.verify();
    }

    @Test
    @DisplayName("a refusal carries Ahamove's code and words; no answer is told apart: the carrier may have acted")
    void refusalsAndSilence() {
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders")).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE)
                .body("{\"code\":\"SERVICE_NOT_VALID_AT_PICKUP\",\"title\":\"Dịch vụ không hỗ trợ tại điểm lấy\"}")
                .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/orders")).andRespond(withException(new IOException("timed out")));

        CarrierBookingGateway.CarrierCallFailed refused = catchThrowableOfType(CarrierBookingGateway.CarrierCallFailed.class,
                () -> gateway.place(caller, order()));
        CarrierBookingGateway.CarrierCallFailed silent = catchThrowableOfType(CarrierBookingGateway.CarrierCallFailed.class,
                () -> gateway.place(caller, order()));

        assertThat(refused.answered()).isTrue();
        assertThat(refused.code()).isEqualTo("SERVICE_NOT_VALID_AT_PICKUP");
        assertThat(refused.getMessage()).contains("406").contains("SERVICE_NOT_VALID_AT_PICKUP")
                .contains("Dịch vụ không hỗ trợ");
        assertThat(silent.answered()).isFalse();
        verify(calls).record(any(), any(), eq("BOOK"), eq(CarrierApiCalls.Outcome.NO_ANSWER), isNull(), any(),
                isNull(), any(), anyLong());
        server.verify();
    }

    @Test
    @DisplayName("a cancel names the order and sends a reason from Ahamove's list; the services of a city are listed")
    void cancelsAndListsServices() {
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders/24ABCD")).andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("{\"comment\":\"Khách hàng muốn hủy đơn\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/services?city_id=SGN&delivery_type=TRUCK"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"_id\":\"SGN-TRUCK-500\",\"city_id\":\"SGN\",\"name_vi_vn\":\"Xe tải 500kg\"},"
                        + "{\"_id\":\"SGN-TRUCK-1000\",\"city_id\":\"SGN\"}]", MediaType.APPLICATION_JSON));

        gateway.cancel(caller, "24ABCD");
        List<CarrierBookingGateway.ServiceOption> services = gateway.services(caller, "SGN");

        assertThat(services).containsExactly(
                new CarrierBookingGateway.ServiceOption("SGN-TRUCK-500", "Xe tải 500kg", "SGN"),
                new CarrierBookingGateway.ServiceOption("SGN-TRUCK-1000", "SGN-TRUCK-1000", "SGN"));
        server.verify();
    }

    @Test
    @DisplayName("a 5xx is no answer to rely on - the order may exist; a log that cannot be written fails nothing")
    void serverErrorsAreNotRefusals() {
        org.mockito.Mockito.doThrow(new IllegalStateException("pool exhausted")).when(calls).record(any(), any(),
                eq("BOOK"), any(), any(), any(), any(), any(), anyLong());
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders")).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                .body("<html>bad gateway</html>"));
        server.expect(requestTo(STAGING + "/v3/orders")).andRespond(withSuccess("{\"order_id\":\"24ABCD\"}",
                MediaType.APPLICATION_JSON));

        CarrierBookingGateway.CarrierCallFailed gateway502 = catchThrowableOfType(
                CarrierBookingGateway.CarrierCallFailed.class, () -> gateway.place(caller, order()));

        assertThat(gateway502.answered()).isFalse();
        assertThat(gateway502.getMessage()).startsWith("502");
        // The order Ahamove made reaches the caller even though its log line failed.
        assertThat(gateway.place(caller, order()).orderId()).isEqualTo("24ABCD");
        server.verify();
    }

    @Test
    @DisplayName("an order's status is read after a refused cancel; an order Ahamove does not know is empty")
    void readsAnOrdersStatus() {
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders/24ABCD")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"_id\":\"24ABCD\",\"status\":\"cancelled\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/orders/24ABCE")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("{\"code\":\"ORDER_NOT_FOUND\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(gateway.orderStatus(caller, "24ABCD")).contains("CANCELLED");
        assertThat(gateway.orderStatus(caller, "24ABCE")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("an order is read whole before a final outcome its callback reported is applied: its status, its "
            + "sub-status and each drop-off's status, the pickup left out; an order with no body is not one")
    void readsAnOrdersState() {
        token("t1");
        server.expect(requestTo(STAGING + "/v3/orders/24ABCD")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("{\"order\":{\"_id\":\"24ABCD\",\"status\":\"completed\",\"sub_status\":"
                        + "\"returned\",\"path\":[{\"address\":\"kho\",\"status\":\"\"},{\"status\":\"Completed\"},"
                        + "{\"address\":\"2 Hai Bà Trưng\"},{\"status\":\"FAILED\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/orders/24ABCF")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        CarrierBookingGateway.OrderState state = gateway.orderState(caller, "24ABCD").orElseThrow();
        assertThat(state.status()).isEqualTo("COMPLETED");
        assertThat(state.subStatus()).isEqualTo("RETURNED");
        assertThat(state.stopStatuses()).containsExactly("COMPLETED", "", "FAILED");
        assertThat(state.stopStatus(1)).isEqualTo("COMPLETED");
        assertThat(state.stopStatus(3)).isEqualTo("FAILED");
        assertThat(state.stopStatus(0)).isNull();
        assertThat(state.stopStatus(4)).isNull();
        assertThat(gateway.orderState(caller, "24ABCF")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("nothing logged ever holds the key or the token")
    void theLogHoldsNoSecret() {
        token("tok-secret-0123456789");
        server.expect(requestTo(STAGING + "/v3/orders")).andRespond(withSuccess("{\"order_id\":\"24ABCD\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(STAGING + "/v3/orders/24ABCD")).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE)
                .body("{\"code\":\"INVALID_ORDER_STATUS\"}").contentType(MediaType.APPLICATION_JSON));

        gateway.place(caller, order());
        catchThrowableOfType(CarrierBookingGateway.CarrierCallFailed.class, () -> gateway.cancel(caller, "24ABCD"));

        org.mockito.ArgumentCaptor<String> requests = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> responses = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> errors = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(calls, org.mockito.Mockito.times(3)).record(any(), any(), any(), any(), any(), requests.capture(),
                responses.capture(), errors.capture(), anyLong());
        java.util.List<String> logged = new java.util.ArrayList<>();
        logged.addAll(requests.getAllValues());
        logged.addAll(responses.getAllValues());
        logged.addAll(errors.getAllValues());
        assertThat(logged).filteredOn(java.util.Objects::nonNull)
                .noneMatch(text -> text.contains(KEY) || text.contains("tok-secret-0123456789"));
        server.verify();
    }

    @Test
    @DisplayName("the caller never prints its key")
    void callerMasksTheKey() {
        assertThat(caller.toString()).doesNotContain(KEY).contains("84908000000");
    }
}
