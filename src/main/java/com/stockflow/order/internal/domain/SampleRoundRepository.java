package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The print sample rounds of production orders (4A-4), and their photos and videos. Written under the production order's
 * lock, which every caller takes first (its sales order before it): the rounds have no version of their own.
 */
public interface SampleRoundRepository {

    /** The rounds of one production order, as {@link SampleRounds}. */
    SampleRounds of(UUID productionOrderId);

    /** The rounds of several production orders, each production order's in round order; none for one without. */
    Map<UUID, SampleRounds> of(Collection<UUID> productionOrderIds);

    /** The production order a round belongs to. */
    Optional<UUID> productionOrderOf(UUID roundId);

    /** The customer's production orders whose print sample waits for them (13 §4.3 step 5), oldest round first. */
    List<UUID> awaitingCustomer(UUID customerId);

    /** A round just opened, with its photos and videos. */
    void insert(SampleRound round);

    /** A round answered, or its physical sample recorded. */
    void update(SampleRound round);

    /** Photos or videos added to a round already stored. */
    void addMedia(UUID roundId, List<SampleRound.Media> media);

    /**
     * Of {@code storageKeys}, those a round already shows, with the round - an upload attached by an earlier try of the
     * same request, or one attached elsewhere.
     */
    Map<String, UUID> roundsOfKeys(Collection<String> storageKeys);
}
