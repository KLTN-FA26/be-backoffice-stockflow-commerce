package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.CycleCounts.PlanCycleCountCommand;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.CartonRows;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cycle-count endpoints through the real filter chain, permission aspect and data scope, with the seeded role
 * grants: the planner plans and counts but does not decide, so a blind count never shows them the books; the
 * warehouse manager decides and sees them; the warehouse staff count what they are assigned (they hold UPDATE since
 * {@code V20261028001000}) but neither plan nor decide. Four eyes runs through both halves: nobody replaces the
 * assignee's figure but the assignee, and nobody approves the variance of a line they counted.
 */
@SecuredIntegrationTest
class CycleCountHttpSecurityIntegrationTest {

    private static final String BASE = "/api/v1/inventory/cycle-counts";
    private static final String BIN = "HCM-A01-1-B";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired CycleCounts cycleCounts;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private String sku;
    /** A real account: a count the planner enters names them in counted_by, a foreign key. */
    private UUID planner;
    private UUID manager;
    /** The one carton of the SKU, at {@link #BIN}: a count line, and a variance of 10 units when it is not found. */
    private CartonRows.Carton carton;

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
            carton = CartonRows.stock(jdbc, sku, BIN, 1, 10, "L1", LocalDate.now().plusYears(1),
                    Instant.parse("2026-01-02T00:00:00Z"), "AVAILABLE").getFirst();
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
                List.of(), List.of(), List.of(BIN), List.of(sku)), false).count();
    }

    /** As {@link #planned()}, handed to {@code assignee}. */
    private CycleCountView plannedFor(UUID assignee) {
        return cycleCounts.plan(new PlanCycleCountCommand(WAREHOUSE_HCM, null, assignee, true, "Count the top shelf",
                List.of(), List.of(), List.of(BIN), List.of(sku)), false).count();
    }

    private String countPath(CycleCountView count) {
        return BASE + "/" + count.id() + "/lines/" + count.lines().get(0).id() + "/count";
    }

    private static String found(boolean found) {
        return "{\"found\":" + found + "}";
    }

    private UUID variance(CycleCountView count) {
        return jdbc.queryForObject("select id from inventory.stock_adjustment where cycle_count_line_id = ?",
                UUID.class, count.lines().get(0).id());
    }

    private String planBody() {
        return """
                {"warehouseId":"%s","note":"Count the top shelf","scope":{"locationCodes":["%s"],"skus":["%s"]}}"""
                .formatted(WAREHOUSE_HCM, BIN, sku);
    }

    @Test
    @DisplayName("after submission a counter still sees no booked state, note or adjustment; the manager does")
    void counterNeverSeesTheBooks() throws Exception {
        CycleCountView count = planned();
        cycleCounts.count(count.id(), count.lines().get(0).id(), false, planner, false);
        cycleCounts.submit(count.id(), planner, false);

        mvc.asRole(Role.INVENTORY_PLANNER, planner, get(BASE + "/" + count.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[0].found").value(false))
                .andExpect(jsonPath("$.data.lines[0].lpn").value(carton.lpn()))
                .andExpect(jsonPath("$.data.lines[*].booked").isEmpty())
                .andExpect(jsonPath("$.data.lines[*].expectedQty").doesNotExist())
                .andExpect(jsonPath("$.data.lines[*].countedQty").doesNotExist())
                .andExpect(jsonPath("$.data.lines[*].adjustments[*]").isEmpty())
                .andExpect(jsonPath("$.data.note").doesNotExist());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(BASE + "/" + count.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].booked").value(true))
                .andExpect(jsonPath("$.data.lines[0].found").value(false))
                .andExpect(jsonPath("$.data.lines[0].adjustments[0].quantityDelta").value(-10))
                .andExpect(jsonPath("$.data.lines[0].adjustments[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.note").value("Count the top shelf"));
    }

    @Test
    @DisplayName("a plan answers with the books only to who decides; the list carries line tallies, never lines, and "
            + "the variance tally only for who decides")
    void planAndListShowTheBooksOnlyToWhoDecides() throws Exception {
        String planned = mvc.asRole(Role.INVENTORY_PLANNER, planner, post(BASE)
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count.lines[0].found").doesNotExist())
                .andExpect(jsonPath("$.data.count.lines[0].booked").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID first = UUID.fromString(com.jayway.jsonpath.JsonPath.read(planned, "$.data.count.id"));
        cycleCounts.cancel(first, "Planned again by the manager", "test");

        String managers = mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(BASE)
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count.lines[0].booked").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(managers, "$.data.count.id");
        cycleCounts.count(UUID.fromString(id), UUID.fromString(com.jayway.jsonpath.JsonPath.read(managers,
                "$.data.count.lines[0].id")), false, planner, false);

        String row = "$.data.items[?(@.id == '" + id + "')]";
        String list = BASE + "?warehouseId=" + WAREHOUSE_HCM + "&status=IN_PROGRESS&size=50";
        mvc.asRole(Role.INVENTORY_PLANNER, planner, get(list))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".lineCount").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(row + ".countedCount").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(row + ".varianceCount").doesNotExist())
                .andExpect(jsonPath(row + ".note").doesNotExist())
                .andExpect(jsonPath(row + ".lines").doesNotExist());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(list))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".varianceCount").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(row + ".note").value(org.hamcrest.Matchers.contains("Count the top shelf")))
                .andExpect(jsonPath(row + ".lines").doesNotExist());
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
                .andExpect(jsonPath("$.data.lines[*].booked").isEmpty());
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
    @DisplayName("a count body is {found}: no body, no found, the old quantity shapes and a non-boolean are 400; "
            + "then a count goes through")
    void countBodyNeedsFound() throws Exception {
        CycleCountView count = planned();
        String path = BASE + "/" + count.id() + "/lines/" + count.lines().get(0).id() + "/count";

        for (String body : List.of("{}", "{\"countedQty\":9}", "{\"present\":true}", "{\"found\":null}",
                "{\"found\":\"maybe\"}")) {
            mvc.asRole(Role.INVENTORY_PLANNER, planner, put(path).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select counted_qty from inventory.cycle_count_line where id = ?",
                Integer.class, count.lines().get(0).id())).as("nothing recorded by a refused body").isNull();
        mvc.asRole(Role.INVENTORY_PLANNER, planner, put(path).contentType(MediaType.APPLICATION_JSON)
                        .content(found(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lines[0].found").value(true))
                .andExpect(jsonPath("$.data.lines[0].booked").doesNotExist());
    }

    @Test
    @DisplayName("a carton the plan missed is added by {locationCode, lpn}; the old sku/lot/countedQty shape is 400")
    void addLineTakesLocationAndLpn() throws Exception {
        UUID second = tx.execute(s -> CartonRows.stock(jdbc, sku, "HCM-A01-2-A", 1, 10, "L1",
                LocalDate.now().plusYears(1), Instant.parse("2026-01-02T00:00:00Z"), "AVAILABLE")).getFirst().id();
        CycleCountView count = planned();
        assertThat(second).isNotNull();

        mvc.asRole(Role.INVENTORY_PLANNER, planner, post(BASE + "/" + count.id() + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationCode\":\"%s\",\"sku\":\"%s\",\"countedQty\":3}".formatted(BIN, sku)))
                .andExpect(status().isBadRequest());
        mvc.asRole(Role.INVENTORY_PLANNER, planner, post(BASE + "/" + count.id() + "/lines")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lpn\":\"%s\"}".formatted(carton.lpn())))
                .andExpect(status().isBadRequest());
        mvc.asRole(Role.INVENTORY_PLANNER, planner, post(BASE + "/" + count.id() + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationCode\":\"HCM-A01-2-A\",\"lpn\":\"%s\"}".formatted(
                                jdbc.queryForObject("select lpn from inventory.stock_package where id = ?", String.class,
                                        second))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[?(@.locationCode == 'HCM-A01-2-A')].found")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("the warehouse staff assignee counts and submits through HTTP, blind (was 403 before the UPDATE grant)")
    void warehouseStaffAssigneeCountsAndSubmits() throws Exception {
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView count = plannedFor(clerk);

        // A colleague who is not the assignee still gets the assignment refusal.
        UUID colleague = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        mvc.asRole(Role.WAREHOUSE_STAFF, colleague, put(countPath(count)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CYCLE_COUNT_NOT_ASSIGNEE"));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put(countPath(count)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lines[0].found").value(false))
                .andExpect(jsonPath("$.data.lines[0].countedBy").value(clerk.toString()))
                .andExpect(jsonPath("$.data.lines[0].booked").doesNotExist());
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(BASE + "/" + count.id() + "/submission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[*].adjustments[*]").isEmpty());
        assertThat(jdbc.queryForObject("select quantity_delta from inventory.stock_adjustment"
                + " where cycle_count_line_id = ?", Integer.class, count.lines().get(0).id())).isEqualTo(-10);
        // Staff submit the variance; they do not decide it.
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post("/api/v1/inventory/adjustments/" + variance(count) + "/approval"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("on an assigned count a manager cannot overwrite the assignee's figure; the assignee can correct "
            + "a manager's; an unassigned count has no owner to protect")
    void noOverwriteOfAnotherCountersLine() throws Exception {
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView count = plannedFor(clerk);
        String path = countPath(count);

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put(path).contentType(MediaType.APPLICATION_JSON).content(found(false)))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, put(path).contentType(MediaType.APPLICATION_JSON).content(found(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CYCLE_COUNT_LINE_COUNTED_BY_OTHER"));
        assertThat(jdbc.queryForObject("select counted_qty from inventory.cycle_count_line where id = ?",
                Integer.class, count.lines().get(0).id())).as("the assignee's answer stands: not found").isZero();
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put(path).contentType(MediaType.APPLICATION_JSON).content(found(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].found").value(true));
        cycleCounts.cancel(count.id(), "next case", "test");

        // Counted first by the manager (an uncounted line is theirs to count), then corrected by the assignee.
        CycleCountView second = plannedFor(clerk);
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, put(countPath(second)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(false)))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put(countPath(second)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].countedBy").value(clerk.toString()));
        cycleCounts.cancel(second.id(), "next case", "test");

        CycleCountView open = planned();
        mvc.asRole(Role.INVENTORY_PLANNER, planner, put(countPath(open)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(true)))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, put(countPath(open)).contentType(MediaType.APPLICATION_JSON)
                        .content(found(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].countedBy").value(manager.toString()));
    }

    @Test
    @DisplayName("whoever counted a line cannot approve (or reject) its variance, even if someone else submitted it; "
            + "another approver can")
    void counterCannotDecideTheVarianceOfTheirOwnLine() throws Exception {
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        UUID otherManager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        CycleCountView count = plannedFor(clerk);
        // The manager enters the figure on the clerk's count; the clerk submits it, so the clerk is the requester.
        cycleCounts.count(count.id(), count.lines().get(0).id(), false, manager, true);
        cycleCounts.submit(count.id(), clerk, false);
        UUID adjustment = variance(count);
        String approval = "/api/v1/inventory/adjustments/" + adjustment + "/approval";

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(approval))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADJUSTMENT_SELF_APPROVAL"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/inventory/adjustments/" + adjustment + "/rejection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"my own count\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADJUSTMENT_SELF_APPROVAL"));
        assertThat(jdbc.queryForObject("select status from inventory.stock_adjustment where id = ?", String.class,
                adjustment)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ?", Integer.class, sku))
                .as("nothing posted").isEqualTo(10);
        assertThat(jdbc.queryForObject("select status from inventory.stock_package where id = ?", String.class,
                carton.id())).isEqualTo("IN_STOCK");

        mvc.asRole(Role.WAREHOUSE_MANAGER, otherManager, post(approval))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("POSTED"));
        assertThat(jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ?", Integer.class, sku))
                .as("the carton written off").isZero();
        assertThat(jdbc.queryForObject("select status from inventory.stock_package where id = ?", String.class,
                carton.id())).isEqualTo("LOST");
    }

    @Test
    @DisplayName("the requester of a count variance still cannot decide it, whoever counted the line")
    void requesterStillCannotDecide() {
        UUID clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        CycleCountView count = plannedFor(clerk);
        cycleCounts.count(count.id(), count.lines().get(0).id(), false, manager, true);
        cycleCounts.submit(count.id(), clerk, false);
        UUID adjustment = variance(count);

        assertThat(((BusinessException) catchThrowable(() -> operations.approve(adjustment, clerk))).errorCode())
                .as("the submitter, who did not count the line").isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        assertThat(((BusinessException) catchThrowable(() -> operations.approve(adjustment, manager))).errorCode())
                .as("the counter, who did not submit").isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
    }
}
