package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sales picking a customer's confirmed design for a quote or a sample round (SCRUM-448/403).
 *
 * <p>Drafts stay private to their owner, designer and reviewer ({@code DesignDraftJpaRepository.findForUser}): they
 * are work in progress. A <em>confirmed</em> snapshot is different - it is the immutable thing a quote, a sample and
 * an order of that customer point at, and {@code DesignService.verifySnapshot} already accepts any of them for the
 * customer named. This lists exactly those, for one customer the caller names, to back-office holders of
 * {@code design-snapshots:READ}. A customer holds that permission too and is refused here (403): the list is about
 * the customer named in the request, not the caller.</p>
 */
@Service
@Transactional(readOnly = true)
public class DesignSnapshotQueryService {

    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDraftJpaRepository drafts;
    private final CustomerService customers;
    private final ProductService products;
    private final DesignService designs;

    DesignSnapshotQueryService(DesignSnapshotJpaRepository snapshots, DesignDraftJpaRepository drafts,
                          CustomerService customers, ProductService products, DesignService designs) {
        this.snapshots = snapshots;
        this.drafts = drafts;
        this.customers = customers;
        this.products = products;
        this.designs = designs;
    }

    /** One confirmed snapshot as Sales sees it: no storage key, no link. */
    public record Summary(UUID snapshotId, UUID draftId, String designName, UUID customerId, UUID productId,
                          String checksum, String spec, Instant confirmedAt, UUID confirmedBy,
                          List<Artifact> artifacts) {
    }

    public record Artifact(UUID artifactId, DesignArtifactRole role, String originalName, String contentType,
                           long sizeBytes, String checksum) {
    }

    /** A short-lived link to the snapshot's CUSTOMER_PREVIEW, verified against the snapshot's checksum first. */
    public record Preview(UUID snapshotId, UUID artifactId, String originalName, String contentType, long sizeBytes,
                          String checksum, String url, Instant expiresAt) {
    }

    /**
     * @param productId only designs of this product; null for any
     * @param sku       only designs of a product this SKU belongs to (a quote line or a sample names its blank SKU);
     *                  null for any
     */
    public Page<Summary> confirmedOf(CurrentUser user, UUID customerId, UUID productId, String sku, Pageable pageable) {
        ScopeGuards.requireBackOffice(user);
        requireCustomer(customerId);
        Page<DesignSnapshotJpaEntity> page;
        if (productId == null && (sku == null || sku.isBlank())) {
            page = snapshots.findConfirmedOfCustomer(customerId, pageable);
        } else {
            List<UUID> candidates = productId != null ? List.of(productId)
                    : snapshots.confirmedProductsOfCustomer(customerId);
            List<UUID> matching = sku == null || sku.isBlank() ? candidates
                    : candidates.stream().filter(product -> products.containsSku(product, sku.trim())).toList();
            if (matching.isEmpty()) {
                return Page.empty(pageable);
            }
            page = snapshots.findConfirmedOfCustomer(customerId, matching, pageable);
        }
        Map<UUID, DesignDraftJpaEntity> owners = drafts.findAllById(page.getContent().stream()
                        .map(DesignSnapshotJpaEntity::getDraftId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DesignDraftJpaEntity::getId, Function.identity()));
        return page.map(snapshot -> summary(snapshot, owners.get(snapshot.getDraftId())));
    }

    /**
     * The picture the customer confirmed, for a snapshot of the customer named: a snapshot of another customer is
     * 404, as an unknown one is, so the id of a design never confirms that some other customer has it.
     */
    public Preview preview(CurrentUser user, UUID snapshotId, UUID customerId) {
        ScopeGuards.requireBackOffice(user);
        DesignSnapshotJpaEntity snapshot = snapshots.findById(Objects.requireNonNull(snapshotId, "snapshotId"))
                .filter(found -> found.getConfirmedBy() != null && found.getArtifactId() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No confirmed design snapshot " + snapshotId));
        DesignDraftJpaEntity draft = drafts.findById(snapshot.getDraftId()).orElseThrow();
        if (customerId == null || !customerId.equals(draft.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No confirmed design snapshot " + snapshotId);
        }
        FulfillmentArtifact file = designs.fulfillmentDownload(snapshot.getId(), snapshot.getChecksum(),
                DesignArtifactRole.CUSTOMER_PREVIEW);
        return new Preview(snapshot.getId(), file.artifactId(), file.originalName(), file.contentType(),
                file.sizeBytes(), file.checksum(), file.url(), file.expiresAt());
    }

    private void requireCustomer(UUID customerId) {
        if (customerId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "customerId is required");
        }
        if (customers.findById(customerId).isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + customerId);
        }
    }

    private static Summary summary(DesignSnapshotJpaEntity snapshot, DesignDraftJpaEntity draft) {
        return new Summary(snapshot.getId(), snapshot.getDraftId(), draft.getName(), draft.getCustomerId(),
                draft.getProductId(), snapshot.getChecksum(), snapshot.getSpec(), snapshot.getConfirmedAt(),
                snapshot.getConfirmedBy(), snapshot.getArtifactManifest().stream()
                        .map(item -> new Artifact(item.artifactId(), item.role(), item.originalName(),
                                item.contentType(), item.sizeBytes(), item.checksum()))
                        .toList());
    }
}
