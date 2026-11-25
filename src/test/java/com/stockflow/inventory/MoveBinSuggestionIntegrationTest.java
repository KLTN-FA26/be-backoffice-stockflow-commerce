package com.stockflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.service.MoveSuggestionService;
import com.stockflow.support.CartonRows;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Issue #412 (kltn-docs 11 §4.1, docs#46): the bins a GROUP of cartons may be moved into, inside one warehouse, as
 * advice only. Every suggested bin takes every carton by size and the whole group by free room, in putaway's order;
 * the group's own locations are never suggested; OVERFLOW areas are named only when no bin takes the group. The move
 * itself checks only what it checks today (11 BR-10, C24), so a bin suggested here must be one the move accepts.
 *
 * <p>The cartons are committed, some into demo bins other tests resize ({@code BinResizeStrandingIntegrationTest}), so
 * each test removes its SKU's cartons afterwards; a carton the move has written a ledger line for cannot be deleted
 * (the ledger is append-only), so it is taken back to the overflow area first, where no bin's room is counted.</p>
 */
@SecuredIntegrationTest
class MoveBinSuggestionIntegrationTest {

    private static final String URL = "/api/v1/inventory/stock-packages/bin-suggestions";
    private static final String OVERFLOW = "HCM-OVF01";
    private static final String RECEIVING = "HCM-RCV01";
    /** A putaway target of the demo map of HCM, measured. */
    private static final String CANDIDATE = "HCM-B01-2-B";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired MoveSuggestionService suggestions;
    @Autowired ObjectMapper json;

