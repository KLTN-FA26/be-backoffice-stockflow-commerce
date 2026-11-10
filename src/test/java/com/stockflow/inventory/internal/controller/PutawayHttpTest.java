package com.stockflow.inventory.internal.controller;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.support.Await;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-92 reading putaway tasks through the real filter chain, permission aspect and data scope. A two-step receipt
 * line of 40 units - cartons of 20 and 15, 5 loose - is confirmed, and the asynchronous creator opens its tasks.
 */
@SecuredProductionReceiptTest
class PutawayHttpTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String TASKS = "/api/v1/putaway-tasks";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ProductionReceivables receivables;
    @Autowired PutawayTasks putawayTasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID receiptId;
    private UUID cartonTask;
    private UUID looseTask;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        Received received = receive();
        receiptId = received.receiptId();
        cartonTask = received.cartonTask();
        looseTask = received.looseTask();
    }

    /** What one confirmed receipt of the fixture opened. */
    private record Received(UUID receiptId, UUID cartonTask, UUID looseTask) {
    }

    /** A confirmed two-step receipt line into HCM: two cartons and a loose remainder, with their tasks opened. */
    private Received receive() {
        ProductionReceivables.Receivable goods = receivables.sent(WAREHOUSE_HCM, 2, 20);
        UUID receiptId = receivables.onTheDock(goods, RECEIVING, 2, List.of(20, 15), clerk).receiptId();
        Await.until("three putaway tasks", Duration.ofSeconds(20),
                () -> putawayTasks.tasksOfReceipt(receiptId).size() >= 3);
        return new Received(receiptId, putawayTasks.tasksOfReceipt(receiptId).stream()
                .filter(t -> t.stockPackageId() != null && t.quantity() == 20).findFirst().orElseThrow().id(),
                putawayTasks.tasksOfReceipt(receiptId).stream().filter(t -> t.stockPackageId() == null)
                        .findFirst().orElseThrow().id());
    }

    /** A real account with this role, assigned to these warehouses. */
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

    private UUID outsider() {
        UUID elsewhere = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", elsewhere,
                "D" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        return staff("WAREHOUSE_STAFF", elsewhere);
    }

    @Test
    @DisplayName("staff see a carton task with its source, what is left and ranked bin suggestions, at most 10")
    void staffSeeTaskWithSuggestions() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS + "/" + cartonTask).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(20))
                .andExpect(jsonPath("$.data.left").value(20))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sourceLocationCode").value(RECEIVING))
                .andExpect(jsonPath("$.data.lpn").isNotEmpty())
                .andExpect(jsonPath("$.data.suggestions[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.data.suggestions.length()").value(Matchers.lessThanOrEqualTo(10)));
    }

    @Test
    @DisplayName("a loose task is read from where its units wait, with no LPN")
    void looseTaskHasNoLpn() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS + "/" + looseTask))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.sourceLocationCode").value(RECEIVING))
                .andExpect(jsonPath("$.data.lpn").doesNotExist());
    }

    @Test
    @DisplayName("a receipt's tasks are listed without suggestions, and a status filter narrows them")
    void listByReceipt() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS).param("receiptId", receiptId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].suggestions.length()").value(0));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS).param("receiptId", receiptId.toString())
                        .param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("a warehouse's open tasks include the new ones")
    void listByWarehouse() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS).param("warehouseId", WAREHOUSE_HCM.toString())
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + cartonTask + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("the warehouse list resolves the sources of loose tasks of several receipts in one batch")
    void listByWarehouseResolvesLooseTasksOfTwoReceipts() throws Exception {
        Received second = receive();
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS).param("warehouseId", WAREHOUSE_HCM.toString())
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + looseTask + "')].sourceLocationCode")
                        .value(Matchers.contains(RECEIVING)))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + second.looseTask() + "')].sourceLocationCode")
                        .value(Matchers.contains(RECEIVING)))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + second.cartonTask() + "')].lpn")
                        .value(Matchers.contains(Matchers.notNullValue())));
    }

    @Test
    @DisplayName("neither a receipt nor a warehouse: 400 VALIDATION_FAILED")
    void listNeedsReceiptOrWarehouse() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a task, a receipt's list and a warehouse's list of another warehouse: 403")
    void taskOfAnotherWarehouseIs403() throws Exception {
        UUID outsider = outsider();
        mvc.asRole(Role.WAREHOUSE_STAFF, outsider, get(TASKS + "/" + cartonTask)).andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_STAFF, outsider, get(TASKS).param("receiptId", receiptId.toString()))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_STAFF, outsider, get(TASKS).param("warehouseId", WAREHOUSE_HCM.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown task: 404 PUTAWAY_TASK_NOT_FOUND")
    void unknownTaskIs404() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PUTAWAY_TASK_NOT_FOUND"));
    }

    private String body(String locationCode, String lpn) {
        return "{\"locationCode\":\"" + locationCode + "\",\"lpn\":\"" + lpn + "\"}";
    }

    /** The first suggested bin and the carton's LPN of the carton task, as the clerk's screen would show them. */
    private String[] scanOfCartonTask() throws Exception {
        String json = mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(TASKS + "/" + cartonTask)).andReturn().getResponse()
                .getContentAsString();
        return new String[]{JsonPath.read(json, "$.data.suggestions[0].locationCode"), JsonPath.read(json, "$.data.lpn")};
    }

    @Test
    @DisplayName("staff confirm a carton by scan: 200 and the task is DONE")
    void staffConfirm() throws Exception {
        String[] scan = scanOfCartonTask();
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(TASKS + "/" + cartonTask + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content(body(scan[0], scan[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.left").value(0))
                .andExpect(jsonPath("$.data.targetLocationCode").value(scan[0]));
    }

    @Test
    @DisplayName("a role without UPDATE on putaway tasks: 403")
    void readOnlyRoleGets403() throws Exception {
        String[] scan = scanOfCartonTask();
        UUID planner = staff("INVENTORY_PLANNER", WAREHOUSE_HCM);
        mvc.asRole(Role.INVENTORY_PLANNER, planner, post(TASKS + "/" + cartonTask + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content(body(scan[0], scan[1])))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a DONE task of another warehouse is 403, not 409: scope is checked before the status")
    void doneTaskOfAnotherWarehouseIs403() throws Exception {
        String[] scan = scanOfCartonTask();
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(TASKS + "/" + cartonTask + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content(body(scan[0], scan[1])))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_STAFF, outsider(), post(TASKS + "/" + cartonTask + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content(body(scan[0], scan[1])))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post(TASKS + "/" + cartonTask + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content(body(scan[0], scan[1])))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PUTAWAY_TASK_DONE"));
    }
}
