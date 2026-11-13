package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.design.internal.domain.DesignSnapshotArtifact;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import java.io.IOException;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies customer ownership and artifact integrity before an order can reference a snapshot.
 * Storage keys and download credentials stay inside the design module.
 *
 * <h2>Three levels of checking, each where it is needed</h2>
 *
 * <ul>
 *   <li><b>Database</b> ({@link #verifySnapshot}): checkout, quotes, sample requests. The snapshot is
 *       immutable and carries the checksum the customer confirmed; whether the customer owns it and it
 *       fits the SKU is a question for the database, so nothing is read from storage. Reading every
 *       file of the bundle here - as this class once did, up to 50 MB a file, inside the checkout
 *       transaction - bought no rule anything and blocked the order on the object store.</li>
 *   <li><b>Metadata</b> ({@link #verifyForFulfillment}, {@link #fulfillmentDownload}): wherever a file is
 *       used - confirming and previewing it in the design workflow. The warehouse no longer checks or
 *       downloads designs (kltn-docs 08 §1, V20261108000500). {@link FileStorage#stat} returns the SHA-256 the store computed when the object
 *       was written; any later write recomputes it, so a match proves the object unchanged without a
 *       download.</li>
 *   <li><b>Bytes</b> ({@link #verifyForPrinting}): prepress, the last gate before ink (BR-PRD-02). Every
 *       file is read and hashed again, once per production order.</li>
 * </ul>
 *
 * <p>An object written before stores recorded SHA-256 (or copied in by a tool that did not ask for
 * it) has no digest in its metadata; the metadata level hashes its bytes instead. Nothing here scans
 * for malware: a file reached its category through the upload-time scan and is never re-scanned on
 * read.</p>
 */
@Service
@Transactional
class DesignServiceImpl implements DesignService {

    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDraftJpaRepository drafts;
    private final FileStorage storage;
    private final ProductService products;
    private final com.stockflow.common.storage.FileTransfers files;

    DesignServiceImpl(DesignSnapshotJpaRepository snapshots,
            DesignDraftJpaRepository drafts,
            FileStorage storage, ProductService products,
            com.stockflow.common.storage.FileTransfers files) {
        this.snapshots = snapshots; this.drafts = drafts; this.storage = storage; this.products = products;
        this.files = files;
    }

    @Override
    @Transactional(readOnly = true)
    public ConfirmedDesign verifySnapshotForSku(UUID snapshotId, UUID customerId, String sku) {
        var result = verifySnapshot(snapshotId, customerId);
        if (!products.containsSku(result.productId(), sku)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Confirmed design does not belong to the ordered product");
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ConfirmedDesign verifySnapshot(UUID snapshotId, UUID customerId) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        var draft = drafts.findById(snapshot.getDraftId()).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND));
        if (customerId == null || !customerId.equals(draft.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (snapshot.getArtifactId() == null || snapshot.getConfirmedBy() == null) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Legacy snapshot requires explicit customer confirmation");
        }
        // The recorded manifest must still add up to the confirmed checksum: a row edited by hand is
        // caught here, at no storage cost. The files themselves are checked where they are used.
        if (!recordedManifestAddsUp(snapshot)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Stored artifact differs from the confirmed snapshot");
        }
        return new ConfirmedDesign(snapshotId, draft.getProductId(), snapshot.getChecksum());
    }

    @Override
    @Transactional(readOnly = true)
    public DesignVerification verifyForFulfillment(UUID snapshotId, String expectedChecksum) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return inspect(snapshot, expectedChecksum, false);
    }

    @Override
    @Transactional(readOnly = true)
    public DesignVerification verifyForPrinting(UUID snapshotId, String expectedChecksum) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return inspect(snapshot, expectedChecksum, true);
    }

    /**
     * The one file asked for, checked against its manifest entry by metadata, and the manifest
     * against the order's frozen checksum. The bundle's other files are not touched: they are not
     * being handed out, and each is checked when it is.
     */
    @Override
    @Transactional(readOnly = true)
    public FulfillmentArtifact fulfillmentDownload(UUID snapshotId, String expectedChecksum, DesignArtifactRole role) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireSha256(expectedChecksum);
        var manifest = snapshot.getArtifactManifest();
        if (manifest.isEmpty()) {
            // A legacy snapshot is one file, so checking the bundle is checking the file.
            if (!inspect(snapshot, expectedChecksum, false).matching()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Design integrity verification failed");
            }
        }
        var item = manifest.stream().filter(value -> value.role() == role).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "The confirmed bundle has no " + role + " artifact"));
        if (!expectedChecksum.equals(snapshot.getChecksum())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Design integrity verification failed");
        }
        requireIntact(snapshot, item);
        // The preview is looked at in the page; every other file is saved under the name it was uploaded with.
        var link = item.role() == DesignArtifactRole.CUSTOMER_PREVIEW ? files.viewLink(item.storageKey())
                : files.downloadLink(item.storageKey(), item.originalName());
        return new FulfillmentArtifact(item.artifactId(), item.role(), item.originalName(), item.contentType(),
                item.sizeBytes(), item.checksum(), link.url(), link.expiresAt());
    }

    /**
     * Refuses a link to a file of a design's approved bundle unless the file is still what was approved (kltn-docs 12
     * §3 "Link tải file", BR-10): the same metadata check as {@link #fulfillmentDownload}, for the customer's own
     * download link. A file that is in no approved bundle - a draft's, or one replaced before approval - is not checked:
     * nothing was frozen for it.
     *
     * @throws BusinessException {@code CONFLICT} when the manifest or the stored file no longer matches
     */
    public void requireApprovedFileIntact(UUID designId, UUID artifactId) {
        snapshots.findByDraftId(designId).ifPresent(snapshot -> {
            var manifest = snapshot.getArtifactManifest();
            if (manifest.isEmpty()) {
                // A legacy snapshot is one file, so checking the bundle is checking the file.
                if (artifactId.equals(snapshot.getArtifactId())
                        && !inspect(snapshot, snapshot.getChecksum(), false).matching()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "Design integrity verification failed");
                }
                return;
            }
            manifest.stream().filter(item -> item.artifactId().equals(artifactId)).findFirst()
                    .ifPresent(item -> requireIntact(snapshot, item));
        });
    }

    /** One manifest entry: the recorded manifest still adds up to the approved checksum, and the stored file to it. */
    private void requireIntact(DesignSnapshotJpaEntity snapshot, DesignSnapshotArtifact item) {
        if (!recordedManifestAddsUp(snapshot) || !item.checksum().equals(storedDigest(item.storageKey(), false))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Design integrity verification failed");
        }
    }

    private DesignVerification inspect(DesignSnapshotJpaEntity snapshot, String expectedChecksum, boolean rehash) {
        requireSha256(expectedChecksum);
        var manifest = snapshot.getArtifactManifest();
        if (manifest.isEmpty()) {
            String actual = storedDigest(snapshot.getArtifactUrl(), rehash);
            return new DesignVerification(snapshot.getId(), expectedChecksum, actual,
                    expectedChecksum.equals(snapshot.getChecksum()) && actual.equals(snapshot.getChecksum()));
        }
        var actualEntries = manifest.stream().map(value -> new DesignSnapshotArtifact(
                value.artifactId(), value.role(), value.storageKey(), value.originalName(), value.contentType(), value.sizeBytes(),
                storedDigest(value.storageKey(), rehash))).toList();
        // A customer's design: the images it places are part of what was approved, and are checked as its files are.
        var assets = snapshot.getAssets() == null ? List.<com.stockflow.design.internal.domain.DesignSnapshotAsset>of()
                : snapshot.getAssets();
        var actualAssets = assets.stream().map(asset -> new com.stockflow.design.internal.domain.DesignSnapshotAsset(
                asset.assetId(), asset.storageKey(), asset.originalName(), asset.contentType(), asset.sizeBytes(),
                storedDigest(asset.storageKey(), rehash), asset.widthPx(), asset.heightPx())).toList();
        String actual = DesignSnapshotJpaEntity.checksumOf(actualEntries, snapshot.getDocumentChecksum(), actualAssets);
        boolean eachMatches = IntStream.range(0, manifest.size())
                .allMatch(index -> manifest.get(index).checksum().equals(actualEntries.get(index).checksum()))
                && IntStream.range(0, assets.size())
                .allMatch(index -> assets.get(index).checksum().equals(actualAssets.get(index).checksum()));
        return new DesignVerification(snapshot.getId(), expectedChecksum, actual,
                eachMatches && expectedChecksum.equals(snapshot.getChecksum()) && actual.equals(snapshot.getChecksum()));
    }

    /** True for a legacy, manifest-less snapshot: its single file is checked by {@link #inspect}. */
    private static boolean recordedManifestAddsUp(DesignSnapshotJpaEntity snapshot) {
        List<DesignSnapshotArtifact> manifest = snapshot.getArtifactManifest();
        return manifest.isEmpty()
                || DesignSnapshotJpaEntity.checksumOf(manifest, snapshot.getDocumentChecksum(),
                        snapshot.getAssets() == null ? List.of() : snapshot.getAssets()).equals(snapshot.getChecksum());
    }

    private static void requireSha256(String checksum) {
        if (!Checksums.isSha256Hex(checksum)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Expected checksum must be SHA-256");
        }
    }

    /**
     * The object's SHA-256: from the store's metadata, or - when {@code rehash} is asked for or the
     * metadata has none - from its bytes.
     */
    private String storedDigest(String key, boolean rehash) {
        if (!rehash) {
            String recorded = storage.stat(key).sha256();
            if (recorded != null) {
                return recorded;
            }
        }
        try (var stream = new DigestInputStream(storage.download(key), Checksums.sha256())) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(stream.getMessageDigest().digest());
        } catch (IOException ex) {
            throw new StorageException("Could not verify the confirmed artifact", ex);
        }
    }
}
