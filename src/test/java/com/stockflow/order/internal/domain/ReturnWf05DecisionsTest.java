package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.ReturnAwaitingRefund;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The WF-05 decisions on the return aggregate alone, one nested class per defect: a refund refused with a reason and a
 * second person (Q-4, BE-12), units back never claimed again (BE-21), receipts opened until the refund is settled and a
 * late parcel sending a proposal back (Q-11, BE-13), nothing decided while a parcel is on a draft receipt (BE-23), staff
 * told a return waits for a proposal (BE-24), the invoice following the units refunded (BE-44), a portal claim decided
 * per delivery (Q-15, BE-18), units of one SKU shared by two lines received once (BE-48), and refunds of one line adding
 * up to its gross to the dong (BE-50).
 */
class ReturnWf05DecisionsTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(2));
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final ReturnPolicy POLICY = ReturnPolicy.defaults();

    private static final UUID CUPS = UUID.randomUUID();
    private static final UUID LIDS = UUID.randomUUID();
    private static final UUID SHIPMENT_1 = UUID.randomUUID();
    private static final UUID SHIPMENT_2 = UUID.randomUUID();

    /** Delivered as a whole: 100 printed cups at 2 000 VND, 50 lids at 500 VND. */
    private static Order order(OrderStatus status) {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 3), CUSTOMER,
                UUID.randomUUID(), List.of(
                new OrderLine(CUPS, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), UUID.randomUUID(), List.of()),
                new OrderLine(LIDS, new Sku("LID-90"), 50, Money.vnd(500), null, List.of())),
                status, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null);
        return order;
    }

    /** Two delivered shipments: 60 cups and 40 lids on the first, 40 cups on the second. */
    private static Order inTwoShipments(OrderStatus status) {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 5), CUSTOMER,
                UUID.randomUUID(), List.of(
                new OrderLine(CUPS, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), UUID.randomUUID(), List.of()),
                new OrderLine(LIDS, new Sku("LID-90"), 50, Money.vnd(500), null, List.of(), 10)),
                status, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null);
        order.restoreShipments(List.of(
                new OrderShipment(SHIPMENT_1, Map.of(CUPS, 60, LIDS, 40), BigDecimal.valueOf(140_000),
                        DELIVERED.minus(Duration.ofDays(1)), SALES, OrderShipmentStatus.DELIVERED, DELIVERED, SALES,
                        null, null),
                new OrderShipment(SHIPMENT_2, Map.of(CUPS, 40), BigDecimal.valueOf(80_000),
                        DELIVERED.minus(Duration.ofDays(1)), SALES, OrderShipmentStatus.DELIVERED, DELIVERED, SALES,
                        null, null)));
        return order;
    }

    private static ReturnRequest misprinted(int cups) {
        return ReturnRequest.open("RMA-20261022-0001", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, "Mực in bị lem", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(CUPS, cups)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
    }

    private static ReturnEvidence claimPhoto() {
        return new ReturnEvidence(UUID.randomUUID(), ReturnEvidence.Stage.CLAIM, "return-evidence/x/" + UUID.randomUUID(),
                "loi.jpg", "image/jpeg", 1024, CUSTOMER, NOW);
    }

    private static ReturnRequest awaiting(int cups) {
        ReturnRequest request = misprinted(cups);
        request.addEvidence(claimPhoto(), POLICY);
        request.approve(SALES, NOW, null, null, null, POLICY);
        return request;
    }

    private static void parcel(ReturnRequest request, int cups) {
        assertThat(request.goodsReceived(UUID.randomUUID(), "GR-RET-" + cups, "HCM-QC01",
                List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", cups)), WAREHOUSE, NOW)).isTrue();
    }

    /** RECEIVED with {@code back} of the {@code cups} approved. */
    private static ReturnRequest received(int cups, int back) {
        ReturnRequest request = awaiting(cups);
        parcel(request, back);
        if (request.status() == ReturnStatus.AWAITING_RETURN) {
            request.markGoodsComplete(SALES, NOW, null);
        }
        return request;
    }

    private static ReturnLine cups(ReturnRequest request) {
        return request.lines().stream().filter(l -> l.orderLineId().equals(CUPS)).findFirst().orElseThrow();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static <T> List<T> events(ReturnRequest request, Class<T> type) {
        List<T> found = new ArrayList<>();
        request.pullDomainEvents().forEach(event -> {
            Object payload = ((OrderEvent.ReturnChanged) event).payload();
            if (type.isInstance(payload)) {
                found.add(type.cast(payload));
            }
        });
        return found;
    }

    private static void refuse(ReturnRequest request, ReturnRefusalReason reason, String note) {
        request.proposeRefund(SALES, NOW, Map.of(cups(request).id(), 0), order(OrderStatus.RETURNED), "internal",
                false, reason, note, Map.of());
    }

    // ================================================================================================= BE-12, Q-4

    @Nested
    @DisplayName("BE-12 (Q-4): goods back usable are refused with a reason from the list, approved by a second person")
    class Refusal {

        @Test
        @DisplayName("nothing refunded of what came back: the reason is required (RETURN_REFUSAL_REASON_REQUIRED)")
        void reasonRequired() {
            ReturnRequest request = received(10, 10);
            assertThat(code(catchThrowable(() -> refuse(request, null, null))))
                    .isEqualTo(ErrorCode.RETURN_REFUSAL_REASON_REQUIRED);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, Map.of(cups(request).id(), 0),
                    order(OrderStatus.RETURNED), "x")))).as("the old overload too")
                    .isEqualTo(ErrorCode.RETURN_REFUSAL_REASON_REQUIRED);
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
        }

        @Test
        @DisplayName("OTHER says what it is; a public note goes with a reason; a reason with everything refunded is "
                + "refused - VALIDATION_FAILED each")
        void reasonAndNoteFitTogether() {
            ReturnRequest request = received(10, 10);
            assertThat(code(catchThrowable(() -> refuse(request, ReturnRefusalReason.OTHER, "  "))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, Map.of(cups(request).id(), 4),
                    order(OrderStatus.RETURNED), null, false, null, "note without a reason", Map.of()))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED),
                    null, false, ReturnRefusalReason.NOT_DEFECTIVE, null, Map.of()))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> refuse(request, ReturnRefusalReason.CUSTOMER_DAMAGE, "x".repeat(501)))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("refused: SETTLEMENT_PENDING at 0, not approved by who proposed it, RESOLVED by the accountant - "
                + "outcome REFUSED, no invoice adjustment, the reason's code announced to the customer")
        void refusedByTwoPeople() {
            ReturnRequest request = received(10, 10);
            refuse(request, ReturnRefusalReason.CUSTOMER_DAMAGE, "Thùng bị ướt do kho của khách");
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
            assertThat(request.settlementAmount()).isZero();
            assertThat(request.refusalReason()).isEqualTo(ReturnRefusalReason.CUSTOMER_DAMAGE);
            assertThat(request.refusalNote()).isEqualTo("Thùng bị ướt do kho của khách");
            assertThat(request.outcome()).as("not decided yet").isNull();

            assertThat(code(catchThrowable(() -> request.resolve(SALES, NOW, ReturnRequest.MoneyOutcome.NONE))))
                    .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            request.pullDomainEvents();
            request.resolve(ACCOUNTANT, NOW, ReturnRequest.MoneyOutcome.NONE);
            assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);
            assertThat(request.outcome()).isEqualTo(ReturnRequest.Outcome.REFUSED);
            assertThat(request.vatAdjustmentRequired()).isFalse();
            assertThat(events(request, ReturnResolved.class)).singleElement().satisfies(resolved -> {
                assertThat(resolved.refusalReasonCode()).isEqualTo("CUSTOMER_DAMAGE");
                assertThat(resolved.vatAdjustmentRequired()).isFalse();
                assertThat(resolved.refundedAmount()).isZero();
            });
        }

        @Test
        @DisplayName("sent back, the refusal goes with the proposal; withholding part may say why and is REFUNDED")
        void sentBackAndPartial() {
            ReturnRequest request = received(10, 10);
            refuse(request, ReturnRefusalReason.OUT_OF_POLICY, null);
            request.rejectSettlement(ACCOUNTANT, NOW, "talk to the customer first");
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.refusalReason()).isNull();
            assertThat(request.refusalNote()).isNull();

            request.proposeRefund(SALES, NOW, Map.of(cups(request).id(), 4), order(OrderStatus.RETURNED), null, false,
                    ReturnRefusalReason.CUSTOMER_DAMAGE, "6 cái bị móp", Map.of());
            request.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(8_000),
                    BigDecimal.ZERO));
            assertThat(request.outcome()).isEqualTo(ReturnRequest.Outcome.REFUNDED);
            assertThat(request.refusalReason()).isEqualTo(ReturnRefusalReason.CUSTOMER_DAMAGE);
        }

        @Test
        @DisplayName("nothing usable came back: still closed by one person, outcome NOTHING_TO_REFUND")
        void nothingUsable() {
            ReturnRequest request = received(10, 0);
            request.closeWithoutRefund(SALES, NOW, "empty carton");
            assertThat(request.outcome()).isEqualTo(ReturnRequest.Outcome.NOTHING_TO_REFUND);
            assertThat(ReturnRequest.outcomeOf(ReturnStatus.RESOLVED, false, ReturnRefusalReason.OTHER))
                    .isEqualTo(ReturnRequest.Outcome.REFUSED);
            assertThat(ReturnRequest.outcomeOf(ReturnStatus.RECEIVED, true, null)).isNull();
        }
    }

    // ================================================================================================= BE-21

    @Nested
    @DisplayName("BE-21 (R-I6): units back in the warehouse are the return's for good")
    class ClaimedByGoods {

        @Test
        @DisplayName("refused, partly refunded, or expired with goods: what came back stays claimed")
        void claimedUnitsNeverBelowWhatCameBack() {
            ReturnRequest refused = received(10, 10);
            refuse(refused, ReturnRefusalReason.NOT_DEFECTIVE, null);
            refused.resolve(ACCOUNTANT, NOW, ReturnRequest.MoneyOutcome.NONE);
            assertThat(cups(refused).claimedUnits(ReturnStatus.RESOLVED)).isEqualTo(10);

            ReturnRequest partly = received(10, 10);
            partly.proposeRefund(SALES, NOW, Map.of(cups(partly).id(), 6), order(OrderStatus.RETURNED), null);
            partly.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(12_000), BigDecimal.ZERO));
            assertThat(cups(partly).claimedUnits(ReturnStatus.RESOLVED)).as("6 refunded, 10 back").isEqualTo(10);

            ReturnRequest more = received(5, 8);
            assertThat(cups(more).claimedUnits(ReturnStatus.RECEIVED)).as("5 approved, 8 came").isEqualTo(8);
            for (ReturnStatus ended : List.of(ReturnStatus.EXPIRED, ReturnStatus.CANCELLED, ReturnStatus.REJECTED)) {
                assertThat(cups(more).claimedUnits(ended)).as(ended.name()).isEqualTo(8);
            }
            assertThat(cups(awaiting(7)).claimedUnits(ReturnStatus.EXPIRED)).as("nothing came").isZero();
            assertThat(cups(misprinted(3)).claimedUnits(ReturnStatus.REQUESTED)).isEqualTo(3);
        }
    }

    // ================================================================================================= BE-13, Q-11

    @Nested
    @DisplayName("BE-13 (Q-11, R-I3): a receipt is opened until the refund is settled")
    class LateParcels {

        @Test
        @DisplayName("openable: awaiting, received, pending, expired, withdrawn after approval; not requested, "
                + "rejected, resolved, withdrawn before approval")
        void openable() {
            assertThat(misprinted(1).receiptOpenable()).isFalse();
            assertThat(awaiting(1).receiptOpenable()).isTrue();
            assertThat(received(2, 1).receiptOpenable()).isTrue();
            ReturnRequest pending = received(2, 2);
            pending.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), null);
            assertThat(pending.receiptOpenable()).isTrue();
            pending.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(4_000), BigDecimal.ZERO));
            assertThat(pending.receiptOpenable()).isFalse();

            ReturnRequest expired = awaiting(1);
            assertThat(expired.expire(NOW.plus(Duration.ofDays(30)))).isTrue();
            assertThat(expired.status()).isEqualTo(ReturnStatus.EXPIRED);
            assertThat(expired.receiptOpenable()).isTrue();

            ReturnRequest withdrawn = awaiting(1);
            withdrawn.cancel(CUSTOMER, NOW, "đổi ý", true);
            assertThat(withdrawn.receiptOpenable()).isTrue();
            ReturnRequest early = misprinted(1);
            early.cancel(CUSTOMER, NOW, "đổi ý", true);
            assertThat(early.receiptOpenable()).isFalse();
            ReturnRequest rejected = misprinted(1);
            rejected.reject(SALES, NOW, "no defect on the photo");
            assertThat(rejected.receiptOpenable()).isFalse();
            assertThat(code(catchThrowable(() -> rejected.draftReceiptOpened(UUID.randomUUID()))))
                    .isEqualTo(ErrorCode.RETURN_NOT_AWAITING_GOODS);
        }

        @Test
        @DisplayName("a parcel while the refund waits for its approver: units added, the proposal sent back to Sales, "
                + "who are told (LATE_PARCEL)")
        void lateParcelSendsTheProposalBack() {
            ReturnRequest request = received(10, 6);
            request.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), null);
            assertThat(request.settlementAmount()).isEqualByComparingTo("12000");
            request.pullDomainEvents();

            parcel(request, 4);
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(cups(request).receivedQuantity()).isEqualTo(10);
            assertThat(cups(request).refundQuantity()).isZero();
            assertThat(request.settlementAmount()).isZero();
            assertThat(request.proposedBy()).isNull();
            assertThat(events(request, ReturnAwaitingRefund.class)).singleElement()
                    .satisfies(e -> assertThat(e.cause()).isEqualTo("LATE_PARCEL"));
        }
    }

    // ================================================================================================= BE-23

    @Nested
    @DisplayName("BE-23: nothing is decided while a parcel of the return is on a draft receipt")
    class DraftReceipt {

        @Test
        @DisplayName("goods-complete, withdrawal and expiry wait for the draft; once closed they go on")
        void awaitingWaitsForTheDraft() {
            ReturnRequest request = awaiting(10);
            parcel(request, 4);
            UUID draft = UUID.randomUUID();
            request.draftReceiptOpened(draft);
            request.draftReceiptOpened(draft);
            assertThat(code(catchThrowable(() -> request.draftReceiptOpened(UUID.randomUUID()))))
                    .isEqualTo(ErrorCode.RETURN_RECEIPT_ALREADY_OPEN);
            assertThat(code(catchThrowable(() -> request.markGoodsComplete(SALES, NOW, null))))
                    .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
            Instant late = NOW.plus(Duration.ofDays(30));
            assertThat(request.expire(late)).isFalse();
            assertThat(request.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);

            assertThat(request.draftReceiptClosed(UUID.randomUUID())).as("not its draft").isFalse();
            assertThat(request.draftReceiptClosed(draft)).isTrue();
            assertThat(request.openDraftReceiptId()).isNull();
            assertThat(request.expire(late)).isTrue();
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);

            ReturnRequest nothingBack = awaiting(3);
            nothingBack.draftReceiptOpened(UUID.randomUUID());
            assertThat(code(catchThrowable(() -> nothingBack.cancel(CUSTOMER, NOW, "x", true))))
                    .as("the parcel is at the dock").isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        }

        @Test
        @DisplayName("no proposal, approval or close without a refund while a late parcel is counted")
        void refundWaitsForTheDraft() {
            ReturnRequest request = received(10, 10);
            request.draftReceiptOpened(UUID.randomUUID());
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED),
                    null)))).isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
            assertThat(code(catchThrowable(() -> request.closeWithoutRefund(SALES, NOW, null))))
                    .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);

            ReturnRequest pending = received(10, 10);
            pending.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), null);
            pending.draftReceiptOpened(UUID.randomUUID());
            assertThat(code(catchThrowable(() -> pending.requireSettlementApprover(ACCOUNTANT))))
                    .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
            assertThat(code(catchThrowable(() -> pending.resolve(ACCOUNTANT, NOW,
                    new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(20_000), BigDecimal.ZERO)))))
                    .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        }
    }

    // ================================================================================================= BE-24

    @Nested
    @DisplayName("BE-24: Sales are told whenever a return waits for its refund to be proposed")
    class AwaitingRefund {

        @Test
        @DisplayName("goods back, goods marked complete, deadline passed with a parcel, proposal sent back - each "
                + "announced with its cause; a parcel carries the status and the receipt number")
        void everyWayIntoReceived() {
            ReturnRequest back = awaiting(5);
            back.pullDomainEvents();
            parcel(back, 5);
            List<Object> payloads = new ArrayList<>();
            back.pullDomainEvents().forEach(e -> payloads.add(((OrderEvent.ReturnChanged) e).payload()));
            assertThat(payloads).filteredOn(ReturnReceived.class::isInstance).singleElement().satisfies(p -> {
                assertThat(((ReturnReceived) p).status()).isEqualTo("RECEIVED");
                assertThat(((ReturnReceived) p).receiptNumber()).isEqualTo("GR-RET-5");
            });
            assertThat(payloads).filteredOn(ReturnAwaitingRefund.class::isInstance).singleElement()
                    .satisfies(p -> assertThat(((ReturnAwaitingRefund) p).cause()).isEqualTo("GOODS_BACK"));

            ReturnRequest complete = awaiting(5);
            parcel(complete, 2);
            complete.pullDomainEvents();
            complete.markGoodsComplete(SALES, NOW, null);
            assertThat(events(complete, ReturnAwaitingRefund.class)).extracting(ReturnAwaitingRefund::cause)
                    .containsExactly("GOODS_COMPLETE");

            ReturnRequest deadline = awaiting(5);
            parcel(deadline, 2);
            deadline.pullDomainEvents();
            deadline.expire(NOW.plus(Duration.ofDays(30)));
            assertThat(events(deadline, ReturnAwaitingRefund.class)).extracting(ReturnAwaitingRefund::cause)
                    .containsExactly("GOODS_DEADLINE_PASSED");

            ReturnRequest sentBack = received(5, 5);
            sentBack.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), null);
            sentBack.pullDomainEvents();
            sentBack.rejectSettlement(ACCOUNTANT, NOW, "wrong amount");
            assertThat(events(sentBack, ReturnAwaitingRefund.class)).extracting(ReturnAwaitingRefund::cause)
                    .containsExactly("REFUND_SENT_BACK");
        }
    }

    // ================================================================================================= BE-44

    @Nested
    @DisplayName("BE-44: the invoice follows the units refunded, not the money")
    class VatByUnits {

        @Test
        @DisplayName("an order with nothing paid: units refunded worth 0 still adjust the invoice")
        void unitsWorthNothing() {
            ReturnRequest request = received(10, 10);
            request.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), null, true);
            assertThat(request.settlementAmount()).isZero();
            request.resolve(ACCOUNTANT, NOW, ReturnRequest.MoneyOutcome.NONE);
            assertThat(request.vatAdjustmentRequired()).isTrue();
            assertThat(request.outcome()).isEqualTo(ReturnRequest.Outcome.REFUNDED);
        }
    }

    // ================================================================================================= BE-18, Q-15

    @Nested
    @DisplayName("BE-18 (Q-15): the portal decides per delivery, not by the order's status")
    class PerDelivery {

        private ReturnRequest portal(Order order, UUID shipment, Set<UUID> refunded) {
            return ReturnRequest.open("RMA-1", UUID.randomUUID(), order, shipment, ReturnChannel.PORTAL,
                    ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(CUPS, 1)),
                    Map.of(), Map.of(), Set.of(), refunded, CUSTOMER, NOW, POLICY, null);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"REFUNDED", "PARTIALLY_REFUNDED", "RETURNED"})
        @DisplayName("refunded on another delivery, or another return's goods back: this delivery is claimable; the "
                + "refunded one is RETURN_SHIPMENT_NOT_RETURNABLE on the portal, Sales' to open")
        void perShipment(OrderStatus status) {
            Order order = inTwoShipments(status);
            assertThat(portal(order, SHIPMENT_1, Set.of(SHIPMENT_2)).status()).isEqualTo(ReturnStatus.REQUESTED);
            assertThat(code(catchThrowable(() -> portal(order, SHIPMENT_2, Set.of(SHIPMENT_2)))))
                    .isEqualTo(ErrorCode.RETURN_SHIPMENT_NOT_RETURNABLE);
            assertThatCode(() -> ReturnRequest.open("RMA-2", UUID.randomUUID(), order, SHIPMENT_2,
                    ReturnChannel.BACK_OFFICE, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(CUPS, 1)), Map.of(), Map.of(), Set.of(), Set.of(SHIPMENT_2), SALES,
                    NOW, POLICY, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("what the portal shows as returnable is decided the same way (claimable); CLOSED takes nothing")
        void claimableAgrees() {
            Order order = inTwoShipments(OrderStatus.PARTIALLY_REFUNDED);
            ReturnClaims claims = ReturnClaims.of(List.of(new ReturnClaims.Claim(SHIPMENT_2, CUPS, 5, false, true)));
            ReturnRequest.Claimable claimable = ReturnRequest.claimable(order, claims, NOW, POLICY);
            assertThat(claimable.byShipment().get(SHIPMENT_1)).filteredOn(l -> l.orderLineId().equals(CUPS))
                    .singleElement().satisfies(l -> assertThat(l.returnable()).isEqualTo(60));
            assertThat(claimable.byShipment().get(SHIPMENT_2)).allSatisfy(l -> assertThat(l.returnable()).isZero());
            assertThat(code(catchThrowable(() -> portal(inTwoShipments(OrderStatus.CLOSED), SHIPMENT_1, Set.of()))))
                    .isEqualTo(ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
        }
    }

    // ================================================================================================= BE-48

    @Nested
    @DisplayName("BE-48: units of a SKU two lines share are received once, line by line")
    class SameSkuLines {

        private static final UUID FIRST = UUID.randomUUID();
        private static final UUID SECOND = UUID.randomUUID();

        private ReturnRequest twoLinesOfOneSku() {
            Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 9),
                    CUSTOMER, UUID.randomUUID(), List.of(
                    new OrderLine(FIRST, new Sku("CUP-12OZ"), 50, Money.vnd(2_000), null, List.of()),
                    new OrderLine(SECOND, new Sku("CUP-12OZ"), 50, Money.vnd(2_000), null, List.of())),
                    OrderStatus.DELIVERED, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
            order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null,
                    null);
            ReturnRequest request = ReturnRequest.open("RMA-3", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                    ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(FIRST, 10), new ReturnRequest.Claim(SECOND, 10)), Map.of(),
                    Set.of(), CUSTOMER, NOW, POLICY);
            request.addEvidence(claimPhoto(), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            return request;
        }

        private static int receivedOn(ReturnRequest request, UUID orderLine) {
            return request.lines().stream().filter(l -> l.orderLineId().equals(orderLine)).findFirst().orElseThrow()
                    .receivedQuantity();
        }

        @Test
        @DisplayName("15 back of 10 + 10 approved: 10 and 5, refundable 15 - not 15 on each; more than both is the "
                + "last line's excess")
        void allocatedNotDoubled() {
            ReturnRequest request = twoLinesOfOneSku();
            parcel(request, 15);
            assertThat(receivedOn(request, FIRST)).isEqualTo(10);
            assertThat(receivedOn(request, SECOND)).isEqualTo(5);
            assertThat(request.lines().stream().mapToInt(ReturnLine::refundableUnits).sum()).isEqualTo(15);

            parcel(request, 8);
            assertThat(receivedOn(request, FIRST)).isEqualTo(10);
            assertThat(receivedOn(request, SECOND)).isEqualTo(13);
            assertThat(request.discrepancies()).filteredOn(d -> d.kind() == ReturnDiscrepancy.Kind.EXCESS)
                    .singleElement().satisfies(d -> assertThat(d.quantity()).isEqualTo(3));
            assertThat(request.lines().stream().mapToInt(ReturnLine::refundableUnits).sum()).isEqualTo(20);
        }
    }

    // ================================================================================================= BE-50

    @Nested
    @DisplayName("BE-50: the refunds of a line add up to its gross to the dong")
    class CumulativeValue {

        private static final UUID LINE = UUID.randomUUID();

        /** 7 units at 1 001 with 10% VAT: 7 708 dong in all, 1 101.14 a unit - rounded alone, 7 of them make 7 707. */
        private Order order() {
            Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 11),
                    CUSTOMER, UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("BOWL-7"), 7, Money.vnd(1_001),
                    null, List.of()).withTax(BigDecimal.TEN)), OrderStatus.DELIVERED,
                    DELIVERED.minus(Duration.ofDays(10)), null, 3L);
            order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null,
                    null);
            return order;
        }

        private ReturnRequest oneUnitBack(Order order) {
            ReturnRequest request = ReturnRequest.open("RMA-4", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                    ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(LINE, 1)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
            request.addEvidence(claimPhoto(), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            assertThat(request.goodsReceived(UUID.randomUUID(), "GR-1", "HCM-QC01",
                    List.of(new ReturnRequest.ReturnedCarton("BOWL-7", 1)), WAREHOUSE, NOW)).isTrue();
            return request;
        }

        @Test
        @DisplayName("seven returns of one unit each, valued on the line's cumulative scale: 7 708, the line's gross")
        void sumOfPartsIsTheWhole() {
            Order order = order();
            BigDecimal gross = order.lines().getFirst().grossOf(7).amount();
            assertThat(gross).isEqualByComparingTo("7708");
            BigDecimal sum = BigDecimal.ZERO;
            Map<UUID, Integer> refundedBefore = new HashMap<>();
            for (int i = 0; i < 7; i++) {
                ReturnRequest request = oneUnitBack(order);
                request.proposeRefund(SALES, NOW, null, order, null, false, null, null, Map.copyOf(refundedBefore));
                sum = sum.add(request.settlementAmount());
                refundedBefore.merge(LINE, 1, Integer::sum);
            }
            assertThat(sum).isEqualByComparingTo(gross);
        }

        @Test
        @DisplayName("approved after other returns of the line were settled: valued again where its units sit now")
        void revaluedAtApproval() {
            Order order = order();
            ReturnRequest request = oneUnitBack(order);
            request.proposeRefund(SALES, NOW, null, order, null, false, null, null, Map.of());
            BigDecimal first = order.lines().getFirst().grossBetween(0, 1).amount();
            assertThat(request.settlementAmount()).isEqualByComparingTo(first);
            // 1 101 for the first unit, 1 102 for the fourth: 4 404.57 rounds up where 3 303.43 rounded down.
            BigDecimal last = order.lines().getFirst().grossBetween(3, 4).amount();
            assertThat(first).isEqualByComparingTo("1101");
            assertThat(last).isEqualByComparingTo("1102");
            assertThat(request.revalue(order, Map.of(LINE, 3))).isTrue();
            assertThat(request.settlementAmount()).isEqualByComparingTo(last);
            assertThat(cups(request, LINE).refundAmount()).isEqualByComparingTo(last);
        }

        private ReturnLine cups(ReturnRequest request, UUID line) {
            return request.lines().stream().filter(l -> l.orderLineId().equals(line)).findFirst().orElseThrow();
        }
    }

    // ================================================================================================= O-1

    @Nested
    @DisplayName("O-1: goods invoiced at another rate than the contract's are refunded what the adjustment takes off")
    class InvoicedRate {

        private static final UUID LINE = UUID.randomUUID();

        /**
         * WF5-C: 3 cartons of 1,000 cups at 1,200, the contract at 10% (3,960,000 with VAT); delivered during NĐ 174's
         * reduction and invoiced at 8%.
         */
        private Order wf5c() {
            Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 11, 8), 1),
                    CUSTOMER, UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("LY-GIAY-DEMO-12OZ-MOC"), 3_000,
                    Money.vnd(1_200), null, List.of()).withTax(BigDecimal.TEN)), OrderStatus.DELIVERED,
                    DELIVERED.minus(Duration.ofDays(10)), null, 3L);
            order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null,
                    null);
            return order;
        }

        private ReturnRequest back(Order order, int units) {
            ReturnRequest request = ReturnRequest.open("RMA-C", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                    ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(LINE, units)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
            request.addEvidence(claimPhoto(), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            request.goodsReceived(UUID.randomUUID(), "GR-1", "HCM-QC01",
                    List.of(new ReturnRequest.ReturnedCarton("LY-GIAY-DEMO-12OZ-MOC", units)), WAREHOUSE, NOW);
            return request;
        }

        @Test
        @DisplayName("TH-4 on WF5-C: 850 cups refunded 850 x 1,200 + 8% = 1,101,600 - the adjustment - not 1,122,000")
        void refundedAtTheInvoicedRate() {
            Order order = wf5c();
            ReturnRequest request = back(order, 850);

            request.proposeRefund(SALES, NOW, null, order, null, false, null, null, Map.of(),
                    Map.of(LINE, new BigDecimal("8.00")));

            assertThat(request.settlementAmount()).isEqualByComparingTo("1101600");
            assertThat(request.lines().getFirst().refundAmount()).isEqualByComparingTo("1101600");
            assertThat(request.lines().getFirst().refundNetAmount()).as("the price paid, whatever the rate")
                    .isEqualByComparingTo("1020000");
        }

        @Test
        @DisplayName("the rate not known (no shipment, no invoice): the contract's line, as before - 1,122,000")
        void unknownRateKeepsTheLine() {
            Order order = wf5c();
            ReturnRequest request = back(order, 850);

            request.proposeRefund(SALES, NOW, null, order, null, false, null, null, Map.of(), Map.of());

            assertThat(request.settlementAmount()).isEqualByComparingTo("1122000");
        }

        @Test
        @DisplayName("approval reads the rate again: proposed before the invoice was issued, approved after - 1,101,600")
        void revaluedAtTheRateReadAtApproval() {
            Order order = wf5c();
            ReturnRequest request = back(order, 850);
            request.proposeRefund(SALES, NOW, null, order, null, false, null, null, Map.of(), Map.of());

            assertThat(request.revalue(order, Map.of(), Map.of(LINE, new BigDecimal("8.00")))).isTrue();

            assertThat(request.settlementAmount()).isEqualByComparingTo("1101600");
        }

        @Test
        @DisplayName("invoiced at the line's own rate: still the line's cumulative scale (BE-50), to the dong")
        void ownRateKeepsTheCumulativeScale() {
            Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 12),
                    CUSTOMER, UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("BOWL-7"), 7, Money.vnd(1_001),
                    null, List.of()).withTax(BigDecimal.TEN)), OrderStatus.DELIVERED,
                    DELIVERED.minus(Duration.ofDays(10)), null, 3L);
            OrderLine line = order.lines().getFirst();

            assertThat(line.grossAt(3, 1, new BigDecimal("10.00"))).isEqualTo(line.grossBetween(3, 4));
            assertThat(line.grossAt(3, 1, new BigDecimal("10.00")).amount()).isEqualByComparingTo("1102");
            assertThat(line.grossAt(3, 1, new BigDecimal("8.00")).amount()).as("1,001 + 80.08")
                    .isEqualByComparingTo("1081");
        }
    }
}
