package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import com.stockflow.design.internal.domain.DesignStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesignArtifactServiceTest {
    private final DesignArtifactRepository designs = mock(DesignArtifactRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final DesignArtifactService service = new DesignArtifactService(designs, files,
            mock(com.stockflow.common.storage.UploadInspection.class));
    private final UUID id = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final byte[] bytes = "%PDF-1.7\nExample design artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private FileUpload upload() {
        return new FileUpload("design.pdf", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
    }

    @Test
    void hashesActualBytesOnceDespiteStorageSniffingAndCreatesNewRevision() throws Exception {
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.DRAFT, UUID.randomUUID())));
        when(files.store(eq(FileCategory.DESIGN_RENDER), any())).thenAnswer(call -> {
            FileUpload file = call.getArgument(1);
            file.content().mark(12);
            file.content().readNBytes(12);
            file.content().reset();
            assertThat(file.content().readAllBytes()).isEqualTo(bytes);
            return new StoredFile("design-renders/new.pdf", file.originalName(), file.contentType(),
                    file.sizeBytes(), Instant.EPOCH);
        });
        var result = service.upload(id, owner, upload());
        assertThat(result.checksum()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        verify(designs).attach(result);
    }

    @Test
    void deniesUnrelatedUserEvenIfRepositoryAccidentallyReturnsDraft() {
        var outsider = UUID.randomUUID();
        when(designs.findDraft(id, outsider, true)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.DRAFT, null)));
        assertThatThrownBy(() -> service.upload(id, outsider, upload()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(files);
    }

    @Test
    void refusesNonDraftAndSnapshotBackedDraftBeforeWritingStorage() {
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.APPROVED, null)));
        assertThatThrownBy(() -> service.upload(id, owner, upload())).isInstanceOf(BusinessException.class);
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.DRAFT, null)));
        when(designs.hasSnapshot(id)).thenReturn(true);
        assertThatThrownBy(() -> service.upload(id, owner, upload())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }

    @Test void replayAfterConfirmationReturnsOriginalWithoutNewStorageWrite() throws Exception {
        when(designs.findDraft(id, owner, true)).thenReturn(Optional.of(
                new DesignDraft(id, owner, null, DesignStatus.APPROVED, null)));
        var checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact = new com.stockflow.design.internal.domain.DesignArtifact(UUID.randomUUID(), id,
                new StoredFile("design-renders/old.pdf", "design.pdf", "application/pdf", bytes.length, Instant.EPOCH),
                checksum, owner + ":CUSTOMER_PREVIEW:artifact-request-1");
        when(designs.findArtifacts(id)).thenReturn(java.util.List.of(artifact));
        assertThat(service.upload(id, owner, upload(), "artifact-request-1")).isSameAs(artifact);
        byte[] changed = bytes.clone(); changed[changed.length - 1] = 'x';
        var replacement = new FileUpload("design.pdf", "application/pdf", changed.length, new ByteArrayInputStream(changed));
        assertThatThrownBy(() -> service.upload(id, owner, replacement, "artifact-request-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(files);
    }
}
