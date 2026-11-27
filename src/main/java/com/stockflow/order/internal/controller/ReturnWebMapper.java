package com.stockflow.order.internal.controller;

import com.stockflow.order.internal.controller.dto.CustomerReturnResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequestRowResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.domain.ReturnDiscrepancy;
import com.stockflow.order.internal.domain.CustodyOutcome;
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
                body.lateClaimReason(), body.shipmentId());
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
        return new ReturnRequestService.ProposeRefundCommand(units, body.note(), body.version(),
                body.refusalReason(), body.refusalNote());
    }

    // ---- responses -----------------------------------------------------------------------------------------------------

    /**
     * The names a staff view shows beside the ids: {@code people} the display name of each person id (from one
     * {@code IdentityService.displayNames} call for the whole answer), {@code customer} the return's customer. Missing
     * entries answer null, so an answer built without them keeps every id and simply names nobody.
     */
    record Names(Map<java.util.UUID, String> people, com.stockflow.customer.api.CustomerLabel customer) {

        static final Names NONE = new Names(Map.of(), null);

        String of(java.util.UUID id) {
            return id == null || people == null ? null : people.get(id);
        }
    }

    /**
     * The names a staff view of {@code r} shows: one {@code displayNames} call for every person id in it and one label
     * lookup for its customer - two reads per answer, whatever the return holds.
     */
    static Names namesOf(ReturnRequest r, List<ReturnTransition> history,
                         com.stockflow.identity.api.IdentityService identities,
                         com.stockflow.customer.api.CustomerService customers) {
        Map<java.util.UUID, String> people = identities.displayNames(peopleOf(r, history));
        com.stockflow.customer.api.CustomerLabel label = r.customerId() == null ? null
                : customers.labels(List.of(r.customerId())).get(r.customerId());
        return new Names(people, label);
    }

    /** Every person id a staff view of {@code r} shows, for one name lookup. */
    static java.util.Set<java.util.UUID> peopleOf(ReturnRequest r, List<ReturnTransition> history) {
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        ids.add(r.requestedBy());
        ids.add(r.decidedBy());
        ids.add(r.receivedBy());
        ids.add(r.proposedBy());
        ids.add(r.settledBy());
        ids.add(r.cancelledBy());
        ids.add(r.custodyChoiceBy());
        r.parcels().forEach(p -> ids.add(p.receivedBy()));
        r.evidence().forEach(e -> {
            ids.add(e.uploadedBy());
            r.custodyOutcome(e.id()).ifPresent(o -> {
                ids.add(o.chosenBy());
                ids.add(o.endedBy());
            });
        });
        r.discrepancies().forEach(d -> ids.add(d.recordedBy()));
        history.forEach(step -> ids.add(step.actor()));
        ids.remove(null);
        return ids;
    }

    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history) {
        return toResponse(r, history, key -> null);
    }

    /** With a thumbnail link per photo ({@code thumbnails} maps a photo's storage key to it, or to null). */
    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history,
                                            java.util.function.Function<String, String> thumbnails) {
        return toResponse(r, history, thumbnails, null);
    }

    /**
     * With the cartons of the return held for the customer ({@code cartons}, WF-05 Q-4): what a custody choice is about,
     * read from inventory by the answers that need it; null where they were not read.
     */
    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history,
                                            java.util.function.Function<String, String> thumbnails,
                                            List<com.stockflow.inventory.api.HeldCarton> cartons) {
        return toResponse(r, history, thumbnails, cartons, Names.NONE);
    }

    /** With the names of its people and its customer ({@code names}, gathered by the caller in one lookup each). */
    static ReturnRequestResponse toResponse(ReturnRequest r, List<ReturnTransition> history,
                                            java.util.function.Function<String, String> thumbnails,
                                            List<com.stockflow.inventory.api.HeldCarton> cartons, Names names) {
        return new ReturnRequestResponse(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.customerId(),
                r.shipmentId(), r.status().name(), r.reason().name(), r.description(), r.requestedResolution().name(),
                r.channel().name(), r.requestedBy(), r.requestedAt(), r.windowOverride(), r.currency(),
                new ReturnRequestResponse.Review(r.decidedBy(), r.decidedAt(), r.decisionNote(), r.rejectionReason(),
                        r.returnRequired(), r.returnDeadline(), names.of(r.decidedBy())),
                new ReturnRequestResponse.Receipt(r.receivedBy(), r.receivedAt(), r.receivingLocation(),
                        r.goodsReceiptId(), r.parcels().stream().map(p -> new ReturnRequestResponse.Parcel(p.parcel(),
                        p.goodsReceiptId(), p.receiptNumber(), p.locationCode(), p.receivedBy(), p.receivedAt(),
                        names.of(p.receivedBy()))).toList(), names.of(r.receivedBy())),
                new ReturnRequestResponse.Resolution(r.moneyMode() == null ? null : r.moneyMode().name(),
                        r.settlementAmount(), r.proposedBy(), r.proposedAt(), r.resolutionNote(), r.settledBy(),
                        r.settledAt(), r.money().offset(), r.money().refunded(), r.vatAdjustmentRequired(),
                        names.of(r.proposedBy()), names.of(r.settledBy())),
                r.cancelledBy(), r.cancellationReason(), r.closedAt(),
                r.lines().stream().map(ReturnWebMapper::toLine).toList(),
                r.evidence().stream().map(e -> toEvidence(r, e, thumbnails, names)).toList(),
                r.discrepancies().stream().map(d -> toDiscrepancy(d, names)).toList(),
                history.stream().map(step -> new ReturnRequestResponse.HistoryEntry(
                        step.from() == null ? null : step.from().name(), step.to() == null ? null : step.to().name(),
                        step.reason(), step.actor(), step.at(), names.of(step.actor()))).toList(),
                r.version(), r.outcome() == null ? null : r.outcome().name(),
                r.refusalReason() == null ? null : r.refusalReason().name(), r.refusalNote(), r.receiptOpenable(),
                r.openDraftReceiptId() != null, r.custodyChoice() == null ? null : r.custodyChoice().name(),
                r.custodyChoiceNote(), r.custodyChoiceBy(), r.custodyChoiceAt(),
                cartons == null ? null : cartons.stream().map(c -> new ReturnRequestResponse.HeldCarton(c.lpn(),
                        c.sku(), c.quantity(), c.locationCode(), c.ownership(), c.choice(), c.heldAt(), c.endedAt()))
                        .toList(),
                names.customer() == null ? null : names.customer().name(),
                names.customer() == null ? null : names.customer().code(),
                names.of(r.requestedBy()), names.of(r.cancelledBy()), names.of(r.custodyChoiceBy()));
    }

    static CustomerReturnResponse toCustomerResponse(ReturnRequest r, List<ReturnTransition> history) {
        return toCustomerResponse(r, history, key -> null);
    }

    /** With a thumbnail link per photo of the claim. */
    static CustomerReturnResponse toCustomerResponse(ReturnRequest r, List<ReturnTransition> history,
                                                     java.util.function.Function<String, String> thumbnails) {
        return toCustomerResponse(r, history, thumbnails, Map.of());
    }

    /**
     * With what each line's SKU is called ({@code LineNames}, read once for the return), by SKU: the customer does not
     * know their SKUs by code.
     */
    static CustomerReturnResponse toCustomerResponse(ReturnRequest r, List<ReturnTransition> history,
                                                     java.util.function.Function<String, String> thumbnails,
                                                     Map<String, com.stockflow.order.internal.service.LineNames.Names> names) {
        return new CustomerReturnResponse(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(), r.shipmentId(),
                r.status().name(),
                r.reason().name(), r.description(), r.requestedResolution().name(), r.requestedAt(),
                r.returnRequired(), r.returnDeadline(), r.rejectionReason(), r.receivedAt(), r.closedAt(),
                r.currency(), decided(r) ? r.money().refunded() : null, decided(r) ? r.money().offset() : null,
                r.lines().stream().map(l -> {
                    var named = com.stockflow.order.internal.service.LineNames.of(names, l.sku());
                    return new CustomerReturnResponse.Line(l.id(), l.orderLineId(), l.sku(),
                            l.requestedQuantity(), l.approvedQuantity(), l.receivedQuantity(),
                            decided(r) ? l.refundQuantity() : 0, decided(r) ? l.refundAmount() : null,
                            named.productName(), named.variantName());
                }).toList(),
                r.discrepancies().stream().map(d -> new CustomerReturnResponse.Discrepancy(d.kind().name(), d.sku(),
                        d.quantity())).toList(),
                r.evidence().stream().filter(e -> e.stage() == ReturnEvidence.Stage.CLAIM)
                        .map(e -> new CustomerReturnResponse.Evidence(e.id(), e.stage().name(), e.originalName(),
                                e.uploadedAt(), thumbnails.apply(e.fileKey()))).toList(),
                history.stream().filter(step -> step.to() != null)
                        .map(step -> new CustomerReturnResponse.Step(step.to().name(), step.at())).toList(),
                r.version(), decided(r) && r.outcome() != null ? r.outcome().name() : null,
                decided(r) && r.refusalReason() != null ? r.refusalReason().name() : null,
                decided(r) && r.refusalReason() != null ? refusalLabel(r.refusalReason()) : null,
                decided(r) ? r.refusalNote() : null, r.openDraftReceiptId() != null,
                r.custodyChoice() == null ? null : r.custodyChoice().name());
    }

    /**
     * The refusal reason as the customer reads it (WF-05 Q-4): its label in the Vietnamese bundle, the language of the
     * portal; the code itself when a label is missing.
     */
    static String refusalLabel(com.stockflow.order.internal.domain.ReturnRefusalReason reason) {
        java.util.ResourceBundle texts = java.util.ResourceBundle.getBundle("i18n.messages",
                java.util.Locale.forLanguageTag("vi"));
        String key = "label.ReturnRefusalReason." + reason.name();
        return texts.containsKey(key) ? texts.getString(key) : reason.name();
    }

    /** What the customer is told is refunded only once a second person approved it: never a proposal. */
    private static boolean decided(ReturnRequest r) {
        return r.status() == ReturnStatus.RESOLVED;
    }

    /**
     * The customer's own list: no amount before it is approved, and no accounting or warehouse flag - except
     * {@code receiptDraftOpen}, which decides whether the customer may withdraw.
     */
    static ReturnRequestRowResponse toCustomerRow(ReturnRequestQueries.Item i) {
        boolean decided = i.status() == ReturnStatus.RESOLVED;
        return new ReturnRequestRowResponse(i.id(), i.rmaNumber(), i.orderId(), i.orderNumber(), i.customerId(),
                i.status().name(), i.reason().name(), i.requestedResolution().name(), i.channel(), i.requestedAt(),
                i.returnDeadline(), decided ? i.settlementAmount() : null, decided ? i.refundedAmount() : null,
                decided ? i.offsetAmount() : null, i.currency(), false, i.closedAt(), i.version(), i.shipmentId(),
                i.outcome() == null ? null : i.outcome().name(), false, i.receiptDraftOpen(),
                // Audit r2 money L-6: the list says why, as the detail does - the code only, once decided.
                decided && i.refusalReason() != null ? i.refusalReason().name() : null, null, null);
    }

    static ReturnRequestRowResponse toRow(ReturnRequestQueries.Item i) {
        return toRow(i, null);
    }

    /** A back-office row with its customer's name and code ({@code label}, from one lookup for the page). */
    static ReturnRequestRowResponse toRow(ReturnRequestQueries.Item i, com.stockflow.customer.api.CustomerLabel label) {
        return new ReturnRequestRowResponse(i.id(), i.rmaNumber(), i.orderId(), i.orderNumber(), i.customerId(),
                i.status().name(), i.reason().name(), i.requestedResolution().name(), i.channel(), i.requestedAt(),
                i.returnDeadline(), i.settlementAmount(), i.refundedAmount(), i.offsetAmount(), i.currency(),
                i.vatAdjustmentRequired(), i.closedAt(), i.version(), i.shipmentId(),
                i.outcome() == null ? null : i.outcome().name(), i.receiptOpenable(), i.receiptDraftOpen(),
                i.refusalReason() == null ? null : i.refusalReason().name(),
                label == null ? null : label.name(), label == null ? null : label.code());
    }

    private static ReturnRequestResponse.Line toLine(ReturnLine l) {
        return new ReturnRequestResponse.Line(l.id(), l.orderLineId(), l.sku(), l.customPrinted(), l.unitPrice(),
                l.requestedQuantity(), l.approvedQuantity(), l.receivedQuantity(), l.refundQuantity(),
                l.refundAmount());
    }

    private static ReturnRequestResponse.Evidence toEvidence(ReturnRequest r, ReturnEvidence e,
                                                             java.util.function.Function<String, String> thumbnails,
                                                             Names names) {
        return new ReturnRequestResponse.Evidence(e.id(), e.stage().name(), e.originalName(), e.contentType(),
                e.sizeBytes(), e.uploadedBy(), e.uploadedAt(), thumbnails.apply(e.fileKey()),
                e.custody() == null ? null : new ReturnRequestResponse.Custody(e.custody().sku(), e.custody().lpn(),
                        e.custody().quantity(), e.custody().note(),
                        r.custodyOutcome(e.id()).map(o -> toOutcome(o, names)).orElse(null)),
                names.of(e.uploadedBy()));
    }

    static ReturnRequestResponse.CustodyOutcome toOutcome(CustodyOutcome o, Names names) {
        return new ReturnRequestResponse.CustodyOutcome(o.choice().name(), o.choiceNote(), o.takenByReturnId(),
                o.takenByRmaNumber(), o.chosenBy(), o.chosenAt(), o.endedAt(), o.endedBy(), o.endedNote(),
                names.of(o.chosenBy()), names.of(o.endedBy()));
    }

    private static ReturnRequestResponse.Discrepancy toDiscrepancy(ReturnDiscrepancy d, Names names) {
        return new ReturnRequestResponse.Discrepancy(d.id(), d.lineId(), d.kind().name(), d.sku(), d.quantity(),
                d.note(), d.recordedBy(), d.recordedAt(), names.of(d.recordedBy()));
    }
}
