package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What an order's returns already claim of its lines, and on which delivery (WF-05 TH-1): the bound a new claim is
 * checked against by {@link ReturnRequest#open}, and the same bound the customer's order detail shows as what is still
 * returnable ({@link ReturnRequest#claimable}). One value read once per order, so the two cannot count differently -
 * a portal that offered more than opening allows would only meet {@code RETURN_QUANTITY_EXCEEDED} afterwards.
 */
public final class ReturnClaims {

    /** No return on the order. */
    public static final ReturnClaims NONE = new ReturnClaims(List.of());

    /**
     * One line of one return.
     *
     * @param shipmentId  the delivery the return is against; null for one against an order delivered without shipments
     * @param orderLineId the order line it claims units of
     * @param units       the units it claims now, as far as it got ({@link ReturnLine#claimedUnits}): 0 once rejected,
     *                    cancelled or expired
     * @param open        whether the return is still open ({@link ReturnStatus#isOpen}): one open return per order line
     *                    and delivery
     * @param refunded    whether units of the line were refunded on that delivery: a RESOLVED return refunded some
     *                    (WF-05 Q-15 - such a delivery takes a further claim through Sales only)
     */
    public record Claim(UUID shipmentId, UUID orderLineId, int units, boolean open, boolean refunded) {

        public Claim {
            Objects.requireNonNull(orderLineId, "orderLineId");
        }

        /** A claim of a line nothing of which was refunded. */
        public Claim(UUID shipmentId, UUID orderLineId, int units, boolean open) {
            this(shipmentId, orderLineId, units, open, false);
        }

        /** The claim a line of a return in {@code status} makes. */
        public static Claim of(UUID shipmentId, ReturnStatus status, ReturnLine line) {
            return new Claim(shipmentId, line.orderLineId(), line.claimedUnits(status), status.isOpen(),
                    status == ReturnStatus.RESOLVED && line.refundQuantity() > 0);
        }
    }

    private final List<Claim> claims;

    private ReturnClaims(List<Claim> claims) {
        this.claims = List.copyOf(claims);
    }

    public static ReturnClaims of(Collection<Claim> claims) {
        return claims == null || claims.isEmpty() ? NONE : new ReturnClaims(List.copyOf(claims));
    }

    /** Per order line, the units every return claims, whatever delivery it is against. */
    public Map<UUID, Integer> inAll() {
        Map<UUID, Integer> units = new HashMap<>();
        claims.forEach(claim -> units.merge(claim.orderLineId(), claim.units(), Integer::sum));
        return units;
    }

    /**
     * Per order line, the units the returns against delivery {@code shipmentId} claim; null reads the returns against
     * the order as a whole.
     */
    public Map<UUID, Integer> onShipment(UUID shipmentId) {
        Map<UUID, Integer> units = new HashMap<>();
        claims.stream().filter(claim -> Objects.equals(claim.shipmentId(), shipmentId))
                .forEach(claim -> units.merge(claim.orderLineId(), claim.units(), Integer::sum));
        return units;
    }

    /**
     * The deliveries a return already refunded units of (WF-05 Q-15): a portal claim is decided per delivery, so a
     * delivery nothing of which was refunded stays claimable on the portal whatever the order's status says - and one
     * that was refunded takes a further claim through Sales only. The order as a whole (null) is never in it: an order
     * delivered without shipments keeps the order-level rule.
     */
    public Set<UUID> refundedShipments() {
        Set<UUID> refunded = new HashSet<>();
        claims.stream().filter(claim -> claim.refunded() && claim.shipmentId() != null)
                .forEach(claim -> refunded.add(claim.shipmentId()));
        return refunded;
    }

    /** The order lines with a return still open against delivery {@code shipmentId} (null: the order as a whole). */
    public Set<UUID> openOn(UUID shipmentId) {
        Set<UUID> open = new HashSet<>();
        claims.stream().filter(claim -> claim.open() && Objects.equals(claim.shipmentId(), shipmentId))
                .forEach(claim -> open.add(claim.orderLineId()));
        return open;
    }
}
