package com.stockflow.notification.api;
import java.time.Instant;
import java.util.UUID;
/** A historical threshold transition, not a claim that the quantity remains unchanged at delivery. */
public record InventoryAlertMessage(UUID alertId,String sku,String kind,String status,long quantity,int threshold,Instant occurredAt) {}
