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
 * The return state machine (SCRUM-243 / SCRUM-260, 03 §12; reshaped by team plan 4D-3) and every rule it protects, on
 * the aggregate alone: the window in calendar days (BR-RMA-001), printed goods only when defective (BR-RMA-002),
 * quantities never above what was delivered, the evidence rules of the review, goods that always come back (17 BR-18)
 * on a return receipt and are counted per SKU with their discrepancies, and a refund valued per unit with its VAT that
 * waits for a second person. There is no inspection, replacement or credit any more.
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
                ReturnChannel.BACK_OFFICE, reason, "Khách gọi điện báo", ReturnResolution.REFUND, List.of(claims),
                Map.of(), Set.of(), SALES, NOW, POLICY);
    }

    private static ReturnRequest misprinted(int units) {
        return open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
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

    private static final UUID RECEIPT = UUID.randomUUID();

    /** Back: a return receipt took {@code arrived} cartons of one unit each of the printed cups into quarantine. */
    private static ReturnRequest received(int units, int arrived) {
        ReturnRequest request = awaiting(units);
        assertThat(request.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", arrived)), WAREHOUSE, NOW)).isTrue();
        return request;
    }

    /** Refund of every unit approved and back, proposed by SALES. */
    private static ReturnRequest pending(int units) {
        ReturnRequest request = received(units, units);
        request.proposeRefund(SALES, NOW, null, order(OrderStatus.RETURNED), "ok");
        return request;
    }

    @Nested
    @DisplayName("opening (BR-RMA-001, BR-RMA-002)")
    class Opening {

        @Test
        @DisplayName("a delivered order, within the window: REQUESTED, the line's SKU, price and print flag copied")
        void opens() {
            ReturnRequest request = open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
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
                    NOW, POLICY, "goodwill")))).isEqualTo(ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
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
                    "Lỗi in phát hiện khi khách mở pallet thứ 3");
            assertThat(late.status()).isEqualTo(ReturnStatus.REQUESTED);
            assertThat(late.windowOverride()).isEqualTo("Lỗi in phát hiện khi khách mở pallet thứ 3");
            assertThat(late.pullTransitions()).singleElement()
                    .satisfies(t -> assertThat(t.reason()).contains("LATE_CLAIM_ACCEPTED"));
            assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-4", UUID.randomUUID(), completed,
                    ReturnChannel.PORTAL, ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                    List.of(new ReturnRequest.Claim(PRINTED_LINE, 5)), Map.of(), Set.of(), CUSTOMER, monthLater, POLICY,
                    "please")))).isEqualTo(ErrorCode.VALIDATION_FAILED);
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
        @DisplayName("OTHER names no defect: no way around printed goods coming back for a named defect only "
                + "(BR-RMA-002); Sales may still take it for stock goods")
        void otherIsNoDefect() {
            assertThat(code(catchThrowable(() -> open(ReturnReason.OTHER, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(PRINTED_LINE, 1))))).isEqualTo(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE);
            assertThat(open(ReturnReason.OTHER, ReturnResolution.REFUND, new ReturnRequest.Claim(STOCK_LINE, 1))
                    .status()).isEqualTo(ReturnStatus.REQUESTED);
            assertThat(ReturnReason.OTHER.isNamedFault()).isFalse();
            assertThat(ReturnReason.PRINT_DEFECT.isNamedFault()).isTrue();
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
        @DisplayName("the goods always come back (17 BR-18): approval that leaves them with the customer is refused")
        void goodsAlwaysComeBack() {
            ReturnRequest request = misprinted(1);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            assertThat(code(catchThrowable(() -> request.approve(SALES, NOW, null, false, null, POLICY))))
                    .isEqualTo(ErrorCode.RETURN_GOODS_REQUIRED);
            assertThat(request.status()).isEqualTo(ReturnStatus.REQUESTED);
            request.approve(SALES, NOW, null, true, null, POLICY);
            assertThat(request.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
            assertThat(request.returnRequired()).isTrue();
        }

        @Test
        @DisplayName("a short delivery has nothing to send back: it is no return (the delivery is corrected instead)")
        void shortDelivery() {
            assertThat(code(catchThrowable(() -> open(ReturnReason.SHORT_DELIVERY, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(STOCK_LINE, 4))))).isEqualTo(ErrorCode.RETURN_REASON_NOT_ACCEPTED);
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
    @DisplayName("goods back on a return receipt (17 §4.10 step 39; 03 §4.2 step 12)")
    class GoodsBack {

        @Test
        @DisplayName("cartons of the approved SKU and count: RECEIVED with who, where and which receipt; no discrepancy")
        void exact() {
            ReturnRequest request = awaiting(10);
            request.pullDomainEvents();
            assertThat(request.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01", List.of(
                    new ReturnRequest.ReturnedCarton("CUP-12OZ", 6), new ReturnRequest.ReturnedCarton("CUP-12OZ", 4)),
                    WAREHOUSE, NOW)).isTrue();
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.goodsReceiptId()).isEqualTo(RECEIPT);
            assertThat(request.receivedBy()).isEqualTo(WAREHOUSE);
            assertThat(request.receivedAt()).isEqualTo(NOW);
            assertThat(request.receivingLocation()).isEqualTo("HCM-QUA01");
            assertThat(line(request, PRINTED_LINE).receivedQuantity()).as("cartons of a SKU add up").isEqualTo(10);
            assertThat(request.discrepancies()).isEmpty();
            assertThat(request.pullTransitions()).last().satisfies(t -> {
                assertThat(t.to()).isEqualTo(ReturnStatus.RECEIVED);
                assertThat(t.reason()).isEqualTo("RECEIVED GR-RET-1");
            });
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnReceived received = (ReturnReceived) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(received.discrepancy()).isFalse();
            });
        }

        @Test
        @DisplayName("fewer than approved, more than approved, a SKU the return does not claim: discrepancies for Sales")
        void discrepancies() {
            ReturnRequest request = open(ReturnReason.WRONG_ITEM, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(PRINTED_LINE, 10), new ReturnRequest.Claim(STOCK_LINE, 10));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.approve(SALES, NOW, null, null, null, POLICY);
            request.goodsReceived(RECEIPT, "GR-RET-2", "HCM-QUA01", List.of(
                    new ReturnRequest.ReturnedCarton("CUP-12OZ", 8), new ReturnRequest.ReturnedCarton("LID-90", 12),
                    new ReturnRequest.ReturnedCarton("XYZ-1", 3)), WAREHOUSE, NOW);
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.discrepancies()).extracting(d -> d.kind() + " " + d.sku() + " " + d.quantity())
                    .containsExactlyInAnyOrder("SHORT CUP-12OZ 2", "EXCESS LID-90 2", "UNEXPECTED_ITEM XYZ-1 3");
            assertThat(request.pullNewDiscrepancies()).hasSize(3);
            assertThat(request.pullNewDiscrepancies()).isEmpty();
            assertThat(request.pullTransitions()).last().satisfies(t ->
                    assertThat(t.reason()).startsWith("RECEIVED_WITH_DISCREPANCY"));
        }

        @Test
        @DisplayName("the same receipt again (the event delivered twice) changes nothing; another receipt is refused")
        void idempotent() {
            ReturnRequest request = received(5, 5);
            request.pullDomainEvents();
            request.pullTransitions();
            assertThat(request.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW)).isFalse();
            assertThat(request.pullDomainEvents()).isEmpty();
            assertThat(request.pullTransitions()).isEmpty();
            assertThat(code(catchThrowable(() -> request.goodsReceived(UUID.randomUUID(), "GR-RET-9", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("a return that is not awaiting goods takes none; no carton at all is a defect, not a receipt")
        void notAwaiting() {
            ReturnRequest requested = misprinted(5);
            assertThat(code(catchThrowable(() -> requested.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            ReturnRequest rejected = misprinted(5);
            rejected.reject(SALES, NOW, "no");
            assertThat(code(catchThrowable(() -> rejected.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            ReturnRequest awaiting = awaiting(5);
            assertThatThrownBy(() -> awaiting.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01", List.of(), WAREHOUSE,
                    NOW)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReturnRequest.ReturnedCarton("CUP-12OZ", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("goods after the deadline, or after the customer withdrew an approved return: counted in, RECEIVED")
        void lateGoods() {
            ReturnRequest expired = awaiting(5);
            expired.expire(expired.returnDeadline());
            assertThat(expired.closedAt()).isNotNull();
            expired.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW);
            assertThat(expired.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(expired.closedAt()).as("reopened").isNull();
            assertThat(expired.pullTransitions()).last().satisfies(t ->
                    assertThat(t.reason()).startsWith("RECEIVED_AFTER_DEADLINE"));

            ReturnRequest withdrawn = awaiting(5);
            withdrawn.cancel(CUSTOMER, NOW, "changed my mind", true);
            withdrawn.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW);
            assertThat(withdrawn.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(withdrawn.cancellationReason()).isNull();

            ReturnRequest neverApproved = misprinted(5);
            neverApproved.cancel(CUSTOMER, NOW, null, true);
            assertThat(code(catchThrowable(() -> neverApproved.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW))))
                    .as("a return nobody approved takes no goods").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }
    }

    @Nested
    @DisplayName("the refund and four eyes (17 §4.10 step 40; 15 §4.5)")
    class Refund {

        @Test
        @DisplayName("proposed per unit at the order's price with its VAT, waits for a second person: SETTLEMENT_PENDING")
        void proposed() {
            ReturnRequest request = received(10, 10);
            Order order = order(OrderStatus.RETURNED);
            request.proposeRefund(SALES, NOW, null, order, "defect confirmed");
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
            assertThat(request.moneyMode()).isEqualTo(ReturnResolution.REFUND);
            BigDecimal worth = order.findLine(PRINTED_LINE).orElseThrow().grossOf(10).amount();
            assertThat(worth).isGreaterThanOrEqualTo(new BigDecimal("20000"));
            assertThat(request.settlementAmount()).isEqualByComparingTo(worth);
            assertThat(line(request, PRINTED_LINE).refundQuantity()).isEqualTo(10);
            assertThat(line(request, PRINTED_LINE).refundAmount()).isEqualByComparingTo(worth);
            assertThat(request.proposedBy()).isEqualTo(SALES);
        }

        @Test
        @DisplayName("part of what came back: at most the units approved and back; at least one unit; a line left out gets none")
        void partly() {
            ReturnRequest request = open(ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                    new ReturnRequest.Claim(PRINTED_LINE, 10), new ReturnRequest.Claim(STOCK_LINE, 5));
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY);
            request.approve(SALES, NOW, Map.of(line(request, PRINTED_LINE).id(), 8), null, null, POLICY);
            request.goodsReceived(RECEIPT, "GR-RET-3", "HCM-QUA01", List.of(
                    new ReturnRequest.ReturnedCarton("CUP-12OZ", 6), new ReturnRequest.ReturnedCarton("LID-90", 5)),
                    WAREHOUSE, NOW);
            UUID printed = line(request, PRINTED_LINE).id();
            UUID stock = line(request, STOCK_LINE).id();
            Order order = order(OrderStatus.RETURNED);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, Map.of(printed, 7), order, null))))
                    .as("8 approved but only 6 back").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, Map.of(printed, 0, stock, 0), order,
                    null)))).isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
            request.proposeRefund(SALES, NOW, Map.of(printed, 6), order, null);
            assertThat(line(request, PRINTED_LINE).refundQuantity()).isEqualTo(6);
            assertThat(line(request, STOCK_LINE).refundQuantity()).isZero();
            assertThat(request.settlementAmount())
                    .isEqualByComparingTo(order.findLine(PRINTED_LINE).orElseThrow().grossOf(6).amount());
        }

        @Test
        @DisplayName("only a return whose goods are back has a refund proposed")
        void onlyAfterGoodsBack() {
            ReturnRequest request = awaiting(5);
            assertThat(code(catchThrowable(() -> request.proposeRefund(SALES, NOW, null,
                    order(OrderStatus.RETURN_REQUESTED), null)))).isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("never approved by its proposer; RESOLVED by another with what payment settled, and the VAT note")
        void fourEyes() {
            ReturnRequest request = pending(10);
            assertThat(code(catchThrowable(() -> request.requireSettlementApprover(SALES))))
                    .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            BigDecimal amount = request.settlementAmount();
            assertThat(code(catchThrowable(() -> request.resolve(SALES, NOW,
                    new ReturnRequest.MoneyOutcome(amount, BigDecimal.ZERO))))).isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
            assertThatThrownBy(() -> request.resolve(ACCOUNTANT, NOW, ReturnRequest.MoneyOutcome.NONE))
                    .as("payment settled another amount than the refund: a defect")
                    .isInstanceOf(IllegalStateException.class);
            request.pullDomainEvents();
            BigDecimal offset = amount.divide(BigDecimal.valueOf(4), 4, java.math.RoundingMode.HALF_UP);
            request.resolve(ACCOUNTANT, NOW, new ReturnRequest.MoneyOutcome(offset, amount.subtract(offset)));
            assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);
            assertThat(request.settledBy()).isEqualTo(ACCOUNTANT);
            assertThat(request.vatAdjustmentRequired()).isTrue();
            assertThat(request.closedAt()).isEqualTo(NOW);
            assertThat(request.pullDomainEvents()).singleElement().satisfies(event -> {
                ReturnResolved resolved = (ReturnResolved) ((OrderEvent.ReturnChanged) event).payload();
                assertThat(resolved.vatAdjustmentRequired()).isTrue();
            });
            assertThat(code(catchThrowable(() -> request.requireSettlementApprover(ACCOUNTANT))))
                    .as("resolved once").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("the approver sends it back: RECEIVED again, the proposal cleared, a reason required; a new proposal may follow")
        void sentBack() {
            ReturnRequest request = pending(10);
            assertThat(code(catchThrowable(() -> request.rejectSettlement(ACCOUNTANT, NOW, " "))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            request.rejectSettlement(ACCOUNTANT, NOW, "count the lids again");
            assertThat(request.status()).isEqualTo(ReturnStatus.RECEIVED);
            assertThat(request.settlementAmount()).isZero();
            assertThat(request.moneyMode()).isNull();
            assertThat(request.proposedBy()).isNull();
            assertThat(line(request, PRINTED_LINE).refundQuantity()).isZero();
            request.proposeRefund(OTHER_SALES, NOW, Map.of(line(request, PRINTED_LINE).id(), 3),
                    order(OrderStatus.RETURNED), null);
            assertThat(request.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
            assertThat(request.proposedBy()).isEqualTo(OTHER_SALES);
        }
    }

    @Nested
    @DisplayName("evidence")
    class Evidence {

        @Test
        @DisplayName("only the claim's photos are the return's, at most the limit; those of the goods back are the receipt's")
        void stages() {
            ReturnPolicy two = new ReturnPolicy(POLICY.window(), POLICY.returnDeadline(), POLICY.noReturnMaxValue(), true,
                    2, false);
            ReturnRequest request = misprinted(5);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.INSPECTION), two))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.RECEIPT), two))))
                    .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two);
            request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), two))))
                    .isEqualTo(ErrorCode.RETURN_EVIDENCE_LIMIT_REACHED);
            assertThat(request.pullNewEvidence()).hasSize(2);
            assertThat(request.pullNewEvidence()).isEmpty();
            request.approve(SALES, NOW, null, null, null, two);
            request.goodsReceived(RECEIPT, "GR-RET-1", "HCM-QUA01",
                    List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 5)), WAREHOUSE, NOW);
            assertThat(code(catchThrowable(() -> request.addEvidence(photo(ReturnEvidence.Stage.CLAIM), POLICY))))
                    .as("goods back: the claim takes no more photos").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        }
    }

    @Test
    @DisplayName("the state machine: open states hold units; RESOLVED and REJECTED go nowhere; late goods reopen")
    void stateMachine() {
        assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen))
                .containsExactly(ReturnStatus.REQUESTED, ReturnStatus.AWAITING_RETURN, ReturnStatus.RECEIVED,
                        ReturnStatus.SETTLEMENT_PENDING);
        for (ReturnStatus end : List.of(ReturnStatus.RESOLVED, ReturnStatus.REJECTED)) {
            assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(end::canTransitionTo)).as(end.name())
                    .isEmpty();
        }
        for (ReturnStatus end : List.of(ReturnStatus.EXPIRED, ReturnStatus.CANCELLED)) {
            assertThat(java.util.Arrays.stream(ReturnStatus.values()).filter(end::canTransitionTo)).as(end.name())
                    .containsExactly(ReturnStatus.RECEIVED);
        }
        assertThat(ReturnStatus.REQUESTED.canTransitionTo(ReturnStatus.RECEIVED)).isFalse();
        assertThat(ReturnStatus.RECEIVED.canTransitionTo(ReturnStatus.RESOLVED)).as("never without approval").isFalse();
        assertThat(ReturnStatus.SETTLEMENT_PENDING.canTransitionTo(ReturnStatus.RECEIVED)).as("sent back").isTrue();
        assertThatThrownBy(() -> new ReturnDiscrepancy(UUID.randomUUID(), null, ReturnDiscrepancy.Kind.SHORT, "X", 1,
                null, WAREHOUSE, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("4B-2: a line of the customer's own SKU in cartons carries no design snapshot, yet it is printed to the "
            + "customer's design - it comes back for a named defect only (17 §4.10, BR-RMA-002)")
    void aLineOfTheCustomersOwnSkuIsPrintedGoods() {
        UUID ownLine = UUID.randomUUID();
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 4), CUSTOMER,
                UUID.randomUUID(), List.of(new OrderLine(ownLine, new Sku("LY12-ACME-01"), 100, Money.vnd(2_000), null,
                        List.of()).withCartons(UUID.randomUUID(), 2, 50)),
                OrderStatus.DELIVERED, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(order.lines().getFirst().isMadeToOrder()).as("not a print line: released from stock").isFalse();
        assertThat(order.lines().getFirst().printedToCustomerDesign()).isTrue();

        assertThat(code(catchThrowable(() -> ReturnRequest.open("RMA-20261022-0009", UUID.randomUUID(), order,
                ReturnChannel.PORTAL, ReturnReason.OTHER, "Không thích", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(ownLine, 1)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY))))
                .isEqualTo(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE);
        ReturnRequest misprint = ReturnRequest.open("RMA-20261022-0010", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                ReturnReason.PRINT_DEFECT, "Mực in bị lem", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(ownLine, 10)), Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
        assertThat(misprint.lines()).singleElement().satisfies(l -> assertThat(l.customPrinted()).isTrue());
    }
}
