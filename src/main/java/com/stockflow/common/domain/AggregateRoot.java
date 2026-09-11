package com.stockflow.common.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every aggregate root.
 * <p>The aggregate records the events it caused; the application layer pulls them out after the
 * save and publishes them, still inside the same transaction. Spring Modulith persists each
 * publication to {@code event_publication} in that same commit, so an event can never be
 * announced for a change that was rolled back, nor lost for one that was not.</p>
 */
public abstract class AggregateRoot {

    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> copy = List.copyOf(this.domainEvents);
        this.domainEvents.clear();
        return copy;
    }
}
