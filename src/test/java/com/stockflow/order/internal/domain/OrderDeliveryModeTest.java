package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * LP-1 / LP-2 (lead's decisions 2026-10-22) on the aggregate alone: the Order Coordinator chooses how an order travels
 * until packing starts; a full truckload never has its balance collected on delivery, whichever is changed second.
 */
class OrderDeliveryModeTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 22), 21);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    /** Two stock sofas, 10 000 000 VND each, on {@code term}; still a DRAFT. */
    private static Order draft(PaymentTerm term, BalancePolicy policy) {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("SOFA-3S-GREY"), 2, Money.vnd(10_000_000), null)), NOW);
        order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null,
                term == PaymentTerm.CREDIT || policy == BalancePolicy.ON_CREDIT ? 30 : null,
                term == PaymentTerm.DEPOSIT ? policy : null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        return order;
    }

    /** Paid (in full, or its deposit) and CONFIRMED. */
    private static Order confirmed(PaymentTerm term, BalancePolicy policy) {
        Order order = draft(term, policy);
        order.submit();
        order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(6_000_000), NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        return order;
    }

    private static Order released(PaymentTerm term, BalancePolicy policy) {
        Order order = confirmed(term, policy);
        order.release(HCM, "HCM", STAFF, NOW, Map.of());
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Nested
    @DisplayName("LP-1: chosen until packing starts")
    class UntilPacking {

        @Test
        @DisplayName("PARCEL by default; changed at CONFIRMED, READY_TO_FULFILL and PICKING; the same mode again changes "
                + "nothing")
        void openUntilPacking() {
            Order order = confirmed(PaymentTerm.PREPAID, null);
            assertThat(order.deliveryMode()).isEqualTo(DeliveryMode.PARCEL);
            assertThat(order.deliveryModeLocked()).isFalse();

            assertThat(order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();
            assertThat(order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).as("the same mode").isFalse();
            order.release(HCM, "HCM", STAFF, NOW, Map.of());
            assertThat(order.changeDeliveryMode(DeliveryMode.PARCEL)).isTrue();
            order.startPicking(STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
            assertThat(order.deliveryModeLocked()).isFalse();
            assertThat(order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();
            assertThat(order.deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
        }

        @Test
        @DisplayName("the first package opened locks it (409 DELIVERY_MODE_LOCKED) and the mode stays; recorded once")
        void lockedOncePackingStarted() {
            Order order = released(PaymentTerm.PREPAID, null);
            order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD);
            order.startPicking(STAFF, NOW);

            assertThat(order.recordPackingStarted(NOW)).isTrue();
            assertThat(order.recordPackingStarted(NOW.plusSeconds(60))).as("idempotent").isFalse();
            assertThat(order.packingStartedAt()).isEqualTo(NOW);
            assertThat(order.status()).as("the status does not move").isEqualTo(OrderStatus.PICKING);
            assertThat(order.deliveryModeLocked()).isTrue();
            assertThat(code(catchThrowable(() -> order.changeDeliveryMode(DeliveryMode.PARCEL))))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_LOCKED);
            assertThat(code(catchThrowable(() -> order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD))))
                    .as("even the same mode: the answer does not depend on the request")
                    .isEqualTo(ErrorCode.DELIVERY_MODE_LOCKED);
            assertThat(order.deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
        }

        @Test
        @DisplayName("a package completed (rows packed before LP-1), PACKED, PARTIALLY_FULFILLED and later: locked")
        void lockedPastPacking() {
            Order packed = released(PaymentTerm.PREPAID, null);
            packed.startPicking(STAFF, NOW);
            packed.recordPacked(NOW);
            assertThat(packed.deliveryModeLocked()).as("a package completed").isTrue();
            packed.markPacked(NOW, STAFF);
            assertThat(code(catchThrowable(() -> packed.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD))))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_LOCKED);
            packed.recordHandover(new ShipmentHandover(UUID.randomUUID(), List.of(new ShipmentHandover.Line(
                    packed.lines().get(0).id(), 2)), NOW, STAFF));
            assertThat(packed.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(packed.deliveryModeLocked()).isTrue();
        }

        @Test
        @DisplayName("cancelled: locked, and nothing of it starts packing (ORDER_CANCELLED)")
        void cancelled() {
            Order order = released(PaymentTerm.PREPAID, null);
            order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, "đổi ý", null, STAFF, NOW);
            assertThat(code(catchThrowable(() -> order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD))))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_LOCKED);
            assertThat(code(catchThrowable(() -> order.recordPackingStarted(NOW))))
                    .isEqualTo(ErrorCode.ORDER_CANCELLED);
        }

        @Test
        @DisplayName("on hold: open when held before packing (PICKING, a credit hold), locked when held from PACKED")
        void onHold() {
            Order picking = released(PaymentTerm.PREPAID, null);
            picking.startPicking(STAFF, NOW);
            picking.putOnHold(HoldReason.ADDRESS, "sai địa chỉ", STAFF, NOW);
            assertThat(picking.deliveryModeLocked()).isFalse();
            assertThat(picking.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();

            Order credit = draft(PaymentTerm.CREDIT, null);
            credit.submitOnCredit(false);
            assertThat(credit.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(credit.deliveryModeLocked()).as("a credit hold names no status to go back to").isFalse();

            Order packed = released(PaymentTerm.PREPAID, null);
            packed.startPicking(STAFF, NOW);
            packed.markPacked(NOW, STAFF);
            packed.putOnHold(HoldReason.CUSTOMER, "khách hẹn lại", STAFF, NOW);
            assertThat(packed.deliveryModeLocked()).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(OrderStatus.class)
        @DisplayName("the list row's reading: open up to PICKING (and on hold from there), locked from "
                + "PARTIALLY_FULFILLED on")
        void listRowReading(OrderStatus status) {
            boolean open = switch (status) {
                case DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, ON_HOLD -> true;
                default -> false;
            };
            assertThat(Order.deliveryModeLocked(status, null, null, null)).isEqualTo(!open);
            assertThat(Order.deliveryModeLocked(status, null, NOW, null)).as("packing started").isTrue();
            assertThat(Order.deliveryModeLocked(status, null, null, NOW)).as("a package completed").isTrue();
            if (status != OrderStatus.ON_HOLD) {
                assertThat(Order.deliveryModeLocked(OrderStatus.ON_HOLD, status, null, null))
                        .as("held from %s", status).isEqualTo(!open);
            }
        }
    }

    @Nested
    @DisplayName("LP-2: a full truckload is never paid on delivery")
    class NoCodOnFullTruckload {

        @Test
        @DisplayName("FULL_TRUCKLOAD for a deposit order collected on delivery: 409 DELIVERY_MODE_FTL_NO_COD, still "
                + "PARCEL; the other policies and terms may")
        void ftlRefusedForCod() {
            Order cod = confirmed(PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY);
            assertThat(code(catchThrowable(() -> cod.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD))))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_FTL_NO_COD);
            assertThat(cod.deliveryMode()).isEqualTo(DeliveryMode.PARCEL);
            assertThat(cod.changeDeliveryMode(DeliveryMode.PARCEL)).as("parcels stay fine").isFalse();

            assertThat(confirmed(PaymentTerm.DEPOSIT, BalancePolicy.BEFORE_DELIVERY)
                    .changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();
            assertThat(confirmed(PaymentTerm.PREPAID, null).changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();
            Order credit = draft(PaymentTerm.CREDIT, null);
            credit.submitOnCredit(true);
            assertThat(credit.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD)).isTrue();
        }

        @Test
        @DisplayName("the other way round: a full-truckload order switched to deposit with its balance on delivery is "
                + "refused (409 DELIVERY_MODE_FTL_NO_COD) and keeps its terms and its hold")
        void codRefusedForFtl() {
            Order order = draft(PaymentTerm.CREDIT, null);
            order.submitOnCredit(false);
            order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD);

            assertThat(code(catchThrowable(() -> order.switchTermsAfterCreditRefusal(PaymentTerm.DEPOSIT,
                    new BigDecimal("30"), BalancePolicy.ON_DELIVERY)))).isEqualTo(ErrorCode.DELIVERY_MODE_FTL_NO_COD);
            assertThat(order.paymentTerm()).isEqualTo(PaymentTerm.CREDIT);
            assertThat(order.balancePolicy()).isNull();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);

            order.switchTermsAfterCreditRefusal(PaymentTerm.DEPOSIT, new BigDecimal("30"),
                    BalancePolicy.BEFORE_DELIVERY);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        }

        @Test
        @DisplayName("a draft set to a full truckload takes no ON_DELIVERY terms; ON_CREDIT and BEFORE_DELIVERY it does")
        void draftTerms() {
            Order order = draft(PaymentTerm.PREPAID, null);
            order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD);
            assertThat(code(catchThrowable(() -> order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null,
                    BalancePolicy.ON_DELIVERY)))).isEqualTo(ErrorCode.DELIVERY_MODE_FTL_NO_COD);
            assertThat(order.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30, BalancePolicy.ON_CREDIT);
            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.ON_CREDIT);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null, null);
            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        }
    }
}
