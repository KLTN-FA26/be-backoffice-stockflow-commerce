package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.support.Await;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.warehouse.api.PutawayTasks;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A carton's history through the real filter chain, permission aspect and data scope (#455; kltn-docs 20 §4.1, the
 * carton as a lookup key, and 11, stock moved by whole carton): {@code GET /inventory/movements?lpn=} keeps the ledger
 * lines about the carton, and every line names it as {@code lpn}. One carton is received into a warehouse of its
 * own, put away into one bin and moved
 * by hand into another: three lines, newest first.
 */
@SecuredProductionReceiptTest
class CartonHistoryHttpTest {

    private static final String MOVEMENTS = "/api/v1/inventory/movements";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ProductionReceivables receivables;
    @Autowired InventoryService inventory;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private String prefix;
    private UUID warehouse;
    private UUID clerk;
    private String receiving;
    private String firstBin;
    private String secondBin;
    private String lpn;
    private String otherLpn;
    private int shelfX;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        prefix = "H" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        warehouse = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Carton history', 'ACTIVE', ?, 'Test address',"
                    + " 'M', 50, 50, 0, NOW())", warehouse, prefix);
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code,"
                    + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, FALSE, FALSE, 'ACTIVE')",
                    location, warehouse, receiving);
            jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width,"
                    + " length, rotation, is_obstacle, status) VALUES (?, ?, ?, 'RCV01', 'RECEIVING', 'RCV01', 1, 1, 5,"
                    + " 5, 0, FALSE, 'ACTIVE')", Identifiers.newId(), warehouse, location);
        });
        clerk = staff("WAREHOUSE_STAFF", warehouse);
        firstBin = roomyBin("A01");
        secondBin = roomyBin("A02");

        ProductionReceivables.Receivable goods = receivables.sent(warehouse, 2, 10);
        ProductionReceivables.Dock dock = receivables.onTheDock(goods, receiving, 2, clerk);
        lpn = dock.lpns().get(0);
        otherLpn = dock.lpns().get(1);
        Await.until("two putaway tasks", Duration.ofSeconds(20),
                () -> putawayTasks.tasksOfReceipt(dock.receiptId()).size() >= 2);
        // The carton unit cut to the bins below (as PutawayConfirmationIntegrationTest does): 50 x 40 x 30 cm.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.uom_conversions SET length_cm = 50, width_cm = 40, height_cm = 30, weight_kg = NULL
                 WHERE id = (SELECT uom_conversion_id FROM inventory.stock_package WHERE lpn = ?)""", lpn));
        UUID carton = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
        UUID task = putawayTasks.tasksOfReceipt(dock.receiptId()).stream()
                .filter(t -> carton.equals(t.stockPackageId())).findFirst().orElseThrow().id();

        putaway.confirm(task, firstBin, lpn, clerk);
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpn, secondBin, MoveReference.MOVE_TASK,
                UUID.randomUUID(), clerk));
    }

    /** A real account with this role, assigned to these warehouses. */
    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID w : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, w);
            }
        });
        return user;
    }

    /** A putaway-target bin on a shelf of its own: 5 m x 1.2 m x 1.4 m, room for a hundred cartons. */
    private String roomyBin(String name) {
        String code = prefix + "-" + name + "-1-A";
        tx.executeWithoutResult(s -> {
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            int x = 5 + 12 * shelfX++;
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, ?, '#4F81BD')",
                    zone, warehouse, "Zone " + name);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west, status)
                    VALUES (?, ?, ?, ?, 'Shelf', ?, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'ACTIVE')""",
                    shelf, warehouse, zone, name, x);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight,
                                                            is_pickable, is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 500, TRUE, TRUE, 'ACTIVE')""", location, warehouse, code);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return code;
    }

    private ResultActions history(UUID user, String query) throws Exception {
        return mvc.asRole(Role.WAREHOUSE_STAFF, user, get(MOVEMENTS + query));
    }

    @Test
    @DisplayName("received, put away, moved: the carton's three lines, newest first, each naming the carton")
    void aCartonListsItsOwnMovements() throws Exception {
        history(clerk, "?lpn=" + lpn.toLowerCase(Locale.ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].type").value("MOVE"))
                .andExpect(jsonPath("$.data.items[0].referenceType").value("MOVE_TASK"))
                .andExpect(jsonPath("$.data.items[0].fromLocation").value(firstBin))
                .andExpect(jsonPath("$.data.items[0].toLocation").value(secondBin))
                .andExpect(jsonPath("$.data.items[0].lpn").value(lpn))
                .andExpect(jsonPath("$.data.items[1].referenceType").value("PUTAWAY_TASK"))
                .andExpect(jsonPath("$.data.items[1].fromLocation").value(receiving))
                .andExpect(jsonPath("$.data.items[1].toLocation").value(firstBin))
                .andExpect(jsonPath("$.data.items[1].lpn").value(lpn))
                .andExpect(jsonPath("$.data.items[2].type").value("RECEIPT"))
                .andExpect(jsonPath("$.data.items[2].toLocation").value(receiving))
                .andExpect(jsonPath("$.data.items[2].fromLocation").doesNotExist())
                .andExpect(jsonPath("$.data.items[2].lpn").value(lpn));
    }

    @Test
    @DisplayName("lpn combines with the other filters; the other carton's lines are not the carton's")
    void lpnCombinesWithTheOtherFilters() throws Exception {
        history(clerk, "?lpn=" + lpn + "&type=MOVE&location=" + secondBin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].referenceType").value("MOVE_TASK"));
        history(clerk, "?lpn=" + otherLpn)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("RECEIPT"))
                .andExpect(jsonPath("$.data.items[0].lpn").value(otherLpn));
    }

    @Test
    @DisplayName("an LPN that is no carton is an empty page, not a 404")
    void anUnknownLpnIsAnEmptyPage() throws Exception {
        history(clerk, "?lpn=LPN-19990101-999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("staff of another warehouse asking for this carton's history: 403 OUT_OF_DATA_SCOPE")
    void aCartonInAnotherWarehouseIsOutOfScope() throws Exception {
        UUID elsewhere = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", elsewhere,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        UUID outsider = staff("WAREHOUSE_STAFF", elsewhere);

        history(outsider, "?lpn=" + lpn)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }
}
