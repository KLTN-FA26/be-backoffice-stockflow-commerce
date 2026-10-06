package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * Verifies customer ownership and artifact integrity before an order can reference a snapshot.
 * Storage keys and download credentials stay inside the design module.
 */
@Service
@Transactional
class DesignServiceImpl implements DesignService {

    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDraftJpaRepository drafts;
    private final FileStorage storage;
    private final ProductService products;
    private final UploadInspection inspection;
    private final com.stockflow.common.storage.FileTransfers files;

    DesignServiceImpl(DesignSnapshotJpaRepository snapshots,
            DesignDraftJpaRepository drafts,
            FileStorage storage, ProductService products, UploadInspection inspection,
            com.stockflow.common.storage.FileTransfers files) {
        this.snapshots = snapshots; this.drafts = drafts; this.storage = storage; this.products = products;
        this.inspection = inspection;
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
        var result = inspect(snapshot, snapshot.getChecksum());
        if (!result.matching()) { throw new BusinessException(ErrorCode.CONFLICT, "Stored artifact differs from the confirmed snapshot"); }
        return new ConfirmedDesign(snapshotId, draft.getProductId(), snapshot.getChecksum());
    }

    @Override
    @Transactional(readOnly = true)
    public DesignVerification verifyForFulfillment(UUID snapshotId, String expectedChecksum) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return inspect(snapshot, expectedChecksum);
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentArtifact fulfillmentDownload(UUID snapshotId, String expectedChecksum, DesignArtifactRole role) {
        var snapshot = snapshots.findById(snapshotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var verification = inspect(snapshot, expectedChecksum);
        if (!verification.matching()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Design integrity verification failed");
        }
        var item = snapshot.getArtifactManifest().stream().filter(value -> value.role() == role).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "The confirmed bundle has no " + role + " artifact"));
        var link = files.downloadLink(item.storageKey());
        return new FulfillmentArtifact(item.artifactId(), item.role(), item.originalName(), item.contentType(),
                item.sizeBytes(), item.checksum(), link.url(), link.expiresAt());
    }

    private DesignVerification inspect(com.stockflow.design.internal.entity.DesignSnapshotJpaEntity snapshot,
                                       String expectedChecksum) {
        if (expectedChecksum == null || !expectedChecksum.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Expected checksum must be SHA-256");
        }
        var manifest = snapshot.getArtifactManifest();
        if (manifest.isEmpty()) {
            String actual = digest(snapshot.getArtifactUrl());
            return new DesignVerification(snapshot.getId(), expectedChecksum, actual,
                    expectedChecksum.equals(snapshot.getChecksum()) && actual.equals(snapshot.getChecksum()));
        }
        var actualEntries = manifest.stream().map(value -> new com.stockflow.design.internal.domain.DesignSnapshotArtifact(
                value.artifactId(), value.role(), value.storageKey(), value.originalName(), value.contentType(), value.sizeBytes(),
                digest(value.storageKey()))).toList();
        String actual = com.stockflow.design.internal.entity.DesignSnapshotJpaEntity.manifestChecksum(actualEntries);
        boolean eachMatches = java.util.stream.IntStream.range(0, manifest.size())
                .allMatch(index -> manifest.get(index).checksum().equals(actualEntries.get(index).checksum()));
        return new DesignVerification(snapshot.getId(), expectedChecksum, actual,
                eachMatches && expectedChecksum.equals(snapshot.getChecksum()) && actual.equals(snapshot.getChecksum()));
    }

    private String digest(String key) {
        try (var stream = storage.download(key)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashing = new java.security.DigestInputStream(stream, digest);
            inspection.requireClean(hashing);
            // The scanner reads the whole stream when it is on, but not when it is switched off (the
            // local profile), and a hash of the bytes not yet read is the hash of nothing: every
            // integrity check would then "differ". Reading what is left makes the hash correct either way.
            hashing.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new StorageException("Could not verify the confirmed artifact", ex);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
