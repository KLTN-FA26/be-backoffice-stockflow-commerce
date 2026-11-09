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
        return lines.stream().map(line -> new Quotes.Line(line.sku(), line.cartons(), line.tiers() == null ? null
                        : line.tiers().stream().map(t -> new Quotes.Tier(t.minQuantity(), t.unitPrice())).toList()))
                .toList();
    }

    static List<Quotes.Fee> fees(List<QuoteRequests.Fee> fees) {
        return fees == null ? List.of() : fees.stream()
                .map(fee -> new Quotes.Fee(fee.kind(), fee.sku(), fee.amount())).toList();
    }

    private static QuoteResponse.Totals totals(Quotes.Totals totals) {
        return totals == null ? null
                : new QuoteResponse.Totals(totals.subtotal(), totals.fees(), totals.tax(), totals.total());
    }

    static QuoteResponse toResponse(Quotes.QuoteView q) {
        return new QuoteResponse(q.id(), q.quoteNumber(), q.customerId(), q.status().name(), q.revision(),
                q.paymentTerm().name(), q.depositPercent(), q.validUntil(), q.terms(), q.requestedDeliveryDate(),
                q.legacy(),
                q.lines().stream().map(l -> new QuoteResponse.Line(l.id(), l.lineNo(), l.measure(), l.sku(), l.cartons(),
                        l.unitsPerCarton(), l.units(), l.designSnapshotId(), l.printSpec(), l.priced(), l.moq(),
                        l.tiers().stream().map(t -> new QuoteResponse.Tier(t.minQuantity(), t.unitPrice())).toList(),
                        l.proposedUnitPrice(), l.proposedLineTotal(), l.acceptedCartons(), l.acceptedUnits(),
                        l.acceptedUnitPrice(), l.acceptedLineTotal(), l.taxClass(), l.taxRate(), l.proposedTax(),
                        l.acceptedTax())).toList(),
                q.fees().stream().map(f -> new QuoteResponse.Fee(f.id(), f.kind().name(), f.sku(), f.amount(),
                        f.taxClass(), f.taxRate(), f.tax())).toList(),
                q.priced(), totals(q.proposed()), totals(q.accepted()), q.proposedTotal(), q.acceptedTotal(), q.sentAt(), q.sentBy(), q.respondedAt(), q.respondedBy(), q.customerFeedback(),
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
