package com.stockflow.common.persistence;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a client-supplied sort string into a {@link Sort} restricted to properties this endpoint
 * has agreed to sort by.
 *
 * <h2>Why an allow-list rather than passing the string through</h2>
 *
 * <p>Spring Data will happily bind {@code ?sort=customer.address.postcode,desc} straight from the
 * query string. Three things go wrong when it does:</p>
 * <ul>
 *   <li><b>Silent table scans.</b> Sorting by an unindexed column on a large table turns a
 *       10 ms query into a 10 s one. The endpoint still works, so nobody notices until the table
 *       grows — and then the cause is a URL parameter, not any code that was changed.</li>
 *   <li><b>Traversal into other tables.</b> A dotted path makes Spring Data join, which can reach
 *       a column the caller has no business ordering by — and, through the ordering, inferring.</li>
 *   <li><b>500s from typos.</b> An unknown property is a {@code PropertyReferenceException}, which
 *       reads as a server fault when it is really a bad request.</li>
 * </ul>
 *
 * <p>Declaring the allowed set next to the endpoint turns all three into a clear 400, and makes
 * "which columns need an index" a question with a written answer.</p>
 *
 * <h2>Using it</h2>
 * <pre>
 * private static final SortWhitelist SORT =
 *         SortWhitelist.of("placedAt", "orderNumber", "status").withDefault("placedAt", DESC);
 *
 * &#64;GetMapping
 * ApiResponse&lt;PageResponse&lt;OrderResponse&gt;&gt; list(
 *         &#64;RequestParam(required = false) String sort, ...) {
 *     Pageable page = Pages.of(pageNumber, pageSize, SORT.parse(sort));
 *     ...
 * }
 * </pre>
 *
 * <p>Accepted syntax is {@code property,direction} pairs separated by semicolons — for example
 * {@code status,asc;placedAt,desc}. The direction is optional and defaults to ascending.</p>
 *
 * <p>Immutable and safe to hold in a {@code static final} field.</p>
 */
public final class SortWhitelist {

    private final Set<String> allowed;
    private final Sort fallback;

    private SortWhitelist(Set<String> allowed, Sort fallback) {
        this.allowed = allowed;
        this.fallback = fallback;
    }

    /**
     * @param properties the entity property names this endpoint may be sorted by. Each one should
     *                   have an index behind it, or be on a table small enough not to care.
     */
    public static SortWhitelist of(String... properties) {
        if (properties.length == 0) {
            throw new IllegalArgumentException("A sort whitelist needs at least one property");
        }
        Set<String> set = new LinkedHashSet<>(List.of(properties));
        return new SortWhitelist(Set.copyOf(set), Sort.by(Sort.Direction.ASC, properties[0]));
    }

    /**
     * The order used when the client asks for none.
     *
     * <p>Worth setting deliberately. An unsorted paged query has no defined order in SQL, so
     * page 2 can repeat a row from page 1 and skip another — a bug that shows up as "an order went
     * missing from the list" and is almost impossible to reproduce.</p>
     */
    public SortWhitelist withDefault(String property, Sort.Direction direction) {
        requireAllowed(property);
        return new SortWhitelist(allowed, Sort.by(direction, property));
    }

    /** @throws BusinessException (400) if the client asked for a property that is not allowed */
    public Sort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String clause : raw.split(";")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",", 2);
            String property = parts[0].trim();
            requireAllowed(property);
            Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? fallback : Sort.by(orders);
    }

    public Set<String> allowedProperties() {
        return allowed;
    }

    private void requireAllowed(String property) {
        if (!allowed.contains(property)) {
            // Naming the allowed set turns a guessing game into a fixable error. These are public
            // API field names, not schema internals, so listing them leaks nothing.
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Cannot sort by '%s'. Allowed: %s".formatted(property, String.join(", ", allowed)));
        }
    }
}
