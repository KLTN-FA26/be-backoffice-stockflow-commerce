package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.BookVisibility;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.inventory.internal.service.LotTraces;
import com.stockflow.inventory.internal.service.StockBrowsing;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.CartonRows;
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

import java.time.Instant;
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
 * SCRUM-146, owner decision BLIND (28/10), by carton since team plan 4C-3 (kltn-docs 20 §4.2, BR-07): the books of a
 * carton a cycle count holds - open, or submitted with its
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
    private static final int CARTON = 50;

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
    /** Both at {@link #BIN} and on the count; {@link #cartonA} has a manual write-off pending, {@link #cartonB} is missing. */
    private CartonRows.Carton cartonA;
    private CartonRows.Carton cartonB;
    /** At {@link #BIN3}: never on the count. */
    private CartonRows.Carton cartonC;
    private UUID clerk;
    private UUID manager;
    private UUID approver;
    /** Asked for before any count, of carton A: touches {@link #BIN}. */
    private UUID manualAdjustment;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        sku = "BLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = staff("WAREHOUSE_STAFF");
        manager = staff("WAREHOUSE_MANAGER");
        approver = staff("WAREHOUSE_MANAGER");
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Blind count fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Blind count fixture");
        });
        Instant received = Instant.parse("2026-01-02T00:00:00Z");
        cartonA = tx.execute(s -> CartonRows.stock(jdbc, sku, BIN, 1, CARTON, "L1", received)).getFirst();
        cartonB = tx.execute(s -> CartonRows.stock(jdbc, sku, BIN, 1, CARTON, "L1", received)).getFirst();
        cartonC = tx.execute(s -> CartonRows.stock(jdbc, sku, BIN3, 1, CARTON, "L1", received)).getFirst();
        lot = jdbc.queryForObject("select lot_id from inventory.stock_item where id = ?", UUID.class,
                cartonA.stockItemId());
        tx.executeWithoutResult(s -> {
            movement(BIN, BIN2, 4, cartonA.id());
            movement(BIN2, BIN3, 2, null);
        });
        manualAdjustment = operations.requestPackageAdjustment(new StockOperations.PackageAdjustmentRequest(
                cartonA.lpn(), AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null, clerk))
                .adjustmentId();
    }

    /**
     * A ledger line moved for a document, naming {@code carton} when given. Not a move by hand: a person moves whole
     * cartons by task ({@code ck_stock_movement_move_task_carton}); what this class reads is the ledger, and which
     * carton a line names is what hides it from a counter.
     */
    private void movement(String from, String to, int quantity, UUID carton) {
        jdbc.update("""
                insert into inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code,
                    to_location_code, quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                    lot_id, from_stock_package_id, to_stock_package_id)
                values (?, 'MOVE', ?, 'L1', ?, ?, ?, 'AVAILABLE', 'AVAILABLE', 'ORDER', ?, now(), ?, ?, ?)""",
                Identifiers.newId(), sku, from, to, quantity, Identifiers.newId(), lot, carton, carton);
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

    private UUID lineOf(CycleCountView count, UUID carton) {
        return count.lines().stream().filter(l -> carton.equals(l.stockPackageId())).findFirst().orElseThrow().id();
    }

    /** Carton A scanned present, carton B not found: one shortage, of B, submitted and waiting for approval. */
    private UUID submittedVariance(CycleCountView count) {
        countBoth(count);
        cycleCounts.submit(count.id(), clerk, false);
        return jdbc.queryForObject("select id from inventory.stock_adjustment where cycle_count_line_id = ?",
                UUID.class, lineOf(count, cartonB.id()));
    }

    private void countBoth(CycleCountView count) {
        cycleCounts.count(count.id(), lineOf(count, cartonA.id()), true, clerk, false);
        cycleCounts.count(count.id(), lineOf(count, cartonB.id()), false, clerk, false);
    }

    // ------------------------------------------------------------------ stock rows

    @Test
    @DisplayName("stock by SKU: while a count holds the bin's cartons the counter's rows have no quantity; the "
            + "approver's and the rows after the count are normal")
    void stockItemsOfASku() throws Exception {
        String url = INVENTORY + "/stock-items?sku=" + sku;
        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(CARTON))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));

        CycleCountView count = plan();

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].locationCode").value(BIN))
                .andExpect(jsonPath("$.data[0].onHand").doesNotExist())
                .andExpect(jsonPath("$.data[0].reserved").doesNotExist())
                .andExpect(jsonPath("$.data[0].available").doesNotExist())
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(true));
        asManager(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(CARTON))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));

        cycleCounts.cancel(count.id(), "done", "test");

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHand").value(CARTON))
                .andExpect(jsonPath("$.data[0].hiddenDuringCount").value(false));
    }

    @Test
    @DisplayName("warehouse stock page: the held slot's row is masked (and ordering by on hand is ignored); other "
            + "rows and the approver see everything")
    void warehouseStockRows() throws Exception {
        plan();
        String url = INVENTORY + "/warehouses/" + WAREHOUSE_HCM + "/stock-items?sku=" + sku + "&sort=onHand,desc";

        asStaff(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].hiddenDuringCount")
                        .value(org.hamcrest.Matchers.contains(true, true)))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].onHand")
                        .value(org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN3 + "')].onHand")
                        .value(org.hamcrest.Matchers.contains(CARTON)))
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN3 + "')].hiddenDuringCount")
                        .value(org.hamcrest.Matchers.contains(false)));
        asManager(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.locationCode == '" + BIN + "')].onHand")
                        .value(org.hamcrest.Matchers.contains(CARTON, CARTON)))
                .andExpect(jsonPath("$.data.items[0].locationCode").value(BIN));
        assertThat(cartonC).isNotNull();
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
                .andExpect(jsonPath("$.data.quantityDelta").value(-CARTON));

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
        asManager(get(trace)).andExpect(jsonPath("$.data.balances[0].onHand").value(2 * CARTON))
                .andExpect(jsonPath("$.data.balances[0].hiddenDuringCount").value(false));
        asStaff(get(trace + "/movements")).andExpect(jsonPath("$.data.totalElements").value(1));
        asManager(get(trace + "/movements")).andExpect(jsonPath("$.data.totalElements").value(2));

        cycleCounts.cancel(count.id(), "done", "test");

        asStaff(get(trace)).andExpect(jsonPath("$.data.balances[0].onHand").value(2 * CARTON));
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

        countBoth(planned);
        assertThat(held()).as("in progress").isEqualTo(Held.EVERYTHING);

        cycleCounts.submit(planned.id(), clerk, false);
        assertThat(held()).as("completed, variance pending").isEqualTo(Held.EVERYTHING);

        UUID variance = jdbc.queryForObject("select id from inventory.stock_adjustment where cycle_count_line_id = ?",
                UUID.class, lineOf(planned, cartonB.id()));
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
