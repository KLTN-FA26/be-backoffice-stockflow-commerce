package com.stockflow.procurement.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.RevisionLine;
import com.stockflow.procurement.internal.domain.RevisionDiff;
import com.stockflow.procurement.internal.repository.PurchaseOrderHistoryRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderHistoryRepository.LineRow;
import com.stockflow.procurement.internal.repository.PurchaseOrderHistoryRepository.OrderHeader;
import com.stockflow.procurement.internal.repository.PurchaseOrderHistoryRepository.RevisionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The read side of SCRUM-117: an order's revisions, one revision against its base, the timeline and the amendment
 * waiting. Read-only; the warehouse scope is the one {@code ProcurementServiceImpl.findById} applies, so the history of
 * an order is never more visible than the order.
 *
 * <p>Why {@code purpose} is derived from the events and not stored: a revision of kind {@code AMENDMENT} is also what
 * a resubmission after a rejection writes (the draft is frozen again under the next number), and the revision row has
 * no column for the difference. The {@code SUBMITTED} event names the revision it froze, and it is append-only, so it
 * is the one record that cannot disagree with what happened.</p>
 *
 * <p>Why the diff base is the closest earlier <em>approved</em> revision: what a reader wants to know is what changes
 * compared with what the supplier and the warehouse have been working to. A rejected or withdrawn revision never
 * governed anything, so diffing a new amendment against it would show changes relative to a text nobody acted on.
 * When nothing earlier was approved (a resubmission after a first rejection) the immediately earlier revision is the
 * best remaining reference.</p>
 *
 * <p>Names are fetched with one {@code identity :: api} call per response.</p>
 */
@Service
@Transactional(readOnly = true)
public class PurchaseOrderHistory {

    /** The timeline page ceiling (design section 9); lower than the platform's, as a timeline row carries a payload. */
    public static final int MAX_TIMELINE_SIZE = 100;

    public static final String SUBMISSION = "SUBMISSION";
    public static final String AMENDMENT = "AMENDMENT";

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() { };

    /** A revision in the list; the {@code decision*} fields are its last decision, null while it waits. */
    public record Revision(long revisionNo, String purpose, String state, UUID changedBy, String changedByName,
                           Instant changedAt, String reason, BigDecimal totalAmount, String decision,
                           UUID decidedBy, String decidedByName, Instant decidedAt, String decisionReason,
                           BigDecimal autoApprovalLimit) {
    }

    public record Line(int lineNo, String sku, String description, String unit, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal taxRate, BigDecimal lineSubtotal, BigDecimal lineTax,
                       BigDecimal lineTotal) {
    }

    /**
     * {@code scope} is LINE or HEADER; {@code type} CHANGED, REMOVED or ADDED (a header field is always CHANGED).
     * REMOVED and ADDED carry the line's quantity as before / after and no field.
     */
    public record Change(String scope, Integer lineNo, String sku, String type, String field, String before,
                         String after) {
    }

    public record RevisionDetail(Revision revision, String currency, LocalDate expectedDate, String note,
                                 BigDecimal subtotal, BigDecimal taxTotal, List<Line> lines, Long comparedWithRevisionNo,
                                 List<Change> changes) {
    }

    public record Event(UUID id, String action, String fromStatus, String toStatus, UUID actorId, String actorName,
                        String reason, Long revisionNo, Map<String, Object> payload, Instant occurredAt) {
    }

    public record Pending(long revisionNo, UUID requestedBy, String requestedByName, Instant requestedAt,
                          String reason, BigDecimal newTotalAmount) {
    }

    private final PurchaseOrderHistoryRepository history;
    private final IdentityService identity;
    private final InventoryService inventory;
    private final ObjectMapper json;

    PurchaseOrderHistory(PurchaseOrderHistoryRepository history, IdentityService identity, InventoryService inventory,
                         ObjectMapper json) {
        this.history = history;
        this.identity = identity;
        this.inventory = inventory;
        this.json = json;
    }

