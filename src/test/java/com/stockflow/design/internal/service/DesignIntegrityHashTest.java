package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.ObjectStat;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The three levels at which a confirmed design is checked (see {@link DesignServiceImpl}): the database
 * at checkout, the store's recorded SHA-256 where a file is used, and the bytes before printing.
 */
class DesignIntegrityHashTest {

    private static final byte[] PRINT = "%PDF-1.7 the confirmed print file".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIEW = "the confirmed preview".getBytes(StandardCharsets.UTF_8);
    private static final String PRINT_KEY = "design-renders/2026/09/20/print.pdf";
    private static final String PREVIEW_KEY = "design-renders/2026/09/20/preview.png";

    private final DesignSnapshotJpaRepository snapshots = mock(DesignSnapshotJpaRepository.class);
    private final DesignDraftJpaRepository drafts = mock(DesignDraftJpaRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final DesignServiceImpl service = new DesignServiceImpl(snapshots, drafts, storage,
            mock(ProductService.class), files);
    private final UUID customer = UUID.randomUUID();

    /** A legacy, manifest-less snapshot: one file, whose digest is the snapshot checksum. */
    private DesignSnapshotJpaEntity legacy(String recorded) {
        var snapshot = new DesignSnapshotJpaEntity(UUID.randomUUID(), UUID.randomUUID(), recorded, PRINT_KEY,
                Instant.now());
        when(snapshots.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));
        return snapshot;
    }

