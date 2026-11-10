package com.stockflow.inventory;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-146, owner decision MOVE-HELD (28/10), kltn-docs 11 BR-06: a carton moved by hand does not leave a slot a
 * cycle count holds (its own carton slot, open or submitted with its variance still pending) - the one
 * {@code CountBusy} predicate reads. A count holds cartons only (team plan 4C-3), so a carton may arrive in a bin whose
 * cartons are being counted: it is on no count line, and an arrival changes no counted line (the "BR-06 arrival rule"
 * 4C-2 left to this package). A count of another bin of the same SKU leaves the move alone.
 */
@SecuredIntegrationTest
class MoveUnderCountIntegrationTest {

    private static final String A = "HCM-A01-1-B";
    private static final String B = "HCM-A01-2-A";
    private static final String C = "HCM-A01-1-A";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CycleCounts cycleCounts;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private String sku;
    private UUID clerk;
    private UUID manager;
    /** The carton that starts at {@link #A}, and the one at {@link #C}. */
    private String lpnA;
    private String lpnC;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        sku = "MHD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = user("WAREHOUSE_STAFF");
        manager = user("WAREHOUSE_MANAGER");
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Move under count fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Move under count fixture");
            lpnA = CartonRows.stock(jdbc, sku, A, 1, 10, "L1",
                    Instant.parse("2026-01-02T00:00:00Z"), "AVAILABLE").getFirst().lpn();
            lpnC = CartonRows.stock(jdbc, sku, C, 1, 10, "L1",
                    Instant.parse("2026-01-02T00:00:00Z"), "AVAILABLE").getFirst().lpn();
        });
    }

    private UUID user(String role) {
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

    private CycleCountView plan(String bin) {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, clerk, true, "Count the bin",
                List.of(), List.of(), List.of(bin), List.of(sku)), false).count();
    }

    /** The carton {@code lpn}, whole, to {@code to}. */
    private void move(String lpn, String to) {
        operations.moveStockPackages(new MoveStockPackagesCommand(List.of(lpn), to,
                MoveReference.MOVE_TASK, Identifiers.newId(), manager));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private void scan(CycleCountView count, String lpn, boolean found) {
        UUID line = count.lines().stream().filter(l -> lpn.equals(l.lpn())).findFirst().orElseThrow().id();
        cycleCounts.count(count.id(), line, found, clerk, false);
    }

    @Test
    @DisplayName("out of a held slot: 409 STOCK_SLOT_UNDER_COUNT, nothing moves")
    void moveOutOfAHeldSlot() {
        plan(A);

        assertThat(code(catchThrowable(() -> move(lpnA, B)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        assertThat(jdbc.queryForObject("select sum(on_hand) from inventory.stock_item where sku = ? and location_code = ?",
                Integer.class, sku, A)).isEqualTo(10);
    }

    @Test
    @DisplayName("into a bin whose cartons are being counted: allowed - the arrival is on no line and changes no counted line")
    void moveIntoACountedBinIsAllowed() {
        CycleCountView count = plan(C);

        move(lpnA, C);

        assertThat(jdbc.queryForObject("select location_code from inventory.stock_package where lpn = ?", String.class,
                lpnA)).isEqualTo(C);
        assertThat(cycleCounts.get(count.id(), true).lines()).extracting(CycleCountView.Line::lpn)
                .containsExactly(lpnC);
        // The carton that arrived is no one's count: the count of the bin still submits on its own line.
        scan(count, lpnC, true);
        assertThat(cycleCounts.submit(count.id(), clerk, false).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a slot is one carton: a count of another bin does not stop the move")
    void unrelatedSlotsAreFree() {
        plan(C);

        move(lpnA, B);

        assertThat(jdbc.queryForObject("select sum(on_hand) from inventory.stock_item where sku = ? and location_code = ?",
                Integer.class, sku, B)).isEqualTo(10);
    }

    @Test
    @DisplayName("the carton is free again when its count is submitted with no variance, or cancelled; held while the "
            + "variance is pending")
    void freedWhenTheCountIsDone() {
        CycleCountView matching = plan(A);
        scan(matching, lpnA, true);
        cycleCounts.submit(matching.id(), clerk, false);
        move(lpnA, B);

        CycleCountView cancelled = plan(B);
        assertThat(code(catchThrowable(() -> move(lpnA, C)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        cycleCounts.cancel(cancelled.id(), "done", "test");
        move(lpnA, C);

        // The carton not found: its COUNT_VARIANCE waits for approval, and the carton stays where the count holds it.
        CycleCountView missing = plan(C);
        scan(missing, lpnA, false);
        scan(missing, lpnC, true);
        cycleCounts.submit(missing.id(), clerk, false);
        assertThat(code(catchThrowable(() -> move(lpnA, B)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        move(lpnC, B);
    }

    @Test
    @DisplayName("through HTTP: 409 with the code and the location and the count named, for a caller who may move stock")
    void httpContract() throws Exception {
        CycleCountView count = plan(A);

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/inventory/movements")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","lpns":["%s"],"toLocation":"%s"}"""
                                .formatted(UUID.randomUUID(), lpnA, B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_SLOT_UNDER_COUNT"))
                .andExpect(jsonPath("$.details.locationCode").value(A))
                .andExpect(jsonPath("$.details.cycleCountNumber").value(count.number()));
    }
}
