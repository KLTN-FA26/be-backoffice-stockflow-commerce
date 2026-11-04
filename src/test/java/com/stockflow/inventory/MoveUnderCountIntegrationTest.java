package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-146, owner decision MOVE-HELD (28/10): a manual stock move does not take loose stock out of, or put it into,
 * a slot a cycle count holds (open, or submitted with its variance still pending). The slot is the bin, SKU and lot,
 * the one {@code CountBusy} predicate reads; a count of another bin of the same SKU leaves the move alone.
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
            stock(A, 10);
            stock(C, 5);
        });
    }

    private void stock(String bin, int onHand) {
        jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                    reserved, status, version, created_at)
                values (?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                Identifiers.newId(), sku, bin, Date.valueOf(LocalDate.now().plusYears(1)),
                Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")), onHand);
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

    private void move(String from, String to) {
        operations.move(new MoveStockCommand(Identifiers.newId(), new Sku(sku), null, from, to, 1,
                MoveReference.MOVE_TASK, null, manager));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private void count(CycleCountView count, int counted) {
        cycleCounts.count(count.id(), count.lines().get(0).id(), counted, null, clerk, false);
    }

    @Test
    @DisplayName("out of a held slot: 409 STOCK_SLOT_UNDER_COUNT, nothing moves")
    void moveOutOfAHeldSlot() {
        plan(A);

        assertThat(code(catchThrowable(() -> move(A, B)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        assertThat(jdbc.queryForObject("select sum(on_hand) from inventory.stock_item where sku = ? and location_code = ?",
                Integer.class, sku, A)).isEqualTo(10);
    }

    @Test
    @DisplayName("into a held slot: 409 STOCK_SLOT_UNDER_COUNT")
    void moveIntoAHeldSlot() {
        plan(C);

        assertThat(code(catchThrowable(() -> move(A, C)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
    }

    @Test
    @DisplayName("a slot is bin, SKU and lot: a count of another bin does not stop the move")
    void unrelatedSlotsAreFree() {
        plan(C);

        move(A, B);

        assertThat(jdbc.queryForObject("select sum(on_hand) from inventory.stock_item where sku = ? and location_code = ?",
                Integer.class, sku, B)).isEqualTo(1);
    }

    @Test
    @DisplayName("the slot is free again when the count is submitted with no variance, or cancelled; held while the "
            + "variance is pending")
    void freedWhenTheCountIsDone() {
        CycleCountView matching = plan(A);
        count(matching, 10);
        cycleCounts.submit(matching.id(), clerk, false);
        move(A, B);

        CycleCountView cancelled = plan(C);
        assertThat(code(catchThrowable(() -> move(A, C)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
        cycleCounts.cancel(cancelled.id(), "done", "test");
        move(A, C);

        CycleCountView short_ = plan(A);
        count(short_, 7);
        cycleCounts.submit(short_.id(), clerk, false);
        assertThat(code(catchThrowable(() -> move(A, B)))).isEqualTo(ErrorCode.STOCK_SLOT_UNDER_COUNT);
    }

    @Test
    @DisplayName("through HTTP: 409 with the code and the location and the count named, for a caller who may move stock")
    void httpContract() throws Exception {
        CycleCountView count = plan(A);

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/inventory/movements")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","sku":"%s","fromLocation":"%s","toLocation":"%s","quantity":1}"""
                                .formatted(UUID.randomUUID(), sku, A, B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_SLOT_UNDER_COUNT"))
                .andExpect(jsonPath("$.details.locationCode").value(A))
                .andExpect(jsonPath("$.details.cycleCountNumber").value(count.number()));
    }
}
