package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import com.stockflow.design.internal.domain.DesignStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The draft is read without a lock to fail fast, the file is prepared with no lock held, and the draft is locked only
 * to attach it.
 */
class DesignArtifactServiceTest {
    private final DesignArtifactRepository designs = mock(DesignArtifactRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final com.stockflow.common.storage.StagedUploads staged = mock(com.stockflow.common.storage.StagedUploads.class);
    private final DesignArtifactService service = new DesignArtifactService(designs, files, staged,
            TransactionOperations.withoutTransaction(), mock(DesignPreflight.class));
    private final UUID id = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final byte[] bytes = "%PDF-1.7\nExample design artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private FileUpload upload() {
        return new FileUpload("design.pdf", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
    }

    private DesignDraft draft(DesignStatus status) {
        return new DesignDraft(id, owner, null, status, UUID.randomUUID());
    }

    private PreparedFiles prepared() {
        var prepared = mock(PreparedFiles.class);
        when(prepared.single()).thenReturn(new PreparedFiles.PreparedFile(new StoredFile("design-renders/new.pdf",
                "design.pdf", "application/pdf", bytes.length, Instant.EPOCH), Checksums.sha256Hex(bytes)));
        when(files.prepare(eq(FileCategory.DESIGN_RENDER), any(FileUpload.class))).thenReturn(prepared);
        return prepared;
    }

    @Test
    void theReviewerIsRefusedBeforeTheirFileIsRead() {
        UUID reviewer = UUID.randomUUID();
        when(designs.findDraft(id, reviewer, false)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.DRAFT, UUID.randomUUID(), reviewer)));

        assertThatThrownBy(() -> service.upload(id, reviewer, upload()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.beginUpload(id, reviewer, new com.stockflow.common.storage.UploadSlotRequest(
                "design.pdf", "application/pdf", bytes.length, Checksums.sha256Hex(bytes))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(files, staged);
    }

    @Test
    void preparesTheFileBeforeLockingTheDraftAndAttachesItUnderTheLock() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        var prepared = prepared();

        var result = service.upload(id, owner, upload());

        assertThat(result.checksum()).isEqualTo(Checksums.sha256Hex(bytes));
        assertThat(result.file().key()).isEqualTo("design-renders/new.pdf");
        InOrder order = inOrder(designs, files, prepared);
        order.verify(designs).findDraft(id, owner, false);
        order.verify(files).prepare(eq(FileCategory.DESIGN_RENDER), any(FileUpload.class));
        order.verify(designs).findDraft(id, owner, true);
        order.verify(designs).attach(result);
        order.verify(prepared).adopt();
        order.verify(prepared).close();
    }

    @Test
    void deniesUnrelatedUserEvenIfRepositoryAccidentallyReturnsDraft() {
        var outsider = UUID.randomUUID();
        when(designs.findDraft(id, outsider, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        assertThatThrownBy(() -> service.upload(id, outsider, upload()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(files);
    }

    @Test
    void refusesNonDraftAndSnapshotBackedDraftBeforeUploadingAnything() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.APPROVED)));
        assertThatThrownBy(() -> service.upload(id, owner, upload())).isInstanceOf(BusinessException.class);
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        when(designs.hasSnapshot(id)).thenReturn(true);
        assertThatThrownBy(() -> service.upload(id, owner, upload())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }

    @Test
    void aDraftConfirmedWhileTheFileWasScannedIsRefusedAndTheFileIsNotKept() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(draft(DesignStatus.APPROVED)));
        var prepared = prepared();

        assertThatThrownBy(() -> service.upload(id, owner, upload())).isInstanceOf(BusinessException.class);

        verify(prepared, never()).adopt();
        verify(prepared).close();
        verify(designs, never()).attach(any());
    }

