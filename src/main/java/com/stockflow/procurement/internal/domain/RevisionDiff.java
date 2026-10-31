package com.stockflow.procurement.internal.domain;

import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.RevisionLine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What differs between two revisions of an order, line by line: what a supplier is told when an amendment is
 * approved (SCRUM-117), and what the revision history shows. Pure, so both read it the same way.
 *
 * <p>Lines are matched by their id, not by line number or SKU: a line keeps its id across revisions, and an amendment
 * may change the SKU's quantity or price but never which line it is.</p>
 */
public final class RevisionDiff {

    /** {@code field}: quantity, unitPrice, taxRate, description, removed or added. */
    public record LineChange(int lineNo, String sku, String field, String before, String after) {
    }

    private static final List<String> FIELD_ORDER =
            List.of("quantity", "unitPrice", "taxRate", "description", "removed", "added");

    private RevisionDiff() {
    }

    /**
     * The changes that turn {@code base} into {@code target}, ordered by line number, then field. A line only in
     * {@code base} is {@code removed} (its quantity as {@code before}); one only in {@code target} is {@code added}
     * (its quantity as {@code after}). Amounts are compared by value, so 1250.0 equals 1250.00.
     */
    public static List<LineChange> between(List<RevisionLine> base, List<RevisionLine> target) {
        Map<UUID, RevisionLine> before = new LinkedHashMap<>();
        base.forEach(line -> before.put(line.lineId(), line));
        Map<UUID, RevisionLine> after = new LinkedHashMap<>();
        target.forEach(line -> after.put(line.lineId(), line));

        List<LineChange> changes = new ArrayList<>();
        before.forEach((id, was) -> {
            RevisionLine now = after.get(id);
            if (now == null) {
                changes.add(new LineChange(was.lineNo(), was.sku(), "removed",
                        Integer.toString(was.quantityOrdered()), null));
                return;
            }
            if (was.quantityOrdered() != now.quantityOrdered()) {
                changes.add(new LineChange(now.lineNo(), now.sku(), "quantity",
                        Integer.toString(was.quantityOrdered()), Integer.toString(now.quantityOrdered())));
            }
            if (differ(was.unitPrice(), now.unitPrice())) {
                changes.add(new LineChange(now.lineNo(), now.sku(), "unitPrice", text(was.unitPrice()),
                        text(now.unitPrice())));
            }
            if (differ(was.taxRate(), now.taxRate())) {
                changes.add(new LineChange(now.lineNo(), now.sku(), "taxRate", text(was.taxRate()),
                        text(now.taxRate())));
            }
            String oldNote = blankToNull(was.description());
            String newNote = blankToNull(now.description());
            if (!java.util.Objects.equals(oldNote, newNote)) {
                changes.add(new LineChange(now.lineNo(), now.sku(), "description", oldNote, newNote));
            }
        });
        after.forEach((id, now) -> {
            if (!before.containsKey(id)) {
                changes.add(new LineChange(now.lineNo(), now.sku(), "added", null,
                        Integer.toString(now.quantityOrdered())));
            }
        });
        changes.sort(Comparator.comparingInt(LineChange::lineNo)
                .thenComparingInt(change -> FIELD_ORDER.indexOf(change.field())));
        return List.copyOf(changes);
    }

    private static boolean differ(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a != b : a.compareTo(b) != 0;
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
