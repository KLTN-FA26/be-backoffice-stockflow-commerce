package com.stockflow.product.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The id of a variant's inventory item on the variant list: right for every row, one inventory query for the whole
 * page, unchanged by a draft rename, and an empty page for a product without variants. Security is off, as in the
 * inventory item HTTP test; the variants are inserted directly and the database trigger makes their items.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class VariantInventoryItemIdIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired InventoryService inventory;
    @Autowired ProductVariantService variants;
    @Autowired EntityManagerFactory entityManagerFactory;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private UUID product(String code) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)",
                id, code, "Item id fixture", code.toLowerCase(Locale.ROOT)));
        return id;
    }

    /** Variants {@code <prefix>-0..n-1}, each with its own signature so the combination stays unique. */
    private List<UUID> variantsOf(UUID product, String prefix, int count) {
        return tx.execute(s -> {
            var ids = new java.util.ArrayList<UUID>();
            for (int i = 0; i < count; i++) {
                UUID id = Identifiers.newId();
                jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature,position)"
                        + " values (?,?,?,?,?,?)", id, product, prefix + "-" + i, "Variant " + i, "S" + i, i);
                ids.add(id);
            }
            return ids;
        });
    }

    private UUID itemId(String sku) {
        return jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
    }

    @Test
    void everyListedVariantCarriesItsItemId() throws Exception {
        UUID product = product("IID-L-" + suffix);
        variantsOf(product, "IID-L-" + suffix, 3);

        var result = mvc.perform(get("/api/v1/products/{p}/variants", product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
        for (int i = 0; i < 3; i++) {
            String sku = "IID-L-" + suffix + "-" + i;
            result.andExpect(jsonPath("$.data.items[" + i + "].sku").value(sku))
                    .andExpect(jsonPath("$.data.items[" + i + "].inventoryItemId").value(itemId(sku).toString()));
        }
    }

    @Test
    void listingCostsOneInventoryQuery() {
        UUID product = product("IID-Q-" + suffix);
        variantsOf(product, "IID-Q-" + suffix, 5);
        var views = variants.list(product, 0, 50).items();
        assertThat(views).hasSize(5);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var ids = variants.inventoryItemIds(views);

            assertThat(ids).hasSize(5);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void renamedDraftSkuKeepsTheSameItemId() throws Exception {
        UUID product = product("IID-R-" + suffix);
        UUID variant = variantsOf(product, "IID-R-" + suffix, 1).get(0);
        String before = itemId("IID-R-" + suffix + "-0").toString();
        String renamed = "IID-R2-" + suffix;

        mvc.perform(get("/api/v1/products/{p}/variants/{v}", product, variant))
                .andExpect(jsonPath("$.data.inventoryItemId").value(before));
        mvc.perform(put("/api/v1/products/{p}/variants/{v}", product, variant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sku\":\"" + renamed + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value(renamed))
                .andExpect(jsonPath("$.data.inventoryItemId").value(before));
        mvc.perform(get("/api/v1/products/{p}/variants/{v}", product, variant))
                .andExpect(jsonPath("$.data.sku").value(renamed))
                .andExpect(jsonPath("$.data.inventoryItemId").value(before));
    }

    @Test
    void emptyProductListsAnEmptyPage() throws Exception {
        UUID product = product("IID-E-" + suffix);

        mvc.perform(get("/api/v1/products/{p}/variants", product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void itemIdsOfEmptyIsEmpty() {
        assertThat(inventory.itemIdsOf(List.of())).isEmpty();
    }
}
