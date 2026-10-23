package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cycle-count endpoints through the real filter chain, permission aspect and data scope, with the seeded role
 * grants: the planner plans and counts but does not decide, so a blind count never shows them the books; the
 * warehouse manager decides and sees them; the warehouse staff (who hold only VIEW_PAGE and READ on this branch)
 * neither plan nor decide. Nothing here relies on the staff's UPDATE grant, which is Tú's migration.
 */
@SecuredIntegrationTest
class CycleCountHttpSecurityIntegrationTest {

    private static final String BASE = "/api/v1/inventory/cycle-counts";
    private static final String BIN = "HCM-A01-1-B";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CycleCounts cycleCounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private String sku;
    /** A real account: a count the planner enters names them in counted_by, a foreign key. */
    private UUID planner;
    private UUID manager;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        sku = "CCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        planner = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Cycle count HTTP fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Cycle count HTTP fixture");
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        });
    }

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        return id;
    }

    /** A blind count of this test's SKU in {@link #BIN}, planned below HTTP (no caller scope: the system). */
    private CycleCountView planned() {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, null, true, "Count the top shelf",
                List.of(), List.of(), List.of(BIN), List.of(sku))).count();
    }

    private String planBody() {
        return """
                {"warehouseId":"%s","scope":{"locationCodes":["%s"],"skus":["%s"]}}"""
                .formatted(WAREHOUSE_HCM, BIN, sku);
    }

    @Test
    @DisplayName("after submission a counter still sees no expected quantity, note or adjustment; the manager does")
    void counterNeverSeesTheBooks() throws Exception {
        CycleCountView count = planned();
        cycleCounts.count(count.id(), count.lines().get(0).id(), 7, null, planner, false);
        cycleCounts.submit(count.id(), planner, false);

        mvc.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + count.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[0].countedQty").value(7))
                .andExpect(jsonPath("$.data.lines[*].expectedQty").isEmpty())
                .andExpect(jsonPath("$.data.lines[*].adjustments[*]").isEmpty())
                .andExpect(jsonPath("$.data.note").doesNotExist());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(BASE + "/" + count.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].expectedQty").value(10))
                .andExpect(jsonPath("$.data.lines[0].adjustments[0].quantityDelta").value(-3))
                .andExpect(jsonPath("$.data.lines[0].adjustments[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.note").value("Count the top shelf"));
    }

    @Test
    @DisplayName("warehouse staff can neither plan a count nor send one for recount: 403 FORBIDDEN")
    void warehouseStaffCannotPlanOrDecide() throws Exception {
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView count = planned();

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(BASE).contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(BASE + "/" + count.id() + "/recounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineIds\":[\"%s\"],\"note\":\"again\"}".formatted(count.lines().get(0).id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(BASE + "/" + count.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[*].expectedQty").isEmpty());
    }

    @Test
    @DisplayName("a manager of another warehouse is refused with OUT_OF_DATA_SCOPE, reading or planning; HCM's own "
            + "manager gets past the scope to the business answer (the slot is already on a count)")
    void outOfScopeWarehouseIsDenied() throws Exception {
        UUID elsewhere = staff("WAREHOUSE_MANAGER", otherWarehouse());
        CycleCountView count = planned();

        mvc.asRole(Role.WAREHOUSE_MANAGER, elsewhere, get(BASE + "/" + count.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, elsewhere, post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CYCLE_COUNT_EMPTY_SCOPE"));
    }

    @Test
    @DisplayName("a count body needs exactly one of countedQty and present: 400, then a count goes through")
    void countBodyNeedsExactlyOneOfCountedOrPresent() throws Exception {
        CycleCountView count = planned();
        String path = BASE + "/" + count.id() + "/lines/" + count.lines().get(0).id() + "/count";

        mvc.asRole(Role.INVENTORY_PLANNER, planner, put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.asRole(Role.INVENTORY_PLANNER, planner, put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countedQty\":3,\"present\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.asRole(Role.INVENTORY_PLANNER, planner, put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countedQty\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lines[0].countedQty").value(9))
                .andExpect(jsonPath("$.data.lines[0].expectedQty").doesNotExist());
    }
}