    /** A confirmed bundle of a print file and a preview, as the workflow writes it. */
    private DesignSnapshotJpaEntity bundle() {
        var draft = new DesignDraftJpaEntity(UUID.randomUUID(), customer, UUID.randomUUID(), "Cup", "Logo",
                com.stockflow.design.internal.domain.DesignStatus.DRAFT);
        var print = new DesignArtifactJpaEntity(UUID.randomUUID(), draft.getId(), DesignArtifactRole.PRINT_READY,
                new StoredFile(PRINT_KEY, "print.pdf", "application/pdf", PRINT.length, Instant.now()),
                Checksums.sha256Hex(PRINT));
        var preview = new DesignArtifactJpaEntity(UUID.randomUUID(), draft.getId(), DesignArtifactRole.CUSTOMER_PREVIEW,
                new StoredFile(PREVIEW_KEY, "preview.png", "image/png", PREVIEW.length, Instant.now()),
                Checksums.sha256Hex(PREVIEW));
        var snapshot = new DesignSnapshotJpaEntity(UUID.randomUUID(), draft, List.of(print, preview),
                UUID.randomUUID(), Instant.now());
        when(snapshots.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));
        when(drafts.findById(draft.getId())).thenReturn(Optional.of(draft));
        return snapshot;
    }

    private void recorded(String key, byte[] bytes) {
        when(storage.stat(key)).thenReturn(new ObjectStat(bytes.length, "application/pdf", Checksums.sha256Hex(bytes)));
    }

    private void unrecorded(String key, byte[] bytes) {
        when(storage.stat(key)).thenReturn(new ObjectStat(bytes.length, "application/pdf", null));
        when(storage.download(key)).thenAnswer(call -> new ByteArrayInputStream(bytes));
    }

    @Test
    void checkoutReadsNothingFromStorage() {
        var snapshot = bundle();

        var confirmed = service.verifySnapshot(snapshot.getId(), customer);

        assertThat(confirmed.checksum()).isEqualTo(snapshot.getChecksum());
        verifyNoInteractions(storage);
    }

    @Test
    void checkoutOfAnotherCustomersDesignIsNotFound() {
        var snapshot = bundle();

        assertThatThrownBy(() -> service.verifySnapshot(snapshot.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void fulfillmentChecksEveryFileByItsRecordedDigestWithoutDownloading() {
        var snapshot = bundle();
        recorded(PRINT_KEY, PRINT);
        recorded(PREVIEW_KEY, PREVIEW);

        var result = service.verifyForFulfillment(snapshot.getId(), snapshot.getChecksum());

        assertThat(result.matching()).isTrue();
        verify(storage, never()).download(anyString());
    }

    @Test
    void aFileTheStoreRecordsDifferentlyIsAMismatch() {
        var snapshot = bundle();
        recorded(PRINT_KEY, "a replaced print file".getBytes(StandardCharsets.UTF_8));
        recorded(PREVIEW_KEY, PREVIEW);

        assertThat(service.verifyForFulfillment(snapshot.getId(), snapshot.getChecksum()).matching()).isFalse();
    }

    @Test
    void anObjectWithoutARecordedDigestIsHashedFromItsBytes() {
        var snapshot = legacy(Checksums.sha256Hex(PRINT));
        unrecorded(PRINT_KEY, PRINT);

        var result = service.verifyForFulfillment(snapshot.getId(), snapshot.getChecksum());

        assertThat(result.matching()).isTrue();
        assertThat(result.actualChecksum()).isEqualTo(Checksums.sha256Hex(PRINT));
    }

    @Test
    void aChangedLegacyArtifactIsStillReportedAsAMismatch() {
        String confirmed = Checksums.sha256Hex("what was confirmed".getBytes(StandardCharsets.UTF_8));
        var snapshot = legacy(confirmed);
        unrecorded(PRINT_KEY, PRINT);

        var result = service.verifyForFulfillment(snapshot.getId(), confirmed);

        assertThat(result.matching()).isFalse();
        assertThat(result.actualChecksum()).isEqualTo(Checksums.sha256Hex(PRINT));
    }

    @Test
    void printingRehashesEveryByteEvenWhenTheStoreRecordedADigest() {
        var snapshot = bundle();
        recorded(PRINT_KEY, PRINT);
        recorded(PREVIEW_KEY, PREVIEW);
        when(storage.download(PRINT_KEY)).thenAnswer(call -> new ByteArrayInputStream(PRINT));
        when(storage.download(PREVIEW_KEY)).thenAnswer(call -> new ByteArrayInputStream(PREVIEW));

        assertThat(service.verifyForPrinting(snapshot.getId(), snapshot.getChecksum()).matching()).isTrue();
        verify(storage, times(1)).download(PRINT_KEY);
        verify(storage, times(1)).download(PREVIEW_KEY);
        verify(storage, never()).stat(anyString());
    }

    @Test
    void printingCatchesAFileWhoseRecordedDigestLies() {
        var snapshot = bundle();
        when(storage.download(PRINT_KEY)).thenAnswer(call -> new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)));
        when(storage.download(PREVIEW_KEY)).thenAnswer(call -> new ByteArrayInputStream(PREVIEW));

        assertThat(service.verifyForPrinting(snapshot.getId(), snapshot.getChecksum()).matching()).isFalse();
    }

    @Test
    void aDownloadChecksOnlyTheFileHandedOut() {
        var snapshot = bundle();
        recorded(PREVIEW_KEY, PREVIEW);
        when(files.downloadLink(PREVIEW_KEY)).thenReturn(new DownloadLink("https://s3/preview", Instant.now()));

        var file = service.fulfillmentDownload(snapshot.getId(), snapshot.getChecksum(), DesignArtifactRole.CUSTOMER_PREVIEW);

        assertThat(file.url()).isEqualTo("https://s3/preview");
        verify(storage).stat(PREVIEW_KEY);
        verify(storage, never()).stat(PRINT_KEY);
        verify(storage, never()).download(anyString());
    }

    @Test
    void aDownloadOfAChangedFileIsRefusedWithoutALink() {
        var snapshot = bundle();
        recorded(PREVIEW_KEY, "another picture".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.fulfillmentDownload(snapshot.getId(), snapshot.getChecksum(),
                DesignArtifactRole.CUSTOMER_PREVIEW)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }

    @Test
    void aDownloadAgainstAnotherOrdersChecksumIsRefused() {
        var snapshot = bundle();
        recorded(PREVIEW_KEY, PREVIEW);

        assertThatThrownBy(() -> service.fulfillmentDownload(snapshot.getId(),
                Checksums.sha256Hex("another bundle".getBytes(StandardCharsets.UTF_8)), DesignArtifactRole.CUSTOMER_PREVIEW))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }
}
