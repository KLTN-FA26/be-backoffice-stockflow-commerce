package com.stockflow.notification.internal.service;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.contracts.InventoryAlertsRaised.Item;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The words of an alert message and its once-a-day key: built from codes and counts, never from typed text. */
class InventoryAlertMessagesTest {

    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final UUID HCM = UUID.randomUUID();

    private static Item item(String sku, String lot, LocalDate expiry, boolean held) {
        return new Item(UUID.randomUUID(), sku, lot, expiry, 5, null, held);
    }

    private static InventoryAlertsRaised event(String kind, UUID warehouse, Instant at, Item... items) {
        return new InventoryAlertsRaised(UUID.randomUUID(), kind, warehouse, List.of(items), at);
    }

    @Test
    void theKeyIsKindWarehouseAndBusinessDay() {
        assertThat(InventoryAlertMessages.dedupeKey("REORDER", null, LocalDate.of(2026, 10, 14))).isEqualTo("REORDER:ALL:2026-10-14");
        assertThat(InventoryAlertMessages.dedupeKey("EXPIRING", HCM, LocalDate.of(2026, 10, 14)))
                .isEqualTo("EXPIRING:" + HCM + ":2026-10-14");
    }

    @Test
    void theDayIsTheVietnameseDayOfTheEventNotUtc() {
        // 17:30 UTC on the 14th is 00:30 on the 15th in Ho Chi Minh City.
        InboxMessage late = InventoryAlertMessages.build(event("REORDER", null, Instant.parse("2026-10-14T17:30:00Z"),
                item("A", null, null, false)), null, VI);
        InboxMessage early = InventoryAlertMessages.build(event("REORDER", null, Instant.parse("2026-10-14T16:30:00Z"),
                item("A", null, null, false)), null, VI);
        assertThat(late.dedupeKey()).isEqualTo("REORDER:ALL:2026-10-15");
        assertThat(early.dedupeKey()).isEqualTo("REORDER:ALL:2026-10-14");
    }

    @Test
    void sameKindWarehouseAndDayGiveTheSameKeyWhateverTheBatch() {
        Instant morning = Instant.parse("2026-10-14T01:00:00Z");
        Instant afternoon = Instant.parse("2026-10-14T09:00:00Z");
        String first = InventoryAlertMessages.build(event("EXPIRED", HCM, morning, item("A", "L", LocalDate.of(2026, 10, 1), false)), "HCM", VI).dedupeKey();
        String second = InventoryAlertMessages.build(event("EXPIRED", HCM, afternoon, item("B", "L", LocalDate.of(2026, 10, 2), false)), "HCM", VI).dedupeKey();
        String otherKind = InventoryAlertMessages.build(event("EXPIRING", HCM, morning, item("A", "L", LocalDate.of(2026, 10, 20), false)), "HCM", VI).dedupeKey();
        String otherWarehouse = InventoryAlertMessages.build(event("EXPIRED", UUID.randomUUID(), morning, item("A", "L", LocalDate.of(2026, 10, 1), false)), "HN", VI).dedupeKey();
        assertThat(first).isEqualTo(second);
        assertThat(otherKind).isNotEqualTo(first);
        assertThat(otherWarehouse).isNotEqualTo(first);
    }

