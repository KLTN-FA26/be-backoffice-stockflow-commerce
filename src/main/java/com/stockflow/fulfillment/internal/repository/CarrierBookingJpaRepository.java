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

    /** The load's order in flight or held, if any (at most one: uk_carrier_booking_live). */
    @Query("select b from CarrierBookingJpaEntity b where b.loadId = :loadId and b.status in ('REQUESTED', 'BOOKED')")
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
     * Orders still held at the carrier for a load that no longer needs them - called off (CANCELLED), or booked again
     * by hand - or whose cancel the carrier refused fewer than {@code attempts} times; oldest first.
     */
    default List<CarrierBookingJpaEntity> findToCancel(int attempts, Pageable page) {
        return findToCancel(attempts, com.stockflow.fulfillment.internal.domain.LoadStatus.CANCELLED, page);
    }

    @Query("select b from CarrierBookingJpaEntity b, DispatchLoadJpaEntity l where l.id = b.loadId and ("
            + "(b.status = 'BOOKED' and l.status = :cancelled) "
            + "or (b.status = 'CANCEL_FAILED' and b.cancelAttempts < :attempts)) order by b.requestedAt")
    List<CarrierBookingJpaEntity> findToCancel(@Param("attempts") int attempts,
                                               @Param("cancelled") com.stockflow.fulfillment.internal.domain.LoadStatus
                                                       cancelled, Pageable page);

    @Query("select p from CarrierPickupPointJpaEntity p where p.carrierId = :carrierId and p.warehouseId = :warehouseId")
    Optional<CarrierPickupPointJpaEntity> pickupPoint(@Param("carrierId") UUID carrierId,
                                                      @Param("warehouseId") UUID warehouseId);
}