    @Test void replayAfterConfirmationReturnsOriginalWithoutNewStorageWrite() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.APPROVED)));
        var artifact = new com.stockflow.design.internal.domain.DesignArtifact(UUID.randomUUID(), id,
                new StoredFile("design-renders/old.pdf", "design.pdf", "application/pdf", bytes.length, Instant.EPOCH),
                Checksums.sha256Hex(bytes), owner + ":CUSTOMER_PREVIEW:artifact-request-1");
        when(designs.findArtifacts(id)).thenReturn(java.util.List.of(artifact));
        assertThat(service.upload(id, owner, upload(), "artifact-request-1")).isSameAs(artifact);
        byte[] changed = bytes.clone(); changed[changed.length - 1] = 'x';
        var replacement = new FileUpload("design.pdf", "application/pdf", changed.length, new ByteArrayInputStream(changed));
        assertThatThrownBy(() -> service.upload(id, owner, replacement, "artifact-request-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(files);
    }

    @Test void anUploadedFileIsAcceptedOutsideTheLockAndAttachedUnderIt() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        UUID uploadId = UUID.randomUUID();
        var prepared = mock(PreparedFiles.class);
        when(prepared.single()).thenReturn(new PreparedFiles.PreparedFile(new StoredFile("design-renders/" + uploadId
                + ".pdf", "design.pdf", "application/pdf", bytes.length, Instant.EPOCH), Checksums.sha256Hex(bytes)));
        when(staged.accept(FileCategory.DESIGN_RENDER, owner, java.util.List.of(uploadId))).thenReturn(prepared);

        var result = service.attachUpload(id, owner, com.stockflow.design.api.DesignArtifactRole.PRINT_READY, uploadId);

        assertThat(result.role()).isEqualTo(com.stockflow.design.api.DesignArtifactRole.PRINT_READY);
        assertThat(result.uploadKey()).isEqualTo(owner + ":PRINT_READY:upload:" + uploadId);
        InOrder order = inOrder(designs, staged, prepared);
        order.verify(designs).findDraft(id, owner, false);
        order.verify(staged).accept(FileCategory.DESIGN_RENDER, owner, java.util.List.of(uploadId));
        order.verify(designs).findDraft(id, owner, true);
        order.verify(designs).attach(result);
        order.verify(prepared).adopt();
        verifyNoInteractions(files);
    }

    @Test void theSameUploadAttachedAgainIsAnsweredWithItsArtifactWithoutAcceptingItTwice() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.APPROVED)));
        UUID uploadId = UUID.randomUUID();
        var earlier = new com.stockflow.design.internal.domain.DesignArtifact(UUID.randomUUID(), id,
                new StoredFile("design-renders/" + uploadId + ".pdf", "design.pdf", "application/pdf", bytes.length,
                        Instant.EPOCH), Checksums.sha256Hex(bytes), owner + ":CUSTOMER_PREVIEW:upload:" + uploadId);
        when(designs.findArtifacts(id)).thenReturn(java.util.List.of(earlier));

        assertThat(service.attachUpload(id, owner, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW,
                uploadId)).isSameAs(earlier);
        verifyNoInteractions(staged, files);
    }

    @Test void aSlotIsGivenOnlyForADraftThatStillTakesFiles() {
        var request = new com.stockflow.common.storage.UploadSlotRequest("d.pdf", "application/pdf", 10,
                Checksums.sha256Hex(bytes));
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.APPROVED)));
        assertThatThrownBy(() -> service.beginUpload(id, owner, request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(staged);

        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        service.beginUpload(id, owner, request);
        verify(staged).begin(FileCategory.DESIGN_RENDER, owner, request);
    }

    @Test void theSameRequestCommittedWhileThisOneWasScanningIsAnsweredWithItsArtifact() {
        when(designs.findDraft(id, owner, false)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(draft(DesignStatus.DRAFT)));
        var earlier = new com.stockflow.design.internal.domain.DesignArtifact(UUID.randomUUID(), id,
                new StoredFile("design-renders/earlier.pdf", "design.pdf", "application/pdf", bytes.length, Instant.EPOCH),
                Checksums.sha256Hex(bytes), owner + ":CUSTOMER_PREVIEW:artifact-request-2");
        when(designs.findArtifacts(id)).thenReturn(java.util.List.of(), java.util.List.of(earlier));
        var prepared = prepared();

        assertThat(service.upload(id, owner, upload(), "artifact-request-2")).isSameAs(earlier);

        verify(prepared, never()).adopt();
        verify(prepared).close();
        verify(designs, never()).attach(any());
    }
}
