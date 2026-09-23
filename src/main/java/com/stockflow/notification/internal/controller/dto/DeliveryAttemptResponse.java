package com.stockflow.notification.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(UUID id, String channel, String status, Instant attemptedAt,
        Instant sentAt, String failure) { }
