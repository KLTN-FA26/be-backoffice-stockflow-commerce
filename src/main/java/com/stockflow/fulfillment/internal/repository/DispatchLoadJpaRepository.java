package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Load headers (G4-2a). Every write to a load takes its row lock first, so two dispatchers on one load queue up. */
public interface DispatchLoadJpaRepository extends BaseJpaRepository<DispatchLoadJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from DispatchLoadJpaEntity l where l.id = :id")
    Optional<DispatchLoadJpaEntity> lockById(@Param("id") UUID id);

    Optional<DispatchLoadJpaEntity> findByRequestId(UUID requestId);

    /** The loads with these codes (3-3: the lines of a waybill import name loads by code). */
    List<DispatchLoadJpaEntity> findByCodeIn(Collection<String> codes);

    /**
     * The id, code and warehouse of the loads with these codes, read without loading the entities: a row lock taken next
     * must read the loads fresh, not hand back copies already in the persistence context.
     */
    @Query("select l.id as id, l.code as code, l.warehouseId as warehouseId from DispatchLoadJpaEntity l "
            + "where l.code in :codes")
    List<LoadPlace> placesOfCodes(@Param("codes") Collection<String> codes);

    /** A load's id, code and warehouse, read without the entity. */
    interface LoadPlace {
        UUID getId();

        String getCode();

        UUID getWarehouseId();
    }

    /** The live loads of these carriers holding one of these waybill numbers (3-3: a number is one load's). */
    default List<DispatchLoadJpaEntity> findLiveByCarriersAndWaybills(Collection<UUID> carrierIds,
                                                                      Collection<String> waybills) {
        return findByWaybills(carrierIds, waybills, com.stockflow.fulfillment.internal.domain.LoadStatus.CANCELLED);
    }

    @Query("select l from DispatchLoadJpaEntity l where l.carrierId in :carrierIds and l.waybillNo in :waybills "
            + "and l.status <> :cancelled")
    List<DispatchLoadJpaEntity> findByWaybills(@Param("carrierIds") Collection<UUID> carrierIds,
                                               @Param("waybills") Collection<String> waybills,
                                               @Param("cancelled") com.stockflow.fulfillment.internal.domain.LoadStatus
                                                       cancelled);

    /** Loads of {@code carrierId} other than {@code loadId}, not cancelled, holding {@code waybill} (09 BR-23). */
    default List<DispatchLoadJpaEntity> findLiveByCarrierAndWaybill(UUID carrierId, String waybill, UUID loadId) {
        return findByWaybill(carrierId, waybill, loadId, com.stockflow.fulfillment.internal.domain.LoadStatus.CANCELLED);
    }

    @Query("select l from DispatchLoadJpaEntity l where l.carrierId = :carrierId and l.waybillNo = :waybill "
            + "and l.id <> :loadId and l.status <> :cancelled order by l.code")
    List<DispatchLoadJpaEntity> findByWaybill(@Param("carrierId") UUID carrierId, @Param("waybill") String waybill,
                                              @Param("loadId") UUID loadId,
                                              @Param("cancelled") com.stockflow.fulfillment.internal.domain.LoadStatus
                                                      cancelled);

    /** What a load is, read without loading the entity (so a lock taken next reads it fresh). */
    /**
     * What the carrier-event applier matches a report on (3-5), read without loading the load: its row is locked, and
     * read fresh, only by the change that follows.
     */
    interface LoadFacts {
        UUID getId();

        String getCode();

        UUID getWarehouseId();

        UUID getCarrierId();

        com.stockflow.fulfillment.internal.domain.LoadStatus getStatus();

        String getWaybillNo();
    }

    @Query("select l.id as id, l.code as code, l.warehouseId as warehouseId, l.carrierId as carrierId, "
            + "l.status as status, l.waybillNo as waybillNo from DispatchLoadJpaEntity l where l.id = :id")
    Optional<LoadFacts> findFactsById(@Param("id") UUID id);

    @Query("select l.id as id, l.code as code, l.warehouseId as warehouseId, l.carrierId as carrierId, "
            + "l.status as status, l.waybillNo as waybillNo from DispatchLoadJpaEntity l where l.code = :code")
    Optional<LoadFacts> findFactsByCode(@Param("code") String code);

    interface LoadKey {
        UUID getWarehouseId();

        UUID getLoadPlanVehicleId();
    }

    @Query("select l.warehouseId as warehouseId, l.loadPlanVehicleId as loadPlanVehicleId "
            + "from DispatchLoadJpaEntity l where l.id = :id")
    Optional<LoadKey> findKeyById(@Param("id") UUID id);

    List<DispatchLoadJpaEntity> findByIdIn(Collection<UUID> ids);

    /** The loads opened for these load-plan vehicles (SCRUM-287 PR D), cancelled ones included. */
    List<DispatchLoadJpaEntity> findByLoadPlanVehicleIdIn(Collection<UUID> loadPlanVehicleIds);
}
