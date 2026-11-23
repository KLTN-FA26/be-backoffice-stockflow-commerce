package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.internal.domain.CustodyNoteRow;
import com.stockflow.order.internal.domain.CustodyOutcome;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnCustodyNoteChoice;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.repository.ReturnCustodyNoteSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Goods kept on custody notes and how they end (WF-05 Q-11 hỏi thêm, TH-6; audit round 3, RAD-3): goods that came back
 * and that no receipt takes - another SKU, more than approved, a parcel after the refund was settled - are the
 * customer's, kept for them, never stock. "Sale chọn: trả lại khách / mở yêu cầu bổ sung / khách đồng ý huỷ hộ (biên
 * bản)": Sales record that choice on the note ({@link #choose}), the warehouse manager records that the goods left as
 * chosen ({@link #end}), from the list of what the warehouse keeps for customers ({@link #kept}).
 *
 * <p>Before, a custody note had no ending at all: the custody choice on the return was only for cartons a refused
 * return reversed, and the custody list only listed those cartons. The goods sat in the warehouse with a photo and no
 * recorded outcome.</p>
 *
 * <p>Locks the return's order, then the return, as every return step does. BR-SEC-002: a caller limited to warehouses
 * acts on and lists the notes of their warehouses' orders only ({@link ReturnWarehouseScope}).</p>
 */
@Service
@Transactional
public class ReturnCustodyNotes {

    private static final Logger log = LoggerFactory.getLogger(ReturnCustodyNotes.class);

    private final ReturnRequestRepository returns;
    private final OrderRepository orders;
    private final ReturnCustodyNoteSearch search;
    private final ReturnWarehouseScope warehouseScope;
    private final Clock clock;

    ReturnCustodyNotes(ReturnRequestRepository returns, OrderRepository orders, ReturnCustodyNoteSearch search,
                       ReturnWarehouseScope warehouseScope, Clock clock) {
        this.returns = returns;
        this.orders = orders;
        this.search = search;
        this.warehouseScope = warehouseScope;
        this.clock = clock;
    }

    /**
     * Sales record what the customer chose for the goods of custody note {@code evidenceId} of return {@code returnId}:
     * SEND_BACK, DESTROY_ON_BEHALF ({@code note} names their written consent) or TAKEN_BY_RETURN on the supplementary
     * return {@code takenByReturnId} of the same customer. Recorded again, it replaces the choice while the goods are
     * kept.
     *
     * @throws BusinessException {@code RETURN_REQUEST_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}; as
     *         {@link ReturnRequest#chooseForCustody} - {@code RETURN_REQUEST_NOT_FOUND} also for a supplementary return
     *         that does not exist
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-custody-note", resourceId = "#evidenceId",
            details = "{returnId: #returnId, choice: #choice, takenBy: #takenByReturnId}")
    public ReturnRequest choose(UUID returnId, UUID evidenceId, ReturnCustodyNoteChoice choice, String note,
                                UUID takenByReturnId, UUID by) {
        ReturnRequest request = locked(returnId);
        ReturnRequest takenBy = takenByReturnId == null ? null : returns.findById(takenByReturnId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND,
                        "No return request " + takenByReturnId));
        request.chooseForCustody(evidenceId, choice, note, takenBy, by, now());
        log.info("Return {}: custody note {} - the customer chose {}{}", request.rmaNumber(), evidenceId, choice,
                takenBy == null ? "" : " on " + takenBy.rmaNumber());
        return returns.save(request);
    }

    /**
     * The warehouse manager records that the goods of custody note {@code evidenceId} left as the customer chose, with
     * the record of it in {@code note}: the hand-over note, the destruction record, the receipt of the supplementary
     * return that took them.
     *
     * @throws BusinessException {@code RETURN_REQUEST_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}; as
     *         {@link ReturnRequest#endCustody}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-custody-note", resourceId = "#evidenceId",
            details = "{returnId: #returnId, note: #note}")
    public CustodyOutcome end(UUID returnId, UUID evidenceId, String note, UUID by) {
        ReturnRequest request = locked(returnId);
        ReturnStatus takenByStatus = request.custodyOutcome(evidenceId).map(CustodyOutcome::takenByReturnId)
                .flatMap(returns::findById).map(ReturnRequest::status).orElse(null);
        CustodyOutcome ended = request.endCustody(evidenceId, note, takenByStatus, by, now());
        returns.save(request);
        log.info("Return {}: the goods of custody note {} left - {}", request.rmaNumber(), evidenceId,
                ended.choice());
        return ended;
    }

    /**
     * The custody notes whose goods the warehouse still keeps, oldest first - or, with {@code ended}, those that left;
     * of one return when {@code returnRequestId} is named; in the caller's warehouses.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustodyNoteRow> kept(UUID returnRequestId, boolean ended, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Set<UUID> limit = warehouseScope.limit();
        long total = search.count(ended, returnRequestId, limit);
        List<CustodyNoteRow> items = total == 0 ? List.of()
                : search.page(ended, returnRequestId, limit, safePage * safeSize, safeSize);
        return PageResponse.of(items, safePage, safeSize, total);
    }

    /** The return, its order locked first and then the return itself; refused outside the caller's warehouses. */
    private ReturnRequest locked(UUID returnId) {
        UUID orderId = returns.orderOf(returnId).orElseThrow(() -> notFound(returnId));
        warehouseScope.require(orderId, null);
        orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow();
        return returns.findByIdForUpdate(returnId).orElseThrow(() -> notFound(returnId));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException notFound(UUID returnId) {
        return new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND, "No return request " + returnId);
    }
}
