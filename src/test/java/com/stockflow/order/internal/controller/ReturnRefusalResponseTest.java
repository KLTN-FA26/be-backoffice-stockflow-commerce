package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.controller.dto.CustomerReturnResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestRowResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRefusalReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnRequestQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 F-20, the fields the storefront and the back office read (Q-4): {@code outcome} on the list row and the
 * customer's detail, the refusal reason with its Vietnamese label and the public note {@code refusalNote} - shown to
 * the customer once the refusal is approved, never on a proposal - and the receipt flags on the staff detail.
 */
class ReturnRefusalResponseTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-20T03:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(2));
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final ReturnPolicy POLICY = ReturnPolicy.defaults();

    private static Order order() {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 1), CUSTOMER,
                UUID.randomUUID(), List.of(new OrderLine(LINE, new Sku("CUP-12OZ"), 100, Money.vnd(2_000), null,
                List.of())), OrderStatus.DELIVERED, DELIVERED.minus(Duration.ofDays(10)), null, 3L);
        order.restoreLifecycle(null, null, null, DELIVERED.minus(Duration.ofDays(1)), DELIVERED, null, null, null, null);
        return order;
    }

    /** 10 approved and back, refused for damage with a public note - proposed only. */
    private static ReturnRequest refusalProposed() {
        Order order = order();
        ReturnRequest request = ReturnRequest.open("RMA-20261123-0001", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(LINE, 10)),
                Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
        request.addEvidence(new ReturnEvidence(UUID.randomUUID(), ReturnEvidence.Stage.CLAIM, "k", "a.jpg", "image/jpeg",
                10, CUSTOMER, NOW), POLICY);
        request.approve(SALES, NOW, null, null, null, POLICY);
        request.goodsReceived(UUID.randomUUID(), "GR-1", "HCM-QC01",
                List.of(new ReturnRequest.ReturnedCarton("CUP-12OZ", 10)), SALES, NOW);
        request.proposeRefund(SALES, NOW, Map.of(request.lines().getFirst().id(), 0), order, "khách tự làm hỏng", false,
                ReturnRefusalReason.CUSTOMER_DAMAGE, "Thùng bị ướt khi lưu kho", Map.of());
        return request;
    }

    private static ReturnRequestQueries.Item item(ReturnRequest r) {
        return new ReturnRequestQueries.Item(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.customerId(),
                r.status(), r.reason(), r.requestedResolution(), r.channel().name(), r.requestedAt(),
                r.returnDeadline(), r.settlementAmount(), BigDecimal.ZERO, BigDecimal.ZERO, r.currency(),
                r.vatAdjustmentRequired(), r.closedAt(), r.version(), r.shipmentId(), r.refusalReason(),
                ReturnRequest.receiptOpenable(r.status(), r.returnRequired(), r.decidedAt()),
                r.openDraftReceiptId() != null);
    }

    @Test
    @DisplayName("a refusal proposed: the customer sees no outcome, reason or note yet; staff see the proposal")
    void proposedIsNotShownToTheCustomer() {
        ReturnRequest request = refusalProposed();
        CustomerReturnResponse customer = ReturnWebMapper.toCustomerResponse(request, List.of());
        assertThat(customer.outcome()).isNull();
        assertThat(customer.refusalReason()).isNull();
        assertThat(customer.refusalNote()).isNull();
        assertThat(ReturnWebMapper.toCustomerRow(item(request)).outcome()).isNull();
        assertThat(ReturnWebMapper.toRow(item(request)).receiptOpenable()).as("a late parcel may still come").isTrue();

        ReturnRequestResponse staff = ReturnWebMapper.toResponse(request, List.of());
        assertThat(staff.outcome()).isNull();
        assertThat(staff.refusalReason()).isEqualTo("CUSTOMER_DAMAGE");
        assertThat(staff.refusalNote()).isEqualTo("Thùng bị ướt khi lưu kho");
        assertThat(staff.receiptOpenable()).isTrue();
        assertThat(staff.receiptDraftOpen()).isFalse();
        assertThat(customer.receiptDraftOpen()).isFalse();
        assertThat(ReturnWebMapper.toRow(item(request)).receiptDraftOpen()).isFalse();
        assertThat(ReturnWebMapper.toCustomerRow(item(request)).receiptDraftOpen()).isFalse();
    }

    @Test
    @DisplayName("a parcel on a DRAFT receipt: receiptDraftOpen on the customer detail and row and on the staff row, "
            + "and the withdraw it stands for is refused")
    void draftReceiptOpen() {
        Order order = order();
        ReturnRequest request = ReturnRequest.open("RMA-20261123-0002", UUID.randomUUID(), order, ReturnChannel.PORTAL,
                ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(LINE, 5)),
                Map.of(), Set.of(), CUSTOMER, NOW, POLICY);
        request.addEvidence(new ReturnEvidence(UUID.randomUUID(), ReturnEvidence.Stage.CLAIM, "k", "a.jpg", "image/jpeg",
                10, CUSTOMER, NOW), POLICY);
        request.approve(SALES, NOW, null, null, null, POLICY);
        UUID draft = UUID.randomUUID();
        request.draftReceiptOpened(draft);

        assertThat(ReturnWebMapper.toCustomerResponse(request, List.of()).receiptDraftOpen()).isTrue();
        assertThat(ReturnWebMapper.toCustomerRow(item(request)).receiptDraftOpen()).isTrue();
        assertThat(ReturnWebMapper.toRow(item(request)).receiptDraftOpen()).isTrue();
        assertThat(ReturnWebMapper.toResponse(request, List.of()).receiptDraftOpen()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> request.cancel(CUSTOMER, NOW, "đổi ý", true))
                .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, e -> assertThat(
                        e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.RETURN_RECEIPT_DRAFT_OPEN));

        request.draftReceiptClosed(draft);
        assertThat(ReturnWebMapper.toCustomerResponse(request, List.of()).receiptDraftOpen()).isFalse();
        assertThat(ReturnWebMapper.toCustomerRow(item(request)).receiptDraftOpen()).isFalse();
    }

    @Test
    @DisplayName("approved: outcome REFUSED on the row and the detail, the reason's Vietnamese label and the public note")
    void approvedIsShown() {
        ReturnRequest request = refusalProposed();
        request.resolve(ACCOUNTANT, NOW, ReturnRequest.MoneyOutcome.NONE);
        assertThat(request.status()).isEqualTo(ReturnStatus.RESOLVED);

        CustomerReturnResponse customer = ReturnWebMapper.toCustomerResponse(request, List.of());
        assertThat(customer.outcome()).isEqualTo("REFUSED");
        assertThat(customer.refusalReason()).isEqualTo("CUSTOMER_DAMAGE");
        assertThat(customer.refusalReasonLabel()).isEqualTo("Hư hỏng do bảo quản/sử dụng");
        assertThat(customer.refusalNote()).isEqualTo("Thùng bị ướt khi lưu kho");

        ReturnRequestRowResponse row = ReturnWebMapper.toCustomerRow(item(request));
        assertThat(row.outcome()).isEqualTo("REFUSED");
        assertThat(ReturnWebMapper.toRow(item(request)).outcome()).isEqualTo("REFUSED");
        assertThat(ReturnWebMapper.toRow(item(request)).receiptOpenable()).isFalse();
        assertThat(ReturnWebMapper.toResponse(request, List.of()).receiptOpenable()).isFalse();
    }

    @Test
    @DisplayName("the request body carries refusalReason and refusalNote to the command")
    void requestBody() {
        var command = ReturnWebMapper.toCommand(new ReturnRequests.Refund(
                List.of(new ReturnRequests.RefundLine(UUID.randomUUID(), 0)), "internal", 3L,
                ReturnRefusalReason.NOT_DEFECTIVE, "Không phát hiện lỗi in"));
        assertThat(command.refusalReason()).isEqualTo(ReturnRefusalReason.NOT_DEFECTIVE);
        assertThat(command.refusalNote()).isEqualTo("Không phát hiện lỗi in");
        assertThat(command.note()).isEqualTo("internal");
    }

    @Test
    @DisplayName("the customer's copy names each line's SKU by its product and itself; an unknown SKU has no names")
    void customerLinesAreNamed() {
        ReturnRequest request = refusalProposed();

        CustomerReturnResponse named = ReturnWebMapper.toCustomerResponse(request, List.of(), key -> null,
                Map.of("CUP-12OZ", new com.stockflow.order.internal.service.LineNames.Names("Paper cup", "Cup 12oz")));
        assertThat(named.lines()).singleElement().satisfies(line -> {
            assertThat(line.productName()).isEqualTo("Paper cup");
            assertThat(line.variantName()).isEqualTo("Cup 12oz");
        });

        assertThat(ReturnWebMapper.toCustomerResponse(request, List.of()).lines()).singleElement()
                .satisfies(line -> assertThat(line.productName()).isNull());
    }
}
