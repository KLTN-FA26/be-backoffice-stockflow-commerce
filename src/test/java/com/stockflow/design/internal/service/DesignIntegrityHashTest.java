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

    private static final byte[] LOGO = "the customer's logo".getBytes(StandardCharsets.UTF_8);
    private static final String LOGO_KEY = "design-assets/2026/11/18/logo.png";

    /** An approved customer's design: its files, its layout and the one image the layout places. */
    private DesignSnapshotJpaEntity approvedWithLogo(UUID logo) {
        var draft = new DesignDraftJpaEntity(UUID.randomUUID(), customer, UUID.randomUUID(), "Cup", "Logo",
                com.stockflow.design.internal.domain.DesignStatus.DRAFT);
        var print = new DesignArtifactJpaEntity(UUID.randomUUID(), draft.getId(), DesignArtifactRole.PRINT_READY,
                new StoredFile(PRINT_KEY, "print.pdf", "application/pdf", PRINT.length, Instant.now()),
                Checksums.sha256Hex(PRINT));
        var preview = new DesignArtifactJpaEntity(UUID.randomUUID(), draft.getId(), DesignArtifactRole.CUSTOMER_PREVIEW,
                new StoredFile(PREVIEW_KEY, "preview.png", "image/png", PREVIEW.length, Instant.now()),
                Checksums.sha256Hex(PREVIEW));
        var image = new com.stockflow.design.internal.domain.DesignSnapshotAsset(logo, LOGO_KEY, "logo quán A.png",
                "image/png", LOGO.length, Checksums.sha256Hex(LOGO), 1200, 800);
        var snapshot = DesignSnapshotJpaEntity.approved(UUID.randomUUID(), draft, List.of(print, preview), "{}",
                "e".repeat(64), List.of(image), "{}", UUID.randomUUID(), Instant.now());
        when(snapshots.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));
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
        when(files.viewLink(PREVIEW_KEY)).thenReturn(new DownloadLink("https://s3/preview", Instant.now()));

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

    // ---- the customer's original images (FE request 2026-11-18 BE-6) ----

    @Test
    void theOriginalImagesAreListedFromTheDatabaseAlone() {
        UUID logo = UUID.randomUUID();
        var snapshot = approvedWithLogo(logo);

        assertThat(service.confirmedAssets(snapshot.getId())).singleElement().satisfies(asset -> {
            assertThat(asset.assetId()).isEqualTo(logo);
            assertThat(asset.originalName()).isEqualTo("logo quán A.png");
            assertThat(asset.checksum()).isEqualTo(Checksums.sha256Hex(LOGO));
        });
        // A snapshot of the staff workflow froze no image: nothing to hand out, nothing refused.
        assertThat(service.confirmedAssets(bundle().getId())).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void anOriginalImageIsHandedOutOnlyWhileItIsTheApprovedOne() {
        UUID logo = UUID.randomUUID();
        var snapshot = approvedWithLogo(logo);
        recorded(LOGO_KEY, LOGO);
        when(files.downloadLink(LOGO_KEY, "logo quán A.png")).thenReturn(new DownloadLink("https://s3/logo",
                Instant.now()));

        var file = service.assetDownload(snapshot.getId(), snapshot.getChecksum(), logo);

        assertThat(file.url()).isEqualTo("https://s3/logo");
        assertThat(file.originalName()).isEqualTo("logo quán A.png");
        verify(storage).stat(LOGO_KEY);
        verify(storage, never()).stat(PRINT_KEY);
        verify(storage, never()).stat(PREVIEW_KEY);
        verify(storage, never()).download(anyString());

        // Replaced in the store after approval: refused, and no link is signed.
        recorded(LOGO_KEY, "another logo".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.assetDownload(snapshot.getId(), snapshot.getChecksum(), logo))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(com.stockflow.common.error.ErrorCode.CONFLICT);
        verify(files, times(1)).downloadLink(anyString(), anyString());
    }

    @Test
    void anOriginalImageIsRefusedAgainstAnotherChecksumOrWhenTheDesignDoesNotPlaceIt() {
        UUID logo = UUID.randomUUID();
        var snapshot = approvedWithLogo(logo);
        recorded(LOGO_KEY, LOGO);

        assertThatThrownBy(() -> service.assetDownload(snapshot.getId(),
                Checksums.sha256Hex("another bundle".getBytes(StandardCharsets.UTF_8)), logo))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(com.stockflow.common.error.ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.assetDownload(snapshot.getId(), snapshot.getChecksum(), UUID.randomUUID()))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(com.stockflow.common.error.ErrorCode.NOT_FOUND);
        verifyNoInteractions(files);
    }
}
