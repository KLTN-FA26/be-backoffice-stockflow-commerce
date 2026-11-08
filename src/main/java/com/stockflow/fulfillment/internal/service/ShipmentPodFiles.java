package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentPodFileJpaEntity;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentPodFileJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The proof of delivery kept with a shipment (3-6; kltn-docs 09 §4.3 step 2: "POD đính kèm shipment: hãng gửi kèm sự
 * kiện, hoặc Quản lý kho tải lên khi ghi tay"; BR-28, E16: optional - a delivery is recorded without one; customers
 * never see it, 17 BR-27). In the {@code documents} category: a PDF, or a photo of the signed paper.
 *
 * <p>As the waybill files are: checked, scanned and stored with no transaction held, then recorded in a short one, if
 * the shipment is delivered - a POD is the proof of a delivery - and the file is a PDF or an image.</p>
 */
@Service
@Transactional
public class ShipmentPodFiles {

    /** A file as a shipment lists it; the bytes come from {@link #downloadLink}. */
    public record PodFile(UUID id, UUID shipmentId, String fileName, String contentType, long sizeBytes,
                          UUID uploadedBy, Instant uploadedAt) {
    }

    private final ShipmentJpaRepository shipments;
    private final ShipmentPodFileJpaRepository files;
    private final FileTransfers transfers;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final Clock clock;

    public ShipmentPodFiles(ShipmentJpaRepository shipments, ShipmentPodFileJpaRepository files,
                            FileTransfers transfers, StagedUploads staged, TransactionOperations tx, Clock clock) {
        this.shipments = shipments;
        this.files = files;
        this.transfers = transfers;
        this.staged = staged;
        this.tx = tx;
        this.clock = clock;
    }

    /** A slot to upload a proof of delivery of this shipment straight to the object store. */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID shipmentId, UploadSlotRequest request, UUID by) {
        requireAttachable(shipmentId);
        return staged.begin(FileCategory.DOCUMENT, by, request);
    }

    /**
     * Keeps one file with the shipment. Not transactional itself: the file is prepared first. A browser upload named
     * again answers with the file it made.
     *
     * @throws BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}; {@code SHIPMENT_STATE_CONFLICT}
     *         for a shipment that did not leave; {@code VALIDATION_FAILED} unless exactly one file
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "shipment-pod-file", resourceId = "#result?.id()",
            details = "{shipmentId: #shipmentId}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PodFile attach(UUID shipmentId, IncomingFiles file, UUID by) {
        if (by == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (file.count() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Attach exactly one proof of delivery");
        }
        if (file instanceof IncomingFiles.Uploaded uploaded) {
            Optional<String> key = staged.keyOf(FileCategory.DOCUMENT, by, uploaded.uploadIds().get(0));
            Optional<PodFile> earlier = key.flatMap(k -> tx.execute(status -> {
                requireShipment(shipmentId);
                return files.findByStorageKeyAndShipmentId(k, shipmentId).map(ShipmentPodFiles::view);
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> requireAttachable(shipmentId));
        try (PreparedFiles prepared = file.prepare(FileCategory.DOCUMENT, transfers, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                requireAttachable(shipmentId);
                // The documents category also takes Word and Excel; a proof of delivery is a PDF or a photo of the paper.
                if (!"application/pdf".equals(stored.contentType())
                        && (stored.contentType() == null || !stored.contentType().startsWith("image/"))) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A proof of delivery is a PDF or an image");
                }
                ShipmentPodFileJpaEntity saved = files.saveAndFlush(new ShipmentPodFileJpaEntity(Identifiers.newId(),
                        shipmentId, stored.key(), stored.originalName(), stored.contentType(), stored.sizeBytes(), by,
                        clock.instant()));
                prepared.adopt();
                return view(saved);
            });
        }
    }

    @Transactional(readOnly = true)
    public List<PodFile> list(UUID shipmentId) {
        requireShipment(shipmentId);
        return files.findByShipmentIdOrderByUploadedAtAscIdAsc(shipmentId).stream().map(ShipmentPodFiles::view)
                .toList();
    }

    /** A link saving the file under its name; audited, as a signed delivery note leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "shipment-pod-file", resourceId = "#fileId",
            details = "{shipmentId: #shipmentId}")
    public DownloadLink downloadLink(UUID shipmentId, UUID fileId) {
        requireShipment(shipmentId);
        ShipmentPodFileJpaEntity found = files.findByIdAndShipmentId(fileId, shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No proof of delivery " + fileId));
        return transfers.downloadLink(found.getStorageKey(), found.getFileName());
    }

    private ShipmentJpaEntity requireShipment(UUID shipmentId) {
        ShipmentJpaEntity shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
        WarehouseScope.requireWarehouse(shipment.getWarehouseId());
        return shipment;
    }

    private void requireAttachable(UUID shipmentId) {
        ShipmentJpaEntity shipment = requireShipment(shipmentId);
        if (shipment.getStatus() != ShipmentStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s is %s: a proof of delivery is "
                    + "kept with a delivered shipment (09 §4.3 step 2)").formatted(shipment.getCode(),
                    shipment.getStatus()));
        }
    }

    private static PodFile view(ShipmentPodFileJpaEntity f) {
        return new PodFile(f.getId(), f.getShipmentId(), f.getFileName(), f.getContentType(), f.getSizeBytes(),
                f.getUploadedBy(), f.getUploadedAt());
    }
}
