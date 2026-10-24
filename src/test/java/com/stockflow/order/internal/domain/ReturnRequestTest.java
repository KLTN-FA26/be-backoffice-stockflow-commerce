package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
import com.stockflow.contracts.ReturnReplacementWithdrawn;
import com.stockflow.contracts.ReturnRequested;
import com.stockflow.contracts.ReturnResolved;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The return state machine (SCRUM-243 / SCRUM-260, 03 §12) and every rule it protects, on the aggregate alone: the
 * window in calendar days (BR-RMA-001), printed goods only when defective (BR-RMA-002), quantities never above what was
 * delivered, the evidence and value rules of the review, parcels counted in one by one or late, QC's arithmetic and
 * whose goods the rejected units are, four eyes on every approval of value, and the withdrawal of a replacement.
 */
class ReturnRequestTest {

    /** 10:00 on 20/10 in Ho Chi Minh City. */
    private static final Instant DELIVERED = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(2));
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID OTHER_SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final ReturnPolicy POLICY = ReturnPolicy.defaults();

    private static final UUID PRINTED_LINE = UUID.randomUUID();
    private static final UUID STOCK_LINE = UUID.randomUUID();

    /** Delivered: 100 printed cups at 2 000 VND, 50 plain lids at 500 VND, 10 of them closed out before delivery. */
    private static Order order(OrderStatus status) {
        return order(status, DELIVERED);
    }

    private static Order order(OrderStatus status, Instant deliveredAt) {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 3), CUSTOMER,
                UUID.randomUUID(), List.of(
                new OrderLine(PRINTED_LINE, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), UUID.randomUUID(), List.of()),
                new OrderLine(STOCK_LINE, new Sku("LID-90"), 50, Money.vnd(500), null, List.of(), 10)),
                status, deliveredAt.minus(Duration.ofDays(10)), status == OrderStatus.CANCELLED ? "x" : null, 3L);
        order.restoreLifecycle(null, null, null, deliveredAt.minus(Duration.ofDays(1)), deliveredAt, null,
                status == OrderStatus.COMPLETED ? deliveredAt.plus(Duration.ofDays(8)) : null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return order;
    }

    private static ReturnRequest open(ReturnReason reason, ReturnResolution wanted, ReturnRequest.Claim... claims) {
        return ReturnRequest.open("RMA-20261022-0001", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                ReturnChannel.PORTAL, reason, "Mực in bị lem", wanted, List.of(claims), Map.of(), Set.of(), CUSTOMER,
                NOW, POLICY);
    }

    private static ReturnRequest openBySales(ReturnReason reason, ReturnRequest.Claim... claims) {
        return ReturnRequest.open("RMA-20261022-0002", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                ReturnChannel.BACK_OFFICE, reason, "Khách gọi điện báo", ReturnResolution.REPLACEMENT, List.of(claims),
                Map.of(), Set.of(), SALES, NOW, POLICY);
    }

    private static ReturnRequest misprinted(int units) {
        return open(ReturnReason.PRINT_DEFECT, ReturnResolution.REPLACEMENT,
                new ReturnRequest.Claim(PRINTED_LINE, units));
    }

    private static ReturnEvidence photo(ReturnEvidence.Stage stage) {
        return new ReturnEvidence(UUID.randomUUID(), stage, "return-evidence/x/" + UUID.randomUUID(), "loi-in.jpg",
                "image/jpeg", 1024, CUSTOMER, NOW);
    }

    private static ReturnLine line(ReturnRequest request, UUID orderLine) {
        return request.lines().stream().filter(l -> l.orderLineId().equals(orderLine)).findFirst().orElseThrow();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /** Approved for {@code units} printed cups, goods to come back. */
    private static ReturnRequest awaiting(int units) {
        ReturnRequest request = misprinted(units);
        request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
        request.approve(SALES, NOW, null, null, null, POLICY);
        return request;
    }

    private static ReturnRequest received(int units, int arrived) {
        ReturnRequest request = awaiting(units);
        request.receive(WAREHOUSE, NOW, "HCM-QC01",
                List.of(new ReturnRequest.Receipt(line(request, PRINTED_LINE).id(), arrived, null, null)), List.of(),
                null);
        return request;
    }

    private static ReturnRequest inspected(int units, int accepted) {
        ReturnRequest request = received(units, units);
        UUID line = line(request, PRINTED_LINE).id();
        request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, accepted, units - accepted,
                ReturnItemCondition.DEFECTIVE, 0, accepted, units - accepted, null, null)), null);
        return request;
    }

    /** Every accepted unit replaced, approved by the accountant: RESOLVED with a replacement order. */
    private static ReturnRequest replaced(int units) {
        ReturnRequest request = inspected(units, units);
        UUID line = line(request, PRINTED_LINE).id();
        request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, units, 0)), null, null);
        request.resolve(ACCOUNTANT, NOW, UUID.randomUUID(), "SO-20261022-000009", Map.of(line, UUID.randomUUID()),
                ReturnRequest.MoneyOutcome.NONE);
        return request;
    }

    @Nested
    @DisplayName("opening (BR-RMA-001, BR-RMA-002)")
    class Opening {

        @Test
        @DisplayName("a delivered order, within the window: REQUESTED, the line's SKU, price and print flag copied")
        void opens() {
            ReturnRequest request = open(ReturnReason.PRINT_DEFECT, ReturnResolution.REPLACEMENT,
                    new ReturnRequest.Claim(PRINTED_LINE, 20), new ReturnRequest.Claim(STOCK_LINE, 5));
            assertThat(request.status()).isEqualTo(ReturnStatus.REQUESTED);
            assertThat(line(request, PRINTED_LINE)).satisfies(l -> {
                assertThat(l.sku()).isEqualTo("CUP-12OZ");
                assertThat(l.customPrinted()).isTrue();
                assertThat(l.unitPrice()).isEqualByComparingTo("2000");
                assertThat(l.requestedQuantity()).isEqualTo(20);
            });
            assertThat(line(request, STOCK_LINE).customPrinted()).isFalse();
            assertThat(request.windowOverride()).isNull();
            assertThat(request.pullTransitions()).singleElement()
                    .satisfies(t -> assertThat(t.to()).isEqualTo(ReturnStatus.REQUESTED));
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((OrderEvent.ReturnChanged) event).payload()).isInstanceOf(ReturnRequested.class));
        }

        @Test
        @DisplayName("the window is 7 calendar days in Vietnam: the whole 7th day, whatever the hour of delivery")
        void windowInCalendarDays() {
            // Delivered 10:00 on 20/10: open until the end of 27/10 (17:00Z), closed from 00:00 on 28/10.
            Instant lastMoment = Instant.parse("2026-10-27T16:59:59Z");
            assertThat(POLICY.windowClosesAt(DELIVERED)).isEqualTo(Instant.parse("2026-10-27T17:00:00Z"));
            assertThat(ReturnRequest.open("RMA-1", UUID.randomUUID(), order(OrderStatus.DELIVERED), ReturnChannel.PORTAL,
                    ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(), CUSTOMER, lastMoment,
                    POLICY).status()).isEqualTo(ReturnStatus.REQUESTED);
            Throwable late = catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.DELIVERED), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(),
                    CUSTOMER, lastMoment.plusSeconds(1), POLICY));
            assertThat(code(late)).isEqualTo(ErrorCode.RETURN_WINDOW_CLOSED);
            // Delivered 17:00 on 01/10: a claim at 18:00 on 08/10 is still within the seventh day.
            Instant evening = Instant.parse("2026-10-01T10:00:00Z");
            assertThat(ReturnRequest.open("RMA-1", UUID.randomUUID(), order(OrderStatus.DELIVERED, evening),
                    ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(), CUSTOMER,
                    Instant.parse("2026-10-08T11:00:00Z"), POLICY).status()).isEqualTo(ReturnStatus.REQUESTED);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "IN_PRODUCTION", "PICKING", "PACKED", "SHIPPED",
                "DELIVERY_FAILED", "PARTIALLY_FULFILLED", "RETURNED"})
        @DisplayName("an order not delivered yet: RETURN_ORDER_NOT_DELIVERED")
        void notDelivered(OrderStatus status) {
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(), order(status),
                    ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_ORDER_NOT_DELIVERED);
        }

        @Test
        @DisplayName("on the portal: a cancelled order, a completed one, one a return settled, a closed one - refused")
        void otherStatusesOnThePortal() {
            Map<OrderStatus, ErrorCode> expected = Map.of(OrderStatus.CANCELLED, ErrorCode.ORDER_CANCELLED,
                    OrderStatus.COMPLETED, ErrorCode.RETURN_WINDOW_CLOSED,
                    OrderStatus.PARTIALLY_REFUNDED, ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    OrderStatus.REFUNDED, ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    OrderStatus.CLOSED, ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
            expected.forEach((status, error) -> assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1",
                    UUID.randomUUID(), order(status), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(),
                    CUSTOMER, NOW, POLICY)))).as(status.name()).isEqualTo(error));
        }

        @Test
        @DisplayName("Sales open a further claim on an order a return settled (03 §12); never on a closed one (BR-05)")
        void furtherClaimBySales() {
            for (OrderStatus status : List.of(OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED)) {
                assertThat(ReturnRequest.open("RMA-3", UUID.randomUUID(), order(status), ReturnChannel.BACK_OFFICE,
                        ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                        List.of(new ReturnRequest.Claim(STOCK_LINE, 2)), Map.of(), Set.of(), SALES, NOW, POLICY)
                        .status()).as(status.name()).isEqualTo(ReturnStatus.REQUESTED);
            }
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-3", UUID.randomUUID(),
                    order(OrderStatus.CLOSED), ReturnChannel.BACK_OFFICE, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(STOCK_LINE, 2)), Map.of(), Set.of(), SALES,
                    NOW, POLICY, "goodwill", Map.of())))).isEqualTo(ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
        }

        @Test
        @DisplayName("a late claim: Sales accept it with a reason, recorded; the portal cannot")
        void lateClaim() {
            Instant monthLater = DELIVERED.plus(Duration.ofDays(30));
            Order completed = order(OrderStatus.COMPLETED);
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-4", UUID.randomUUID(), completed,
                    ReturnChannel.BACK_OFFICE, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 5)), Map.of(), Set.of(), SALES, monthLater,
                    POLICY)))).as("no reason, no late claim").isEqualTo(ErrorCode.RETURN_WINDOW_CLOSED);
            ReturnRequest late = ReturnRequest.open("RMA-4", UUID.randomUUID(), completed, ReturnChannel.BACK_OFFICE,
                    ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 5)), Map.of(), Set.of(), SALES, monthLater, POLICY,
                    "Lỗi in phát hiện khi khách mở pallet thứ 3", Map.of());
            assertThat(late.status()).isEqualTo(ReturnStatus.REQUESTED);
            assertThat(late.windowOverride()).isEqualTo("Lỗi in phát hiện khi khách mở pallet thứ 3");
            assertThat(late.pullTransitions()).singleElement()
                    .satisfies(t -> assertThat(t.reason()).contains("LATE_CLAIM_ACCEPTED"));
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-4", UUID.randomUUID(), completed,
                    ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 5)), Map.of(), Set.of(), CUSTOMER, monthLater, POLICY,
                    "please", Map.of())))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a claim on a free replacement order is credited at the prices the replaced goods were paid at")
        void replacementPrices() {
            ReturnRequest request = ReturnRequest.open("RMA-5", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                    ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 5)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY, null,
                    Map.of(PRINTED_LINE, new BigDecimal("1800")));
            assertThat(line(request, PRINTED_LINE).unitPrice()).isEqualByComparingTo("1800");
        }

        @Test
        @DisplayName("an order already RETURN_REQUESTED (another line's return open) takes another return")
        void anotherReturnOfTheOrder() {
            assertThat(ReturnRequest.open("RMA-2", UUID.randomUUID(), order(OrderStatus.RETURN_REQUESTED),
                    ReturnChannel.BACK_OFFICE, ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(STOCK_LINE, 3)), Map.of(), Set.of(PRINTED_LINE), SALES, NOW, POLICY)
                    .status()).isEqualTo(ReturnStatus.REQUESTED);
        }

        @Test
        @DisplayName("never more than delivered (ordered less closed) less what other returns still claim")
        void quantity() {
            assertThat(code(catchThrowable(() -> open(ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 41))))).as("50 ordered, 10 closed")
                    .isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
            assertThat(open(ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 40)).lines()).hasSize(1);
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.DELIVERED), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 31)),
                    Map.of(PRINTED_LINE, 70), Set.of(), CUSTOMER, NOW, POLICY))))
                    .as("a line already partly returned").isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("one open return per order line")
        void oneOpenPerLine() {
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.RETURN_REQUESTED), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(),
                    Set.of(PRINTED_LINE), CUSTOMER, NOW, POLICY)))).isEqualTo(ErrorCode.RETURN_LINE_ALREADY_OPEN);
        }

        @Test
        @DisplayName("a change of mind: refused for printed goods (BR-RMA-002) and, by default, for any goods")
        void changeOfMind() {
            assertThat(code(catchThrowable(() -> open(ReturnReason.CHANGED_MIND, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(PRINTED_LINE, 1))))).isEqualTo(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE);
            assertThat(code(catchThrowable(() -> open(ReturnReason.CHANGED_MIND, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 1))))).isEqualTo(ErrorCode.RETURN_REASON_NOT_ACCEPTED);
            ReturnPolicy lenient = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), POLICY.noReturnMaxValue(),
                    true, 10, true);
            assertThat(ReturnRequest.open("RMA-1", UUID.randomUUID(), order(OrderStatus.DELIVERED), ReturnChannel.PORTAL,
                    ReturnReason.CHANGED_MIND, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(STOCK_LINE, 1)), Map.of(), Set.of(), CUSTOMER, NOW, lenient)
                    .status()).as("allowed for stock items by configuration").isEqualTo(ReturnStatus.REQUESTED);
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.DELIVERED), ReturnChannel.PORTAL, ReturnReason.CHANGED_MIND, null,
                    ReturnResolution.REPLACEMENT, List.of(new ReturnRequest.Claim(STOCK_LINE, 1)), Map.of(), Set.of(),
                    CUSTOMER, NOW, lenient)))).as("refunded or credited, never exchanged")
                    .isEqualTo(ErrorCode.RETURN_REASON_NOT_ACCEPTED);
        }

        @Test
        @DisplayName("malformed claims: OTHER without a description, no line, a line twice, zero units, another order's line")
        void malformed() {
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.DELIVERED), ReturnChannel.PORTAL, ReturnReason.OTHER, " ", ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(STOCK_LINE, 1)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND))))
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 1), new ReturnRequest.Claim(STOCK_LINE, 2)))))
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 0))))).isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(UUID.randomUUID(), 1))))).isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
        }
    }

    @Nested
    @DisplayName("review")
    class Review {

        @Test
        @DisplayName("a defect is approved on a photo; then AWAITING_RETURN until the end of the 14th day; not twice")
        void approve() {
            ReturnRequest request = misprinted(20);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_EVIDENCE_REQUIRED);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.pullDomainEvents();
            request.approve(SALES, NOW, null, null, "ok", POLICY);
            assertThat(request.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
            assertThat(request.returnRequired()).isTrue();
            // Approved 10:00 on 22/10: the goods may come until the end of 05/11 (00:00 on 06/11, 17:00Z on 05/11).
            assertThat(request.returnDeadline()).isEqualTo(Instant.parse("2026-11-05T17:00:00Z"));
            assertThat(line(request, PRINTED_LINE).approvedQuantity()).isEqualTo(20);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((OrderEvent.ReturnChanged) event).payload()).isInstanceOf(ReturnApproved.class));
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("a claim Sales opened is approved by someone else; a customer's by any Sales")
        void fourEyesOnApproval() {
            ReturnRequest bySales = openBySales(ReturnReason.PRINT_DEFECT, new ReturnRequest.Claim(PRINTED_LINE, 100));
            bySales.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            assertThat(code(catchThrowable(() -> bySales.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            bySales.approve(OTHER_SALES, NOW, null, null, null, POLICY);
            assertThat(bySales.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
            assertThat(awaiting(5).decidedBy()).as("the customer's own claim").isEqualTo(SALES);
        }

        @Test
        @DisplayName("partly: fewer units, 0 for a line; never more than asked, never nothing at all")
        void partly() {
            ReturnRequest request = open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(PRINTED_LINE, 20), new ReturnRequest.Claim(STOCK_LINE, 5));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            UUID printed = line(request, PRINTED_LINE).id();
            UUID stock = line(request, STOCK_LINE).id();
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, Map.of(printed, 21), null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, Map.of(printed, 0, stock, 0), null, null,
                    POLICY)))).isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.approve(SALES, NOW, Map.of(printed, 12, stock, 0), null, null, POLICY);
            assertThat(line(request, PRINTED_LINE).approvedQuantity()).isEqualTo(12);
            assertThat(line(request, STOCK_LINE).approvedQuantity()).isZero();
        }

        @Test
        @DisplayName("goods stay with the customer only up to the no-return value: accepted on the photos (INSPECTED)")
        void noReturnNeeded() {
            ReturnPolicy exact = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), BigDecimal.valueOf(20_000),
                    true, 10, false);
            ReturnRequest atTheLimit = misprinted(10);
            atTheLimit.addEvidence(photo(ReturnEvidence.Stage.CLAIM), exact);
            atTheLimit.approve(SALES, NOW, null, false, null, exact);
            assertThat(atTheLimit.status()).as("10 x 2 000 is not above 20 000").isEqualTo(ReturnStatus.INSPECTED);
            ReturnRequest cheap = misprinted(10);
            cheap.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            cheap.approve(SALES, NOW, null, false, null, POLICY);
            assertThat(cheap.status()).isEqualTo(ReturnStatus.INSPECTED);
            assertThat(cheap.returnDeadline()).isNull();
            assertThat(line(cheap, PRINTED_LINE).acceptedQuantity()).isEqualTo(10);
            assertThat(cheap.inspectedBy()).isEqualTo(SALES);
        }

        @Test
        @DisplayName("above the no-return value the goods must come back (RETURN_GOODS_REQUIRED)")
        void goodsRequired() {
            ReturnPolicy tight = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), BigDecimal.valueOf(10_000),
                    true, 10, false);
            ReturnRequest request = misprinted(6);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), tight);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, false, null, tight))))
                    .isEqualTo(ErrorCode.RETURN_GOODS_REQUIRED);
            request.approve(SALES, NOW, null, false, null, POLICY);
            assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
        }

        @Test
        @DisplayName("a short delivery has nothing to send back, but is approved on the signed delivery note's photo")
        void shortDelivery() {
            ReturnRequest request = open(ReturnReason.SHORT_DELIVERY, ReturnResolution.REPLACEMENT,
                    new ReturnRequest.Claim(STOCK_LINE, 4));
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_EVIDENCE_REQUIRED);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, true, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.approve(SALES, NOW, null, null, null, POLICY);
            assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
            assertThat(request.returnRequired()).isFalse();
        }

        @Test
        @DisplayName("rejected with a reason: REJECTED and closed; the reason is required")
        void reject() {
            ReturnRequest request = misprinted(5);
            assertThat(code(catchThrowable(() -> request.reject(SALES, NOW, " ")))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.pullDomainEvents();
            request.reject(SALES, NOW, "Ảnh không cho thấy lỗi in");
            assertThat(request.status()).isEqualTo(ReturnStatus.REJECTED);
            assertThat(request.rejectionReason()).isEqualTo("Ảnh không cho thấy lỗi in");
            assertThat(request.closedAt()).isEqualTo(NOW);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((ReturnRejected) ((OrderEvent.ReturnChanged) event).payload()).stage())
                            .isEqualTo("REVIEW"));
        }
    }

    @Nested
    @DisplayName("withdrawal and expiry")
    class Withdrawal {

        @Test
        @DisplayName("the customer withdraws before or after approval, not once the goods are back")
        void cancel() {
            ReturnRequest requested = misprinted(5);
            requested.cancel(CUSTOMER, NOW, null, true);
            assertThat(requested.status()).isEqualTo(ReturnStatus.CANCELLED);
            assertThat(requested.cancellationReason()).isEqualTo("WITHDRAWN");

            ReturnRequest approved = awaiting(5);
            approved.pullDomainEvents();
            approved.cancel(CUSTOMER, NOW, "Đã tự xử lý", true);
            assertThat(approved.status()).isEqualTo(ReturnStatus.CANCELLED);
            assertThat(approved.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnClosed closed = (ReturnClosed) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(closed.outcome()).isEqualTo("CANCELLED");
                assertThat(closed.byCustomer()).isTrue();
            });

            ReturnRequest back = received(5, 5);
            assertThat(code(catchThrowable(() -> back.cancel(CUSTOMER, NOW, null, true))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("goods not back by the end of the deadline's day: EXPIRED, by nobody; not a second before")
        void expire() {
            ReturnRequest request = awaiting(5);
            assertThat(request.expire(request.returnDeadline().minusSeconds(1))).isFalse();
            assertThat(request.expire(request.returnDeadline())).isTrue();
            assertThat(request.status()).isEqualTo(ReturnStatus.EXPIRED);
            assertThat(request.pullTransitions()).last().satisfies(t -> assertThat(t.actor()).isNull());
            assertThat(request.expire(request.returnDeadline().plusSeconds(2))).as("once").isFalse();
        }
    }

    @Nested
    @DisplayName("receipt")
    class Receipt {

        @Test
        @DisplayName("other goods than approved, more than approved, something of no line: discrepancies of the parcel")
        void discrepancies() {
            ReturnRequest request = open(ReturnReason.WRONG_ITEM, ReturnResolution.REPLACEMENT,
                    new ReturnRequest.Claim(PRINTED_LINE, 10), new ReturnRequest.Claim(STOCK_LINE, 10));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            request.pullDomainEvents();
            UUID printed = line(request, PRINTED_LINE).id();
            UUID stock = line(request, STOCK_LINE).id();
            request.receive(WAREHOUSE, NOW, "HCM-QC01", List.of(
                    new ReturnRequest.Receipt(printed, 8, null, null),
                    new ReturnRequest.Receipt(stock, 12, "LID-80", "L-7")),
                    List.of(new ReturnRequest.Unexpected("XYZ-1", 3, "hàng không phải của mình")), "thùng móp");
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.receivingLocation()).isEqualTo("HCM-QC01");
            assertThat(request.parcelCount()).isEqualTo(1);
            assertThat(line(request, STOCK_LINE).receivedSku()).isEqualTo("LID-80");
            assertThat(line(request, STOCK_LINE).lotNumber()).isEqualTo("L-7");
            assertThat(request.discrepancies()).extracting(d -> d.kind() + " " + d.sku() + " " + d.quantity())
                    .as("a shortfall waits for the last parcel")
                    .containsExactlyInAnyOrder("WRONG_ITEM LID-80 12", "EXCESS LID-80 2", "UNEXPECTED_ITEM XYZ-1 3");
            assertThat(request.pullNewDiscrepancies()).hasSize(3);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnReceived received = (ReturnReceived) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(received.discrepancy()).isTrue();
                assertThat(received.parcelNumber()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("nothing of the return arrived: refused, the return waits (or expires)")
        void nothing() {
            ReturnRequest request = awaiting(5);
            assertThat(code(catchThrowable(() -> request.receive(WAREHOUSE, NOW, "HCM-QC01",
                    List.of(new ReturnRequest.Receipt(line(request, PRINTED_LINE).id(), 0, null, null)), List.of(),
                    null)))).isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(request.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
        }

        @Test
        @DisplayName("a second parcel before QC: counted onto the first, at the same place, the same goods per line")
        void secondParcel() {
            ReturnRequest request = received(100, 60);
            UUID line = line(request, PRINTED_LINE).id();
            Instant later = NOW.plus(Duration.ofDays(3));
            assertThat(code(catchThrowable(() -> request.receive(WAREHOUSE, later, "HCM-QC02",
                    List.of(new ReturnRequest.Receipt(line, 40, null, null)), List.of(), null))))
                    .as("the goods of one return wait for QC together")
                    .isEqualTo(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID);
            assertThat(code(catchThrowable(() -> request.receive(WAREHOUSE, later, "HCM-QC01",
                    List.of(new ReturnRequest.Receipt(line, 40, "CUP-16OZ", null)), List.of(), null))))
                    .as("other goods for the line are an unexpected item").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.pullDomainEvents();
            request.pullTransitions();
            request.receive(WAREHOUSE, later, "HCM-QC01", List.of(new ReturnRequest.Receipt(line, 45, null, null)),
                    List.of(), "đợt 2");
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.parcelCount()).isEqualTo(2);
            assertThat(line(request, PRINTED_LINE).receivedQuantity()).isEqualTo(105);
            assertThat(request.receivedAt()).as("the first parcel names the stock layer").isEqualTo(NOW);
            assertThat(request.pullTransitions()).as("no transition: the count grows").isEmpty();
            assertThat(request.discrepancies()).extracting(d -> d.kind() + " " + d.quantity())
                    .containsExactly("EXCESS 5");
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((ReturnReceived) ((OrderEvent.ReturnChanged) event).payload()).parcelNumber())
                            .isEqualTo(2));
        }

        @Test
        @DisplayName("goods after the deadline, or after the customer withdrew an approved return: counted in, RECEIVED")
        void lateGoods() {
            ReturnRequest expired = awaiting(10);
            expired.expire(expired.returnDeadline());
            assertThat(expired.receivingReopens()).isTrue();
            expired.pullTransitions();
            expired.receive(WAREHOUSE, expired.returnDeadline().plus(Duration.ofDays(2)), "HCM-QC01",
                    List.of(new ReturnRequest.Receipt(line(expired, PRINTED_LINE).id(), 10, null, null)), List.of(),
                    null);
            assertThat(expired.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(expired.closedAt()).isNull();
            assertThat(expired.pullTransitions()).singleElement()
                    .satisfies(t -> assertThat(t.reason()).isEqualTo("RECEIVED_AFTER_DEADLINE"));

            ReturnRequest withdrawn = awaiting(10);
            withdrawn.cancel(CUSTOMER, NOW, "không gửi nữa", true);
            withdrawn.receive(WAREHOUSE, NOW.plus(Duration.ofDays(5)), "HCM-QC01",
                    List.of(new ReturnRequest.Receipt(line(withdrawn, PRINTED_LINE).id(), 10, null, null)), List.of(),
                    null);
            assertThat(withdrawn.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(withdrawn.cancelledBy()).isNull();
            assertThat(withdrawn.cancellationReason()).isNull();

            ReturnRequest neverApproved = misprinted(10);
            neverApproved.cancel(CUSTOMER, NOW, null, true);
            assertThat(neverApproved.receivingReopens()).isFalse();
            assertThat(code(catchThrowable(() -> neverApproved.receive(WAREHOUSE, NOW, "HCM-QC01",
                    List.of(new ReturnRequest.Receipt(line(neverApproved, PRINTED_LINE).id(), 10, null, null)),
                    List.of(), null)))).as("Sales open a return for those goods").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }
    }

    @Nested
    @DisplayName("inspection")
    class Inspection {

        @Test
        @DisplayName("every unit received is accepted or rejected, never more accepted than approved; where each goes")
        void arithmetic() {
            ReturnRequest request = received(10, 12);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 1, null, 0, 11, 0, null, null)), null)))).as("11 of 12")
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 11, 1, null, 0, 12, 0, null, null)), null)))).as("more accepted than approved")
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 2, null, 0, 9, 3, null, null)), null)))).as("only rejected units go back")
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 2, null, 2, 8, 2, "HCM-A01-1-A", null)), null)))).as("printed goods never back on sale")
                    .isEqualTo(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(), null))))
                    .as("received units left uninspected").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 10, 2,
                    ReturnItemCondition.DEFECTIVE, 0, 10, 2, null, "lem mực")), null);
            assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
            assertThat(request.acceptedUnits()).isEqualTo(10);
            assertThat(line(request, PRINTED_LINE).customerReturnQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejected units are the customer's: back to them, scrapped only with their recorded consent")
        void customersGoods() {
            ReturnRequest request = received(10, 10);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 6, 4, ReturnItemCondition.GOOD, 0, 10, 0, null, null)), null))))
                    .isEqualTo(ErrorCode.RETURN_DISPOSAL_CONSENT_REQUIRED);
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 6, 4, ReturnItemCondition.GOOD,
                    0, 10, 0, null, null, null, "Khách đồng ý huỷ qua email 24/10")), null);
            assertThat(line(request, PRINTED_LINE).disposalConsent()).isEqualTo("Khách đồng ý huỷ qua email 24/10");
            assertThat(line(request, PRINTED_LINE).rejectedScrapped()).isEqualTo(4);
        }

        @Test
        @DisplayName("a plain stock item that passed QC goes back on sale at a bin - accepted units only")
        void restock() {
            ReturnRequest request = open(ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 10));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            UUID line = line(request, STOCK_LINE).id();
            request.receive(WAREHOUSE, NOW, "HCM-QC01", List.of(new ReturnRequest.Receipt(line, 10, null, null)),
                    List.of(), null);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 0, null, 4, 6, 0, null, null)), null)))).as("names the bin")
                    .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 6, 4, null, 8, 0, 2, "HCM-A01-1-A", null, null, "ok")), null))))
                    .as("the customer's rejected units are not sold").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 0, ReturnItemCondition.DAMAGED, 4, 6, 0, "HCM-A01-1-A", null)), null))))
                    .as("units QC graded damaged never go back on sale")
                    .isEqualTo(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED);
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 10, 0,
                    ReturnItemCondition.GOOD, 4, 6, 0, "HCM-A01-1-A", null)), null);
            assertThat(line(request, STOCK_LINE).restockQuantity()).isEqualTo(4);
            assertThat(line(request, STOCK_LINE).receivedPlain()).isTrue();
        }

        @Test
        @DisplayName("another SKU that came back goes on sale only when QC says it is plain stock (BR-RMA-002)")
        void wrongItemRestock() {
            ReturnRequest request = open(ReturnReason.WRONG_ITEM, ReturnResolution.REPLACEMENT,
                    new ReturnRequest.Claim(STOCK_LINE, 10));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            UUID line = line(request, STOCK_LINE).id();
            request.receive(WAREHOUSE, NOW, "HCM-QC01", List.of(new ReturnRequest.Receipt(line, 10, "CUP-16OZ", null)),
                    List.of(), null);
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(
                    line, 10, 0, ReturnItemCondition.GOOD, 10, 0, 0, "HCM-A01-1-A", null)), null))))
                    .as("possibly someone else's printed cups").isEqualTo(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED);
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 10, 0, ReturnItemCondition.GOOD,
                    10, 0, 0, "HCM-A01-1-A", "ly trơn, nguyên thùng", Boolean.TRUE, null)), null);
            assertThat(line(request, STOCK_LINE).restockQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("a line that came back short of what was approved is recorded at inspection")
        void shortfallAtInspection() {
            ReturnRequest request = received(10, 8);
            request.pullNewDiscrepancies();
            UUID line = line(request, PRINTED_LINE).id();
            List<ReturnRequest.Inspection> verdict = List.of(new ReturnRequest.Inspection(line, 8, 0,
                    ReturnItemCondition.DEFECTIVE, 0, 8, 0, null, null));
            assertThat(code(catchThrowable(() -> request.inspect(WAREHOUSE, NOW, verdict, null))))
                    .as("the next parcel may still come").isEqualTo(ErrorCode.RETURN_PARCELS_OUTSTANDING);
            request.inspect(WAREHOUSE, NOW, verdict, null, true);
            assertThat(request.pullNewDiscrepancies()).extracting(d -> d.kind() + " " + d.quantity())
                    .containsExactly("SHORT 2");
        }

        @Test
        @DisplayName("nothing defective: REJECTED at inspection; the customer reads the code, QC's words stay inside")
        void noDefect() {
            ReturnRequest request = received(5, 5);
            request.pullDomainEvents();
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line(request, PRINTED_LINE).id(), 0, 5,
                    ReturnItemCondition.GOOD, 0, 0, 5, null, null)), "in đúng mẫu duyệt");
            assertThat(request.status()).isEqualTo(ReturnStatus.REJECTED);
            assertThat(request.rejectionReason()).isEqualTo("NO_DEFECT_FOUND");
            assertThat(request.inspectionNote()).isEqualTo("in đúng mẫu duyệt");
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((ReturnRejected) ((OrderEvent.ReturnChanged) event).payload()).stage())
                            .isEqualTo("INSPECTION"));
        }
    }

    @Nested
    @DisplayName("resolution and four eyes")
    class Resolution {

        @Test
        @DisplayName("replacements only: they wait for a second person too, RESOLVED without money or a VAT note")
        void replacementOnly() {
            ReturnRequest request = inspected(10, 8);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(code(catchThrowable(() -> request.proposeResolution(SALES, NOW,
                    List.of(new ReturnRequest.Remedy(line, 7, 0)), null, null))))
                    .as("every accepted unit").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 8, 0)), null, null);
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
            assertThat(code(catchThrowable(() -> request.resolve(SALES, NOW, UUID.randomUUID(), "SO-1",
                    Map.of(line, UUID.randomUUID()), ReturnRequest.MoneyOutcome.NONE))))
                    .as("free goods leave on two people's word").isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            UUID replacementOrder = UUID.randomUUID();
            UUID replacementLine = UUID.randomUUID();
            request.pullDomainEvents();
            request.resolve(ACCOUNTANT, NOW, replacementOrder, "SO-20261022-000009", Map.of(line, replacementLine),
                    ReturnRequest.MoneyOutcome.NONE);
            assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);
            assertThat(request.vatAdjustmentRequired()).isFalse();
            assertThat(line(request, PRINTED_LINE).replacementOrderLineId()).isEqualTo(replacementLine);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnResolved resolved = (ReturnResolved) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(resolved.replacementOrderId()).isEqualTo(replacementOrder);
                assertThat(resolved.replacementOrderNumber()).isEqualTo("SO-20261022-000009");
                assertThat(resolved.moneyOrderId()).isEqualTo(request.orderId());
            });
        }

        @Test
        @DisplayName("money: SETTLEMENT_PENDING; never approved by its proposer; RESOLVED by another, with the VAT note")
        void fourEyes() {
            ReturnRequest request = inspected(10, 10);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(code(catchThrowable(() -> request.proposeResolution(SALES, NOW,
                    List.of(new ReturnRequest.Remedy(line, 4, 6)), null, null))))
                    .as("money needs a mode").isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 4, 6)),
                    ReturnResolution.REFUND, "4 in lại, 6 hoàn tiền");
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
            assertThat(request.settlementAmount()).isEqualByComparingTo("12000");
            assertThat(code(catchThrowable(() -> request.requireSettlementApprover(SALES))))
                    .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            assertThat(catchThrowable(() -> request.resolve(ACCOUNTANT, NOW, UUID.randomUUID(), "SO-1",
                    Map.of(line, UUID.randomUUID()), new ReturnRequest.MoneyOutcome(BigDecimal.ONE, null, null, null))))
                    .as("payment booked another amount").isInstanceOf(IllegalStateException.class);
            request.resolve(ACCOUNTANT, NOW, UUID.randomUUID(), "SO-1", Map.of(line, UUID.randomUUID()),
                    new ReturnRequest.MoneyOutcome(BigDecimal.valueOf(2_000), BigDecimal.valueOf(10_000), null,
                            UUID.randomUUID()));
            assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);
            assertThat(request.vatAdjustmentRequired()).isTrue();
            assertThat(request.settledBy()).isEqualTo(ACCOUNTANT);
            assertThat(request.money().total()).isEqualByComparingTo("12000");
        }

        @Test
        @DisplayName("the approver sends it back: INSPECTED again, the proposal cleared")
        void sentBack() {
            ReturnRequest request = inspected(10, 10);
            UUID line = line(request, PRINTED_LINE).id();
            request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 0, 10)),
                    ReturnResolution.CREDIT_NOTE, null);
            assertThat(code(catchThrowable(() -> request.rejectSettlement(ACCOUNTANT, NOW, null))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.rejectSettlement(ACCOUNTANT, NOW, "Khách muốn in lại");
            assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
            assertThat(request.settlementAmount()).isZero();
            assertThat(request.moneyMode()).isNull();
            assertThat(line(request, PRINTED_LINE).creditQuantity()).isZero();
            request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 10, 0)), null, null);
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        }

        @Test
        @DisplayName("a change of mind is credited, never exchanged")
        void changeOfMindNotExchanged() {
            ReturnPolicy lenient = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), POLICY.noReturnMaxValue(),
                    true, 10, true);
            ReturnRequest request = ReturnRequest.open("RMA-1", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                    ReturnChannel.PORTAL, ReturnReason.CHANGED_MIND, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(STOCK_LINE, 4)), Map.of(), Set.of(), CUSTOMER, NOW, lenient);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), lenient);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, false, null, lenient))))
                    .as("the goods come back").isEqualTo(ErrorCode.RETURN_GOODS_REQUIRED);
            request.approve(SALES, NOW, null, null, null, lenient);
            UUID line = line(request, STOCK_LINE).id();
            request.receive(WAREHOUSE, NOW, "HCM-QC01", List.of(new ReturnRequest.Receipt(line, 4, null, null)),
                    List.of(), null);
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 4, 0, ReturnItemCondition.UNOPENED,
                    4, 0, 0, "HCM-A01-1-A", null)), null);
            assertThat(code(catchThrowable(() -> request.proposeResolution(SALES, NOW,
                    List.of(new ReturnRequest.Remedy(line, 4, 0)), null, null))))
                    .isEqualTo(ErrorCode.RETURN_REASON_NOT_ACCEPTED);
        }
    }

    @Test
    @DisplayName("goods stay with the customer up to the limit per order: other returns of it count")
    void noReturnLimitPerOrder() {
        // 2 000 000 VND accepted on photos alone; the stock line is 500 VND a unit.
        ReturnRequest request = open(ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND,
                new ReturnRequest.Claim(STOCK_LINE, 10));
        request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
        assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, false, null, POLICY,
                new BigDecimal("1999000"))))).as("5 000 here on top of 1 999 000 elsewhere")
                .isEqualTo(ErrorCode.RETURN_GOODS_REQUIRED);
        request.approve(SALES, NOW, null, false, null, POLICY, new BigDecimal("1995000"));
        assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
    }

    @Nested
    @DisplayName("withdrawing a replacement")
    class ReplacementWithdrawal {

        @Test
        @DisplayName("proposed with a reason on a resolved return that replaced goods; approved by another; credited")
        void withdraw() {
            ReturnRequest request = replaced(10);
            assertThat(code(catchThrowable(() -> request.proposeReplacementWithdrawal(SALES, NOW, " "))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.proposeReplacementWithdrawal(SALES, NOW, "Khách muốn nhận tiền thay vì in lại");
            assertThat(request.withdrawalStatus()).isEqualTo(ReturnRequest.WithdrawalStatus.PENDING);
            assertThat(request.withdrawalAmount()).as("10 x 2 000").isEqualByComparingTo("20000");
            assertThat(request.status()).as("the return stays resolved").isEqualTo(ReturnStatus.RESOLVED);
            assertThat(code(catchThrowable(() -> request.proposeReplacementWithdrawal(SALES, NOW, "again"))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> request.requireWithdrawalApprover(SALES))))
                    .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            assertThat(catchThrowable(() -> request.approveReplacementWithdrawal(ACCOUNTANT, NOW,
                    new ReturnRequest.MoneyOutcome(BigDecimal.ZERO, BigDecimal.valueOf(20_000), BigDecimal.ZERO, null),
                    null))).as("never refunded in cash here").isInstanceOf(IllegalStateException.class);
            request.pullDomainEvents();
            UUID transfer = UUID.randomUUID();
            UUID paidOn = UUID.randomUUID();
            request.approveReplacementWithdrawal(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(
                    BigDecimal.valueOf(5_000), BigDecimal.ZERO, BigDecimal.valueOf(15_000), transfer), paidOn);
            assertThat(request.withdrawalStatus()).isEqualTo(ReturnRequest.WithdrawalStatus.APPROVED);
            assertThat(request.withdrawalMoney().total()).isEqualByComparingTo("20000");
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnReplacementWithdrawn withdrawn = (ReturnReplacementWithdrawn)
                        ((OrderEvent.ReturnChanged) event).payload();
                assertThat(withdrawn.paidOrderId()).isEqualTo(paidOn);
                assertThat(withdrawn.lines()).singleElement().satisfies(line -> {
                    assertThat(line.sku()).isEqualTo("CUP-12OZ");
                    assertThat(line.quantity()).isEqualTo(10);
                    assertThat(line.amount()).isEqualByComparingTo("20000");
                });
            });
            assertThat(code(catchThrowable(() -> request.proposeReplacementWithdrawal(SALES, NOW, "once more"))))
                    .as("withdrawn once").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("a replacement that shipped in part: the rest is withdrawn and credited; the credit flags the "
                + "adjustment invoice")
        void partWithdrawn() {
            ReturnRequest request = replaced(10);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(request.vatAdjustmentRequired()).as("nothing given back yet").isFalse();
            request.proposeReplacementWithdrawal(SALES, NOW, "In lại 4 chiếc không đạt, khách nhận tiền phần còn lại");
            assertThat(request.withdrawalAmountFor(Map.of(line, 4))).as("4 x 2 000").isEqualByComparingTo("8000");
            assertThat(catchThrowable(() -> request.approveReplacementWithdrawal(ACCOUNTANT, NOW,
                    new ReturnRequest.MoneyOutcome(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(20_000),
                            UUID.randomUUID()), null, Map.of(line, 4))))
                    .as("credited exactly what is withdrawn").isInstanceOf(IllegalStateException.class);
            assertThat(code(catchThrowable(() -> request.approveReplacementWithdrawal(ACCOUNTANT, NOW,
                    ReturnRequest.MoneyOutcome.NONE, null, Map.of(line, 0)))))
                    .as("everything shipped").isEqualTo(ErrorCode.RETURN_REPLACEMENT_NOT_WITHDRAWABLE);
            request.pullDomainEvents();
            request.approveReplacementWithdrawal(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.valueOf(8_000), UUID.randomUUID()), null, Map.of(line, 4));
            assertThat(request.withdrawalAmount()).isEqualByComparingTo("8000");
            assertThat(request.vatAdjustmentRequired()).isTrue();
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> assertThat(
                    ((ReturnReplacementWithdrawn) ((OrderEvent.ReturnChanged) event).payload()).lines())
                    .singleElement().satisfies(withdrawn -> assertThat(withdrawn.quantity()).isEqualTo(4)));
        }

        @Test
        @DisplayName("sent back with a reason: the replacement ships; a new proposal may follow")
        void sentBack() {
            ReturnRequest request = replaced(4);
            request.proposeReplacementWithdrawal(SALES, NOW, "Không in lại được");
            assertThat(code(catchThrowable(() -> request.rejectReplacementWithdrawal(ACCOUNTANT, NOW, null))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.rejectReplacementWithdrawal(ACCOUNTANT, NOW, "Xưởng xác nhận in lại được");
            assertThat(request.withdrawalStatus()).isEqualTo(ReturnRequest.WithdrawalStatus.REJECTED);
            request.proposeReplacementWithdrawal(OTHER_SALES, NOW, "Khách huỷ đơn thay thế");
            assertThat(request.withdrawalStatus()).isEqualTo(ReturnRequest.WithdrawalStatus.PENDING);
            assertThat(request.withdrawalDecidedBy()).isNull();
        }

        @Test
        @DisplayName("only a resolved return with a replacement has one to withdraw")
        void nothingToWithdraw() {
            assertThat(code(catchThrowable(() -> inspected(5, 5).proposeReplacementWithdrawal(SALES, NOW, "x"))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            ReturnRequest credited = inspected(5, 5);
            UUID line = line(credited, PRINTED_LINE).id();
            credited.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 0, 5)),
                    ReturnResolution.CREDIT_NOTE, null);
            credited.resolve(ACCOUNTANT, NOW, null, null, Map.of(), new ReturnRequest.MoneyOutcome(BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.valueOf(10_000), UUID.randomUUID()));
            assertThat(code(catchThrowable(() -> credited.proposeReplacementWithdrawal(SALES, NOW, "x"))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }
    }

    @Nested
    @DisplayName("evidence")
    class Evidence {

        @Test
        @DisplayName("photos at their steps, at most the limit per step: the customer's never crowd out QC's")
        void stages() {
            ReturnPolicy two = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), POLICY.noReturnMaxValue(), true,
                    2, false);
            ReturnRequest request = misprinted(5);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.INSPECTION), two))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two))))
                    .isEqualTo(ErrorCode.RETURN_EVIDENCE_LIMIT_REACHED);
            assertThat(request.pullNewEvidence()).hasSize(2);
            assertThat(request.pullNewEvidence()).isEmpty();
            request.approve(SALES, NOW, null, null, null, two);
            request.addEvidence(photo(ReturnEvidence.Stage.RECEIPT), two);
            assertThat(request.pullNewEvidence()).as("the warehouse still has room").hasSize(1);
        }
    }

    @Test
    @DisplayName("the state machine: open states hold units; RESOLVED and REJECTED go nowhere; late goods reopen")
    void stateMachine() {
        assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen))
                .containsExactly(ReturnStatus.REQUESTED, ReturnStatus.AWAITING_RETURN, ReturnStatus.RECEIVED,
                        ReturnStatus.INSPECTED, ReturnStatus.SETTLEMENT_PENDING);
        for (ReturnStatus end : List.of(ReturnStatus.RESOLVED, ReturnStatus.REJECTED)) {
            assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(end::canTransitionTo)).as(end.name())
                    .isEmpty();
        }
        for (ReturnStatus end : List.of(ReturnStatus.EXPIRED, ReturnStatus.CANCELLED)) {
            assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(end::canTransitionTo)).as(end.name())
                    .containsExactly(ReturnStatus.RECEIVED);
        }
        assertThat(ReturnStatus.REQUESTED.canTransitionTo(ReturnStatus.RECEIVED)).isFalse();
        assertThat(ReturnStatus.INSPECTED.canTransitionTo(ReturnStatus.REJECTED)).isFalse();
        assertThat(ReturnStatus.INSPECTED.canTransitionTo(ReturnStatus.RESOLVED)).as("never without approval").isFalse();
        assertThatThrownBy(() -> new ReturnDiscrepancy(UUID.randomUUID(), null, ReturnDiscrepancy.Kind.SHORT, "X", 1,
                null, WAREHOUSE, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
