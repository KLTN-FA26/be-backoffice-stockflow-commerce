package com.stockflow.payment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.payment.internal.domain.CreditRequest;
import com.stockflow.payment.internal.domain.CreditRequests;
import com.stockflow.payment.internal.entity.CreditRequestFileJpaEntity;
import com.stockflow.payment.internal.repository.CreditRequestFileJpaRepository;
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
 * The documents kept with a credit request - the business registration, financial statements - in the common
 * storage's {@code documents} category (PDF, Word, Excel, JPEG, PNG; 25 MB). Added while the request is open, by the
 * customer to their own (portal) or by Sales; read by whoever may read the request.
 *
 * <p>As a shipment's proofs of delivery are ({@code ShipmentPodFiles}): checked, scanned and stored with no transaction
 * held, then recorded in a short one if the request is still open. An upload named again answers with the file it
 * made.</p>
 */
@Service
@Transactional
public class CreditRequestFiles {

    /** More documents than any credit application needs; a request is not a document store. */
    public static final int MAX_FILES = 10;

    /**
     * A file as a request lists it; the bytes come from {@link #downloadLink}.
     *
     * @param uploadedBy the account that uploaded it: the customer's portal account, or staff
     */
    public record CreditFile(UUID id, UUID creditRequestId, String fileName, String contentType, long sizeBytes,
                             UUID uploadedBy, Instant uploadedAt) {
    }

    private final CreditRequests requests;
    private final CreditRequestFileJpaRepository files;
    private final FileTransfers transfers;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final Clock clock;

    public CreditRequestFiles(CreditRequests requests, CreditRequestFileJpaRepository files, FileTransfers transfers,
                              StagedUploads staged, TransactionOperations tx, Clock clock) {
        this.requests = requests;
        this.files = files;
        this.transfers = transfers;
        this.staged = staged;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * A slot to upload a document of this request straight to the object store.
     *
     * @param ownCustomerId the caller's customer when a portal customer; null for the back office
     */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID creditRequestId, UUID ownCustomerId, UploadSlotRequest request, UUID by) {
        requireAttachable(creditRequestId, ownCustomerId);
        return staged.begin(FileCategory.DOCUMENT, by, request);
    }

    /**
     * Keeps one file with the request. Not transactional itself: the file is prepared first.
     *
     * @throws BusinessException {@code CREDIT_REQUEST_NOT_FOUND} (for a customer: not theirs);
     *         {@code CREDIT_REQUEST_INVALID_TRANSITION} once it is decided or withdrawn; {@code VALIDATION_FAILED}
     *         unless exactly one file, or past {@value #MAX_FILES}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "credit-request-file", resourceId = "#result?.id()",
            details = "{creditRequestId: #creditRequestId}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreditFile attach(UUID creditRequestId, UUID ownCustomerId, IncomingFiles file, UUID by) {
        if (by == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (file.count() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Attach exactly one document");
        }
        if (file instanceof IncomingFiles.Uploaded uploaded) {
            Optional<String> key = staged.keyOf(FileCategory.DOCUMENT, by, uploaded.uploadIds().get(0));
            Optional<CreditFile> earlier = key.flatMap(k -> tx.execute(status -> {
                requireVisible(creditRequestId, ownCustomerId);
                return files.findByStorageKeyAndCreditRequestId(k, creditRequestId).map(CreditRequestFiles::view);
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> requireAttachable(creditRequestId, ownCustomerId));
        try (PreparedFiles prepared = file.prepare(FileCategory.DOCUMENT, transfers, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                requireAttachable(creditRequestId, ownCustomerId);
                CreditRequestFileJpaEntity saved = files.saveAndFlush(new CreditRequestFileJpaEntity(
                        Identifiers.newId(), creditRequestId, stored.key(), stored.originalName(),
                        stored.contentType(), stored.sizeBytes(), by, clock.instant()));
                prepared.adopt();
                return view(saved);
            });
        }
    }

    /** The request's documents, oldest first. The caller has already read the request. */
    @Transactional(readOnly = true)
    public List<CreditFile> list(UUID creditRequestId) {
        return files.findByCreditRequestIdOrderByUploadedAtAscIdAsc(creditRequestId).stream()
                .map(CreditRequestFiles::view).toList();
    }

    /** A link saving the file under its name; audited, as a customer's financial statement leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "credit-request-file", resourceId = "#fileId",
            details = "{creditRequestId: #creditRequestId}")
    public DownloadLink downloadLink(UUID creditRequestId, UUID fileId, UUID ownCustomerId) {
        requireVisible(creditRequestId, ownCustomerId);
        CreditRequestFileJpaEntity found = files.findByIdAndCreditRequestId(fileId, creditRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No document " + fileId));
        return transfers.downloadLink(found.getStorageKey(), found.getFileName());
    }

    private CreditRequest requireVisible(UUID creditRequestId, UUID ownCustomerId) {
        return requests.find(creditRequestId)
                .filter(r -> ownCustomerId == null || ownCustomerId.equals(r.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT_REQUEST_NOT_FOUND,
                        "No credit request " + creditRequestId));
    }

    private void requireAttachable(UUID creditRequestId, UUID ownCustomerId) {
        CreditRequest request = requireVisible(creditRequestId, ownCustomerId);
        if (!request.status().open()) {
            throw new BusinessException(ErrorCode.CREDIT_REQUEST_INVALID_TRANSITION, ("Credit request %s is %s: "
                    + "documents are added while it is open").formatted(creditRequestId, request.status()));
        }
        if (files.countByCreditRequestId(creditRequestId) >= MAX_FILES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A credit request keeps at most %d documents".formatted(MAX_FILES));
        }
    }

    private static CreditFile view(CreditRequestFileJpaEntity f) {
        return new CreditFile(f.getId(), f.getCreditRequestId(), f.getFileName(), f.getContentType(),
                f.getSizeBytes(), f.getUploadedBy(), f.getUploadedAt());
    }
}