    /** Newest first. */
    public List<Revision> revisions(UUID purchaseOrderId) {
        OrderHeader header = scoped(purchaseOrderId);
        List<RevisionRow> rows = history.revisions(purchaseOrderId);
        Map<UUID, String> names = names(rows.stream().flatMap(r -> java.util.stream.Stream.of(r.changedBy(), r.decidedBy()))
                .toList());
        return rows.stream().map(r -> revision(r, header, names)).toList();
    }

    public RevisionDetail revision(UUID purchaseOrderId, long revisionNo) {
        OrderHeader header = scoped(purchaseOrderId);
        List<RevisionRow> rows = history.revisions(purchaseOrderId);
        RevisionRow row = rows.stream().filter(r -> r.revisionNo() == revisionNo).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No revision " + revisionNo + " on purchase order " + purchaseOrderId));
        RevisionRow base = base(rows, row);

        List<LineRow> lines = history.lines(row.id());
        List<LineRow> baseLines = base == null ? List.of() : history.lines(base.id());
        Set<UUID> items = new HashSet<>();
        lines.forEach(l -> items.add(l.inventoryItemId()));
        baseLines.forEach(l -> items.add(l.inventoryItemId()));
        Map<UUID, String> skus = items.isEmpty() ? Map.of() : inventory.skusOf(items);

        Map<UUID, String> names = names(java.util.stream.Stream.of(row.changedBy(), row.decidedBy()).toList());
        JsonNode snapshot = snapshot(row);

        List<Change> changes = new ArrayList<>();
        if (base != null) {
            changes.addAll(headerChanges(base, row));
            RevisionDiff.between(revisionLines(baseLines, skus), revisionLines(lines, skus))
                    .forEach(c -> changes.add(lineChange(c)));
        }
        return new RevisionDetail(revision(row, header, names), text(snapshot, "currency"),
                date(snapshot, "expectedDate"), text(snapshot, "note"), row.subtotal(), row.taxTotal(),
                lines.stream().map(l -> new Line(l.lineNo(), skus.getOrDefault(l.inventoryItemId(), "UNKNOWN"),
                        l.note(), l.unit(), l.quantity(), l.unitPrice(), l.taxRate(), l.lineSubtotal(), l.lineTax(),
                        l.lineTotal())).toList(),
                base == null ? null : base.revisionNo(), List.copyOf(changes));
    }

