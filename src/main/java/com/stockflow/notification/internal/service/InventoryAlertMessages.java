package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;

/**
 * Words the inbox message for a batch of stock alerts, and chooses its dedupe key.
 *
 * <p><b>Only system text.</b> Title and body are built from codes (the kind, SKU codes, a warehouse prefix) and
 * numbers (counts, a date) with the templates of {@code i18n/messages*.properties}; nothing a person typed ever reaches
 * them, which is what lets the inbox store them (hotfix #99).</p>
 *
 * <p><b>Dedupe key = kind + warehouse + business day</b> (plan G5a-3: at most one message per kind, warehouse,
 * recipient and day). It is derived from the event's own time, not from the clock at delivery, so a retried delivery
 * across midnight is still the same message. Later batches of the same kind and warehouse on the same day are not
 * messaged: the list at {@code /inventory/alerts} has them all.</p>
 */
final class InventoryAlertMessages {

    static final String CATEGORY = "INVENTORY_ALERT";
    static final String SOURCE_TYPE = "STOCK_ALERT";
    /** How many SKU codes the body names before saying "and N more". */
    static final int SKUS_SHOWN = 5;

    private InventoryAlertMessages() {
    }

    static String dedupeKey(String kind, UUID warehouseId, LocalDate businessDay) {
        return kind + ":" + (warehouseId == null ? "ALL" : warehouseId) + ":" + businessDay;
    }

    static InboxMessage build(InventoryAlertsRaised event, String warehouseCode, Locale locale) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", locale);
        List<InventoryAlertsRaised.Item> items = event.items();
        String kind = event.kind();
        String prefix = warehouseCode == null || warehouseCode.isBlank() ? "?" : warehouseCode;
        String title = switch (kind) {
            case "REORDER" -> texts.getString("inbox.alert.reorder.title").formatted(items.size());
            case "SAFETY" -> texts.getString("inbox.alert.safety.title").formatted(items.size());
            case "EXPIRING" -> texts.getString("inbox.alert.expiring.title").formatted(items.size(), prefix);
            case "EXPIRED" -> texts.getString("inbox.alert.expired.title").formatted(items.size(), prefix);
            default -> throw new IllegalArgumentException("Unknown alert kind " + kind);
        };

        Set<String> skus = new LinkedHashSet<>();
        items.forEach(item -> skus.add(item.sku()));
        List<String> shown = skus.stream().limit(SKUS_SHOWN).toList();
        StringBuilder body = new StringBuilder(texts.getString("inbox.alert.body.skus").formatted(String.join(", ", shown)));
        if (skus.size() > shown.size()) {
            body.append(' ').append(texts.getString("inbox.alert.body.more").formatted(skus.size() - shown.size()));
        }
        items.stream().map(InventoryAlertsRaised.Item::expiryDate).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).ifPresent(date -> body.append(". ")
                        .append(texts.getString("inbox.alert.body.nearest").formatted(date)));
        long held = items.stream().filter(InventoryAlertsRaised.Item::reservationImpaired).count();
        if (held > 0) {
            body.append(". ").append(texts.getString("inbox.alert.body.held").formatted(held));
        }

        String link = "/admin/inventory/alerts?kind=" + kind + (event.warehouseId() == null ? ""
                : "&warehouseId=" + event.warehouseId());
        LocalDate day = BusinessCalendar.date(event.occurredAt());
        InboxSeverity severity = switch (kind) {
            case "SAFETY", "EXPIRED" -> InboxSeverity.CRITICAL;
            default -> InboxSeverity.WARN;
        };
        // One alert: point at it. Several: there is no single thing to point at, so the batch id says which evaluation.
        String sourceId = items.size() == 1 ? items.get(0).alertId().toString() : event.batchId().toString();
        return new InboxMessage(CATEGORY, severity, title, body.toString(), link, SOURCE_TYPE, sourceId,
                dedupeKey(kind, event.warehouseId(), day));
    }
}
