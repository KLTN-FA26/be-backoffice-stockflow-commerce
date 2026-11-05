package com.stockflow.procurement.internal.service;

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
import com.stockflow.procurement.internal.domain.PurchaseOrderAttachmentCategory;
import com.stockflow.procurement.internal.entity.PurchaseOrderAttachmentJpaEntity;
import com.stockflow.procurement.internal.repository.PurchaseOrderAttachmentJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The documents kept with a purchase order (table {@code purchase_order_attachments}): the supplier's quotation, the
 * contract, the supplier's confirmation, a delivery note.
 *
 * <p>A document is checked, scanned and stored the way every upload is - sent through the backend or straight to the
 * object store ({@link IncomingFiles}) - with no transaction held while that happens; a short transaction then records
 * it. {@link FileCategory#DOCUMENT}: PDF, Word, Excel, or a photo of a paper document.</p>
 */
@Service
@Transactional
public class PurchaseOrderAttachmentService {

    /** One document as a purchase order lists it; the file comes from {@link #downloadLink}. */
    public record AttachmentView(UUID id, UUID purchaseOrderId, String fileName, String fileType,
                                 PurchaseOrderAttachmentCategory category, Integer sizeBytes, UUID uploadedBy,
                                 Instant uploadedAt) {
    }

    private final PurchaseOrderAttachmentJpaRepository attachments;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionOperations tx;

    public PurchaseOrderAttachmentService(PurchaseOrderAttachmentJpaRepository attachments, FileTransfers files,
                                          StagedUploads staged, TransactionOperations tx) {
        this.attachments = attachments;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
    }

    /** A slot to upload a document of this order straight to the object store. */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID purchaseOrderId, UploadSlotRequest request, UUID by) {
        requireOrder(purchaseOrderId);
        return staged.begin(FileCategory.DOCUMENT, by, request);
    }

    /**
     * Keeps one document with the order. Not transactional itself: the file is prepared first. A browser upload named
     * again answers with the document it made.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "purchase-order-attachment", resourceId = "#result?.id()",
            details = "{purchaseOrderId: #purchaseOrderId, category: #category}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttachmentView attach(UUID purchaseOrderId, PurchaseOrderAttachmentCategory category, IncomingFiles file,
                                 UUID by) {
        Objects.requireNonNull(category, "category");
        if (by == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (file.count() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Attach exactly one document");
        }
        if (file instanceof IncomingFiles.Uploaded uploaded) {
            // The same upload again - also after a commit whose outcome the first request never learned - answers with
            // the document it made: the key is the upload's own, so a row naming it is that document.
            Optional<String> key = staged.keyOf(FileCategory.DOCUMENT, by, uploaded.uploadIds().get(0));
            Optional<AttachmentView> earlier = key.flatMap(k -> tx.execute(status -> {
                requireOrder(purchaseOrderId);
                return attachments.findByStorageKey(k).filter(found -> found.getPurchaseOrderId().equals(purchaseOrderId))
                        .map(PurchaseOrderAttachmentService::view);
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> requireOrder(purchaseOrderId));
        try (PreparedFiles prepared = file.prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                requireOrder(purchaseOrderId);
                var saved = attachments.saveAndFlush(new PurchaseOrderAttachmentJpaEntity(Identifiers.newId(),
                        purchaseOrderId, stored.originalName(), stored.key(), extensionOf(stored.key()), category,
                        stored.sizeBytes(), by));
                prepared.adopt();
                return view(saved);
            });
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> list(UUID purchaseOrderId) {
        requireOrder(purchaseOrderId);
        return attachments.findByPurchaseOrderIdOrderByIdAsc(purchaseOrderId).stream()
                .map(PurchaseOrderAttachmentService::view).toList();
    }

    /** A link saving the document under its name; audited, as a supplier's document leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "purchase-order-attachment", resourceId = "#attachmentId",
            details = "{purchaseOrderId: #purchaseOrderId}")
    public DownloadLink downloadLink(UUID purchaseOrderId, UUID attachmentId) {
        requireOrder(purchaseOrderId);
        var attachment = attachments.findByIdAndPurchaseOrderId(attachmentId, purchaseOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No attachment " + attachmentId));
        return files.downloadLink(attachment.getStorageKey(), attachment.getFileName());
    }

    /** The order exists and is of a warehouse the caller may act in (BR-SEC-002), as for every other PO read or write. */
    private void requireOrder(UUID purchaseOrderId) {
        UUID warehouse = purchaseOrderId == null ? null : attachments.warehouseOf(purchaseOrderId).orElse(null);
        if (warehouse == null) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + purchaseOrderId);
        }
        WarehouseScope.requireWarehouse(warehouse);
    }

    /** {@code pdf}, {@code docx}, ...: the extension the storage key was given from the accepted type. */
    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? null : key.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static AttachmentView view(PurchaseOrderAttachmentJpaEntity a) {
        return new AttachmentView(a.getId(), a.getPurchaseOrderId(), a.getFileName(), a.getFileType(), a.getCategory(),
                a.getFileSize(), a.getUploadedBy(), a.getUploadedAt());
    }
}
