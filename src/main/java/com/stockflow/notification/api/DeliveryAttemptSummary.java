package com.stockflow.notification.api;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptSummary(UUID id, String channel, String status, Instant attemptedAt,
        Instant sentAt, String failure) { }
