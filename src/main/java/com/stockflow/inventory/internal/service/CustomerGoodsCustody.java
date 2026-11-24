package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.CustodyInstruction;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Goods held for a customer whose return refunded nothing (WF-05 Q-4, TH-2 step 6, TH-6): the warehouse manager's list
 * of them, and the end of their custody as the customer chose - "Sale hỏi khách, ghi lại một trong hai: gửi trả (khách
 * chịu phí), hoặc khách đồng ý để công ty huỷ hộ (biên bản). Quản lý kho thực hiện từ danh sách khu cách ly."
 *
 * <p>The carton is already out of the books ({@link CartonReturnService#settleOwnership}); ending its custody moves no
 * stock and writes no ledger line. It records who handed it back or destroyed it, when, and the record of it (the
 * hand-over note, or the destruction record the customer agreed to) - on the customer's choice as Sales recorded it,
 * never on another: the goods are the customer's, and only they decide.</p>
 */
@Service
@Transactional
public class CustomerGoodsCustody {

    private static final Logger log = LoggerFactory.getLogger(CustomerGoodsCustody.class);

    /** As long as the other notes of a carton: the reference of a paper record, not the record itself. */
    private static final int NOTE_LIMIT = 500;

    private final ReturnedCartonOwnership ownership;
    private final StockPackageRepository stockPackages;
    private final Clock clock;

    public CustomerGoodsCustody(ReturnedCartonOwnership ownership, StockPackageRepository stockPackages, Clock clock) {
        this.ownership = ownership;
        this.stockPackages = stockPackages;
        this.clock = clock;
    }

    /**
     * The cartons held for customers in the caller's warehouses, oldest first - or, with {@code ended}, those handed back
     * or destroyed; of one return when {@code returnRequestId} is named.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReturnedCartonOwnership.Custody> held(UUID returnRequestId, boolean ended, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Collection<String> prefixes = WarehouseScope.restriction().map(AssignedWarehouses::prefixes)
                .map(codes -> (Collection<String>) codes).orElse(null);
        long total = ownership.countHeld(prefixes, returnRequestId, ended);
        List<ReturnedCartonOwnership.Custody> items = total == 0 ? List.of()
                : ownership.held(prefixes, returnRequestId, ended, safePage * safeSize, safeSize);
        return PageResponse.of(items, safePage, safeSize, total);
    }

    /**
     * Custody of carton {@code lpn} ends as the customer chose: handed back to them ({@code SEND_BACK}) or destroyed on
     * their behalf ({@code DESTROY_ON_BEHALF}), with {@code note} naming the record (biên bản bàn giao / biên bản huỷ).
     *
     * @throws BusinessException {@code STOCK_PACKAGE_NOT_FOUND} - also for a carton nobody holds for a customer;
     *         403 outside the caller's warehouses; {@code VALIDATION_FAILED} with no note;
     *         {@code STOCK_PACKAGE_DECISION_PENDING} while Sales have not recorded the customer's choice;
     *         {@code INVALID_STOCK_PACKAGE_TRANSITION} once its custody ended
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-package-custody", resourceId = "#lpn",
            details = "{note: #note}")
    public ReturnedCartonOwnership.Custody end(String lpn, String note, UUID by) {
        String normal = StockPackageView.normalLpn(lpn);
        StockPackage carton = (normal == null ? java.util.Optional.<StockPackage>empty()
                : stockPackages.findByLpn(normal)).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + normal));
        // BR-SEC-002 first: nothing is said about another warehouse's carton.
        WarehouseScope.requireLocation(carton.location().code());
        String record = note == null ? null : note.strip();
        if (record == null || record.isEmpty() || record.length() > NOTE_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the record of the hand-over or destruction",
                    List.of(FieldError.of("note", "1 to %d characters".formatted(NOTE_LIMIT))));
        }
        stockPackages.findAllByIdForUpdate(List.of(carton.id()));
        ReturnedCartonOwnership.Custody custody = ownership.custody(carton.id()).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, ("%s is not held for a customer")
                        .formatted(carton.lpn()), List.of(), Map.of("lpn", carton.lpn())));
        if (custody.ownership() != ReturnedCartonOwnership.Ownership.CUSTOMER_HELD) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, "%s was already %s"
                    .formatted(carton.lpn(), custody.ownership()), List.of(), Map.of("lpn", carton.lpn()));
        }
        if (custody.choice() == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING, ("Sales have not recorded what the "
                    + "customer chose for %s: hand it back or destroy it only as they chose").formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn(), "returnRequestId", custody.returnRequestId()));
        }
        ReturnedCartonOwnership.Ownership ended = CustodyInstruction.valueOf(custody.choice())
                == CustodyInstruction.SEND_BACK ? ReturnedCartonOwnership.Ownership.CUSTOMER_SENT_BACK
                : ReturnedCartonOwnership.Ownership.CUSTOMER_DESTROYED;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ownership.end(carton.id(), ended, by, now, record);
        log.info("Carton {} held for the customer of return {}: {} by {}", carton.lpn(), custody.returnRequestId(),
                ended, by);
        return ownership.custody(carton.id()).orElseThrow();
    }
}
