package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.ReturnTransition;
import com.stockflow.order.internal.repository.ReturnRequestSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reading returns: the back-office queue, a customer's own list, one return with its history, and a photo's link.
 * A list is a projection of the header (one query a page); the detail is the aggregate.
 */
@Service
@Transactional(readOnly = true)
public class ReturnRequestQueries {

    private static final SortWhitelist SORT = SortWhitelist.of("requestedAt", "status", "rmaNumber", "returnDeadline",
            "closedAt").withDefault("requestedAt", Sort.Direction.DESC);

    /** A photo link lives minutes, not days: anyone holding it can fetch the file until it expires. */
    private static final Duration LINK_LIFETIME = Duration.ofMinutes(5);

    /** One return as a list shows it. */
    public record Item(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                       ReturnStatus status, ReturnReason reason, ReturnResolution requestedResolution, String channel,
                       Instant requestedAt, Instant returnDeadline, BigDecimal settlementAmount,
                       BigDecimal refundedAmount, BigDecimal creditedAmount, BigDecimal offsetAmount, String currency,
                       boolean vatAdjustmentRequired, UUID replacementOrderId, Instant closedAt, long version) {
    }

    /** One return with its status history, oldest first. */
    public record Detail(ReturnRequest request, List<ReturnTransition> history) {
    }

    private final ReturnRequestRepository returns;
    private final ReturnRequestSearch search;
    private final FileStorage storage;

    ReturnRequestQueries(ReturnRequestRepository returns, ReturnRequestSearch search, FileStorage storage) {
        this.returns = returns;
        this.search = search;
        this.storage = storage;
    }

    /** Every customer's returns, for the back office; every filter null means any. */
    public PageResponse<Item> list(List<ReturnStatus> statuses, UUID customerId, UUID orderId, String text,
                                   Boolean vatAdjustment, int page, int size, String sort) {
        return Pages.toResponse(search.search(new ReturnRequestSearch.Criteria(statuses, customerId, orderId, text,
                vatAdjustment), Pages.of(page, size, SORT.parse(sort))).map(ReturnRequestQueries::toItem));
    }

    /** One customer's own returns, newest first: the customer is the caller's profile, never a parameter. */
    public PageResponse<Item> mine(UUID customerId, List<ReturnStatus> statuses, UUID orderId, int page, int size,
                                   String sort) {
        return Pages.toResponse(search.search(new ReturnRequestSearch.Criteria(statuses, customerId, orderId, null,
                null), Pages.of(page, size, SORT.parse(sort))).map(ReturnRequestQueries::toItem));
    }

    /**
     * One return with its history; {@code ownCustomerId} set: only the customer's own (another's, or none, is 404
     * either way, so the existence of someone else's return is not revealed).
     */
    public Detail detail(UUID returnId, UUID ownCustomerId) {
        ReturnRequest request = returns.findById(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        return new Detail(request, returns.history(returnId));
    }

    /** A short presigned link to one photo of the return, for the owner or the back office. */
    public String evidenceLink(UUID returnId, UUID evidenceId, UUID ownCustomerId) {
        ReturnRequest request = returns.findById(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        ReturnEvidence photo = request.evidence().stream().filter(e -> e.id().equals(evidenceId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No photo " + evidenceId));
        return storage.presignedDownloadUrl(photo.fileKey(), LINK_LIFETIME);
    }

    private static Item toItem(ReturnRequestSearch.ReturnRequestRow row) {
        return new Item(row.id(), row.rmaNumber(), row.orderId(), row.orderNumber(), row.customerId(), row.status(),
                row.reason(), row.requestedResolution(), row.channel(), row.requestedAt(), row.returnDeadline(),
                row.settlementAmount(), row.refundedAmount(), row.creditedAmount(), row.offsetAmount(),
                row.currency(), row.vatAdjustmentRequired(), row.replacementOrderId(), row.closedAt(),
                row.version());
    }

    private static BusinessException notFound(UUID returnId) {
        return new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND, "No return request " + returnId);
    }
}
