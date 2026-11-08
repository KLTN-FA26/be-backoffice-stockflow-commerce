package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.ImageDimensions;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignAssetJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The direct-to-storage path of a design's images, and the checks the short attaching transaction repeats: the same
 * upload attached twice makes one image, a type the template stopped taking while the file was scanned is refused and
 * the file left to be deleted, and a slot is only handed out for what the design can take.
 */
class DesignAssetServiceTest {

    private final DesignDraftJpaRepository drafts = mock(DesignDraftJpaRepository.class);
    private final DesignAssetJpaRepository assets = mock(DesignAssetJpaRepository.class);
    private final PrintConfigs printConfigs = mock(PrintConfigs.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final StagedUploads staged = mock(StagedUploads.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC);
    private final DesignAssetService service = new DesignAssetService(drafts, assets, printConfigs, files, staged,
            TransactionOperations.withoutTransaction(), mock(EntityManager.class), clock);

    private final UUID owner = UUID.randomUUID();
    private final UUID designId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID uploadId = UUID.randomUUID();

    @BeforeEach
    void aDraftOnATemplateTakingPng() {
        var draft = new DesignDraftJpaEntity(designId, UUID.randomUUID(), UUID.randomUUID(), "Ly", null,
                DesignStatus.DRAFT);
        draft.assign(owner, null, null);
        draft.drawOn(UUID.randomUUID(), "CUP-12", templateId, "CUP12-BODY");
        when(drafts.findById(designId)).thenReturn(Optional.of(draft));
        when(drafts.lockById(designId)).thenReturn(Optional.of(draft));
        template(List.of("image/png"));
    }

    private void template(List<String> types) {
        when(printConfigs.byId(templateId)).thenReturn(Optional.of(new PrintConfig(templateId, UUID.randomUUID(),
                UUID.randomUUID(), "CUP-12", "CUP12-BODY", "Ly", 300, "CMYK", types, 20, null, null, true,
                new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white"),
                List.of(new PrintArea("BODY", "Thân", 0, 0, 280, 104, 3, 2, new PrintAreaShape.Rect(), "body")),
                null, null, 0)));
    }

    private PreparedFiles prepared(String type) {
        var prepared = mock(PreparedFiles.class);
        when(prepared.single()).thenReturn(new PreparedFiles.PreparedFile(new StoredFile(
                "design-assets/2026/11/08/" + uploadId + ".png", "logo.png", type, 2048, Instant.now(clock)),
                "c".repeat(64), List.of(), new ImageDimensions.Size(1200, 800)));
        when(staged.accept(FileCategory.DESIGN_ASSET, owner, List.of(uploadId))).thenReturn(prepared);
        return prepared;
    }

    @Test
    void anAttachedUploadIsRecordedMeasuredAndAdopted() {
        PreparedFiles prepared = prepared("image/png");

        var asset = service.attachUpload(designId, owner, uploadId);

        assertThat(asset.widthPx()).isEqualTo(1200);
        assertThat(asset.heightPx()).isEqualTo(800);
        assertThat(asset.contentType()).isEqualTo("image/png");
        assertThat(asset.storedAt()).isEqualTo(Instant.now(clock));
        var saved = ArgumentCaptor.forClass(DesignAssetJpaEntity.class);
        verify(assets).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUploadKey()).isEqualTo(owner + ":asset:upload:" + uploadId);
        assertThat(saved.getValue().getStorageKey()).startsWith("design-assets/");
        verify(prepared).adopt();
    }

    @Test
    void attachingTheSameUploadAgainAnswersWithItsImageWithoutAcceptingItAgain() {
        var earlier = new DesignAssetJpaEntity(designId, UUID.randomUUID(), "design-assets/a.png", "logo.png",
                "image/png", 2048, "c".repeat(64), 1200, 800, owner + ":asset:upload:" + uploadId,
                Instant.now(clock), owner.toString());
        when(assets.findByDraftIdAndUploadKey(designId, owner + ":asset:upload:" + uploadId))
                .thenReturn(Optional.of(earlier));

        var asset = service.attachUpload(designId, owner, uploadId);

        assertThat(asset.assetId()).isEqualTo(earlier.getAssetId());
        verifyNoInteractions(staged);
        verify(assets, never()).saveAndFlush(any());
    }

    @Test
    void aTypeTheTemplateStoppedTakingWhileTheFileWasScannedIsRefusedAndTheFileNotKept() {
        PreparedFiles prepared = prepared("image/jpeg");
        template(List.of("image/png", "image/jpeg"));
        // The admission saw PNG and JPEG; the Sales Manager narrowed the template before the attach.
        when(staged.accept(FileCategory.DESIGN_ASSET, owner, List.of(uploadId))).thenAnswer(invocation -> {
            template(List.of("image/png"));
            return prepared;
        });

        assertThatThrownBy(() -> service.attachUpload(designId, owner, uploadId))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verify(prepared, never()).adopt();
        verify(prepared).close();
        verify(assets, never()).saveAndFlush(any());
    }

    @Test
    void aSlotIsOnlyForWhatTheDesignCanTake() {
        var webp = new UploadSlotRequest("a.webp", "image/webp", 10, "a".repeat(64));
        assertThatThrownBy(() -> service.beginUpload(designId, owner, webp))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        var tooBig = new UploadSlotRequest("a.png", "image/png", 21L * 1024 * 1024, "a".repeat(64));
        assertThatThrownBy(() -> service.beginUpload(designId, owner, tooBig))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        when(assets.countByDraftId(designId)).thenReturn(100L);
        var png = new UploadSlotRequest("a.png", "image/png", 10, "a".repeat(64));
        assertThatThrownBy(() -> service.beginUpload(designId, owner, png))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.DESIGN_ASSET_LIMIT_REACHED);
        verifyNoInteractions(staged);

        when(assets.countByDraftId(designId)).thenReturn(99L);
        var slot = new UploadSlot(uploadId, "PUT", "https://storage.example/put", Map.of(), Instant.now(clock));
        when(staged.begin(FileCategory.DESIGN_ASSET, owner, png)).thenReturn(slot);
        assertThat(service.beginUpload(designId, owner, png)).isEqualTo(slot);
    }

    @Test
    void anotherUserIsToldTheDesignDoesNotExist() {
        assertThatThrownBy(() -> service.attachUpload(designId, UUID.randomUUID(), uploadId))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(staged);
    }
}
