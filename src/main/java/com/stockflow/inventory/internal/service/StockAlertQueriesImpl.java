package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.ExpiryAlertView;
import com.stockflow.inventory.api.StockAlertQueries;
import com.stockflow.inventory.internal.domain.StockAlert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@link StockAlertQueries} over {@link StockAlerts}, which applies the caller's warehouse limit; this class only
 * picks the expiry kinds and orders by expiry date.
 *
 * <p>The alert list sorts by when an alert opened, not by expiry, so every open expiry alert is read (in pages of
 * {@link Pages#MAX_PAGE_SIZE}) and sorted here. That is bounded by the stock the business holds, not by history:
 * resolved alerts are excluded in the query.</p>
 */
@Service
@Transactional(readOnly = true)
class StockAlertQueriesImpl implements StockAlertQueries {

    private static final Set<StockAlert.Kind> EXPIRY = Set.of(StockAlert.Kind.EXPIRING, StockAlert.Kind.EXPIRED);
    private static final Set<StockAlert.Status> UNRESOLVED =
            Set.of(StockAlert.Status.OPEN, StockAlert.Status.ACKNOWLEDGED);

    private final StockAlerts alerts;

    StockAlertQueriesImpl(StockAlerts alerts) {
        this.alerts = alerts;
    }

    @Override
    public ExpiryAlerts openExpiryAlerts(int withinDays, int limit) {
        if (limit < 1 || limit > MAX_ALERTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_ALERTS);
        }
        if (withinDays < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "withinDays must not be negative");
        }
        List<StockAlerts.AlertView> open = new ArrayList<>();
        PageResponse<StockAlerts.AlertView> page;
        int number = 0;
        do {
            page = alerts.list(EXPIRY, UNRESOLVED, null, null, number++, Pages.MAX_PAGE_SIZE, null);
            open.addAll(page.items());
        } while (page.hasNext());
        List<ExpiryAlertView> sorted = open.stream()
                .sorted(Comparator.comparing(StockAlerts.AlertView::expiryDate,
                                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(StockAlerts.AlertView::sku))
                .map(alert -> new ExpiryAlertView(alert.sku(), alert.warehouseCode(), alert.lotNumber(),
                        alert.expiryDate(), alert.daysToExpiry(), alert.observedQty(),
                        alert.kind() == StockAlert.Kind.EXPIRED, alert.status() == StockAlert.Status.ACKNOWLEDGED,
                        alert.lastObservedAt(), alert.reservationImpaired()))
                .toList();
        List<ExpiryAlertView> expiring = sorted.stream()
                .filter(alert -> !alert.expired()
                        && (alert.daysToExpiry() == null || alert.daysToExpiry() <= withinDays))
                .toList();
        List<ExpiryAlertView> expired = sorted.stream().filter(ExpiryAlertView::expired).toList();
        return new ExpiryAlerts(expiring.stream().limit(limit).toList(), expiring.size(),
                expired.stream().limit(limit).toList(), expired.size());
    }
}
