package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignDocumentJpaEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignDocumentJpaEntity}, and the two reads of {@code design.design_asset} a layout
 * needs before the images have an entity of their own: which images a design holds, and copying them into a revision.
 */
public interface DesignDocumentJpaRepository extends JpaRepository<DesignDocumentJpaEntity, UUID> {

    /** When each of these designs' layouts was last saved, without reading the layouts. */
    @Query("select d.draftId as draftId, d.updatedAt as updatedAt from DesignDocumentJpaEntity d "
            + "where d.draftId in :draftIds")
    List<SavedAt> savedAt(@Param("draftIds") Collection<UUID> draftIds);

    interface SavedAt {
        UUID getDraftId();
        Instant getUpdatedAt();
    }

    /** The ids of the images uploaded into a design, as text: what a layout's {@code assetId} may name. */
    @Query(value = "SELECT CAST(a.id AS VARCHAR) FROM design.design_asset a WHERE a.draft_id = :draftId",
            nativeQuery = true)
    List<String> assetIds(@Param("draftId") UUID draftId);

    /**
     * A revision gets its source's images as they are - same ids, same stored files (kltn-docs 12 §4.7): the layout it
     * copies names them by id. The files are shared, not copied; neither design can lose them, because an image row is
     * never removed ({@code tg_design_asset_append_only}). Retaining them in the store with the approved design comes
     * with the approval (2F-6).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO design.design_asset (draft_id, id, storage_key, original_name, content_type, size_bytes,
                                             checksum, width_px, height_px, upload_key, created_at, created_by)
            SELECT :target, a.id, a.storage_key, a.original_name, a.content_type, a.size_bytes, a.checksum,
                   a.width_px, a.height_px, NULL, a.created_at, a.created_by
            FROM design.design_asset a WHERE a.draft_id = :source""", nativeQuery = true)
    int copyAssets(@Param("source") UUID source, @Param("target") UUID target);
}
