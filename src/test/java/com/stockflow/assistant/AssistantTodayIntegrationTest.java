package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.StockAlertQueries;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/assistant/today} with the real security chain, the real permission aspect, the real tools and
 * the seeded roles (design §9): each role's card holds the sections its own tools answer and nothing else, a manager
 * limited to HCM sees HCM rows only, and a tool that fails costs its own sections and nothing more.
 *
 * <p>Its own application context, and so its own database ({@code PostgresContainer}), for one reason: {@link #alerts}
 * spies on the inventory module's expiry alerts so that one test can make {@code findExpiringLots} fail. A spy calls
 * the real bean in every other test.</p>
 *
 * <p>The rows are inserted as the modules leave them. The card lists the first rows of tables the demo seed and the
 * other tests here fill too, so the fixtures sort first by construction: a SKU starting with a digit, orders placed in
 * the 2190s, lots expired and production and purchase orders due in 1990.</p>
 */
@SecuredIntegrationTest
class AssistantTodayIntegrationTest {

    private static final String TODAY = "/api/v1/assistant/today";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String BLANK = "SOFA-3S-GREY";
    private static final String CHECKSUM = "b".repeat(64);
    private static final String CONTACT_NAME = "Trần Thị Liên Hệ";
    private static final String CONTACT_EMAIL = "lienhe@khach-hang.vn";
    private static final String CONTACT_PHONE = "0987654321";
    private static final String ADDRESS = "99 Đường Thử Nghiệm";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @MockitoSpyBean StockAlertQueries alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private AlertFixtures fixtures;
    private String tag;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("a planner gets low stock and the expired and expiring lots, and no order section: their grants read "
            + "no orders; no model is asked and no question is counted")
    void planner() throws Exception {
        String low = lowSku();
        UUID planner = fixtures.staff("INVENTORY_PLANNER");

        JsonNode card = card(Role.INVENTORY_PLANNER, planner);

        assertThat(keys(card)).containsExactly("lowStock", "expiredLots", "expiringLots");
        assertThat(card.path("generatedAt").asText()).isNotBlank();
        JsonNode lowStock = section(card, "lowStock");
        assertThat(lowStock.path("tool").asText()).isEqualTo("findLowStock");
        assertThat(lowStock.path("outcome").asText()).isEqualTo("OK");
        assertThat(texts(lowStock.path("table").path("columns"))).startsWith("sku", "usableOnHand");
        assertThat(lowStock.path("table").path("total").asLong()).isPositive();
        assertThat(column(lowStock, "sku")).contains(low);
        assertThat(row(lowStock, "sku", low).path("usableOnHand").asLong()).isEqualTo(5);
        for (String key : List.of("expiredLots", "expiringLots")) {
            assertThat(section(card, key).path("tool").asText()).isEqualTo("findExpiringLots");
            assertThat(section(card, key).path("outcome").asText()).isEqualTo("OK");
            assertThat(section(card, key).path("note").asText()).as("the nightly check's quantities").isNotBlank();
        }

        verify(model, never()).next(any());
        assertThat(jdbc.queryForObject("select count(*) from assistant.conversation where owner_user_id = ?",
                Long.class, planner)).as("no conversation").isZero();
        assertThat(jdbc.queryForList("""
                SELECT tool_name FROM assistant.tool_call
                WHERE channel = 'TODAY' AND user_id = ? AND conversation_id IS NULL ORDER BY id""", String.class,
                planner)).as("every read recorded, as the planner's").containsExactly("findLowStock",
                "findExpiringLots");
        http.asRole(Role.INVENTORY_PLANNER, planner, get("/api/v1/assistant/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.askedToday").value(0));
    }

    @Test
    @DisplayName("a coordinator gets the held orders, SO- numbers with the hold reason and a masked note, and the "
            + "orders waiting for payment; never a contact")
    void coordinator() throws Exception {
        String sku = fixtures.sku(null, null);
        String held = order(sku, "ON_HOLD", "PAYMENT", "Khách hẹn chuyển khoản, gọi 0903 123 456", true);
        String waiting = order(sku, "PENDING_PAYMENT", null, null, false);
        UUID coordinator = fixtures.staff("ORDER_COORDINATOR");

        JsonNode card = card(Role.ORDER_COORDINATOR, coordinator);

        assertThat(keys(card)).containsExactly("heldOrders", "awaitingPayment", "overdueProduction");
        JsonNode heldOrders = section(card, "heldOrders");
        assertThat(heldOrders.path("tool").asText()).isEqualTo("findOrders");
        assertThat(heldOrders.path("outcome").asText()).isEqualTo("OK");
        assertThat(column(heldOrders, "orderNumber")).isNotEmpty().contains(held)
                .allSatisfy(number -> assertThat(number).startsWith("SO-"));
        JsonNode order = row(heldOrders, "orderNumber", held);
        assertThat(order.path("hold.reason").asText()).isEqualTo("PAYMENT");
        assertThat(order.path("hold.statusBeforeHold").asText()).isEqualTo("CONFIRMED");
        assertThat(order.path("hold.note").asText()).contains("[phone]").doesNotContain("0903 123 456");
        assertThat(heldOrders.path("table").path("total").asLong()).isPositive();
        JsonNode awaiting = section(card, "awaitingPayment");
        assertThat(column(awaiting, "orderNumber")).contains(waiting);
        assertThat(row(awaiting, "orderNumber", waiting).path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(card.toString()).doesNotContain(CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, ADDRESS);
    }

    @Test
    @DisplayName("a manager limited to HCM gets HCM rows only, in every section, and no low stock: its thresholds are "
            + "company-wide")
    void warehouseLimitedManager() throws Exception {
        AlertFixtures.Warehouse other = fixtures.otherWarehouse();
        UUID hcm = DemoData.WAREHOUSE_HCM;
        // No thresholds: never low. A digit first: of the lots expiring today, its own come first.
        String sku = fixtures.sku("0TD-" + tag, null, null);
        LocalDate today = LocalDate.now(VIETNAM);
        String expired = "TD-EXP-" + tag;
        String expiredElsewhere = "TD-EXO-" + tag;
        String expiring = "TD-SOON-" + tag;
        String expiringElsewhere = "TD-SOO-" + tag;
        alert("EXPIRED", sku, hcm, expired, LocalDate.of(1990, 1, 1));
        alert("EXPIRED", sku, other.id(), expiredElsewhere, LocalDate.of(1989, 12, 31));
        alert("EXPIRING", sku, hcm, expiring, today);
        alert("EXPIRING", sku, other.id(), expiringElsewhere, today);
        String latest = purchaseOrder(hcm, LocalDate.of(1990, 1, 10), sku);
        String later = purchaseOrder(hcm, LocalDate.of(1990, 2, 10), sku);
        String poElsewhere = purchaseOrder(other.id(), LocalDate.of(1990, 1, 5), sku);
        String lsx = "LSX-TD-" + tag + "-1";
        String lsxElsewhere = "LSX-TD-" + tag + "-2";
        production(lsx, hcm, LocalDate.of(1990, 1, 1));
        production(lsxElsewhere, other.id(), LocalDate.of(1989, 12, 30));
        UUID manager = fixtures.staff("WAREHOUSE_MANAGER", hcm);

        JsonNode card = card(Role.WAREHOUSE_MANAGER, manager);

        assertThat(keys(card)).containsExactly("expiredLots", "expiringLots", "overduePurchaseOrders",
                "overdueProduction");
        for (JsonNode section : card.path("sections")) {
            String key = section.path("key").asText();
            assertThat(section.path("outcome").asText()).as(key).isEqualTo("OK");
            assertThat(column(section, "warehouseCode")).as("%s lists HCM rows only", key).isNotEmpty()
                    .containsOnly("HCM");
        }
        assertThat(column(section(card, "expiredLots"), "lotNumber")).contains(expired);
        assertThat(column(section(card, "expiringLots"), "lotNumber")).contains(expiring);
        assertThat(column(section(card, "overduePurchaseOrders"), "poNumber")).as("the most days late first")
                .startsWith(latest, later);
        assertThat(row(section(card, "overduePurchaseOrders"), "poNumber", latest).path("daysLate").asLong())
                .isEqualTo(ChronoUnit.DAYS.between(LocalDate.of(1990, 1, 10), today));
        assertThat(column(section(card, "overdueProduction"), "productionOrderNumber")).contains(lsx);
        assertThat(row(section(card, "overdueProduction"), "productionOrderNumber", lsx).path("salesOrderNumber")
                .isNull()).as("a manager may not read orders: no order number").isTrue();
        assertThat(card.toString()).doesNotContain(expiredElsewhere, expiringElsewhere, poElsewhere, lsxElsewhere,
                other.prefix());
    }

    @Test
    @DisplayName("a tool that fails leaves its sections FAILED with a note and no rows, and the rest of the card still "
            + "answers")
    void aFailingToolCostsOnlyItsSections() throws Exception {
        String low = lowSku();
        doThrow(new IllegalStateException("alert store unreachable")).when(alerts).openExpiryAlerts(anyInt(), anyInt());
        UUID planner = fixtures.staff("INVENTORY_PLANNER");

        JsonNode card = card(Role.INVENTORY_PLANNER, planner);

        assertThat(keys(card)).containsExactly("lowStock", "expiredLots", "expiringLots");
        for (String key : List.of("expiredLots", "expiringLots")) {
            JsonNode failed = section(card, key);
            assertThat(failed.path("outcome").asText()).as(key).isEqualTo("FAILED");
            assertThat(failed.has("table")).isTrue();
            assertThat(failed.path("table").isNull()).as("no rows").isTrue();
            assertThat(failed.path("note").asText()).isNotBlank();
        }
        JsonNode lowStock = section(card, "lowStock");
        assertThat(lowStock.path("outcome").asText()).isEqualTo("OK");
        assertThat(column(lowStock, "sku")).contains(low);
        assertThat(card.toString()).doesNotContain("alert store unreachable");
    }

    @Test
    @DisplayName("one card a minute per user: a second is 429 RATE_LIMITED, and another user's card is not held up")
    void oneCardAMinute() throws Exception {
        UUID planner = fixtures.staff("INVENTORY_PLANNER");
        UUID colleague = fixtures.staff("INVENTORY_PLANNER");

        http.asRole(Role.INVENTORY_PLANNER, planner, get(TODAY)).andExpect(status().isOk());
        http.asRole(Role.INVENTORY_PLANNER, planner, get(TODAY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        http.asRole(Role.INVENTORY_PLANNER, colleague, get(TODAY)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a portal customer gets no card")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, get(TODAY)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private JsonNode card(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, get(TODAY)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private static List<String> keys(JsonNode card) {
        List<String> keys = new ArrayList<>();
        card.path("sections").forEach(section -> keys.add(section.path("key").asText()));
        return keys;
    }

    private static JsonNode section(JsonNode card, String key) {
        for (JsonNode section : card.path("sections")) {
            if (section.path("key").asText().equals(key)) {
                return section;
            }
        }
        throw new AssertionError("no section " + key + " in " + keys(card));
    }

    private static List<String> column(JsonNode section, String column) {
        List<String> values = new ArrayList<>();
        section.path("table").path("rows").forEach(row -> values.add(row.path(column).asText()));
        return values;
    }

    private static JsonNode row(JsonNode section, String column, String value) {
        for (JsonNode row : section.path("table").path("rows")) {
            if (row.path(column).asText().equals(value)) {
                return row;
            }
        }
        throw new AssertionError("no row with " + column + " " + value + " in " + section);
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    /** A purchasable SKU with 5 units and a reorder point of 100; starting with a digit, it is listed first. */
    private String lowSku() {
        String sku = fixtures.sku("0TD-" + tag, 100, 50);
        fixtures.stock(sku, AlertFixtures.HCM_BIN, 5, "AVAILABLE", null, null);
        return sku;
    }

    /**
     * One order of 100 x 10,000 VND, prepaid, placed in the 2190s so that it is among the newest, with a contact block
     * and a shipping address that must never reach the card.
     */
    private String order(String sku, String status, String holdReason, String holdNote, boolean paid) {
        UUID id = Identifiers.newId();
        LocalDate placedOn = LocalDate.of(2190, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3_000));
        String number = "SO-" + placedOn.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + (1_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        Timestamp placed = Timestamp.from(placedOn.atTime(9, 0).atZone(VIETNAM).toInstant());
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                        total_amount, currency, placed_at, payment_term, paid_amount, paid_in_full_at, hold_reason,
                        status_before_hold, hold_note, contact_name, contact_email, contact_phone,
                        shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                        shipping_ward_name, shipping_province_code, shipping_province_name, shipping_country_code,
                        version, created_at)
                    VALUES (?, ?, ?, ?, ?, 1000000, 'VND', ?, 'PREPAID', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        '26734', 'Phường Bến Nghé', '79', 'Thành phố Hồ Chí Minh', 'VN', 0, NOW())""",
                    id, number, DemoData.CUSTOMER_ID, Identifiers.newId(), status, placed,
                    paid ? BigDecimal.valueOf(1_000_000) : BigDecimal.ZERO, paid ? placed : null, holdReason,
                    holdReason == null ? null : "CONFIRMED", holdNote, CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE,
                    CONTACT_NAME, CONTACT_PHONE, ADDRESS);
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                    + "VALUES (?, ?, ?, 100, 10000, 'VND')", Identifiers.newId(), id, sku);
        });
        return number;
    }

    /** An open expiry alert, as the nightly sweep leaves one. */
    private void alert(String kind, String sku, UUID warehouse, String lot, LocalDate expiry) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, lot_number, expiry_date, observed_qty,
                    status, opened_at, last_observed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 7, 'OPEN', NOW(), NOW(), 0)""",
                Identifiers.newId(), kind, sku, warehouse, lot, Date.valueOf(expiry)));
    }

    /**
     * A purchase order from the demo supplier, confirmed by it and partly received, expected on {@code expected} and
     * placed a week before. Left unmodified since, it is among the first the PO list returns.
     */
    private String purchaseOrder(UUID warehouse, LocalDate expected, String sku) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        String number = "PO-TD-" + po.toString().substring(24).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, expected_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at,
                        confirmed_by, active_revision_id, supplier_confirmation_status, supplier_responded_at,
                        version, created_at)
                    SELECT ?, ?, 'PARTIALLY_RECEIVED', s.id, ?, 'VND', ?, ?, NOW(), ?, NOW(), ?, NOW(), ?, ?,
                        'CONFIRMED', NOW(), 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, number, warehouse, Date.valueOf(expected.minusDays(7)), Date.valueOf(expected), submitter,
                    approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, status, version, created_at)
                    SELECT ?, ?, ?, 1, id, 10, 1000, 'PARTIALLY_RECEIVED', 0, NOW()
                      FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, sku);
        });
        return number;
    }

    /**
     * A production order of a paid customer order released to {@code warehouse}, PENDING_PREPRESS as a release creates
     * it and due on {@code due}: overdue.
     */
    private void production(String number, UUID warehouse, LocalDate due) {
        UUID releasedBy = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID draft = Identifiers.newId();
            UUID snapshot = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID,
                    DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, CHECKSUM);
            UUID order = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                        total_amount, currency, placed_at, version, created_at, paid_amount, paid_in_full_at,
                        warehouse_id, released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 5000000, 'VND', NOW(), 0, NOW(), 5000000, NOW(), ?, NOW(),
                        ?)""",
                    order, "SO-20991227-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID(), warehouse, releasedBy);
            UUID line = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                                                     design_snapshot_id, design_checksum)
                    VALUES (?, ?, ?, 5000, 1000, 'VND', ?, ?)""", line, order, BLANK, snapshot, CHECKSUM);
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id,
                        blank_sku, design_snapshot_id, design_checksum, planned_quantity, due_date, sales_order_id,
                        sales_order_line_id)
                    VALUES (?, ?, 'ORDER', 'IN_HOUSE', 'PENDING_PREPRESS', ?, ?, ?, ?, 5000, ?, ?, ?)""",
                    Identifiers.newId(), number, warehouse, BLANK, snapshot, CHECKSUM, Date.valueOf(due), order,
                    line);
        });
    }
}
