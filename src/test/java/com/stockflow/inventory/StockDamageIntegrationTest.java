package com.stockflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.StockDamageDisposed;
import com.stockflow.contracts.StockDamageReported;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-475 against a real database, through the real filter chain, permissions and warehouse scope: damaged units
 * of a stock row leave available-to-promise for a quarantine area with their photos and one ledger line, a manager
 * who did not report them writes them off (posted at once, destruction record numbered), restores them or sends them
 * back; and every way of getting it wrong is refused with its own code and changes nothing.
 *
 * <p>Each test owns a fresh SKU with one lot-tracked layer at {@code HCM-A01-1-A}: 10 units, lot {@code L1},
 * expiring in 60 days. The demo map gives HCM two QUARANTINE areas, {@code HCM-QC01} and {@code HCM-RTV01}.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class StockDamageIntegrationTest {

    private static final String BIN = "HCM-A01-1-A";
    private static final String QC01 = "HCM-QC01";
    private static final String RTV01 = "HCM-RTV01";
    /** The first bytes of a JPEG and of a PNG: what the evidence category checks. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F',
            0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H',
            'D', 'R', 0, 0, 0, 1};

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired InventoryService inventory;
    @Autowired FileStorage storage;
    @Autowired CycleCountRepository cycleCounts;
    @Autowired RecordedEvents.Recorder recorded;

    private String sku;
    private UUID row;
    private LocalDate today;
    private UUID staff;
    private UUID otherStaff;
    private UUID manager;
    private UUID managerElsewhere;
    private UUID qc;
    private UUID planner;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        sku = "DMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Damage fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Damage fixture','')", Identifiers.newId(), product, sku);
        });
        row = stock(BIN, 10, "L1", today.plusDays(60), "AVAILABLE", null);
        staff = staff("WAREHOUSE_STAFF", DemoData.WAREHOUSE_HCM);
        otherStaff = staff("WAREHOUSE_STAFF", DemoData.WAREHOUSE_HCM);
        manager = staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        managerElsewhere = staff("WAREHOUSE_MANAGER", otherWarehouse());
        qc = staff("QC_STAFF", DemoData.WAREHOUSE_HCM);
        planner = staff("INVENTORY_PLANNER");
        recorded.clear();
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private UUID stock(String location, int onHand, String lot, LocalDate expiry, String status, String serial) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved,
                                                  status, received_at, serial_number, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, NOW())""", id, sku, location, lot, expiry, onHand, status,
                Timestamp.from(Instant.parse("2026-10-01T03:00:00Z")), serial));
        return id;
    }

    private UUID otherWarehouse() {
        UUID warehouse = Identifiers.newId();
        String prefix = "D" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", warehouse, prefix));
        return warehouse;
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

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private String reportJson(UUID request, UUID stockItem, int quantity, String reason, String note,
                              String damageLocation) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", request);
        body.put("stockItemId", stockItem);
        body.put("quantity", quantity);
        body.put("reason", reason);
        body.put("note", note);
        body.put("damageLocationCode", damageLocation);
        return json.writeValueAsString(body);
    }

    private ResultActions report(UUID user, String role, String body, MockMultipartFile... photos) throws Exception {
        var request = multipart("/api/v1/inventory/damage-reports")
                .file(new MockMultipartFile("report", "", "application/json", body.getBytes(StandardCharsets.UTF_8)));
        for (MockMultipartFile photo : photos) {
            request.file(photo);
        }
        return mvc.perform(request.header("Authorization", token(user, role)));
    }

    private ResultActions report(UUID request, UUID stockItem, int quantity) throws Exception {
        return report(staff, "WAREHOUSE_STAFF", reportJson(request, stockItem, quantity, "BROKEN", null, null),
                jpeg("broken-1.jpg"), png("broken-2.png"));
    }

    private ResultActions dispose(UUID user, String role, UUID report, String disposition, int quantity, String note,
                                  String restoreLocation) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID());
        body.put("disposition", disposition);
        body.put("quantity", quantity);
        body.put("note", note);
        body.put("restoreLocationCode", restoreLocation);
        return mvc.perform(post("/api/v1/inventory/damage-reports/" + report + "/dispositions")
                .header("Authorization", token(user, role)).contentType("application/json")
                .content(json.writeValueAsString(body)));
    }

    private static MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("photos", name, "image/jpeg", JPEG);
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("photos", name, "image/png", PNG);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int onHand(UUID stockItem) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE id = ?", Integer.class, stockItem);
    }

    private int damagedAt(String location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item
                 WHERE sku = ? AND location_code = ? AND status = 'DAMAGED'""", Integer.class, sku, location);
    }

    private int reports() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?", Integer.class,
                sku);
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    private void nothingChanged(int before) {
        assertThat(onHand(row)).isEqualTo(before);
        assertThat(reports()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE sku = ?", Integer.class,
                sku)).isZero();
    }

    // ---- the happy path --------------------------------------------------------------------------------

    @Test
    @DisplayName("a report moves the damaged units to quarantine: ATP drops, one MOVE line, photos stored, announced")
    void reportMovesTheDamagedUnitsOutOfAtp() throws Exception {
        assertThat(atp()).isEqualTo(10);

        JsonNode report = body(report(UUID.randomUUID(), row, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.number").value(org.hamcrest.Matchers.startsWith("DMG-")))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.origin").value("STOCK_CHECK"))
                .andExpect(jsonPath("$.data.damageLocationCode").value(QC01))
                .andExpect(jsonPath("$.data.quantity").value(3))
                .andExpect(jsonPath("$.data.pendingQuantity").value(3))
                .andExpect(jsonPath("$.data.lotNumber").value("L1"))
                .andExpect(jsonPath("$.data.photos.length()").value(2))
                .andReturn());
        UUID reportId = UUID.fromString(report.path("id").asText());

        assertThat(onHand(row)).isEqualTo(7);
        assertThat(damagedAt(QC01)).isEqualTo(3);
        assertThat(atp()).as("out of ATP at once").isEqualTo(7);
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, from_location_code, to_location_code, quantity, from_status, to_status,
                       lot_number, actor_id
                  FROM inventory.stock_movement WHERE reference_type = 'DAMAGE_REPORT' AND reference_id = ?""", reportId))
                .containsEntry("movement_type", "MOVE").containsEntry("from_location_code", BIN)
                .containsEntry("to_location_code", QC01).containsEntry("quantity", 3)
                .containsEntry("from_status", "AVAILABLE").containsEntry("to_status", "DAMAGED")
                .containsEntry("lot_number", "L1").containsEntry("actor_id", staff);
        List<String> keys = jdbc.queryForList("""
                SELECT storage_key FROM inventory.stock_damage_photo WHERE damage_report_id = ? ORDER BY sort_order""",
                String.class, reportId);
        assertThat(keys).hasSize(2).allSatisfy(key -> {
            assertThat(key).startsWith("qc-evidence/");
            assertThat(storage.exists(key)).isTrue();
        });
        assertThat(recorded.matching(StockDamageReported.class, event -> event.reportId().equals(reportId)))
                .singleElement().satisfies(event -> {
                    assertThat(event.quantity()).isEqualTo(3);
                    assertThat(event.damageLocationCode()).isEqualTo(QC01);
                    assertThat(event.skuReservationShortfall()).isZero();
                });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'stock-damage-report' AND action = 'CREATE'
                   AND resource_id = ? AND outcome = 'SUCCESS'""", Integer.class, reportId.toString())).isEqualTo(1);

        // The detail and the manager's queue.
        String header = token(manager, "WAREHOUSE_MANAGER");
        mvc.perform(get("/api/v1/inventory/damage-reports/" + reportId).header("Authorization", header))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("BROKEN"));
        mvc.perform(get("/api/v1/inventory/damage-reports").param("status", "OPEN").param("sku", sku)
                        .header("Authorization", header))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.data.items[0].pendingQuantity").value(3));
        mvc.perform(get("/api/v1/inventory/damage-reports/damage-areas")
                        .param("warehouseId", DemoData.WAREHOUSE_HCM.toString()).header("Authorization", header))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value(QC01))
                .andExpect(jsonPath("$.data[1]").value(RTV01));
    }

    @Test
    @DisplayName("write-off by a manager: posted at once, the reporter as requester, a destruction record, on-hand falls")
    void writeOffIsPostedWithItsRecord() throws Exception {
        UUID reportId = UUID.fromString(body(report(UUID.randomUUID(), row, 3).andExpect(status().isCreated())
                .andReturn()).path("id").asText());

        JsonNode decided = body(dispose(manager, "WAREHOUSE_MANAGER", reportId, "WRITE_OFF", 3, "crushed by forklift",
                null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.dispositions[0].disposition").value("WRITE_OFF"))
                .andExpect(jsonPath("$.data.dispositions[0].recordNumber").value(org.hamcrest.Matchers.startsWith("WO-")))
                .andReturn());

        UUID adjustment = UUID.fromString(decided.path("dispositions").get(0).path("adjustmentId").asText());
        assertThat(jdbc.queryForMap("""
                SELECT status, reason_code, quantity_delta, requested_by, decided_by, location_code
                  FROM inventory.stock_adjustment WHERE id = ?""", adjustment))
                .containsEntry("status", "POSTED").containsEntry("reason_code", "DAMAGED")
                .containsEntry("quantity_delta", -3).containsEntry("requested_by", staff)
                .containsEntry("decided_by", manager).containsEntry("location_code", QC01);
        assertThat(damagedAt(QC01)).isZero();
        assertThat(onHand(row)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'ADJUSTMENT'
                   AND reference_type = 'STOCK_ADJUSTMENT' AND reference_id = ? AND from_status = 'DAMAGED'""",
                Integer.class, adjustment)).isEqualTo(1);
        assertThat(recorded.matching(StockDamageDisposed.class, event -> event.reportId().equals(reportId)))
                .singleElement().satisfies(event -> assertThat(event.disposition()).isEqualTo("WRITE_OFF"));
        // Nothing is left to decide.
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 1, "found fine", "HCM-A01-1-B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_DISPOSITION_EXCEEDS_PENDING"));
    }

    @Test
    @DisplayName("restore: back onto a bin as AVAILABLE, ATP back; part written off; restore after write-off refused")
    void restoreThenWriteOff() throws Exception {
        UUID reportId = UUID.fromString(body(report(UUID.randomUUID(), row, 4).andExpect(status().isCreated())
                .andReturn()).path("id").asText());
        assertThat(atp()).isEqualTo(6);

        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 2, null, "HCM-A01-1-B")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 2, "outer carton dented", "HCM-PACK01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_RESTORE_LOCATION_INVALID"));
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 2, "outer carton dented, cups sound", "HCM-A01-1-B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingQuantity").value(2))
                .andExpect(jsonPath("$.data.dispositions[0].restoreLocationCode").value("HCM-A01-1-B"));
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM inventory.stock_item WHERE sku = ? AND location_code = 'HCM-A01-1-B'
                   AND status = 'AVAILABLE' AND lot_number = 'L1'""", Integer.class, sku)).isEqualTo(2);
        assertThat(atp()).as("restored units sell again").isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'DAMAGE_REPORT' AND reference_id = ?
                   AND from_status = 'DAMAGED' AND to_status = 'AVAILABLE' AND to_location_code = 'HCM-A01-1-B'""",
                Integer.class, reportId)).isEqualTo(1);

        dispose(manager, "WAREHOUSE_MANAGER", reportId, "WRITE_OFF", 2, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 1, "after all", "HCM-A01-1-B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_DISPOSITION_EXCEEDS_PENDING"));
        assertThat(damagedAt(QC01)).isZero();
    }

    @Test
    @DisplayName("restore onto a bin holding the same lot as other goods: refused, naming the bin and what it holds")
    void restoreOntoAnotherLayerOfTheLot() throws Exception {
        stock("HCM-A01-2-B", 3, "L1", today.plusDays(90), "AVAILABLE", null);
        UUID reportId = UUID.fromString(body(report(UUID.randomUUID(), row, 2).andExpect(status().isCreated())
                .andReturn()).path("id").asText());

        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RESTORE", 2, "cups sound", "HCM-A01-2-B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_STATUS_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "HCM-A01-2-B holds these goods as AVAILABLE; restore them to another bin")));
        assertThat(damagedAt(QC01)).as("still in quarantine").isEqualTo(2);
    }

    @Test
    @DisplayName("return to the supplier: the decision is recorded, the units stay DAMAGED in quarantine, out of sale")
    void returnToSupplierRecordsIntent() throws Exception {
        UUID reportId = UUID.fromString(body(report(UUID.randomUUID(), row, 2).andExpect(status().isCreated())
                .andReturn()).path("id").asText());

        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RETURN_TO_SUPPLIER", 2, null, null)
                .andExpect(status().isBadRequest());
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "RETURN_TO_SUPPLIER", 2, "supplier agreed to credit", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SUPPLIER"))
                .andExpect(jsonPath("$.data.awaitingSupplierQuantity").value(2))
                .andExpect(jsonPath("$.data.dispositions[0].disposition").value("RETURN_TO_SUPPLIER"));

        assertThat(damagedAt(QC01)).as("still here until the supplier collects them").isEqualTo(2);
        assertThat(atp()).isEqualTo(8);
        assertThat(recorded.matching(StockDamageDisposed.class, event -> event.reportId().equals(reportId)))
                .singleElement().satisfies(event -> assertThat(event.disposition()).isEqualTo("RETURN_TO_SUPPLIER"));

        // The supplier's carrier takes one; the supplier refuses the other, which is then written off.
        UUID decision = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM inventory.stock_damage_disposition WHERE damage_report_id = ?", String.class,
                reportId));
        String base = "/api/v1/inventory/damage-reports/" + reportId + "/dispositions/" + decision;
        String one = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"deliveryNote\":\"PXT-NCC-01\"}";
        mvc.perform(post(base + "/supplier-shipment").header("Authorization", token(staff, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(one)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/supplier-shipment").header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(one))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SUPPLIER"))
                .andExpect(jsonPath("$.data.awaitingSupplierQuantity").value(1))
                .andExpect(jsonPath("$.data.dispositions[0].handedOverQuantity").value(1))
                .andExpect(jsonPath("$.data.supplierHandOvers[0].outcome").value("SHIPPED"))
                .andExpect(jsonPath("$.data.supplierHandOvers[0].deliveryNote").value("PXT-NCC-01"));
        mvc.perform(post(base + "/supplier-shipment").header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                .contentType("application/json").content(one)).andExpect(status().isOk());   // the same hand-over again
        assertThat(damagedAt(QC01)).isEqualTo(1);
        mvc.perform(post(base + "/supplier-shipment").header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"quantity\":2,\"deliveryNote\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_RETURN_NOT_SHIPPABLE"));

        mvc.perform(post(base + "/supplier-refusal").header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,"
                                + "\"note\":\"NCC chỉ nhận lại 1 ly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.pendingQuantity").value(1))
                .andExpect(jsonPath("$.data.dispositions[0].refusedQuantity").value(1));
        dispose(manager, "WAREHOUSE_MANAGER", reportId, "WRITE_OFF", 1, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        assertThat(damagedAt(QC01)).isZero();
        assertThat(atp()).isEqualTo(8);
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, from_location_code, to_location_code, quantity, from_status
                  FROM inventory.stock_movement WHERE reference_type = 'DAMAGE_REPORT' AND reference_id = ?
                   AND movement_type = 'SUPPLIER_RETURN'""", reportId))
                .containsEntry("from_location_code", QC01).containsEntry("to_location_code", null)
                .containsEntry("quantity", 1).containsEntry("from_status", "DAMAGED");
    }

    // ---- who may do it -----------------------------------------------------------------------------------

    @Test
    @DisplayName("four eyes: the reporter never decides; staff cannot decide; another warehouse cannot see or report")
    void permissionsAndScope() throws Exception {
        UUID reportId = UUID.fromString(body(report(manager, "WAREHOUSE_MANAGER",
                reportJson(UUID.randomUUID(), row, 1, "DEFORMED", null, null), jpeg("dent.jpg"))
                .andExpect(status().isCreated()).andReturn()).path("id").asText());

        dispose(manager, "WAREHOUSE_MANAGER", reportId, "WRITE_OFF", 1, null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DAMAGE_SELF_DISPOSITION"));
        dispose(staff, "WAREHOUSE_STAFF", reportId, "WRITE_OFF", 1, null, null).andExpect(status().isForbidden());
        dispose(managerElsewhere, "WAREHOUSE_MANAGER", reportId, "WRITE_OFF", 1, null, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.perform(get("/api/v1/inventory/damage-reports/" + reportId)
                        .header("Authorization", token(managerElsewhere, "WAREHOUSE_MANAGER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DAMAGE_REPORT_NOT_FOUND"));
        report(managerElsewhere, "WAREHOUSE_MANAGER", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, null),
                jpeg("x.jpg")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        report(planner, "INVENTORY_PLANNER", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, null),
                jpeg("x.jpg")).andExpect(status().isForbidden());
        // QC staff find damage too; a manager of the warehouse who did not report it decides.
        report(qc, "QC_STAFF", reportJson(UUID.randomUUID(), row, 1, "DIRTY_OR_MISPRINTED", null, null),
                png("ink.png")).andExpect(status().isCreated());
        dispose(otherStaff, "WAREHOUSE_STAFF", reportId, "WRITE_OFF", 1, null, null).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/inventory/damage-reports").header("Authorization", token(planner, "INVENTORY_PLANNER")))
                .andExpect(status().isOk());
        // The table says four eyes again, whatever path wrote the decision.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_damage_disposition (id, damage_report_id, request_id, disposition_type,
                       quantity, record_number, stock_adjustment_id, decided_by, decided_at)
                SELECT gen_random_uuid(), id, gen_random_uuid(), 'RETURN_TO_SUPPLIER', 1, NULL, NULL, reported_by, NOW()
                  FROM inventory.stock_damage_report WHERE id = ?""", reportId))))
                .hasMessageContaining("cannot be decided by the person who reported it");
    }

    // ---- refusals that change nothing --------------------------------------------------------------------

    @Test
    @DisplayName("no photo, a file that is not an image, OTHER with no note, an unknown row: refused, nothing written")
    void badRequestsWriteNothing() throws Exception {
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("DAMAGE_PHOTO_REQUIRED"));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, null),
                new MockMultipartFile("photos", "fake.png", "image/png", "not a picture at all".getBytes()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, null),
                new MockMultipartFile("photos", "evil.svg", "image/svg+xml", "<svg/>".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "OTHER", " ", null), jpeg("x.jpg"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 0, "BROKEN", null, null), jpeg("x.jpg"))
                .andExpect(status().isBadRequest());
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), UUID.randomUUID(), 1, "BROKEN", null, null),
                jpeg("x.jpg")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STOCK_ITEM_NOT_FOUND"));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, "HCM-PACK01"),
                jpeg("x.jpg")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOCATION_AREA_MISMATCH"));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, "HCM-NOPE9"),
                jpeg("x.jpg")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));

        nothingChanged(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_photo p "
                + "JOIN inventory.stock_damage_report r ON r.id = p.damage_report_id WHERE r.sku = ?", Integer.class, sku))
                .isZero();
    }

    @Test
    @DisplayName("more than on hand, more than free while the rest is held: refused; only AVAILABLE unexpired stock")
    void quantitiesAndStatuses() throws Exception {
        report(UUID.randomUUID(), row, 11).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_EXCEEDS_FREE_STOCK"));
        // 6 of the 10 allocated to an order released to HCM: only 4 are free.
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""",
                order, "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        tx.executeWithoutResult(s -> {
            inventory.prepareReservation(Set.of(new Sku(sku)));
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 6, order));
        });
        tx.executeWithoutResult(s -> inventory.allocate(new AllocateStockCommand(order, "HCM")));
        report(UUID.randomUUID(), row, 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_EXCEEDS_FREE_STOCK"));
        report(UUID.randomUUID(), row, 4).andExpect(status().isCreated());
        assertThat(jdbc.queryForMap("SELECT on_hand, reserved FROM inventory.stock_item WHERE id = ?", row))
                .as("the allocation is untouched").containsEntry("on_hand", 6).containsEntry("reserved", 6);

        // IN_PRODUCTION: blanks in the print shop are production's to scrap, never a damage report's.
        for (String status : List.of("DAMAGED", "QUARANTINE", "EXPIRED", "INBOUND", "IN_PRODUCTION")) {
            UUID other = stock("HCM-B01-2-" + (status.equals("DAMAGED") ? "A" : "B"), 2, "L-" + status,
                    today.plusDays(30), status, null);
            report(UUID.randomUUID(), other, 1).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("STOCK_STATUS_MISMATCH"));
        }
        UUID expired = stock("HCM-A02-1-A", 2, "OLD", today.minusDays(1), "AVAILABLE", null);
        report(UUID.randomUUID(), expired, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_EXPIRED"));
    }

    @Test
    @DisplayName("quarantine areas holding the layer in another status are skipped; none left is DAMAGE_AREA_MISSING")
    void destinations() throws Exception {
        stock(QC01, 1, "L1", today.plusDays(60), "QUARANTINE", null);
        report(UUID.randomUUID(), row, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.damageLocationCode").value(RTV01));
        report(staff, "WAREHOUSE_STAFF", reportJson(UUID.randomUUID(), row, 1, "BROKEN", null, QC01), jpeg("x.jpg"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("STOCK_STATUS_MISMATCH"));
        // A second report of the same layer merges into its DAMAGED row.
        report(UUID.randomUUID(), row, 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.damageLocationCode").value(RTV01));
        assertThat(damagedAt(RTV01)).isEqualTo(3);

        UUID otherLayer = stock("HCM-A01-2-A", 5, "L9", today.plusDays(60), "AVAILABLE", null);
        stock(QC01, 1, "L9", today.plusDays(60), "QUARANTINE", null);
        stock(RTV01, 1, "L9", today.plusDays(60), "BLOCKED", null);
        report(UUID.randomUUID(), otherLayer, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_AREA_MISSING"));
        assertThat(onHand(otherLayer)).isEqualTo(5);
    }

    // ---- retries and races --------------------------------------------------------------------------------

    @Test
    @DisplayName("the same request again answers with the first report: units moved once, its photos only")
    void replayIsIdempotent() throws Exception {
        UUID request = UUID.randomUUID();
        String first = body(report(request, row, 2).andExpect(status().isCreated()).andReturn()).path("id").asText();
        String second = body(report(request, row, 2).andExpect(status().isCreated()).andReturn()).path("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(onHand(row)).isEqualTo(8);
        assertThat(reports()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_photo WHERE damage_report_id = ?",
                Integer.class, UUID.fromString(first))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE reference_id = ?",
                Integer.class, UUID.fromString(first))).isEqualTo(1);
    }

    @Test
    @DisplayName("two reports of the same row at once: the row lock decides, the second sees what is left")
    void concurrentReports() throws Exception {
        UUID small = stock("HCM-A02-2-B", 5, "L1", today.plusDays(60), "AVAILABLE", null);
        List<String> bodies = List.of(reportJson(UUID.randomUUID(), small, 3, "BROKEN", null, null),
                reportJson(UUID.randomUUID(), small, 3, "BROKEN", null, null));
        List<String> headers = List.of(token(staff, "WAREHOUSE_STAFF"), token(otherStaff, "WAREHOUSE_STAFF"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(multipart("/api/v1/inventory/damage-reports")
                                    .file(new MockMultipartFile("report", "", "application/json",
                                            bodies.get(index).getBytes(StandardCharsets.UTF_8)))
                                    .file(jpeg("race.jpg"))
                                    .header("Authorization", headers.get(index)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(onHand(small)).isEqualTo(2);
        assertThat(damagedAt(QC01)).isEqualTo(3);
    }

    // ---- shapes of stock ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a serial unit is reported one at a time and keeps its serial number in quarantine")
    void serialUnit() throws Exception {
        UUID unit = stock("HCM-B01-1-A", 1, null, null, "AVAILABLE", "SN-" + sku);
        report(UUID.randomUUID(), unit, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DAMAGE_EXCEEDS_FREE_STOCK"));

        report(UUID.randomUUID(), unit, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.serialNumber").value("SN-" + sku));

        assertThat(onHand(unit)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT on_hand FROM inventory.stock_item WHERE sku = ? AND serial_number = ? AND status = 'DAMAGED'""",
                Integer.class, sku, "SN-" + sku)).isEqualTo(1);
    }

    @Test
    @DisplayName("units out of a carton: it opens, the last unit closes it CONSUMED; damaged units arrive loose, merged")
    void cartonRows() throws Exception {
        // A FIFO SKU with its policy configured: the second report merges into the DAMAGED row of the same receipt
        // layer, which the policy trigger accepts only as a same-receipt merge.
        UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
        UUID unit = Identifiers.newId();
        UUID carton = Identifiers.newId();
        UUID contents = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE inventory.inventory_items SET removal_strategy = 'FIFO', policy_configured = true"
                    + " WHERE sku = ?", sku);
            jdbc.update("INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " VALUES (?, ?, 'CARTON', 5)", unit, item);
            jdbc.update("""
                    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status) VALUES (?, ?, ?, ?, 5, 5, 'HCM-A01-2-B', 'IN_STOCK')""",
                    carton, "LPN-" + sku, item, unit);
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                        version, created_at, stock_package_id) VALUES (?, ?, 'HCM-A01-2-B', ?, 5, 0, 'AVAILABLE', 0,
                        NOW(), ?)""", contents, sku, Timestamp.from(Instant.parse("2026-09-01T03:00:00Z")), carton);
        });
        String cartonState = "SELECT status, unopened FROM inventory.stock_package WHERE id = ?";

        String first = body(report(UUID.randomUUID(), contents, 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceStockPackageId").value(carton.toString()))
                .andReturn()).path("id").asText();

        assertThat(jdbc.queryForMap(cartonState, carton)).containsEntry("status", "IN_STOCK")
                .containsEntry("unopened", false);
        assertThat(onHand(contents)).isEqualTo(3);
        assertThat(jdbc.queryForMap("""
                SELECT on_hand, stock_package_id FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'""", sku))
                .as("the damaged units are loose in quarantine").containsEntry("on_hand", 2)
                .containsEntry("stock_package_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT from_stock_package_id FROM inventory.stock_movement WHERE reference_id = ?::uuid""", UUID.class,
                first)).isEqualTo(carton);

        report(UUID.randomUUID(), contents, 3).andExpect(status().isCreated());

        assertThat(jdbc.queryForMap(cartonState, carton)).containsEntry("status", "CONSUMED");
        assertThat(onHand(contents)).isZero();
        assertThat(damagedAt(QC01)).isEqualTo(5);
    }

    @Test
    @DisplayName("a layer an open recount froze: its free units may be reported, ATP stays right")
    void frozenByARecount() throws Exception {
        tx.executeWithoutResult(s -> cycleCounts.save(CycleCount.shortPick(cycleCounts.nextNumber(today),
                DemoData.WAREHOUSE_HCM, CountSlot.loose(new LocationId(BIN), new Sku(sku), "L1"), 10, "test recount",
                today)));
        assertThat(atp()).as("frozen").isZero();

        report(UUID.randomUUID(), row, 3).andExpect(status().isCreated());

        assertThat(onHand(row)).isEqualTo(7);
        assertThat(atp()).as("still frozen, never negative").isZero();
        assertThat(damagedAt(QC01)).isEqualTo(3);
    }
}
