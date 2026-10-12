package com.stockflow.order.internal.controller;

import com.stockflow.order.internal.controller.dto.QuoteRequests;
import com.stockflow.order.internal.controller.dto.QuoteResponse;
import com.stockflow.order.internal.controller.dto.QuoteRowResponse;
import com.stockflow.order.internal.service.Quotes;

import java.util.List;

/** Between the quote DTOs and the application records; no rule lives here. */
final class QuoteWebMapper {

    private QuoteWebMapper() {
    }

    static List<Quotes.Line> lines(List<QuoteRequests.Line> lines) {
        return lines.stream().map(line -> new Quotes.Line(line.blankSku(), line.designSnapshotId(), line.printSpec(),
                line.quantity(), line.tiers() == null ? null
                        : line.tiers().stream().map(t -> new Quotes.Tier(t.minQuantity(), t.unitPrice())).toList()))
                .toList();
    }

    static QuoteResponse toResponse(Quotes.QuoteView q) {
        return new QuoteResponse(q.id(), q.quoteNumber(), q.customerId(), q.status().name(), q.revision(),
                q.paymentTerm().name(), q.depositPercent(), q.validUntil(), q.terms(),
                q.lines().stream().map(l -> new QuoteResponse.Line(l.id(), l.lineNo(), l.blankSku(), l.designSnapshotId(),
                        l.printSpec(), l.quantity(), l.moq(),
                        l.tiers().stream().map(t -> new QuoteResponse.Tier(t.minQuantity(), t.unitPrice())).toList(),
                        l.proposedUnitPrice(), l.proposedLineTotal(), l.acceptedQuantity(), l.acceptedUnitPrice(), l.acceptedLineTotal(),
                        new QuoteResponse.Sample(l.sample().satisfied(), l.sample().approvedSampleId(),
                                l.sample().reorder()))).toList(),
                q.proposedTotal(), q.acceptedTotal(), q.sentAt(), q.sentBy(), q.respondedAt(), q.respondedBy(), q.customerFeedback(),
                q.cancellationReason(),
                q.convertedOrder() == null ? null : new QuoteResponse.Order(q.convertedOrder().orderId(),
                        q.convertedOrder().orderNumber(), q.convertedOrder().status().name(), q.convertedOrder().total()),
                q.convertedAt(),
                q.history().stream().map(t -> new QuoteResponse.Transition(t.revision(),
                        t.fromStatus() == null ? null : t.fromStatus().name(), t.toStatus().name(), t.reason(),
                        t.changedAt(), t.changedBy())).toList(),
                q.createdAt(), q.createdBy(), q.version());
    }

    static QuoteRowResponse toResponse(Quotes.Row r) {
        return new QuoteRowResponse(r.id(), r.quoteNumber(), r.customerId(), r.status().name(), r.revision(),
                r.paymentTerm().name(), r.validUntil(), r.sentAt(), r.convertedOrderId(), r.createdAt(), r.version());
    }
}
