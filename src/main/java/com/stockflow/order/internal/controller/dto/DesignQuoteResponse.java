package com.stockflow.order.internal.controller.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record DesignQuoteResponse(UUID id,UUID customerId,UUID designSnapshotId,String sku,String status,long version,
                                  Offer offer,UUID acceptedBy,Instant acceptedAt,UUID consumedOrderId,String responseNote) {
    public record Offer(int revision,int quantity,BigDecimal unitPrice,BigDecimal total,String currency,
                        Instant validUntil,String terms,Instant createdAt,UUID createdBy){}
}
