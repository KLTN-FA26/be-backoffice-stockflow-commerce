package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.internal.service.BookVisibility;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.inventory.internal.service.LotTraces;
import com.stockflow.inventory.internal.service.StockBrowsing;
import com.stockflow.inventory.internal.service.StockOperations;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-146, owner decision BLIND (28/10): the books of a slot a cycle count holds - open, or submitted with its
 * variance still pending approval - are for who holds {@code inventory-cycle-counts:APPROVE}, in every stock screen
 * and not just on the count screen. For everyone else the stock rows of the slot lose their quantities
 * ({@code hiddenDuringCount}), and its ledger lines and adjustments are left out.
 *
 * <p>The HTTP tests go through the real filter chain with the seeded grants: the warehouse staff read stock, the
 * ledger and adjustments but do not decide counts; the warehouse manager does. The matrix test runs the same reads
 * over every state a slot passes through, because the SQL rule ({@code CountBusy}) and the criteria rule
 * ({@code UnderCountSpecs}) are two statements of one predicate and must not drift.</p>
 */
@SecuredIntegrationTest
class BlindCountIntegrationTest {

    private static final String BIN = "HCM-A01-1-B";
    private static final String BIN2 = "HCM-A01-2-A";
    private static final String BIN3 = "HCM-A01-1-A";
    private static final String INVENTORY = "/api/v1/inventory";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CycleCounts cycleCounts;
    @Autowired StockOperations operations;
    @Autowired StockBrowsing browsing;
    @Autowired LotTraces traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private String sku;
    private UUID lot;
    private UUID stockItem;
    private UUID clerk;
    private UUID manager;
    private UUID approver;
    /** Asked for before any count: touches {@link #BIN}. */
    private UUID manualAdjustment;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        sku = "BLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = staff("WAREHOUSE_STAFF");
        manager = staff("WAREHOUSE_MANAGER");
        approver = staff("WAREHOUSE_MANAGER");
        stockItem = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Blind count fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Blind count fixture");
            // The lot is made by the table's trigger (an UNTRACED one), as for any stock that entered without a receipt.
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at)
                    values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    stockItem, sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
            lot = jdbc.queryForObject("select lot_id from inventory.stock_item where id = ?", UUID.class, stockItem);
            movement(BIN, BIN2, 4);
            movement(BIN2, BIN3, 2);
        });
        manualAdjustment = operations.requestAdjustment(new RequestAdjustmentCommand(BIN, new Sku(sku), null, -1,
                StockAdjustmentReason.DAMAGED, null, clerk)).adjustmentId();
    }

    private void movement(String from, String to, int quantity) {
        jdbc.update("""
                insert into inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                    quantity, from_status, to_status, reference_type, reference_id, occurred_at, lot_id)
                values (?, 'MOVE', ?, ?, ?, ?, 'AVAILABLE', 'AVAILABLE', 'MOVE_TASK', ?, now(), ?)""",
                Identifiers.newId(), sku, from, to, quantity, Identifiers.newId(), lot);
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, WAREHOUSE_HCM);
        });
        return user;
    }

    /** A blind count of {@link #BIN} for this test's SKU. */
    private CycleCountView plan() {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, clerk, true, "Count the bin",
                List.of(), List.of(), List.of(BIN), List.of(sku)), false).count();
    }

    private ResultActions asStaff(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.asRole(Role.WAREHOUSE_STAFF, clerk, request);
    }

    private ResultActions asManager(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.asRole(Role.WAREHOUSE_MANAGER, manager, request);
    }

    private UUID submittedVariance(CycleCountView count) {
        cycleCounts.count(count.id(), count.lines().get(0).id(), 7, null, clerk, false);
        cycleCounts.submit(count.id(), clerk, false);
        return jdbc.queryForObject("select id from inventory.stock_adjustment where cycle_count_line_id = ?",
                UUID.class, count.lines().get(0).id());
    }

    // ------------------------------------------------------------------ stock rows

    @Test
    @DisplayName("stock by SKU: while a count holds the bin the counter's row has no quantity; the approver's and "
            + "the row after the count are normal")
    void stockItemsOfASku() throws Exception {
        String url = INVENTORY + "/stock-items?sku=" + sku;
        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(10))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));

        CycleCountView count = plan();

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].locationCode").value(BIN))
                .andExpect(jsonPath("$.data[0].onHand").doesNotExist())
                .andExpect(jsonPath("$.data[0].reserved").doesNotExist())
                .andExpect(jsonPath("$.data[0].available").doesNotExist())
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(true));
        asManager(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(10))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));

        cycleCounts.cancel(count.id(), "done", "test");

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(10))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));
    }

    @Test
    @DisplayName("warehouse stock page: the held slot's row is masked (and ordering by on hand is ignored); other "
            + "rows and the approver see everything")
    void warehouseStockRows() throws Exception {
        UUID other = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand, reserved,
                    status, version, created_at)
                values (?, ?, ?, ?, ?, 6, 0, 'AVAILABLE', 0, now())""", other, sku, BIN3,
                Date.valueOf(LocalDate.now().plusYears(1)), Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"))));
        plan();
        String url = INVENTORY + "/warehouses/" + WAREHOUSE_HCM + "/stock-items?sku=" + sku + "&sort=onHand,desc";

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].hiddenDuringCount")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].onHand")
                        .value(org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN3 + "')].onHand")
                        .value(org.hamcrest.Matchers.contains(6)))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN3 + "')].hiddenDuringCount")
                        .value(org.hamcrest.Matchers.contains(false)));
        asManager(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].onHand")
                        .value(org.hamcrest.Matchers.contains(10)))
                .andExpect(jsonPath("$.data.items[0].locationCode").value(BIN));
    }

    // ------------------------------------------------------------------ ledger and adjustments

    @Test
    @DisplayName("ledger: lines touching the held bin are left out for the counter, the rest stay; the approver "
            + "sees all; after the count closes the counter sees them again")
    void ledger() throws Exception {
        String url = INVENTORY + "/movements?sku=" + sku;
        asStaff(get(url)).andExpect(jsonPath("$.data.totalElements").value(2));

        CycleCountView count = plan();

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].fromLocation").value(BIN2))
                .andExpect(jsonPath("$.data.items[0].toLocation").value(BIN3));
        asStaff(get(INVENTORY + "/movements?location=" + BIN + "&sku=" + sku))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        asManager(get(url)).andExpect(jsonPath("$.data.totalElements").value(2));

        cycleCounts.cancel(count.id(), "done", "test");

        asStaff(get(url)).andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("adjustments: a pending COUNT_VARIANCE and any adjustment of the held bin are left out for the "
            + "counter (list and detail, 404); the approver sees them; once decided they are back")
    void adjustments() throws Exception {
        CycleCountView count = plan();
        UUID variance = submittedVariance(count);
        String list = INVENTORY + "/adjustments?sku=" + sku;

        asStaff(get(list)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        asStaff(get(INVENTORY + "/adjustments/" + variance)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STOCK_ADJUSTMENT_NOT_FOUND"));
        asStaff(get(INVENTORY + "/adjustments/" + manualAdjustment)).andExpect(status().isNotFound());
        asManager(get(list)).andExpect(jsonPath("$.data.totalElements").value(2));
        asManager(get(INVENTORY + "/adjustments/" + variance)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityDelta").value(-3));

        // Approving the variance frees the slot: the delta is now history, and visible.
        mvc.asRole(Role.WAREHOUSE_MANAGER, approver, post(INVENTORY + "/adjustments/" + variance + "/approval"))
                .andExpect(status().isOk());

        asStaff(get(list)).andExpect(jsonPath("$.data.totalElements").value(2));
        asStaff(get(INVENTORY + "/adjustments/" + variance)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ lot trace

    @Test
    @DisplayName("lot trace: the balance at the held bin has no quantity and its ledger lines are left out for the "
            + "counter; the approver sees both")
    void lotTrace() throws Exception {
        CycleCountView count = plan();
        String trace = INVENTORY + "/lots/" + lot;

        asStaff(get(trace)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balances[0].locationCode").value(BIN))
                .andExpect(jsonPath("$.data.balances[0].onHand").doesNotExist())
                .andExpect(jsonPath("$.data.balances[0].hiddenDuringCount").value(true));
        asManager(get(trace)).andExpect(jsonPath("$.data.balances[0].onHand").value(10))
                .andExpect(jsonPath("$.data.balances[0].hiddenDuringCount").value(false));
        asStaff(get(trace + "/movements")).andExpect(jsonPath("$.data.totalElements").value(1));
        asManager(get(trace + "/movements")).andExpect(jsonPath("$.data.totalElements").value(2));

        cycleCounts.cancel(count.id(), "done", "test");

        asStaff(get(trace)).andExpect(jsonPath("$.data.balances[0].onHand").value(10));
        asStaff(get(trace + "/movements")).andExpect(jsonPath("$.data.totalElements").value(2));
    }

    // ------------------------------------------------------------------ one rule, every state

    @Test
    @DisplayName("every read agrees on what is held, through every state of a slot: planned, counting, submitted "
            + "with the variance pending, decided, and a cancelled count")
    void everyReadAgreesThroughEveryState() {
        assertThat(held()).as("no count").isEqualTo(Held.NOTHING);

        CycleCountView planned = plan();
        assertThat(held()).as("planned").isEqualTo(Held.EVERYTHING);

        cycleCounts.count(planned.id(), planned.lines().get(0).id(), 7, null, clerk, false);
        assertThat(held()).as("in progress").isEqualTo(Held.EVERYTHING);

        cycleCounts.submit(planned.id(), clerk, false);
        assertThat(held()).as("completed, variance pending").isEqualTo(Held.EVERYTHING);

        UUID variance = jdbc.queryForObject("select id from inventory.stock_adjustment where cycle_count_line_id = ?",
                UUID.class, planned.lines().get(0).id());
        tx.executeWithoutResult(s -> operations.reject(variance, approver, "Doubtful"));
        assertThat(held()).as("completed, variance rejected").isEqualTo(Held.NOTHING);

        CycleCountView again = plan();
        assertThat(held()).as("recount planned").isEqualTo(Held.EVERYTHING);
        cycleCounts.cancel(again.id(), "never mind", "test");
        assertThat(held()).as("cancelled").isEqualTo(Held.NOTHING);
    }

    /** What each read withholds from the counter, in the same units: nothing, or everything about {@link #BIN}. */
    private Held held() {
        var rows = browsing.warehouseStock(WAREHOUSE_HCM, new StockBrowsing.StockQuery(null, sku, null, null, false, 0,
                20, null), BookVisibility.HIDE_COUNTED_SLOTS).items();
        boolean rowHidden = rows.stream().filter(r -> BIN.equals(r.locationCode())).allMatch(r -> r.hiddenDuringCount());
        long ledger = operations.ledger(sku, null, null, 0, 20, BookVisibility.HIDE_COUNTED_SLOTS).totalElements();
        long adjustments = operations.adjustments(null, sku, null, 0, 20, null, BookVisibility.HIDE_COUNTED_SLOTS)
                .totalElements();
        long lotLines = traces.movements(lot, null, null, null, 0, 20, BookVisibility.HIDE_COUNTED_SLOTS).totalElements();
        boolean balanceHidden = traces.trace(lot, null, BookVisibility.HIDE_COUNTED_SLOTS).balances().stream()
                .filter(b -> BIN.equals(b.locationCode())).allMatch(b -> b.hiddenDuringCount());
        long allAdjustments = operations.adjustments(null, sku, null, 0, 20, null, BookVisibility.FULL).totalElements();

        boolean nothing = !rowHidden && ledger == 2 && adjustments == allAdjustments && lotLines == 2 && !balanceHidden;
        boolean everything = rowHidden && ledger == 1 && adjustments == 0 && lotLines == 1 && balanceHidden;
        return nothing ? Held.NOTHING : everything ? Held.EVERYTHING
                : Held.DISAGREE;
    }

    private enum Held { NOTHING, EVERYTHING, DISAGREE }
}
