package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.WaybillImportJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Files of waybill numbers imported (3-3). */
public interface WaybillImportJpaRepository extends BaseJpaRepository<WaybillImportJpaEntity> {

    /**
     * The imports a caller may see, newest first: those they made, and those with a line on a load of one of
     * {@code warehouses}.
     */
    @Query(value = "select i from WaybillImportJpaEntity i where i.uploadedBy = :user or exists (select 1 from "
            + "WaybillImportRowJpaEntity r, DispatchLoadJpaEntity l where r.importId = i.id and l.id = r.loadId "
            + "and l.warehouseId in :warehouses) order by i.uploadedAt desc, i.id desc",
            countQuery = "select count(i) from WaybillImportJpaEntity i where i.uploadedBy = :user or exists (select 1 "
                    + "from WaybillImportRowJpaEntity r, DispatchLoadJpaEntity l where r.importId = i.id and l.id = "
                    + "r.loadId and l.warehouseId in :warehouses)")
    Page<WaybillImportJpaEntity> visibleTo(@Param("user") UUID user, @Param("warehouses") Collection<UUID> warehouses,
                                          Pageable page);

    @Query("select i.storageKey from WaybillImportJpaEntity i where i.storageKey in :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}
