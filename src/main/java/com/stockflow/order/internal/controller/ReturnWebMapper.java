package com.stockflow.order.internal.controller;

import com.stockflow.order.internal.controller.dto.CustomerReturnResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestRowResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.domain.ReturnDiscrepancy;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnTransition;
import com.stockflow.order.internal.service.ReturnRequestQueries;
import com.stockflow.order.internal.service.ReturnRequestService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written, as {@code OrderWebMapper}: the shapes do not line up field for field (a staff view grouped by step, a
 * customer view without actors or internal notes), and a generated mapper would hide what each audience is shown.
 */
final class ReturnWebMapper {

    private ReturnWebMapper() {
    }

    // ---- requests ------------------------------------------------------------------------------------------------------

    static ReturnRequestService.OpenReturnCommand toCommand(java.util.UUID orderId, ReturnRequests.Open body) {
        return new ReturnRequestService.OpenReturnCommand(body.requestId(), orderId, body.reasonCode(),
                body.description(), body.requestedResolution(), body.lines().stream()
                .map(line -> new ReturnRequest.Claim(line.orderLineId(), line.quantity())).toList(),
                body.lateClaimReason());
    }

    static ReturnRequestService.ApproveReturnCommand toCommand(ReturnRequests.Approve body) {
        Map<java.util.UUID, Integer> approved = null;
        if (body != null && body.lines() != null && !body.lines().isEmpty()) {
            approved = new LinkedHashMap<>();
            for (ReturnRequests.ApprovedLine line : body.lines()) {
                approved.put(line.lineId(), line.approvedQuantity());
            }
        }
        return body == null ? new ReturnRequestService.ApproveReturnCommand(null, null, null, null)
                : new ReturnRequestService.ApproveReturnCommand(approved, body.returnRequired(), body.note(),
                        body.version());
    }

    static ReturnRequestService.ProposeRefundCommand toCommand(ReturnRequests.Refund body) {
        if (body == null) {
            return new ReturnRequestService.ProposeRefundCommand(null, null, null);
        }
        Map<java.util.UUID, Integer> units = null;
        if (body.lines() != null && !body.lines().isEmpty()) {
            units = new LinkedHashMap<>();
            for (ReturnRequests.RefundLine line : body.lines()) {
                units.put(line.lineId(), line.quantity());
            }
        }
        return new ReturnRequestService.ProposeRefundCommand(units, body.note(), body.version());
    }

