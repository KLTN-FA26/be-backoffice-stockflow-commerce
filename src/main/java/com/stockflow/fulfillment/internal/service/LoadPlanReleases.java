package com.stockflow.fulfillment.internal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * What the packing station and the order-cancellation path need of load plans (SCRUM-287 PR D, BR-09).
 */
interface LoadPlanReleases {

    /** 409 {@code PACKAGE_IN_LOAD_PLAN} when a live plan holds one of these parcels: it is not voided or re-opened. */
    void requireNotPlanned(Collection<UUID> packageIds);

    /**
     * The shipment's order was cancelled (09 edge "đơn bị huỷ khi kiện đã nằm trong kế hoạch chuyến xe"): every live
     * plan holding its parcels lets them go; a confirmed plan with no vehicle loading goes back to PLANNED and its
     * dispatch loads are cancelled. Called before the parcels are voided.
     */
    void releaseShipment(UUID shipmentId, Instant at);
}
