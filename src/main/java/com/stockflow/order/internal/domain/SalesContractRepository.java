package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port: an order's sales contracts (4B-4), the schedule and the plan written and read with each. */
public interface SalesContractRepository {

    /** The order's live contract - any status but CANCELLED (14 BR-12: there is at most one). */
    Optional<SalesContract> findLiveByOrderId(UUID orderId);

    /** Every contract of the order, the cancelled ones too, newest first. */
    List<SalesContract> findByOrderId(UUID orderId);

    /** How many contracts the order ever had: the next one's number follows them. */
    int countByOrderId(UUID orderId);

    /**
     * Writes the contract; a draft's schedule and plan are written whole with it, replacing what was there. Checks the
     * version read: a contract changed meanwhile is refused ({@code OPTIMISTIC_LOCK}).
     */
    SalesContract save(SalesContract contract);

    /** Of {@code keys}, those a contract names - its sent PDF or its signed file - for the orphaned-file sweep. */
    Set<String> referencedKeys(Collection<String> keys);
}
