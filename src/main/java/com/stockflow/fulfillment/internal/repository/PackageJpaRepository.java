package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Shipping cartons and repack parcels: read per pack (one check session), per shipment, per pick line or by code. */
public interface PackageJpaRepository extends BaseJpaRepository<PackageJpaEntity> {

    /**
     * 4C-6: the labelled carton carrying {@code lpn}, newest label first: a loader scans the LPN the shipping label
     * prints (kltn-docs 08 §4.3 step 6: "mã kiện ở các bước sau chính là mã LPN"). A carton shipped, returned and
     * picked again for another order has an older LABELED row; the newest is the one on its way now.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from PackageJpaEntity p
             where p.lpn = :lpn and p.status = com.stockflow.fulfillment.internal.domain.PackageStatus.LABELED
             order by p.labeledAt desc, p.id desc""")
    List<PackageJpaEntity> findLabeledByLpn(@org.springframework.data.repository.query.Param("lpn") String lpn);

    /** 4C-6: the shipping cartons of these pick lines (one row per pick line, uk_package_pick_line). */
    List<PackageJpaEntity> findByPickLineIdIn(Collection<UUID> pickLineIds);

    /**
     * 4C-6: the highest package number the order's parcels and cartons use, over every session of the order, so a new
     * carton's number - and its label code {@code <order>-C<n>} - is new for the order.
     */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(max(p.packageNo), 0) from PackageJpaEntity p
             where p.packId in (select k.id from PackJpaEntity k
                                 where k.pickId in (select t.id from PickJpaEntity t where t.orderId = :orderId))""")
    int lastPackageNoOfOrder(@org.springframework.data.repository.query.Param("orderId") UUID orderId);

    List<PackageJpaEntity> findByPackIdOrderByPackageNoAsc(UUID packId);

    List<PackageJpaEntity> findByShipmentIdOrderByPackageNoAsc(UUID shipmentId);

    List<PackageJpaEntity> findByPackIdIn(Collection<UUID> packIds);

    /** G4-2a: the parcel a loader scanned, by the code on its label. */
    java.util.Optional<PackageJpaEntity> findByLabelCode(String labelCode);

    List<PackageJpaEntity> findByShipmentIdIn(Collection<UUID> shipmentIds);
}
