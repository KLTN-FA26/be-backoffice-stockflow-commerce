package com.stockflow.inventory.internal.domain;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record CycleCountLine(UUID stockId,String sku,String location,String lot,String serial,Instant receivedAt,
                             LocalDate expiry,int baseline,long version,Integer counted,String reason,
                             int onHand,int reserved,long currentVersion) {}
