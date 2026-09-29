package com.stockflow.inventory.internal.controller.dto;
import java.time.Instant;
import java.util.UUID;
public record AlertDeliveryResponse(UUID id,String phase,String recipient,String status,int attempts,
                                    Instant nextAttemptAt,Instant sentAt,String lastError) {}
