package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierPickupPointJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orders created at carriers for loads (3-4), and their pickup points. */
public interface CarrierBookingJpaRepository extends BaseJpaRepository<CarrierBookingJpaEntity> {

    /** The load's order in flight, held or possibly held, if any (at most one: uk_carrier_booking_live). */
    @Query("select b from CarrierBookingJpaEntity b where b.loadId = :loadId "
            + "and b.status in ('REQUESTED', 'BOOKED', 'UNCONFIRMED')")
    Optional<CarrierBookingJpaEntity> findLive(@Param("loadId") UUID loadId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CarrierBookingJpaEntity b where b.id = :id")
    Optional<CarrierBookingJpaEntity> lockById(@Param("id") UUID id);

    /** The latest order of each of these loads, for showing on the load. */
    @Query("select b from CarrierBookingJpaEntity b where b.loadId in :loadIds order by b.requestedAt desc")
    List<CarrierBookingJpaEntity> findByLoads(@Param("loadIds") Collection<UUID> loadIds);

    /** Claims left REQUESTED by a call that never came back (a crash, a lost answer), oldest first. */
    @Query("select b from CarrierBookingJpaEntity b where b.status = 'REQUESTED' and b.requestedAt < :before "
            + "order by b.requestedAt")
    List<CarrierBookingJpaEntity> findAbandoned(@Param("before") Instant before, Pageable page);

    /**
     * Orders still held at the carrier for a load that no longer needs them - called off (CANCELLED), or un-booked
     * without the carrier being told (PLANNED: a shipment was taken off the vehicle, so the order's drop-offs are
     * wrong) - or whose cancel the carrier refused fewer than {@code attempts} times; oldest first. A booked order and a
     * PLANNED load never meet otherwise: the load is booked and un-booked in the transaction that books or cancels the
     * order.
     */
    default List<CarrierBookingJpaEntity> findToCancel(int attempts, Pageable page) {
        return findToCancel(attempts, List.of(com.stockflow.fulfillment.internal.domain.LoadStatus.CANCELLED,
                com.stockflow.fulfillment.internal.domain.LoadStatus.PLANNED), page);
    }

    @Query("select b from CarrierBookingJpaEntity b, DispatchLoadJpaEntity l where l.id = b.loadId and ("
            + "(b.status = 'BOOKED' and l.status in :unwanted) "
            + "or (b.status = 'CANCEL_FAILED' and b.cancelAttempts < :attempts)) order by b.requestedAt")
    List<CarrierBookingJpaEntity> findToCancel(@Param("attempts") int attempts,
                                               @Param("unwanted") Collection<com.stockflow.fulfillment.internal.domain
                                                       .LoadStatus> unwanted, Pageable page);

    /** A booking as the carrier-event applier matches it (3-5), read without loading it: its lock reads it fresh. */
    interface BookingKey {
        UUID getId();

        UUID getLoadId();

        String getStatus();
    }

    /** The booking holding the carrier's order {@code orderId} (BR-22: an API carrier's events name its order). */
    @Query("select b.id as id, b.loadId as loadId, b.status as status from CarrierBookingJpaEntity b "
            + "where b.carrierId = :carrierId and b.carrierOrderId = :orderId")
    Optional<BookingKey> findKeyByOrder(@Param("carrierId") UUID carrierId, @Param("orderId") String orderId);

    /** The shipment of drop-off {@code seq} of the booking's order (1 = the first after the pickup), as it was made. */
    @Query(value = """
            SELECT CAST(stop ->> 'shipmentId' AS uuid)
              FROM fulfillment.carrier_booking b CROSS JOIN LATERAL jsonb_array_elements(b.stops) AS stop
             WHERE b.id = :bookingId AND CAST(stop ->> 'seq' AS integer) = :seq""", nativeQuery = true)
    Optional<UUID> shipmentAtStop(@Param("bookingId") UUID bookingId, @Param("seq") int seq);

    /** How many drop-offs the booking's order has. */
    @Query(value = "SELECT jsonb_array_length(stops) FROM fulfillment.carrier_booking WHERE id = :bookingId",
            nativeQuery = true)
    int stopCount(@Param("bookingId") UUID bookingId);

    @Query("select p from CarrierPickupPointJpaEntity p where p.carrierId = :carrierId and p.warehouseId = :warehouseId")
    Optional<CarrierPickupPointJpaEntity> pickupPoint(@Param("carrierId") UUID carrierId,
                                                      @Param("warehouseId") UUID warehouseId);
}
