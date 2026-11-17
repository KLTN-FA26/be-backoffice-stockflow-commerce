package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadWaybillFileJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The carrier's waybill documents kept with each load (3-2). */
public interface LoadWaybillFileJpaRepository extends BaseJpaRepository<LoadWaybillFileJpaEntity> {

    List<LoadWaybillFileJpaEntity> findByLoadIdOrderByUploadedAtAscIdAsc(UUID loadId);

    Optional<LoadWaybillFileJpaEntity> findByIdAndLoadId(UUID id, UUID loadId);

    /** A file of this load stored under this key; one stored file may be several loads' (3-3: an import). */
    Optional<LoadWaybillFileJpaEntity> findByStorageKeyAndLoadId(String storageKey, UUID loadId);

    @Query("select f.storageKey from LoadWaybillFileJpaEntity f where f.storageKey in :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}