    private SecuredMvc mvc;
    private StaffFixtures staff;
    private String sku;
    private UUID manager;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        staff = new StaffFixtures(jdbc, tx, entityManager);
        sku = "MBS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        manager = staff.staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Move suggestion fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Move suggestion fixture");
        });
    }

    @AfterEach
    void removeCartons() {
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_item set location_code = ? where sku = ? and stock_package_id in"
                    + " (select stock_package_id from inventory.stock_movement where sku = ?)", OVERFLOW, sku, sku);
            jdbc.update("update inventory.stock_package set location_code = ? where id in"
                    + " (select stock_package_id from inventory.stock_movement where sku = ?)", OVERFLOW, sku);
            jdbc.update("delete from inventory.stock_item where sku = ? and stock_package_id not in"
                    + " (select stock_package_id from inventory.stock_movement where sku = ?"
                    + "  and stock_package_id is not null)", sku, sku);
            jdbc.update("delete from inventory.stock_package where inventory_item_id in"
                    + " (select id from inventory.inventory_items where sku = ?) and id not in"
                    + " (select stock_package_id from inventory.stock_movement where sku = ?"
                    + "  and stock_package_id is not null)", sku, sku);
        });
    }

    /** {@code count} cartons of the SKU at {@code location}, lot L1, the unit {@code l} x {@code w} x {@code h} cm. */
    private List<String> cartons(String location, int count, int l, int w, int h) {
        return tx.execute(s -> {
            List<CartonRows.Carton> made = CartonRows.stock(jdbc, sku, location, count, 10, "L1",
                    Instant.now().minus(Duration.ofDays(1)), "AVAILABLE");
            jdbc.update("update inventory.uom_conversions set length_cm = ?, width_cm = ?, height_cm = ?, weight_kg = 2"
                    + " where id = (select uom_conversion_id from inventory.stock_package where id = ?)", l, w, h,
                    made.getFirst().id());
            return made.stream().map(CartonRows.Carton::lpn).toList();
        });
    }

    private static MockHttpServletRequestBuilder ask(String... lpns) {
        MockHttpServletRequestBuilder request = get(URL);
        for (String lpn : lpns) {
            request = request.param("lpn", lpn);
        }
        return request;
    }

    private JsonNode answer(UUID user, String... lpns) throws Exception {
        String body = mvc.asRole(Role.WAREHOUSE_MANAGER, user, ask(lpns))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private static List<String> codes(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.get("suggestions").forEach(s -> out.add(s.get("locationCode").asText()));
        return out;
    }

    @Test
    @DisplayName("two cartons in the overflow area get bins of their warehouse, and the move takes them to the first")
    void overflowCartonGetsABinTheMoveAccepts() throws Exception {
        List<String> lpns = cartons(OVERFLOW, 2, 30, 20, 20);

        JsonNode data = answer(manager, lpns.toArray(String[]::new));

        assertThat(codes(data)).isNotEmpty().allMatch(code -> code.startsWith("HCM-")).doesNotContain(OVERFLOW);
        assertThat(data.get("overflowAreas")).isEmpty();
        String target = codes(data).getFirst();
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post("/api/v1/inventory/movements")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","lpns":["%s","%s"],"toLocation":"%s"}"""
                                .formatted(UUID.randomUUID(), lpns.get(0), lpns.get(1), target)))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForList("select distinct location_code from inventory.stock_package where lpn in (?, ?)",
                String.class, lpns.get(0), lpns.get(1))).containsExactly(target);
    }

    @Test
    @DisplayName("a bin one of the cartons stands in is never suggested, though it is the best bin for the other alone")
    void ownLocationIsNeverSuggested() throws Exception {
        String standing = cartons(CANDIDATE, 1, 30, 20, 20).getFirst();
        String loose = cartons(OVERFLOW, 1, 30, 20, 20).getFirst();

        JsonNode alone = answer(manager, loose);
        assertThat(alone.get("suggestions").get(0).get("locationCode").asText())
                .as("the bin holding the same lot is the best for the other carton alone").isEqualTo(CANDIDATE);
        assertThat(alone.get("suggestions").get(0).get("reason").asText()).isEqualTo("SAME_LOT");

        assertThat(codes(answer(manager, standing, loose))).isNotEmpty().doesNotContain(CANDIDATE, OVERFLOW);
    }

    @Test
    @DisplayName("an LPN named twice, in either case, is one carton")
    void duplicateLpnCountedOnce() throws Exception {
        String lpn = cartons(OVERFLOW, 1, 30, 20, 20).getFirst();

        JsonNode once = answer(manager, lpn);
        JsonNode twice = answer(manager, lpn, lpn.toLowerCase(Locale.ROOT));

        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("a carton no bin takes: no suggestion, and the warehouse's overflow areas instead")
    void nothingFitsGivesOverflowAreas() throws Exception {
        String lpn = cartons(RECEIVING, 1, 900, 900, 900).getFirst();

        JsonNode data = answer(manager, lpn);

        assertThat(data.get("suggestions")).isEmpty();
        assertThat(data.get("overflowAreas")).hasSize(1);
        assertThat(data.get("overflowAreas").get(0).asText()).isEqualTo(OVERFLOW);
    }

    @Test
    @DisplayName("a carton in a warehouse the caller is not assigned to: 403")
    void otherWarehouseCartonIs403() throws Exception {
        StaffFixtures.Warehouse other = staff.otherWarehouse();
        String lpn = cartons(other.area(), 1, 30, 20, 20).getFirst();
        UUID clerk = staff.staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, ask(lpn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    @Test
    @DisplayName("cartons of two warehouses: 409 MOVE_ACROSS_WAREHOUSES, stock never changes warehouse")
    void twoWarehousesIs409() throws Exception {
        StaffFixtures.Warehouse other = staff.otherWarehouse();
        String there = cartons(other.area(), 1, 30, 20, 20).getFirst();
        String here = cartons(OVERFLOW, 1, 30, 20, 20).getFirst();
        UUID both = staff.staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM, other.id());

        mvc.asRole(Role.WAREHOUSE_MANAGER, both, ask(here, there))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MOVE_ACROSS_WAREHOUSES"));
    }

    @Test
    @DisplayName("an LPN nobody issued: 404 STOCK_PACKAGE_NOT_FOUND")
    void unknownLpnIs404() throws Exception {
        String lpn = cartons(OVERFLOW, 1, 30, 20, 20).getFirst();

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, ask(lpn, "NO-SUCH-LPN-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("no LPN, or a blank one: 400 VALIDATION_FAILED")
    void noLpnIs400() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, ask(" "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the statements read do not grow with the number of cartons: 5 and 50 read the same")
    void queryCountIsFlat() {
        List<String> five = cartons(OVERFLOW, 5, 10, 10, 10);
        List<String> fifty = new ArrayList<>(five);
        fifty.addAll(cartons(OVERFLOW, 45, 10, 10, 10));
        suggestions.suggest(five);

        long forFive = statements(five);
        long forFifty = statements(fifty);

        assertThat(forFive).isPositive();
        assertThat(forFifty).isEqualTo(forFive);
    }

    /**
     * Every statement {@code suggest} sends: Hibernate's prepared statements, plus every call on the JdbcTemplate,
     * which Hibernate does not see (the occupancy reads are plain SQL).
     */
    private long statements(List<String> lpns) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        Mockito.clearInvocations(jdbc);
        try {
            suggestions.suggest(lpns);
            return statistics.getPrepareStatementCount() + Mockito.mockingDetails(jdbc).getInvocations().size();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
