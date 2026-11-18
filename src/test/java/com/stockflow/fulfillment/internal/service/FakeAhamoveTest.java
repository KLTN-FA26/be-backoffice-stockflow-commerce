package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The fake Ahamove (stockflow.fulfillment.ahamove.mode=fake), without a database: that what it reports reads, through
 * the system's own {@link AhamoveWebhookReader}, as the events 09 §4.5 maps; that the order it keeps agrees with what
 * the check made before a final outcome is applied ({@link AhamoveOutcomeVerifier#mismatch}) asks of it; Ahamove's
 * cancel rule; a step out of turn; a step the system did not take put back; and that it exists in fake mode only.
 */
class FakeAhamoveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODE = "AHAMOVE";

    private final UUID carrierId = Identifiers.newId();
    private final List<byte[]> sent = new ArrayList<>();
    private final AtomicReference<String> verdict = new AtomicReference<>("APPLIED");
    private FakeAhamoveGateway gateway;
    private FakeAhamoveDriver driver;
    private CarrierBookingGateway.Caller caller;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"), ZoneOffset.UTC);
        FakeAhamoveOrders orders = new FakeAhamoveOrders(new MockEnvironment(), clock);
        gateway = new FakeAhamoveGateway(orders, mock(CarrierApiCalls.class), JSON, clock);
        CarrierJpaRepository carriers = mock(CarrierJpaRepository.class);
        CarrierJpaEntity carrier = mock(CarrierJpaEntity.class);
        when(carrier.getCode()).thenReturn(CODE);
        when(carriers.findById(carrierId)).thenReturn(Optional.of(carrier));
        FakeAhamoveCallbacks callbacks = (code, body) -> {
            assertThat(code).isEqualTo(CODE);
            sent.add(body);
            return new CarrierEvents.Receipt(Identifiers.newId(), verdict.get(), true, false);
        };
        driver = new FakeAhamoveDriver(orders, callbacks, mock(CarrierEvents.class, Mockito.RETURNS_DEEP_STUBS),
                carriers, JSON);
        caller = new CarrierBookingGateway.Caller(carrierId, null, null, "0900000000", CarrierSecrets.FAKE_KEY);
    }

    /** An order of a pickup and two drop-offs, as the booking builds it. */
    private String place() {
        return gateway.place(caller, new CarrierBookingGateway.Order("SGN-TRUCK-2000", null, List.of(
                new CarrierBookingGateway.Stop("1 Le Loi", "Kho HCM", "0909000111", null),
                new CarrierBookingGateway.Stop("2 Hai Ba Trung", "Lan", "0912345678", "SH-1"),
                new CarrierBookingGateway.Stop("3 Nguyen Hue", "Mai", "0987654321", "SH-2")), "LD-1")).orderId();
    }

    private JsonNode lastBody() throws Exception {
        return JSON.readTree(new String(sent.get(sent.size() - 1), StandardCharsets.UTF_8));
    }

    private CarrierReport lastReport() throws Exception {
        return AhamoveWebhookReader.read(lastBody());
    }

    private FakeAhamove.Played step(String order, FakeAhamove.Step step) {
        return driver.send(order, step, FakeAhamove.Details.none());
    }

    // ---- placing

    @Test
    @DisplayName("an order placed gets an id of letters and digits, as Ahamove's are (the load's waybill number), a "
            + "tracking link, and is looking for a driver; the call is not logged as anything but OK")
    void placing() {
        CarrierBookingGateway.Placed placed = gateway.place(caller, new CarrierBookingGateway.Order("HAN-TRUCK-1000",
                null, List.of(new CarrierBookingGateway.Stop("A", null, null, null),
                new CarrierBookingGateway.Stop("B", null, null, "SH-9")), "LD-9"));

        assertThat(placed.orderId()).matches("^FK[A-Z0-9]{12}$");
        assertThat(placed.trackingLink()).contains(placed.orderId());
        CarrierBookingGateway.OrderState state = gateway.orderState(caller, placed.orderId()).orElseThrow();
        assertThat(state.status()).isEqualTo("ASSIGNING");
        assertThat(state.stopStatuses()).containsExactly("");
        assertThat(gateway.orderState(caller, "NOPE")).isEmpty();
    }

    @Test
    @DisplayName("an order with no drop-off is refused as Ahamove refuses it (answered, so a refusal, not a silence)")
    void anOrderNeedsADropOff() {
        assertThatThrownBy(() -> gateway.place(caller, new CarrierBookingGateway.Order("HAN-TRUCK-1000", null,
                List.of(new CarrierBookingGateway.Stop("A", null, null, null)), "LD-9")))
                .isInstanceOfSatisfying(CarrierBookingGateway.CarrierCallFailed.class,
                        failed -> assertThat(failed.answered()).isTrue());
    }

    @Test
    @DisplayName("the services of a city, for the carrier screen's list")
    void services() {
        assertThat(gateway.services(caller, "han")).extracting(CarrierBookingGateway.ServiceOption::id)
                .containsExactly("HAN-TRUCK-500", "HAN-TRUCK-1000", "HAN-TRUCK-2000");
    }

    // ---- what it reports, read by the system's own reader

    @Test
    @DisplayName("every step reads, by AhamoveWebhookReader, as the event 09 §4.5 maps it - the driver's name and "
            + "phone on the acceptance, the drop-off by its number and shipment code, the failure's wording - and the "
            + "times only ever rise")
    void theCallbacksReadAsAhamovesDo() throws Exception {
        String order = place();
        List<Instant> times = new ArrayList<>();

        step(order, FakeAhamove.Step.ASSIGNING);
        assertThat(lastReport().kind()).isEqualTo(CarrierEventKind.DRIVER_RELEASED);
        assertThat(lastReport().reference()).isEqualTo(order);

        driver.send(order, FakeAhamove.Step.ACCEPT, new FakeAhamove.Details(null, null, "Le Van Tai", "0911222333",
                "51C-123.45"));
        CarrierReport accepted = lastReport();
        assertThat(accepted.kind()).isEqualTo(CarrierEventKind.DRIVER_ASSIGNED);
        assertThat(accepted.driverName()).isEqualTo("Le Van Tai");
        assertThat(accepted.driverPhone()).isEqualTo("0911222333");
        times.add(accepted.reportedAt());
        // Ahamove's callback carries no plate; the fake keeps the one it was given on its own order.
        assertThat(lastBody().toString()).doesNotContain("51C-123.45");
        assertThat(driver.order(order).vehiclePlate()).isEqualTo("51C-123.45");

        step(order, FakeAhamove.Step.PICKUP);
        assertThat(lastReport().kind()).isEqualTo(CarrierEventKind.PICKED_UP);
        times.add(lastReport().reportedAt());

        step(order, FakeAhamove.Step.EN_ROUTE);
        assertThat(lastReport().kind()).isEqualTo(CarrierEventKind.IN_TRANSIT);

        step(order, FakeAhamove.Step.DELIVER);
        CarrierReport delivered = lastReport();
        assertThat(delivered.kind()).isEqualTo(CarrierEventKind.DELIVERED);
        assertThat(delivered.reference()).isEqualTo(order);
        assertThat(delivered.stop()).isEqualTo(1);
        assertThat(delivered.shipmentCode()).isEqualTo("SH-1");
        times.add(delivered.reportedAt());
        assertVerified(order, delivered, List.of());

        driver.send(order, FakeAhamove.Step.FAIL, new FakeAhamove.Details(null, "CUSTOMER_REFUSED", null, null,
                null));
        CarrierReport failed = lastReport();
        assertThat(failed.kind()).isEqualTo(CarrierEventKind.DELIVERY_FAILED);
        assertThat(failed.stop()).isEqualTo(2);
        assertThat(failed.shipmentCode()).isEqualTo("SH-2");
        assertThat(failed.failureWording()).isEqualTo("CUSTOMER_REFUSED");
        times.add(failed.reportedAt());
        assertVerified(order, failed, List.of());

        step(order, FakeAhamove.Step.COMPLETE);
        CarrierReport completed = lastReport();
        assertThat(completed.kind()).isEqualTo(CarrierEventKind.ORDER_COMPLETED);
        List<CarrierReport> stops = AhamoveWebhookReader.stops(lastBody(), order);
        assertThat(stops).extracting(CarrierReport::kind).containsExactly(CarrierEventKind.DELIVERED,
                CarrierEventKind.DELIVERY_FAILED);
        assertVerified(order, completed, stops);
        times.add(completed.reportedAt());

        assertThat(times).isSorted().doesNotHaveDuplicates();
    }

    /** What the callback says, the fake's own order bears out: the check made before an outcome is applied passes. */
    private void assertVerified(String order, CarrierReport report, List<CarrierReport> stops) {
        assertThat(AhamoveOutcomeVerifier.mismatch(report, stops, gateway.orderState(caller, order).orElseThrow()))
                .isNull();
    }

    @Test
    @DisplayName("failed goods coming back: COMPLETED/IN_RETURN then COMPLETED/RETURNED, the second verified against the "
            + "order the fake holds")
    void returning() throws Exception {
        String order = place();
        step(order, FakeAhamove.Step.ACCEPT);
        step(order, FakeAhamove.Step.PICKUP);
        driver.send(order, FakeAhamove.Step.FAIL, new FakeAhamove.Details(1, null, null, null, null));
        assertThat(lastReport().failureWording()).isEqualTo("CUSTOMER_NOT_AVAILABLE");
        driver.send(order, FakeAhamove.Step.FAIL, new FakeAhamove.Details(2, "WRONG_ADDRESS", null, null, null));

        step(order, FakeAhamove.Step.RETURNING);
        assertThat(lastReport().kind()).isEqualTo(CarrierEventKind.RETURNING);
        step(order, FakeAhamove.Step.RETURNED);
        CarrierReport returned = lastReport();
        assertThat(returned.kind()).isEqualTo(CarrierEventKind.RETURNED);
        assertVerified(order, returned, List.of());
    }

    @Test
    @DisplayName("the driver giving the order back is DRIVER_RELEASED at the acceptance it undoes; Ahamove cancelling "
            + "it is ORDER_CANCELLED (not the partner's own cancel), verified as CANCELLED")
    void releasedAndCancelledByTheCarrier() throws Exception {
        String order = place();
        step(order, FakeAhamove.Step.ACCEPT);
        Instant acceptedAt = lastReport().reportedAt();
        step(order, FakeAhamove.Step.ASSIGNING);
        assertThat(lastReport().kind()).isEqualTo(CarrierEventKind.DRIVER_RELEASED);
        assertThat(lastReport().reportedAt()).isEqualTo(acceptedAt);
        assertThat(driver.order(order).driverName()).isNull();

        driver.send(order, FakeAhamove.Step.CARRIER_CANCEL, new FakeAhamove.Details(null, "no driver", null, null,
                null));
        CarrierReport cancelled = lastReport();
        assertThat(cancelled.kind()).isEqualTo(CarrierEventKind.ORDER_CANCELLED);
        assertThat(cancelled.failureWording()).isEqualTo("no driver");
        assertVerified(order, cancelled, List.of());
    }

    // ---- Ahamove's cancel rule

    @Test
    @DisplayName("cancel works until pickup (with or without a driver) and is refused after it, and once cancelled; "
            + "a refusal changes nothing")
    void cancelBeforePickupOnly() {
        String early = place();
        gateway.cancel(caller, early);
        assertThat(gateway.orderState(caller, early).orElseThrow().status()).isEqualTo("CANCELLED");
        assertRefused(() -> gateway.cancel(caller, early));

        String accepted = place();
        step(accepted, FakeAhamove.Step.ACCEPT);
        gateway.cancel(caller, accepted);
        assertThat(driver.order(accepted).status()).isEqualTo("CANCELLED");

        String underWay = place();
        step(underWay, FakeAhamove.Step.ACCEPT);
        step(underWay, FakeAhamove.Step.PICKUP);
        assertRefused(() -> gateway.cancel(caller, underWay));
        assertThat(gateway.orderState(caller, underWay).orElseThrow().status()).isEqualTo("IN PROCESS");
        assertRefused(() -> gateway.cancel(caller, "NOPE"));
    }

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CarrierBookingGateway.CarrierCallFailed.class,
                failed -> assertThat(failed.answered()).isTrue());
    }

    // ---- turns

    @Test
    @DisplayName("a step out of turn is refused, as is an order the fake does not hold")
    void outOfTurn() {
        String order = place();
        assertThatThrownBy(() -> step(order, FakeAhamove.Step.PICKUP)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> step(order, FakeAhamove.Step.DELIVER)).isInstanceOf(BusinessException.class);
        step(order, FakeAhamove.Step.ACCEPT);
        assertThatThrownBy(() -> step(order, FakeAhamove.Step.ACCEPT)).isInstanceOf(BusinessException.class);
        step(order, FakeAhamove.Step.PICKUP);
        assertThatThrownBy(() -> step(order, FakeAhamove.Step.COMPLETE)).as("drop-offs unsettled")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> step(order, FakeAhamove.Step.CARRIER_CANCEL)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> driver.send(order, FakeAhamove.Step.DELIVER,
                new FakeAhamove.Details(3, null, null, null, null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> step("NOPE", FakeAhamove.Step.ACCEPT)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("a step the system did not take (the load has not left the warehouse: NEEDS_REVIEW) is put back, so it "
            + "can be sent again after the handover")
    void aStepTheSystemRefusedIsUndone() {
        String order = place();
        step(order, FakeAhamove.Step.ACCEPT);
        verdict.set("NEEDS_REVIEW");

        FakeAhamove.Played refused = step(order, FakeAhamove.Step.PICKUP);

        assertThat(refused.callbacks().get(0).result()).isEqualTo("NEEDS_REVIEW");
        assertThat(refused.order().status()).isEqualTo("ACCEPTED");
        verdict.set("APPLIED");
        assertThat(step(order, FakeAhamove.Step.PICKUP).order().status()).isEqualTo("IN PROCESS");
    }

    // ---- play

    @Test
    @DisplayName("play: a driver, the pickup, every drop-off delivered, the order completed - and a failed variant that "
            + "ends with the goods back; it stops at the first step the system did not take")
    void play() {
        String delivered = place();
        FakeAhamove.Played all = driver.play(delivered, true, null);
        assertThat(all.callbacks()).extracting(FakeAhamove.Callback::step).containsExactly("ACCEPT", "PICKUP",
                "DELIVER", "DELIVER", "COMPLETE");
        assertThat(all.order().status()).isEqualTo("COMPLETED");
        assertThat(all.order().stops()).extracting(FakeAhamove.StopView::status).containsExactly("COMPLETED",
                "COMPLETED", "COMPLETED");

        String failed = place();
        FakeAhamove.Played back = driver.play(failed, false, null);
        assertThat(back.callbacks()).extracting(FakeAhamove.Callback::step).containsExactly("ACCEPT", "PICKUP",
                "FAIL", "FAIL", "RETURNING", "RETURNED");
        assertThat(back.order().subStatus()).isEqualTo("RETURNED");
        assertThat(back.order().stops().get(1).failComment()).isEqualTo("CUSTOMER_NOT_AVAILABLE");

        String stuck = place();
        verdict.set("NEEDS_REVIEW");
        FakeAhamove.Played stopped = driver.play(stuck, true, null);
        assertThat(stopped.callbacks()).hasSize(1);
        assertThat(stopped.order().status()).isEqualTo("ASSIGNING");
    }

    // ---- fake mode only

    @Test
    @DisplayName("the fake refuses to exist under the prod profile")
    void notInProduction() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThatThrownBy(() -> new FakeAhamoveOrders(production, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
    }

    @Test
    @DisplayName("fake beans exist only with mode=fake; the mode is live by default")
    void onlyInFakeMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withBean(Clock.class, Clock::systemUTC)
                .withBean(MockEnvironment.class).withUserConfiguration(FakeAhamoveOrders.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(FakeAhamoveOrders.class));
        runner.withPropertyValues("stockflow.fulfillment.ahamove.mode=live")
                .run(context -> assertThat(context).doesNotHaveBean(FakeAhamoveOrders.class));
        runner.withPropertyValues("stockflow.fulfillment.ahamove.mode=fake")
                .run(context -> assertThat(context).hasSingleBean(FakeAhamoveOrders.class));
        assertThat(new AhamoveProperties("a", "b", "c", null).mode()).isEqualTo(AhamoveProperties.Mode.LIVE);
        assertThat(new AhamoveProperties("a", "b", "c", null).fake()).isFalse();
    }

    @Test
    @DisplayName("in fake mode a carrier's sealed key is not opened (no secrets key is needed); in live mode it is")
    void theSealedKeyIsNotOpenedInFakeMode() {
        SecretCipher cipher = mock(SecretCipher.class);
        CarrierJpaEntity carrier = mock(CarrierJpaEntity.class);
        when(carrier.getId()).thenReturn(carrierId);
        when(carrier.getApiKeySealed()).thenReturn("v1:ZmFrZQ==");
        when(carrier.getApiEnvironment()).thenReturn("STAGING");
        when(carrier.getApiAccount()).thenReturn("0900000000");
        AhamoveProperties fake = new AhamoveProperties("a", "b", "c", null, AhamoveProperties.Mode.FAKE);

        CarrierBookingGateway.Caller opened = new CarrierSecrets(cipher, fake).caller(carrier, null);

        assertThat(opened.apiKey()).isEqualTo(CarrierSecrets.FAKE_KEY);
        verifyNoInteractions(cipher);
        when(cipher.open(any(), any())).thenReturn("clear-key");
        assertThat(new CarrierSecrets(cipher).caller(carrier, null).apiKey()).isEqualTo("clear-key");
    }
}
