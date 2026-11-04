package com.stockflow.notification.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The checks {@link InboxMessage} makes before the table has to: the same limits as {@code user_notification}. */
class InboxMessageTest {

    private static InboxMessage message(String category, String title, String body, String link, String dedupeKey) {
        return new InboxMessage(category, InboxSeverity.WARN, title, body, link, "STOCK_ALERT", "alert-1", dedupeKey);
    }

    @Test
    void keepsAWellFormedMessageAsIs() {
        InboxMessage m = message("INVENTORY_ALERT", "12 lots near expiry", "At HCM-01", "/admin/inventory/alerts",
                "EXPIRING:HCM:2026-10-14");

        assertThat(m.category()).isEqualTo("INVENTORY_ALERT");
        assertThat(m.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(m.title()).isEqualTo("12 lots near expiry");
        assertThat(m.body()).isEqualTo("At HCM-01");
        assertThat(m.link()).isEqualTo("/admin/inventory/alerts");
        assertThat(m.sourceType()).isEqualTo("STOCK_ALERT");
        assertThat(m.sourceId()).isEqualTo("alert-1");
        assertThat(m.dedupeKey()).isEqualTo("EXPIRING:HCM:2026-10-14");
    }

    @Test
    void trimsTextAndTurnsBlankOptionalsIntoNull() {
        InboxMessage m = new InboxMessage("  CAT ", InboxSeverity.INFO, "  Title  ", "   ", " ", "", null, " key ");

        assertThat(m.category()).isEqualTo("CAT");
        assertThat(m.title()).isEqualTo("Title");
        assertThat(m.body()).isNull();
        assertThat(m.link()).isNull();
        assertThat(m.sourceType()).isNull();
        assertThat(m.sourceId()).isNull();
        assertThat(m.dedupeKey()).isEqualTo("key");
    }

    @Test
    void requiresCategoryTitleDedupeKeyAndSeverity() {
        assertThatThrownBy(() -> message(null, "t", null, null, "k")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");
        assertThatThrownBy(() -> message(" ", "t", null, null, "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message("C", null, null, null, "k")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> message("C", "t", null, null, " ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedupeKey");
        assertThatThrownBy(() -> new InboxMessage("C", null, "t", null, null, null, null, "k"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsTheLimitsAndRefusesOneCharacterMore() {
        assertThat(message("C".repeat(40), "t".repeat(200), "b".repeat(1000), "/" + "l".repeat(299), "k".repeat(200)))
                .isNotNull();
        assertThatThrownBy(() -> message("C".repeat(41), "t", null, null, "k")).hasMessageContaining("category");
        assertThatThrownBy(() -> message("C", "t".repeat(201), null, null, "k")).hasMessageContaining("title");
        assertThatThrownBy(() -> message("C", "t", "b".repeat(1001), null, "k")).hasMessageContaining("body");
        assertThatThrownBy(() -> message("C", "t", null, "/" + "l".repeat(300), "k")).hasMessageContaining("link");
        assertThatThrownBy(() -> message("C", "t", null, null, "k".repeat(201))).hasMessageContaining("dedupeKey");
        assertThatThrownBy(() -> new InboxMessage("C", InboxSeverity.INFO, "t", null, null, "s".repeat(41), null, "k"))
                .hasMessageContaining("sourceType");
        assertThatThrownBy(() -> new InboxMessage("C", InboxSeverity.INFO, "t", null, null, null, "s".repeat(101), "k"))
                .hasMessageContaining("sourceId");
    }

    @Test
    void aLinkIsARouteOfThisApplication() {
        assertThat(message("C", "t", null, "/admin/receipts?status=DRAFT", "k").link())
                .isEqualTo("/admin/receipts?status=DRAFT");
        for (String bad : new String[] {"https://evil.example/x", "//evil.example/x", "javascript:alert(1)", "admin/x"}) {
            assertThatThrownBy(() -> message("C", "t", null, bad, "k")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("link");
        }
    }
}
