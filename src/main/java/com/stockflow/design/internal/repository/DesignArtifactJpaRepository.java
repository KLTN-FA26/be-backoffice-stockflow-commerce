package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DesignArtifactJpaRepository extends BaseJpaRepository<DesignArtifactJpaEntity> {
    List<DesignArtifactJpaEntity> findByDraftIdOrderByStoredAtDescIdDesc(UUID draftId);

    /** An artifact's id and where its file is stored, without the rest of its row. */
    interface StoredAt {
        UUID getId();

        String getStorageKey();
    }

    /** Where each of {@code ids} that has {@code role} is stored: the previews of a page of designs, in one query. */
    @Query("select a.id as id, a.storageKey as storageKey from DesignArtifactJpaEntity a "
            + "where a.id in :ids and a.role = :role")
    List<StoredAt> storedAt(@Param("ids") Collection<UUID> ids, @Param("role") DesignArtifactRole role);
}
