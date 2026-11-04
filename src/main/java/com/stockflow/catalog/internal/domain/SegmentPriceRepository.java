package com.stockflow.catalog.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link SegmentPrice}. Joins the caller's transaction. */
public interface SegmentPriceRepository {

    Optional<SegmentPrice> find(UUID segmentId, String sku);

    /** Under a row lock held to the end of the transaction. */
    Optional<SegmentPrice> findForUpdate(UUID segmentId, String sku);

    /** The group's prices for these SKUs (all of them for an empty set), by SKU. */
    List<SegmentPrice> ofSegment(UUID segmentId, Collection<String> skus);

    /** Inserts, or writes through a compare-and-set on the version; returns it as stored (new version). */
    SegmentPrice save(SegmentPrice price);

    void delete(UUID priceId);
}
