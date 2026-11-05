package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.api.ReturnQueries;
import com.stockflow.order.internal.domain.ReturnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ReturnQueries} over {@link ReturnRequestQueries#list}, the very list the returns screen reads, so its
 * warehouse rule is applied there, once, for the screen and for this alike. What a row of that list also carries -
 * the ids of the order and of a replacement order, the offset amount, the VAT flag and the version - stays here. The
 * screen's back-office check is made here too, so no caller can forget it.
 */
@Service
@Transactional(readOnly = true)
public class ReturnQueriesImpl implements ReturnQueries {

    /** The nearest deadline first; PostgreSQL puts the returns without one last in an ascending order. */
    private static final String DEADLINE_FIRST = "returnDeadline,asc;requestedAt,desc";

    private final ReturnRequestQueries returns;
    private final CurrentUserProvider currentUsers;

    ReturnQueriesImpl(ReturnRequestQueries returns, CurrentUserProvider currentUsers) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(ReturnStatus.values()).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("ReturnQueries.STATUSES no longer matches ReturnStatus");
        }
        if (!Set.copyOf(OPEN_STATUSES).equals(Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen)
                .map(Enum::name).collect(Collectors.toSet()))) {
            throw new IllegalStateException("ReturnQueries.OPEN_STATUSES no longer matches ReturnStatus.isOpen");
        }
        this.returns = returns;
        this.currentUsers = currentUsers;
    }

    @Override
    public PageResponse<ReturnSummary> returns(List<String> statuses, UUID customerId, String number,
                                               boolean deadlineFirst, int limit) {
        // A portal customer holds sales-rmas:READ for their own returns; this list is every customer's.
        currentUsers.current().ifPresent(ScopeGuards::requireBackOffice);
        if (limit < 1 || limit > MAX_RETURNS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_RETURNS + ", not " + limit);
        }
        List<ReturnStatus> wanted = statuses == null ? List.of()
                : statuses.stream().map(ReturnQueriesImpl::status).toList();
        String text = number == null || number.isBlank() ? null : number.strip();
        return returns.list(wanted, customerId, null, text, null, 0, limit, deadlineFirst ? DEADLINE_FIRST : null)
                .map(item -> new ReturnSummary(item.rmaNumber(), item.orderNumber(), item.customerId(),
                        item.status().name(), item.reason() == null ? null : item.reason().name(),
                        item.requestedResolution() == null ? null : item.requestedResolution().name(),
                        item.channel(), item.requestedAt(), item.returnDeadline(), item.settlementAmount(),
                        item.refundedAmount(), item.creditedAmount(), item.currency(), item.closedAt()));
    }

    private static ReturnStatus status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A return status is one of " + STATUSES + ", not " + name);
        }
        return ReturnStatus.valueOf(name);
    }
}
