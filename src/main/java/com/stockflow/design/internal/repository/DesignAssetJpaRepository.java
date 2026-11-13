package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignAssetJpaEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignAssetJpaEntity}: the images uploaded into a design. */
public interface DesignAssetJpaRepository extends JpaRepository<DesignAssetJpaEntity, DesignAssetJpaEntity.Key> {

    /** A design's images, newest first, as the studio lists them. */
    List<DesignAssetJpaEntity> findByDraftIdOrderByCreatedAtDescIdDesc(UUID draftId);

    Optional<DesignAssetJpaEntity> findByDraftIdAndId(UUID draftId, UUID id);

    /** The image an upload with this key already made in this design: what a retry of it is answered with. */
    Optional<DesignAssetJpaEntity> findByDraftIdAndUploadKey(UUID draftId, String uploadKey);

    long countByDraftId(UUID draftId);

    /** The ids of a design's images: what a layout's {@code assetId} may name. */
    @Query("select a.id from DesignAssetJpaEntity a where a.draftId = :draftId")
    List<UUID> idsOf(@Param("draftId") UUID draftId);

    /**
     * A revision gets its source's images as they are - same ids, same stored files (kltn-docs 12 §4.7): the layout it
     * copies names them by id. The files are shared, not copied; neither design can lose them, because an image row is
     * never removed ({@code tg_design_asset_append_only}). Retaining them in the store with the approved design comes
     * with the approval (2F-6). The upload keys stay behind: they answer retries of uploads into the source.
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
