package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignDocumentJpaEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignDocumentJpaEntity}: a design's layout, one row per design. */
public interface DesignDocumentJpaRepository extends JpaRepository<DesignDocumentJpaEntity, UUID> {

    /** When each of these designs' layouts was last saved, without reading the layouts. */
    @Query("select d.draftId as draftId, d.updatedAt as updatedAt from DesignDocumentJpaEntity d "
            + "where d.draftId in :draftIds")
    List<SavedAt> savedAt(@Param("draftIds") Collection<UUID> draftIds);

    interface SavedAt {
        UUID getDraftId();
        Instant getUpdatedAt();
    }
}