    // ---- responses -----------------------------------------------------------------------------------------------------

    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history) {
        return toResponse(r, history, key -> null);
    }

    /** With a thumbnail link per photo ({@code thumbnails} maps a photo's storage key to it, or to null). */
    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history,
                                            java.util.function.Function<String, String> thumbnails) {
        return new ReturnRequestResponse(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.customerId(),
                r.status().name(), r.reason().name(), r.description(), r.requestedResolution().name(),
                r.channel().name(), r.requestedBy(), r.requestedAt(), r.windowOverride(), r.currency(),
                new ReturnRequestResponse.Review(r.decidedBy(), r.decidedAt(), r.decisionNote(), r.rejectionReason(),
                        r.returnRequired(), r.returnDeadline()),
                new ReturnRequestResponse.Receipt(r.receivedBy(), r.receivedAt(), r.receivingLocation(),
                        r.goodsReceiptId()),
                new ReturnRequestResponse.Resolution(r.moneyMode() == null ? null : r.moneyMode().name(),
                        r.settlementAmount(), r.proposedBy(), r.proposedAt(), r.resolutionNote(), r.settledBy(),
                        r.settledAt(), r.money().offset(), r.money().refunded(), r.vatAdjustmentRequired()),
                r.cancelledBy(), r.cancellationReason(), r.closedAt(),
                r.lines().stream().map(ReturnWebMapper::toLine).toList(),
                r.evidence().stream().map(e -> toEvidence(e, thumbnails)).toList(),
                r.discrepancies().stream().map(ReturnWebMapper::toDiscrepancy).toList(),
                history.stream().map(step -> new ReturnRequestResponse.HistoryEntry(
                        step.from() == null ? null : step.from().name(), step.to() == null ? null : step.to().name(),
                        step.reason(), step.actor(), step.at())).toList(),
                r.version());
    }

    static CustomerReturnResponse toCustomerResponse(ReturnRequest r, List<ReturnTransition> history) {
        return toCustomerResponse(r, history, key -> null);
    }

    /** With a thumbnail link per photo of the claim. */
    static CustomerReturnResponse toCustomerResponse(ReturnRequest r, List<ReturnTransition> history,
                                                     java.util.function.Function<String, String> thumbnails) {
        return new CustomerReturnResponse(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.status().name(),
                r.reason().name(), r.description(), r.requestedResolution().name(), r.requestedAt(),
                r.returnRequired(), r.returnDeadline(), r.rejectionReason(), r.receivedAt(), r.closedAt(),
                r.currency(), decided(r) ? r.money().refunded() : null, decided(r) ? r.money().offset() : null,
                r.lines().stream().map(l -> new CustomerReturnResponse.Line(l.id(), l.orderLineId(), l.sku(),
                        l.requestedQuantity(), l.approvedQuantity(), l.receivedQuantity(),
                        decided(r) ? l.refundQuantity() : 0, decided(r) ? l.refundAmount() : null)).toList(),
                r.discrepancies().stream().map(d -> new CustomerReturnResponse.Discrepancy(d.kind().name(), d.sku(),
                        d.quantity())).toList(),
                r.evidence().stream().filter(e -> e.stage() == ReturnEvidence.Stage.CLAIM)
                        .map(e -> new CustomerReturnResponse.Evidence(e.id(), e.stage().name(), e.originalName(),
                                e.uploadedAt(), thumbnails.apply(e.fileKey()))).toList(),
                history.stream().filter(step -> step.to() != null)
                        .map(step -> new CustomerReturnResponse.Step(step.to().name(), step.at())).toList(),
                r.version());
    }

    /** What the customer is told is refunded only once a second person approved it: never a proposal. */
    private static boolean decided(ReturnRequest r) {
        return r.status() == ReturnStatus.RESOLVED;
    }

    /** The customer's own list: no amount before it is approved, and no accounting flag. */
    static ReturnRequestRowResponse toCustomerRow(ReturnRequestQueries.Item i) {
        boolean decided = i.status() == ReturnStatus.RESOLVED;
        return new ReturnRequestRowResponse(i.id(), i.rmaNumber(), i.orderId(), i.orderNumber(), i.customerId(),
                i.status().name(), i.reason().name(), i.requestedResolution().name(), i.channel(), i.requestedAt(),
                i.returnDeadline(), decided ? i.settlementAmount() : null, decided ? i.refundedAmount() : null,
                decided ? i.offsetAmount() : null, i.currency(), false, i.closedAt(), i.version());
    }

    static ReturnRequestRowResponse toRow(ReturnRequestQueries.Item i) {
        return new ReturnRequestRowResponse(i.id(), i.rmaNumber(), i.orderId(), i.orderNumber(), i.customerId(),
                i.status().name(), i.reason().name(), i.requestedResolution().name(), i.channel(), i.requestedAt(),
                i.returnDeadline(), i.settlementAmount(), i.refundedAmount(), i.offsetAmount(), i.currency(),
                i.vatAdjustmentRequired(), i.closedAt(), i.version());
    }

    private static ReturnRequestResponse.Line toLine(ReturnLine l) {
        return new ReturnRequestResponse.Line(l.id(), l.orderLineId(), l.sku(), l.customPrinted(), l.unitPrice(),
                l.requestedQuantity(), l.approvedQuantity(), l.receivedQuantity(), l.refundQuantity(),
                l.refundAmount());
    }

    private static ReturnRequestResponse.Evidence toEvidence(ReturnEvidence e,
                                                             java.util.function.Function<String, String> thumbnails) {
        return new ReturnRequestResponse.Evidence(e.id(), e.stage().name(), e.originalName(), e.contentType(),
                e.sizeBytes(), e.uploadedBy(), e.uploadedAt(), thumbnails.apply(e.fileKey()));
    }

    private static ReturnRequestResponse.Discrepancy toDiscrepancy(ReturnDiscrepancy d) {
        return new ReturnRequestResponse.Discrepancy(d.id(), d.lineId(), d.kind().name(), d.sku(), d.quantity(),
                d.note(), d.recordedBy(), d.recordedAt());
    }
}
