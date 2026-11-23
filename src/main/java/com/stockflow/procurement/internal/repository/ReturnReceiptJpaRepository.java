package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.entity.ReturnReceiptJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReturnReceiptJpaRepository extends BaseJpaRepository<ReturnReceiptJpaEntity> {

    /** Cartons fetched with the header. */
    @Query("select distinct r from ReturnReceiptJpaEntity r left join fetch r.cartons where r.id = :id")
    Optional<ReturnReceiptJpaEntity> findByIdWithCartons(@Param("id") UUID id);

    @Query("select r.id from ReturnReceiptJpaEntity r where r.returnShipmentId = :id and r.status = :status")
    List<UUID> findByShipmentInStatus(@Param("id") UUID shipmentId, @Param("status") GoodsReceiptStatus status);

    /** The LPNs on this shipment's receipts in {@code status}: what of a carrier's return came back so far. */
    @Query("""
            select c.lpn from ReturnReceiptCartonJpaEntity c
             where c.receipt.returnShipmentId = :id and c.receipt.status = :status""")
    List<String> lpnsOnShipmentReceipts(@Param("id") UUID shipmentId, @Param("status") GoodsReceiptStatus status);

    /** {@code procurement.return_units_received}: units of the SKU back for the return on its receipts not cancelled. */
    @Query(value = "SELECT procurement.return_units_received(:returnId, :sku, NULL, :exceptLoose)", nativeQuery = true)
    long unitsReceivedForReturn(@Param("returnId") UUID returnRequestId, @Param("sku") String sku,
                                @Param("exceptLoose") UUID exceptLooseLine);

    @Query("select r.id from ReturnReceiptJpaEntity r where r.returnRequestId = :id and r.status = :status")
    List<UUID> findByReturnRequestInStatus(@Param("id") UUID returnRequestId,
                                           @Param("status") GoodsReceiptStatus status);

    /** How many cartons each receipt lists, for a page of the list: one query, not one per row. */
    @Query("""
            select c.receipt.id, count(c) from ReturnReceiptCartonJpaEntity c
             where c.receipt.id in :ids group by c.receipt.id""")
    List<Object[]> countCartons(@Param("ids") Collection<UUID> receiptIds);
}