    @Test
    void aReorderMessageCountsTheSkusAndLinksToTheList() {
        InboxMessage message = InventoryAlertMessages.build(event("REORDER", null, Instant.parse("2026-10-14T01:00:00Z"),
                item("A", null, null, false), item("B", null, null, false)), null, VI);
        assertThat(message.category()).isEqualTo("INVENTORY_ALERT");
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.title()).isEqualTo("2 SKU chạm ngưỡng đặt lại hàng");
        assertThat(message.body()).isEqualTo("SKU: A, B");
        assertThat(message.link()).isEqualTo("/admin/inventory/alerts?kind=REORDER");
        assertThat(message.sourceType()).isEqualTo("STOCK_ALERT");
    }

    @Test
    void aSafetyMessageIsCritical() {
        InboxMessage message = InventoryAlertMessages.build(event("SAFETY", null, Instant.now(), item("A", null, null, false)), null, VI);
        assertThat(message.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(message.title()).isEqualTo("1 SKU dưới mức tồn an toàn");
    }

    @Test
    void anExpiryMessageNamesTheWarehouseAndTheNearestDate() {
        InboxMessage message = InventoryAlertMessages.build(event("EXPIRING", HCM, Instant.parse("2026-10-14T01:00:00Z"),
                item("A", "L1", LocalDate.of(2026, 10, 30), false), item("B", "L2", LocalDate.of(2026, 10, 20), false)), "HCM", VI);
        assertThat(message.title()).isEqualTo("2 lô sắp hết hạn tại kho HCM");
        assertThat(message.body()).isEqualTo("SKU: A, B. Hạn gần nhất: 2026-10-20");
        assertThat(message.link()).isEqualTo("/admin/inventory/alerts?kind=EXPIRING&warehouseId=" + HCM);
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
    }

    @Test
    void anExpiredMessageIsCriticalAndSaysWhenLotsAreStillHeld() {
        InboxMessage message = InventoryAlertMessages.build(event("EXPIRED", HCM, Instant.parse("2026-10-14T01:00:00Z"),
                item("A", "L1", LocalDate.of(2026, 10, 13), true), item("B", "L2", LocalDate.of(2026, 10, 12), false)), "HCM", VI);
        assertThat(message.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(message.title()).isEqualTo("2 lô đã hết hạn tại kho HCM");
        assertThat(message.body()).contains("1 lô còn đơn giữ hàng");
    }

    @Test
    void aLongListOfSkusIsCutAtFiveAndCounted() {
        Item[] items = new Item[12];
        for (int i = 0; i < items.length; i++) {
            items[i] = item("SKU-" + i, null, null, false);
        }
        InboxMessage message = InventoryAlertMessages.build(event("REORDER", null, Instant.now(), items), null, VI);
        assertThat(message.title()).startsWith("12 SKU");
        assertThat(message.body()).isEqualTo("SKU: SKU-0, SKU-1, SKU-2, SKU-3, SKU-4 và 7 SKU khác");
    }

    @Test
    void aSkuInTwoLotsIsNamedOnce() {
        InboxMessage message = InventoryAlertMessages.build(event("EXPIRING", HCM, Instant.now(),
                item("A", "L1", LocalDate.of(2026, 10, 20), false), item("A", "L2", LocalDate.of(2026, 10, 21), false)), "HCM", VI);
        assertThat(message.title()).startsWith("2 lô");
        assertThat(message.body()).startsWith("SKU: A.");
    }

    @Test
    void oneAlertPointsAtItselfAndSeveralAtTheirBatch() {
        Item only = item("A", null, null, false);
        InventoryAlertsRaised one = event("REORDER", null, Instant.now(), only);
        assertThat(InventoryAlertMessages.build(one, null, VI).sourceId()).isEqualTo(only.alertId().toString());
        InventoryAlertsRaised many = event("REORDER", null, Instant.now(), item("A", null, null, false), item("B", null, null, false));
        assertThat(InventoryAlertMessages.build(many, null, VI).sourceId()).isEqualTo(many.batchId().toString());
    }

    @Test
    void theEnglishBundleWorksToo() {
        InboxMessage message = InventoryAlertMessages.build(event("EXPIRED", HCM, Instant.now(),
                item("A", "L1", LocalDate.of(2026, 10, 13), false)), "HCM", Locale.ENGLISH);
        assertThat(message.title()).isEqualTo("1 lot(s) expired at warehouse HCM");
    }

    @Test
    void messagesStayInsideTheInboxLimitsWhateverTheBatch() {
        Item[] items = new Item[500];
        for (int i = 0; i < items.length; i++) {
            items[i] = item("A-VERY-LONG-SKU-CODE-" + i, "LOT-" + i, LocalDate.of(2026, 10, 20), true);
        }
        InboxMessage message = InventoryAlertMessages.build(event("EXPIRED", HCM, Instant.now(), items), "HCM", VI);
        assertThat(message.title().length()).isLessThanOrEqualTo(InboxMessage.TITLE_MAX);
        assertThat(message.body().length()).isLessThanOrEqualTo(InboxMessage.BODY_MAX);
        assertThat(message.dedupeKey().length()).isLessThanOrEqualTo(InboxMessage.DEDUPE_KEY_MAX);
        assertThat(message.link().length()).isLessThanOrEqualTo(InboxMessage.LINK_MAX);
    }

    @Test
    void anUnknownKindIsABugNotAMessage() {
        assertThatThrownBy(() -> InventoryAlertMessages.build(event("BOGUS", null, Instant.now(), item("A", null, null, false)), null, VI))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
