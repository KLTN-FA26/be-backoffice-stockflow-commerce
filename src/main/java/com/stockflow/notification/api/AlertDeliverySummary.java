package com.stockflow.notification.api;
import java.time.Instant;
import java.util.UUID;
public record AlertDeliverySummary(UUID id,String phase,String recipient,String status,int attempts,
                                   Instant nextAttemptAt,Instant sentAt,String lastError) {}
