package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.repository.CancellationRequestSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Deciding a customer's cancellation request (SCRUM-460, kltn-docs 17 §2, §4.4): approving cancels
 * the order in the same transaction, with any fee kept for work already done; rejecting tells the
 * customer why. Back-office only, reached from {@code CancellationRequestController}.
 */
@Service
@Transactional
public class CancellationRequestService {

    private static final Logger log = LoggerFactory.getLogger(CancellationRequestService.class);

    private static final SortWhitelist SORT = SortWhitelist.of("requestedAt", "status")
            .withDefault("requestedAt", Sort.Direction.DESC);

    private final CancellationRequestRepository requests;
    private final CancellationRequestSearch search;
    private final OrderServiceImpl orders;
    private final OrderService orderService;
    private final Clock clock;

    CancellationRequestService(CancellationRequestRepository requests, CancellationRequestSearch search,
                               OrderServiceImpl orders, OrderService orderService, Clock clock) {
        this.requests = requests;
        this.search = search;
        this.orders = orders;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CancellationRequest> list(CancellationRequestStatus status, UUID orderId, int page, int size,
                                                  String sort) {
        return Pages.toResponse(search.search(status, orderId, Pages.of(page, size, SORT.parse(sort))));
    }

    /**
     * @throws BusinessException {@code ORDER_NOT_CANCELLABLE} when the order shipped in the meantime;
     *         {@code ORDER_CANCELLATION_REQUEST_NOT_PENDING} when it was already decided
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "order-cancellation-request", resourceId = "#requestId")
    public OrderSummary approve(UUID orderId, UUID requestId, BigDecimal retainedPercent, String note, UUID by) {
        CancellationRequest request = load(orderId, requestId);
        request.approve(by, retainedPercent, note, clock.instant());
        Order order = orders.loadForUpdate(orderId);
        orders.cancelLoaded(order, new CancelOrderCommand(request.reasonCode(), request.note(), retainedPercent, by));
        requests.save(request);
        log.info("Cancellation request {} approved; order {} cancelled", requestId, order.orderNumber());
        return orderService.findById(orderId).orElseThrow();
    }

    @Auditable(action = AuditAction.REJECT, resourceType = "order-cancellation-request", resourceId = "#requestId")
    public CancellationRequest reject(UUID orderId, UUID requestId, String note, UUID by) {
        CancellationRequest request = load(orderId, requestId);
        request.reject(by, note, clock.instant());
        log.info("Cancellation request {} rejected", requestId);
        return requests.save(request);
    }

    private CancellationRequest load(UUID orderId, UUID requestId) {
        return requests.findById(requestId)
                .filter(request -> request.orderId().equals(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CANCELLATION_REQUEST_NOT_FOUND,
                        "No cancellation request " + requestId + " on order " + orderId));
    }
}
