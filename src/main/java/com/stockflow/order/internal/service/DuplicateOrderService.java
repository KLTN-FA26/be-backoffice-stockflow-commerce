package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link DuplicateOrders}. The window is {@code stockflow.order.duplicate-window} (an ISO-8601 duration). The docs ask
 * for a "time window" without saying how long, so 24 hours is a default pending the owner's decision; zero switches the
 * check off.
 */
@Service
@Transactional(readOnly = true)
class DuplicateOrderService implements DuplicateOrders {

    private final OrderRepository repository;
    private final Clock clock;
    private final Duration window;

    DuplicateOrderService(OrderRepository repository, Clock clock,
                          @Value("${stockflow.order.duplicate-window:PT24H}") Duration window) {
        if (window.isNegative()) {
            throw new IllegalArgumentException("stockflow.order.duplicate-window must not be negative");
        }
        this.repository = repository;
        this.clock = clock;
        this.window = window;
    }

    @Override
    public Optional<Earlier> findSuspected(UUID customerId, UUID requestId, List<Line> lines) {
        if (window.isZero() || repository.findByRequestId(requestId).isPresent()) {
            return Optional.empty();
        }
        Map<String, Integer> wanted = content(lines.stream()
                .map(line -> new Key(line.sku(), line.designSnapshotId(), line.quantity())).toList());
        // Newest first, so the order the customer is told about is the latest one.
        return repository.findLiveSince(customerId, clock.instant().minus(window)).stream()
                .filter(order -> content(order.lines().stream().map(DuplicateOrderService::key).toList())
                        .equals(wanted))
                .findFirst()
                .map(DuplicateOrderService::earlier);
    }

    private record Key(String sku, UUID designSnapshotId, int quantity) {
    }

    private static Key key(OrderLine line) {
        return new Key(line.sku().code(), line.designSnapshotId(), line.quantity());
    }

    private static Earlier earlier(Order order) {
        return new Earlier(order.id().value(), order.orderNumber().value(), order.placedAt());
    }

    /** Total quantity per SKU and design; a sorted map so equal content is equal whatever the order of the lines. */
    static Map<String, Integer> content(List<Key> keys) {
        Map<String, Integer> totals = new TreeMap<>();
        for (Key key : keys) {
            String design = key.designSnapshotId() == null ? "" : key.designSnapshotId().toString();
            totals.merge(key.sku().toUpperCase(Locale.ROOT) + "|" + design, key.quantity(), Integer::sum);
        }
        return totals;
    }
}
