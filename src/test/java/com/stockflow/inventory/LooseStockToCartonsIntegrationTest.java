package com.stockflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.DemoData;
import com.stockflow.support.Holds;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the 4C-1 conversion (team plan 4C-1, QD-5; migration V20261111000110, squashed into the baseline and the demo
 * seed on 2026-11-11, which keep the cartons it made and its ledger lines) promises the application, read back through
 * the application on the demo stock it converted: the loose demo sofas (25 and 8, no lot) and oak tables (12 available, 30 quarantined) are
 * whole sealed cartons of their SKU's carton unit, each found by its LPN where its units were (kltn-docs 20 BR-14,
 * BR-18, §4.6, §5.5; 01 BR-03, BR-11); nothing is loose or opened; availability in pieces is what it was before
 * (BR-01); the conversion's ledger lines - a MOVE that stays in place into the carton it names - read through the
 * ledger and the lot trace (§4.7, BR-16), the lot-less sofas as lot {@code UNTRACED} of origin {@code UNTRACED} (BR-22,
 * C45); none of the demo stock was written off (QD-5: the carton unit of one leaves no remainder); and the allocator
 * hands the converted stock out as whole cartons named by LPN (07 BR-03). The database rules themselves are
 * pinned by {@code tools/db/qa/40_loose_stock_to_cartons.sql}.
 *
 * <p><b>A context of its own, on purpose</b> (the extra property below): the assertions are about the demo stock as
 * the migration left it, and the shared secured context lends that same stock to dozens of classes that reserve, pick,
 * receive and move it. Sharing it would make every number here depend on which classes ran first. A context of its own
 * gets a fresh copy of the migrated template database, so nothing here reads another class's writes, and nothing it
 * does reaches them. It writes nothing that stays: the manager it signs in as, and an allocation it rolls back.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.test.own-database=loose-stock-to-cartons"})
class LooseStockToCartonsIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";
    private static final String TABLE = "TABLE-OAK-160";
    private static final String SOFA_BIN = "HCM-A01-2-B";
    private static final String SOFA_BIN_2 = "HCM-A02-1-A";
    private static final String TABLE_BIN = "HCM-B01-1-A";
    private static final String QUARANTINE = "HCM-QC01";
    private static final String MIGRATION = "V20261111000110";
    /** LpnSequence's shape: the business day and six digits of that day's sequence. */
    private static final String LPN = "LPN-\\d{8}-\\d{6}";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired InventoryService inventory;

    private SecuredMvc mvc;
    private UUID manager;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        // A warehouse manager of HCM: every read here is one a manager makes (20 §2), inside their warehouse (BR-19).
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'""",
                    manager);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", manager, WAREHOUSE_HCM);
        });
    }

    // ------------------------------------------------------------------ cartons

    @Test
    @DisplayName("every demo unit is a sealed carton of its SKU's standard count, listed and found by its LPN where its"
            + " units were, of its lot (UNTRACED for the lot-less sofas) and stock status")
    void theDemoStockIsInWholeCartonsFoundByLpn() throws Exception {
        List<JsonNode> sofas = cartons(SOFA);
        List<JsonNode> tables = cartons(TABLE);

        // Where the units were, as many cartons as units: the carton unit of both is one piece (V20261111000100).
        assertThat(countBy(sofas, c -> c.path("locationCode").asText() + "/" + c.path("lotNumber").asText()))
                .isEqualTo(Map.of(SOFA_BIN + "/UNTRACED", 25L, SOFA_BIN_2 + "/UNTRACED", 8L));
        assertThat(countBy(tables, c -> c.path("locationCode").asText() + "/" + c.path("lotNumber").asText()))
                .isEqualTo(Map.of(TABLE_BIN + "/LOT-2026-03", 12L, QUARANTINE + "/LOT-2026-04", 30L));

        List<JsonNode> all = new ArrayList<>(sofas);
        all.addAll(tables);
        assertThat(all).extracting(c -> c.path("lpn").asText()).allMatch(lpn -> lpn.matches(LPN))
                .doesNotHaveDuplicates();
        Map<String, Integer> standard = cartonUnitFactors();
        for (JsonNode carton : all) {
            String sku = carton.path("sku").asText();
            String lpn = carton.path("lpn").asText();
            int perCarton = standard.get(sku);
            // 20 §5.5 / BR-18: IN_STOCK, sealed, holding exactly the standard count it was counted in at.
            assertThat(carton.path("unitOfMeasure").asText()).as(lpn).isEqualTo("CARTON");
            assertThat(carton.path("nominalQty").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(carton.path("receivedQty").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(carton.path("onHand").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(carton.path("reserved").asInt()).as(lpn).isZero();
            assertThat(carton.path("status").asText()).as(lpn).isEqualTo("IN_STOCK");
            assertThat(carton.has("unopened")).as("%s: the flag is gone (4C-3)", lpn).isFalse();
            assertThat(absent(carton.path("goodsReceiptLineId"))).as("%s was made in the warehouse", lpn).isTrue();

            // The LPN a person scans finds the same carton, with its one stock row: the units, their lot and status.
            JsonNode detail = data(get("/api/v1/inventory/stock-packages/{lpn}", lpn.toLowerCase()));
            assertThat(detail.path("lpn").asText()).isEqualTo(lpn);
            assertThat(detail.path("sku").asText()).as(lpn).isEqualTo(sku);
            assertThat(detail.path("locationCode").asText()).as(lpn).isEqualTo(carton.path("locationCode").asText());
            assertThat(detail.path("lotNumber").asText()).as(lpn).isEqualTo(carton.path("lotNumber").asText());
            assertThat(detail.path("nominalQty").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(detail.path("onHand").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(detail.has("unopened")).as("%s: the flag is gone (4C-3)", lpn).isFalse();
            assertThat(detail.path("status").asText()).as(lpn).isEqualTo("IN_STOCK");
            assertThat(absent(detail.path("closedAt"))).as(lpn).isTrue();
            String stockStatus = carton.path("locationCode").asText().equals(QUARANTINE) ? "QUARANTINE" : "AVAILABLE";
            assertThat(detail.path("contents")).as(lpn).hasSize(1);
            JsonNode content = detail.path("contents").get(0);
            assertThat(content.path("lotNumber").asText()).as(lpn).isEqualTo(carton.path("lotNumber").asText());
            assertThat(content.path("status").asText()).as(lpn).isEqualTo(stockStatus);
            assertThat(content.path("onHand").asInt()).as(lpn).isEqualTo(perCarton);
            assertThat(content.path("reserved").asInt()).as(lpn).isZero();
        }

        // These are the cartons the migration made: one per unit of the four demo rows, and no other.
        assertThat(jdbc.queryForList("""
                SELECT p.lpn FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
                 WHERE i.sku IN (?, ?) AND p.created_by = ?""", String.class, SOFA, TABLE, MIGRATION))
                .containsExactlyInAnyOrderElementsOf(all.stream().map(c -> c.path("lpn").asText()).toList());
    }

    @Test
    @DisplayName("where the demo stock stands, every unit is in a sealed carton: no opened carton, nothing loose, and"
            + " every stock row carries a lot")
    void nothingIsLooseOrOpened() throws Exception {
        assertThat(locationLine(SOFA_BIN, SOFA)).isEqualTo("UNTRACED/AVAILABLE whole 25 (25) opened 0 (0) loose 0 = 25");
        assertThat(locationLine(SOFA_BIN_2, SOFA)).isEqualTo("UNTRACED/AVAILABLE whole 8 (8) opened 0 (0) loose 0 = 8");
        assertThat(locationLine(TABLE_BIN, TABLE))
                .isEqualTo("LOT-2026-03/AVAILABLE whole 12 (12) opened 0 (0) loose 0 = 12");
        assertThat(locationLine(QUARANTINE, TABLE))
                .isEqualTo("LOT-2026-04/QUARANTINE whole 30 (30) opened 0 (0) loose 0 = 30");

        // The stock screen of the warehouse (20 §4.1): one row per carton, each with a lot (BR-22), none empty-handed.
        for (String sku : List.of(SOFA, TABLE)) {
            JsonNode page = data(get("/api/v1/inventory/warehouses/{id}/stock-items", WAREHOUSE_HCM)
                    .param("sku", sku).param("size", "200"));
            List<JsonNode> rows = items(page);
            assertThat(rows).as(sku).hasSize(sku.equals(SOFA) ? 33 : 42);
            assertThat(rows).as(sku).allSatisfy(row -> {
                assertThat(row.path("lotNumber").asText("")).isNotBlank();
                assertThat(row.path("onHand").asInt()).isEqualTo(1);
            });
        }
    }

    // ------------------------------------------------------------------ availability

    @Test
    @DisplayName("availability in pieces is the demo stock's: 25 + 8 sofas in two bins, 12 oak tables, the 30"
            + " quarantined ones not available")
    void availabilityIsUnchangedInPieces() throws Exception {
        // Per warehouse (20 BR-01): on hand, available, held, ATP.
        assertThat(level(SOFA)).isEqualTo("HCM onHand 33 available 33 reserved 0 allocated 0 atp 33");
        assertThat(level(TABLE)).isEqualTo("HCM onHand 42 available 12 reserved 0 allocated 0 atp 12");

        // ATP system-wide and for the warehouse.
        assertThat(data(get("/api/v1/inventory/stock-items/atp").param("sku", SOFA)).asInt()).isEqualTo(33);
        assertThat(data(get("/api/v1/inventory/stock-items/atp").param("sku", SOFA).param("warehouse", "HCM")).asInt())
                .isEqualTo(33);
        assertThat(data(get("/api/v1/inventory/stock-items/atp").param("sku", TABLE)).asInt()).isEqualTo(12);
        assertThat(data(get("/api/v1/inventory/stock-items/atp").param("sku", TABLE).param("warehouse", "HCM")).asInt())
                .isEqualTo(12);

        // Stock available by location: the sofas in their two bins, the tables in theirs, nothing from quarantine.
        assertThat(availableByLocation(SOFA)).isEqualTo(Map.of(SOFA_BIN, 25, SOFA_BIN_2, 8));
        assertThat(availableByLocation(TABLE)).isEqualTo(Map.of(TABLE_BIN, 12));
    }

    // ------------------------------------------------------------------ ledger and lot trace

    @Test
    @DisplayName("the stock history reads the conversion's lines: one MOVE per carton, in place, into the carton it"
            + " names, keeping the units' status and lot")
    void theLedgerReadsTheConversionLines() throws Exception {
        Map<UUID, String> madeHere = cartonsOfTheMigration();

        Map<String, Integer> sofaLines = conversionLines(SOFA, madeHere);
        assertThat(sofaLines).isEqualTo(Map.of(SOFA_BIN + " UNTRACED AVAILABLE", 25, SOFA_BIN_2 + " UNTRACED AVAILABLE", 8));
        Map<String, Integer> tableLines = conversionLines(TABLE, madeHere);
        assertThat(tableLines).isEqualTo(Map.of(TABLE_BIN + " LOT-2026-03 AVAILABLE", 12,
                QUARANTINE + " LOT-2026-04 QUARANTINE", 30));

        // A location's history (either side of a line) reads them too.
        List<JsonNode> atQuarantine = items(data(get("/api/v1/inventory/movements").param("location", QUARANTINE)
                .param("size", "200")));
        assertThat(atQuarantine).filteredOn(m -> m.path("referenceType").asText().equals("STOCK_PACKAGE")).hasSize(30);

        // Nothing of the demo stock was written off (QD-5): one piece to a carton leaves no remainder.
        for (String sku : List.of(SOFA, TABLE)) {
            assertThat(items(data(get("/api/v1/inventory/movements").param("sku", sku).param("type", "ADJUSTMENT")
                    .param("size", "200")))).as(sku).isEmpty();
            assertThat(items(data(get("/api/v1/inventory/adjustments").param("sku", sku).param("size", "200"))))
                    .as(sku).isEmpty();
        }
    }

    @Test
    @DisplayName("the lot trace of the lot-less sofas is lot UNTRACED, origin UNTRACED, its cartons where they were and"
            + " its ledger the conversion's lines; the oak tables keep their lots")
    void theLotTraceReadsTheConvertedLots() throws Exception {
        List<JsonNode> sofaLots = items(data(get("/api/v1/inventory/lots").param("sku", SOFA)
                .param("lotNumber", "UNTRACED")));
        assertThat(sofaLots).hasSize(1);
        JsonNode untraced = sofaLots.getFirst();
        // 20 §4.7 step 3 / C45: stock with no receipt in the system is lot UNTRACED, traced to no receipt.
        assertThat(untraced.path("lotNumber").asText()).isEqualTo("UNTRACED");
        assertThat(untraced.path("origin").asText()).isEqualTo("UNTRACED");
        assertThat(absent(untraced.path("goodsReceiptId"))).isTrue();
        assertThat(absent(untraced.path("purchaseOrderId"))).isTrue();
        assertThat(untraced.path("onHand").asLong()).isEqualTo(33);
        assertThat(untraced.path("available").asLong()).isEqualTo(33);
        assertThat(untraced.path("held").asLong()).isZero();

        UUID lot = UUID.fromString(untraced.path("lotId").asText());
        assertThat(balances(lot)).containsExactly(SOFA_BIN + " AVAILABLE 25 held 0", SOFA_BIN_2 + " AVAILABLE 8 held 0");
        JsonNode trace = data(get("/api/v1/inventory/lots/{id}", lot));
        assertThat(trace.path("holds")).isEmpty();
        assertThat(trace.path("orders")).isEmpty();
        assertThat(lotMovements(lot)).containsOnly("MOVE STOCK_PACKAGE in place 1")
                .hasSize(33);

        // The tables' lots are theirs still, each with what it had, where it had it.
        UUID available = lotOf(TABLE, "LOT-2026-03");
        UUID quarantined = lotOf(TABLE, "LOT-2026-04");
        assertThat(balances(available)).containsExactly(TABLE_BIN + " AVAILABLE 12 held 0");
        assertThat(balances(quarantined)).containsExactly(QUARANTINE + " QUARANTINE 30 held 0");
        JsonNode quarantinedLot = data(get("/api/v1/inventory/lots/{id}", quarantined)).path("lot");
        assertThat(quarantinedLot.path("onHand").asLong()).isEqualTo(30);
        assertThat(quarantinedLot.path("available").asLong()).as("quarantine is not available (BR-01)").isZero();
        assertThat(lotMovements(available)).filteredOn(m -> m.startsWith("MOVE STOCK_PACKAGE"))
                .containsOnly("MOVE STOCK_PACKAGE in place 1").hasSize(12);
        assertThat(lotMovements(quarantined)).filteredOn(m -> m.startsWith("MOVE STOCK_PACKAGE"))
                .containsOnly("MOVE STOCK_PACKAGE in place 1").hasSize(30);
    }

    // ------------------------------------------------------------------ allocation

    @Test
    @DisplayName("an order released after the conversion is given converted sofas as whole cartons, named by LPN"
            + " (rolled back)")
    void convertedStockIsAllocatedAsWholeCartons() throws Exception {
        Set<String> sofaCartons = cartons(SOFA).stream().map(c -> c.path("lpn").asText()).collect(Collectors.toSet());

        List<HoldView> holds = tx.execute(s -> {
            UUID order = confirmedOrder();
            Holds.units(inventory, order, SOFA, "HCM", 3);
            List<UUID> ids = inventory.allocate(new AllocateStockCommand(order, "HCM")).allocations().stream()
                    .flatMap(allocation -> allocation.holdIds().stream()).toList();
            List<HoldView> made = inventory.holds(ids);
            s.setRollbackOnly();   // the demo stock stays as the migration left it
            return made;
        });

        // 07 BR-03 / 20 §5.6: three cartons, one hold each, whole - so its pick line can only be confirmed by its LPN.
        assertThat(holds).hasSize(3).allSatisfy(hold -> {
            assertThat(hold.quantity()).isEqualTo(1);
            assertThat(hold.wholeCarton()).isTrue();
            assertThat(hold.lpn()).isIn(sofaCartons);
            assertThat(hold.lotNumber()).isEqualTo("UNTRACED");
        });
        assertThat(holds).extracting(HoldView::lpn).doesNotHaveDuplicates();
        assertThat(level(SOFA)).as("rolled back").isEqualTo("HCM onHand 33 available 33 reserved 0 allocated 0 atp 33");
    }

    // ------------------------------------------------------------------ helpers

    /** The {@code data} of a 200 answer to the manager's request. */
    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.asRole(Role.WAREHOUSE_MANAGER, manager, request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    /** Null in the answer, written out or left out. */
    private static boolean absent(JsonNode value) {
        return value.isNull() || value.isMissingNode();
    }

    /** Every item of one page, checked to be the whole of the list. */
    private static List<JsonNode> items(JsonNode page) {
        List<JsonNode> items = new ArrayList<>();
        page.path("items").forEach(items::add);
        assertThat(page.path("totalElements").asLong()).as("the list fits on one page").isEqualTo(items.size());
        return items;
    }

    /** The live cartons of {@code sku} (RECEIVING, IN_STOCK: the list's default). */
    private List<JsonNode> cartons(String sku) throws Exception {
        return items(data(get("/api/v1/inventory/stock-packages").param("sku", sku).param("size", "200")));
    }

    private static Map<String, Long> countBy(List<JsonNode> nodes, java.util.function.Function<JsonNode, String> key) {
        return nodes.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    /** The standard count per carton of each demo SKU: the factor of its one active unit that is not the base one. */
    private Map<String, Integer> cartonUnitFactors() {
        Map<String, Integer> factors = new HashMap<>();
        jdbc.query("""
                SELECT i.sku, u.factor FROM inventory.uom_conversions u
                  JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                 WHERE i.sku IN (?, ?) AND u.active AND NOT u.is_base""",
                rs -> {
                    assertThat(factors.put(rs.getString("sku"), rs.getInt("factor")))
                            .as("one carton unit per SKU (01 BR-03)").isNull();
                }, SOFA, TABLE);
        return factors;
    }

    /** What one location holds of {@code sku}, by carton, as one line of text; there must be exactly one line. */
    private String locationLine(String location, String sku) throws Exception {
        List<String> lines = new ArrayList<>();
        data(get("/api/v1/inventory/stock-packages/location-summary").param("location", location)).forEach(line -> {
            if (line.path("sku").asText().equals(sku)) {
                lines.add("%s/%s whole %d (%d) opened %d (%d) loose %d = %d".formatted(
                        line.path("lotNumber").asText(), line.path("status").asText(),
                        line.path("wholeCartons").asInt(), line.path("wholeCartonUnits").asInt(),
                        line.path("openedCartons").asInt(), line.path("openedCartonUnits").asInt(),
                        line.path("looseUnits").asInt(), line.path("totalUnits").asInt()));
            }
        });
        assertThat(lines).as("%s at %s", sku, location).hasSize(1);
        return lines.getFirst();
    }

    /** The one warehouse level of {@code sku}, as text. */
    private String level(String sku) throws Exception {
        JsonNode levels = data(get("/api/v1/inventory/stock-levels").param("sku", sku));
        assertThat(levels).as(sku).hasSize(1);
        JsonNode level = levels.get(0);
        return "%s onHand %d available %d reserved %d allocated %d atp %d".formatted(
                level.path("warehouseCode").asText(), level.path("onHand").asInt(), level.path("available").asInt(),
                level.path("reserved").asInt(), level.path("allocated").asInt(), level.path("atp").asInt());
    }

    /** Units available to promise of {@code sku}, summed per location, from the stock-by-SKU answer. */
    private Map<String, Integer> availableByLocation(String sku) throws Exception {
        Map<String, Integer> byLocation = new TreeMap<>();
        data(get("/api/v1/inventory/stock-items").param("sku", sku)).forEach(row -> {
            if (row.path("available").asInt() > 0) {
                byLocation.merge(row.path("locationCode").asText(), row.path("available").asInt(), Integer::sum);
            }
        });
        return byLocation;
    }

    /** The cartons V20261111000110 made, by id: what its ledger lines name. */
    private Map<UUID, String> cartonsOfTheMigration() {
        Map<UUID, String> cartons = new HashMap<>();
        jdbc.query("SELECT id, lpn FROM inventory.stock_package WHERE created_by = ?",
                rs -> {
                    cartons.put(rs.getObject("id", UUID.class), rs.getString("lpn"));
                }, MIGRATION);
        return cartons;
    }

    /**
     * The MOVE lines of {@code sku} naming a STOCK_PACKAGE, as the stock history answers them: each checked to stay in
     * place, move one unit and keep its status, and the cartons they name to be the migration's, one line each;
     * counted by location, lot and status.
     */
    private Map<String, Integer> conversionLines(String sku, Map<UUID, String> madeHere) throws Exception {
        List<JsonNode> lines = items(data(get("/api/v1/inventory/movements").param("sku", sku).param("type", "MOVE")
                .param("size", "200"))).stream()
                .filter(m -> m.path("referenceType").asText().equals("STOCK_PACKAGE")).toList();
        Set<UUID> named = new HashSet<>();
        Map<String, Integer> counted = new TreeMap<>();
        for (JsonNode line : lines) {
            UUID carton = UUID.fromString(line.path("referenceId").asText());
            assertThat(madeHere).as("the carton a conversion line names").containsKey(carton);
            assertThat(named.add(carton)).as("one line into %s", madeHere.get(carton)).isTrue();
            assertThat(line.path("type").asText()).isEqualTo("MOVE");
            assertThat(line.path("fromLocation").asText()).as(madeHere.get(carton))
                    .isEqualTo(line.path("toLocation").asText());
            assertThat(line.path("quantity").asInt()).as(madeHere.get(carton)).isEqualTo(1);
            assertThat(line.path("toStatus").asText()).as(madeHere.get(carton))
                    .isEqualTo(line.path("status").asText());
            counted.merge(line.path("toLocation").asText() + " " + line.path("lotNumber").asText() + " "
                    + line.path("status").asText(), 1, Integer::sum);
        }
        return counted;
    }

    private UUID lotOf(String sku, String lotNumber) throws Exception {
        List<JsonNode> lots = items(data(get("/api/v1/inventory/lots").param("sku", sku).param("lotNumber", lotNumber)));
        assertThat(lots).as("%s lot %s", sku, lotNumber).hasSize(1);
        return UUID.fromString(lots.getFirst().path("lotId").asText());
    }

    private List<String> balances(UUID lot) throws Exception {
        List<String> balances = new ArrayList<>();
        data(get("/api/v1/inventory/lots/{id}", lot)).path("balances").forEach(b -> balances.add(
                "%s %s %d held %d".formatted(b.path("locationCode").asText(), b.path("status").asText(),
                        b.path("onHand").asInt(), b.path("held").asInt())));
        return balances;
    }

    /** The lot's ledger lines through the lot trace, as {@code TYPE REFERENCE in place|moved QUANTITY}. */
    private List<String> lotMovements(UUID lot) throws Exception {
        List<String> lines = new ArrayList<>();
        items(data(get("/api/v1/inventory/lots/{id}/movements", lot).param("size", "200"))).forEach(m -> lines.add(
                "%s %s %s %d".formatted(m.path("type").asText(), m.path("referenceType").asText(),
                        m.path("fromLocation").asText().equals(m.path("toLocation").asText()) ? "in place" : "moved",
                        m.path("quantity").asInt())));
        return lines;
    }

    /** A CONFIRMED order of the demo customer, in the caller's transaction. */
    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order,
                "SO-20991226-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID());
        return order;
    }
}
