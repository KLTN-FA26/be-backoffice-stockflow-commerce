package com.stockflow.inventory.internal.controller.dto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public record CycleCountResponse(UUID id,String warehouse,UUID assignedTo,String status,long version,
                                 UUID approvedBy,String note,Instant createdAt,Instant approvedAt,Instant postedAt,List<Line> lines) {
    public record Line(UUID stockId,String sku,String location,String lot,String serial,Instant receivedAt,
                       LocalDate expiry,int baseline,Integer counted,String reason,int currentOnHand,int reserved,boolean recountRequired){}
}
