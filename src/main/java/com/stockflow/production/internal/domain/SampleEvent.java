package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.DomainEvent;
import com.stockflow.contracts.SampleApproved;

import java.time.Instant;
import java.util.UUID;

/** Internal event envelope; the existing contracts record is the only published payload. */
public record SampleEvent(UUID eventId, Instant occurredAt, SampleApproved payload) implements DomainEvent {
    @Override
    public String aggregateId() {
        return payload.sampleRequestId().toString();
    }
}
