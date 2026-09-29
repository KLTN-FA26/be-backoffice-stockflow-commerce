package com.stockflow.order.internal.controller;
import com.stockflow.order.internal.controller.dto.DesignQuoteResponse;
import com.stockflow.order.internal.domain.QuoteOffer;
import com.stockflow.order.internal.service.DesignQuoteService;
import org.springframework.stereotype.Component;
@Component
public class DesignQuoteWebMapper {
    public DesignQuoteResponse toResponse(DesignQuoteService.Summary s){return new DesignQuoteResponse(s.id(),s.customerId(),s.designSnapshotId(),s.sku(),s.status(),s.version(),
            toResponse(s.offer()),s.acceptedBy(),s.acceptedAt(),s.consumedOrderId(),s.responseNote());}
    public DesignQuoteResponse.Offer toResponse(QuoteOffer o){return new DesignQuoteResponse.Offer(o.revision(),o.quantity(),o.unitPrice().amount(),o.unitPrice().times(o.quantity()).amount(),
            "VND",o.validUntil(),o.terms(),o.createdAt(),o.createdBy());}
}
