package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
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
 * window (BR-RMA-001), printed goods only when defective (BR-RMA-002), quantities never above what was delivered, the
 * evidence and value rules of the review, what a parcel held, QC's arithmetic, and four eyes on the money.
 */
class ReturnRequestTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(2));
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final ReturnPolicy POLICY = ReturnPolicy.defaults();

    private static final UUID PRINTED_LINE = UUID.randomUUID();
    private static final UUID STOCK_LINE = UUID.randomUUID();

    /** Delivered: 100 printed cups at 2 000 VND, 50 plain lids at 500 VND, 10 of them closed out before delivery. */
    private static Order order(OrderStatus status) {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 3), CUSTOMER,
                UUID.randomUUID(), List.of(
                new OrderLine(PRINTED_LINE, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), UUID.randomUUID(), List.of()),
                new OrderLine(STOCK_LINE, new Sku("LID-90"), 50, Money.vnd(500), null, List.of(), 10)),
                status, DELIVERED.minus(Duration.ofDays(10)), status == OrderStatus.CANCELLED ? "x" : null, 3L);
        order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null,
                status == OrderStatus.COMPLETED ? DELIVERED.plus(Duration.ofDays(7)) : null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return order;
    }

    private static ReturnRequest open(ReturnReason reason, ReturnResolution wanted, ReturnRequest.Claim... claims) {
        return ReturnRequest.open("RMA-20261022-0001", UUID.randomUUID(), order(OrderStatus.DELIVERED),
                ReturnChannel.PORTAL, reason, "Mực in bị lem", wanted, List.of(claims), Map.of(), Set.of(), CUSTOMER,
                NOW, POLICY);
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
            assertThat(request.pullTransitions()).singleElement()
                    .satisfies(t -> assertThat(t.to()).isEqualTo(ReturnStatus.REQUESTED));
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((OrderEvent.ReturnChanged) event).payload()).isInstanceOf(ReturnRequested.class));
        }

        @Test
        @DisplayName("after the return window: RETURN_WINDOW_CLOSED")
        void windowClosed() {
            Throwable late = catchThrowable(() -> ReturnRequest.open("RMA-1", UUID.randomUUID(),
                    order(OrderStatus.DELIVERED), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(),
                    CUSTOMER, DELIVERED.plus(Duration.ofDays(7)).plusSeconds(1), POLICY));
            assertThat(code(late)).isEqualTo(ErrorCode.RETURN_WINDOW_CLOSED);
            // On the last second of the window it is still open.
            assertThat(ReturnRequest.open("RMA-1", UUID.randomUUID(), order(OrderStatus.DELIVERED), ReturnChannel.PORTAL,
                    ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(), CUSTOMER,
                    DELIVERED.plus(Duration.ofDays(7)), POLICY).status()).isEqualTo(ReturnStatus.REQUESTED);
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
        @DisplayName("a cancelled order, a completed one, one settled by a return: each refused by name")
        void otherStatuses() {
            Map<OrderStatus, ErrorCode> expected = Map.of(OrderStatus.CANCELLED, ErrorCode.ORDER_CANCELLED,
                    OrderStatus.COMPLETED, ErrorCode.RETURN_WINDOW_CLOSED,
                    OrderStatus.PARTIALLY_REFUNDED, ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    OrderStatus.REFUNDED, ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
            expected.forEach((status, error) -> assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-1",
                    UUID.randomUUID(), order(status), ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null,
                    ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(PRINTED_LINE, 1)), Map.of(), Set.of(),
                    CUSTOMER, NOW, POLICY)))).as(status.name()).isEqualTo(error));
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
        @DisplayName("a defect is approved on a photo; then AWAITING_RETURN with the deadline; approving twice refused")
        void approve() {
            ReturnRequest request = misprinted(20);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_EVIDENCE_REQUIRED);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.pullDomainEvents();
            request.approve(SALES, NOW, null, null, "ok", POLICY);
            assertThat(request.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
            assertThat(request.returnRequired()).isTrue();
            assertThat(request.returnDeadline()).isEqualTo(NOW.plus(Duration.ofDays(14)));
            assertThat(line(request, PRINTED_LINE).approvedQuantity()).isEqualTo(20);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((OrderEvent.ReturnChanged) event).payload()).isInstanceOf(ReturnApproved.class));
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, null, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
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
        @DisplayName("a short delivery has nothing to send back and needs no photo")
        void shortDelivery() {
            ReturnRequest request = open(ReturnReason.SHORT_DELIVERY, ReturnResolution.REPLACEMENT,
                    new ReturnRequest.Claim(STOCK_LINE, 4));
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
        @DisplayName("goods not back by the deadline: EXPIRED, by nobody; not a second before")
        void expire() {
            ReturnRequest request = awaiting(5);
            assertThat(request.expire(request.returnDeadline())).isFalse();
            assertThat(request.expire(request.returnDeadline().plusSeconds(1))).isTrue();
            assertThat(request.status()).isEqualTo(ReturnStatus.EXPIRED);
            assertThat(request.pullTransitions()).last().satisfies(t -> assertThat(t.actor()).isNull());
            assertThat(request.expire(request.returnDeadline().plusSeconds(2))).as("once").isFalse();
        }
    }

    @Nested
    @DisplayName("receipt")
    class Receipt {

        @Test
        @DisplayName("fewer, more and other goods than approved, and something of no line, are recorded as discrepancies")
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
            assertThat(line(request, STOCK_LINE).receivedSku()).isEqualTo("LID-80");
            assertThat(line(request, STOCK_LINE).lotNumber()).isEqualTo("L-7");
            assertThat(request.discrepancies()).extracting(d -> d.kind() + " " + d.sku() + " " + d.quantity())
                    .containsExactlyInAnyOrder("SHORT CUP-12OZ 2", "WRONG_ITEM LID-80 12", "EXCESS LID-80 2",
                            "UNEXPECTED_ITEM XYZ-1 3");
            assertThat(request.pullNewDiscrepancies()).hasSize(4);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((ReturnReceived) ((OrderEvent.ReturnChanged) event).payload()).discrepancy()).isTrue());
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
                    line, 10, 2, null, 2, 10, 0, "HCM-A01-1-A", null)), null)))).as("printed goods never back on sale")
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
        @DisplayName("a stock item that passed QC goes back on sale, at a bin")
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
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line, 10, 0,
                    ReturnItemCondition.DAMAGED, 4, 6, 0, "HCM-A01-1-A", null)), null);
            assertThat(line(request, STOCK_LINE).restockQuantity()).isEqualTo(4);
        }

        @Test
        @DisplayName("nothing defective: the return is REJECTED at inspection")
        void noDefect() {
            ReturnRequest request = received(5, 5);
            request.pullDomainEvents();
            request.inspect(WAREHOUSE, NOW, List.of(new ReturnRequest.Inspection(line(request, PRINTED_LINE).id(), 0, 5,
                    ReturnItemCondition.GOOD, 0, 0, 5, null, null)), "in đúng mẫu duyệt");
            assertThat(request.status()).isEqualTo(ReturnStatus.REJECTED);
            assertThat(request.rejectionReason()).isEqualTo("NO_DEFECT_FOUND: in đúng mẫu duyệt");
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event ->
                    assertThat(((ReturnRejected) ((OrderEvent.ReturnChanged) event).payload()).stage())
                            .isEqualTo("INSPECTION"));
        }
    }

    @Nested
    @DisplayName("resolution and four eyes")
    class Resolution {

        @Test
        @DisplayName("replacements only: carried out at once, RESOLVED without money and without a VAT note")
        void replacementOnly() {
            ReturnRequest request = inspected(10, 8);
            UUID line = line(request, PRINTED_LINE).id();
            assertThat(code(catchThrowable(() -> request.proposeResolution(SALES, NOW,
                    List.of(new ReturnRequest.Remedy(line, 7, 0)), null, null))))
                    .as("every accepted unit").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 8, 0)), null, null))
                    .isFalse();
            assertThat(request.status()).isEqualTo(ReturnStatus.INSPECTED);
            UUID replacementOrder = UUID.randomUUID();
            UUID replacementLine = UUID.randomUUID();
            request.pullDomainEvents();
            request.resolve(SALES, NOW, replacementOrder, "SO-20261022-000009", Map.of(line, replacementLine),
                    ReturnRequest.MoneyOutcome.NONE);
            assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);
            assertThat(request.vatAdjustmentRequired()).isFalse();
            assertThat(line(request, PRINTED_LINE).replacementOrderLineId()).isEqualTo(replacementLine);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnResolved resolved = (ReturnResolved) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(resolved.replacementOrderId()).isEqualTo(replacementOrder);
                assertThat(resolved.replacementOrderNumber()).isEqualTo("SO-20261022-000009");
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
            assertThat(request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 4, 6)),
                    ReturnResolution.REFUND, "4 in lại, 6 hoàn tiền")).isTrue();
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
            assertThat(request.proposeResolution(SALES, NOW, List.of(new ReturnRequest.Remedy(line, 10, 0)), null,
                    null)).isFalse();
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

    @Nested
    @DisplayName("evidence")
    class Evidence {

        @Test
        @DisplayName("claim photos while the claim is open, receipt and inspection photos at their steps, at most the limit")
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
        }
    }

    @Test
    @DisplayName("the state machine: open states hold units; terminal states go nowhere")
    void stateMachine() {
        assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen))
                .containsExactly(ReturnStatus.REQUESTED, ReturnStatus.AWAITING_RETURN, ReturnStatus.RECEIVED,
                        ReturnStatus.INSPECTED, ReturnStatus.SETTLEMENT_PENDING);
        for (ReturnStatus from : ReturnStatus.values()) {
            if (!from.isOpen()) {
                assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(from::canTransitionTo)).as(from.name())
                        .isEmpty();
            }
        }
        assertThat(ReturnStatus.REQUESTED.canTransitionTo(ReturnStatus.RECEIVED)).isFalse();
        assertThat(ReturnStatus.INSPECTED.canTransitionTo(ReturnStatus.REJECTED)).isFalse();
        assertThatThrownBy(() -> new ReturnDiscrepancy(UUID.randomUUID(), null, ReturnDiscrepancy.Kind.SHORT, "X", 1,
                null, WAREHOUSE, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