    /** Newest first; {@code size} above {@link #MAX_TIMELINE_SIZE} is refused, not clamped. */
    public PageResponse<Event> timeline(UUID purchaseOrderId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_TIMELINE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "page must not be negative and size must be between 1 and " + MAX_TIMELINE_SIZE);
        }
        scoped(purchaseOrderId);
        var rows = history.events(purchaseOrderId, page, size);
        Map<UUID, String> names = names(rows.items().stream().map(e -> e.actorId()).toList());
        return rows.map(e -> new Event(e.id(), e.action(), e.fromStatus(), e.toStatus(), e.actorId(),
                e.actorId() == null ? null : names.get(e.actorId()), e.reason(), e.revisionNo(), payload(e.payload()),
                e.occurredAt()));
    }

    /**
     * The amendment waiting on each of these orders (absent for an order with none), in one statement and with the
     * requesters' names in one call, so a page of orders costs two queries however many wait. The caller has already
     * read the orders under the warehouse scope.
     */
    public Map<UUID, Pending> pendingAmendments(Collection<UUID> purchaseOrderIds) {
        var rows = history.pendingAmendments(purchaseOrderIds);
        Map<UUID, String> names = names(rows.values().stream().map(r -> r.requestedBy()).toList());
        return rows.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
            var r = e.getValue();
            return new Pending(r.revisionNo(), r.requestedBy(), names.get(r.requestedBy()), r.requestedAt(),
                    r.reason(), r.totalAmount());
        }));
    }

    // ------------------------------------------------------------------ revisions

    private Revision revision(RevisionRow r, OrderHeader header, Map<UUID, String> names) {
        return new Revision(r.revisionNo(), r.submission() ? SUBMISSION : AMENDMENT, state(r, header), r.changedBy(),
                names.get(r.changedBy()), r.changedAt(), r.reason(), r.totalAmount(), r.decision(),
                r.decidedBy(), r.decidedBy() == null ? null : names.get(r.decidedBy()), r.decidedAt(),
                r.decisionReason(), r.limitAmount());
    }

    /** ACTIVE and PENDING come from the header; otherwise how it ended: rejected, withdrawn, or replaced. */
    private static String state(RevisionRow r, OrderHeader header) {
        if (r.id().equals(header.activeRevisionId())) {
            return "ACTIVE";
        }
        if (r.id().equals(header.pendingRevisionId())) {
            return "PENDING";
        }
        return switch (Objects.toString(r.decision(), "")) {
            case "REJECTED" -> "REJECTED";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> "SUPERSEDED";
        };
    }

    /** The closest earlier approved revision, else the immediately earlier one, else none ({@code rows} newest first). */
    private static RevisionRow base(List<RevisionRow> rows, RevisionRow target) {
        RevisionRow earlier = null;
        for (RevisionRow r : rows) {
            if (r.revisionNo() >= target.revisionNo()) {
                continue;
            }
            if (r.approved()) {
                return r;
            }
            if (earlier == null) {
                earlier = r;
            }
        }
        return earlier;
    }

    private static List<RevisionLine> revisionLines(List<LineRow> lines, Map<UUID, String> skus) {
        return lines.stream().map(l -> new RevisionLine(l.lineId(), l.lineNo(),
                skus.getOrDefault(l.inventoryItemId(), "UNKNOWN"), l.quantity().intValueExact(), l.unitPrice(),
                l.taxRate(), l.note())).toList();
    }

    private static Change lineChange(RevisionDiff.LineChange c) {
        return switch (c.field()) {
            case "removed" -> new Change("LINE", c.lineNo(), c.sku(), "REMOVED", null, c.before(), c.after());
            case "added" -> new Change("LINE", c.lineNo(), c.sku(), "ADDED", null, c.before(), c.after());
            default -> new Change("LINE", c.lineNo(), c.sku(), "CHANGED", c.field(), c.before(), c.after());
        };
    }

    private List<Change> headerChanges(RevisionRow before, RevisionRow after) {
        JsonNode was = snapshot(before);
        JsonNode now = snapshot(after);
        List<Change> changes = new ArrayList<>();
        String oldDate = text(was, "expectedDate");
        String newDate = text(now, "expectedDate");
        if (!Objects.equals(oldDate, newDate)) {
            changes.add(new Change("HEADER", null, null, "CHANGED", "expectedDate", oldDate, newDate));
        }
        String oldNote = blankToNull(text(was, "note"));
        String newNote = blankToNull(text(now, "note"));
        if (!Objects.equals(oldNote, newNote)) {
            changes.add(new Change("HEADER", null, null, "CHANGED", "note", oldNote, newNote));
        }
        BigDecimal oldTotal = before.totalAmount();
        BigDecimal newTotal = after.totalAmount();
        if (oldTotal == null || newTotal == null ? oldTotal != newTotal : oldTotal.compareTo(newTotal) != 0) {
            changes.add(new Change("HEADER", null, null, "CHANGED", "totalAmount",
                    oldTotal == null ? null : oldTotal.toPlainString(),
                    newTotal == null ? null : newTotal.toPlainString()));
        }
        return changes;
    }

    // ------------------------------------------------------------------ plumbing

    /** The order's header under the caller's warehouse scope, exactly as {@code findById}: unknown is 404, out of scope 403. */
    private OrderHeader scoped(UUID purchaseOrderId) {
        OrderHeader header = history.header(purchaseOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
        WarehouseScope.requireWarehouse(header.warehouseId());
        return header;
    }

    private Map<UUID, String> names(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : identity.displayNames(ids);
    }

    /** Text fields only: amounts come from the repository as text, because a JSON tree does not keep their scale. */
    private JsonNode snapshot(RevisionRow row) {
        try {
            return json.readTree(row.snapshotHeader());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A revision's snapshot_header is a JSON object (ck_purchase_order_revisions_snapshot)", e);
        }
    }

    private Map<String, Object> payload(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return json.readValue(stored, PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An event's payload is a JSON object of strings", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
