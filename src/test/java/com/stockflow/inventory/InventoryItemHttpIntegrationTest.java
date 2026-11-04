package com.stockflow.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import java.util.Locale;
import java.util.UUID;
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
 * {@code /api/v1/inventory/items} with security off: what the detail and the list answer, that both PUTs return the
 * whole item, and that a bad id or a bad body is refused with the right status. Who may call is in
 * {@link InventoryItemSecurityIntegrationTest}.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class InventoryItemHttpIntegrationTest {

    private static final String BASE = "/api/v1/inventory/items";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** A variant makes its inventory item; the barcode and storage class are then set on that item. */
    private UUID item(String sku, String barcode, String storageClass) {
        return tx.execute(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Item fixture", slugOf(sku));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Item fixture','')", Identifiers.newId(), product, sku);
            jdbc.update("update inventory.inventory_items set barcode=?, storage_class=? where sku=?", barcode,
                    storageClass, sku);
            return jdbc.queryForObject("select id from inventory.inventory_items where sku=?", UUID.class, sku);
        });
    }

    /** ck_products_slug: lowercase runs of [a-z0-9] separated by single hyphens. */
    private static String slugOf(String sku) {
        return sku.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private long versionOf(UUID id) {
        return jdbc.queryForObject("select version from inventory.inventory_items where id=?", Long.class, id);
    }

    private static String logisticsBody(long version) {
        return "{\"version\":" + version + ",\"unitOfMeasure\":\"EACH\",\"storageClass\":\"NORMAL\","
                + "\"weightKg\":0.120,\"requiresAdultSignature\":false,\"qcRequired\":false}";
    }

    private static String policyBody(long version, int reorderPoint) {
        return "{\"version\":" + version + ",\"reorderPoint\":" + reorderPoint
                + ",\"removalStrategy\":\"FEFO\",\"trackingMode\":\"NONE\",\"expiryTracked\":false}";
    }

    @Test
    void detailHasLogisticsPolicyAndEvaluation() throws Exception {
        UUID id = item("HTTP-D-" + suffix, null, "NORMAL");
        mvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.sku").value("HTTP-D-" + suffix))
                .andExpect(jsonPath("$.data.logistics.unitOfMeasure").value("EACH"))
                .andExpect(jsonPath("$.data.policy.removalStrategy").value("FEFO"))
                .andExpect(jsonPath("$.data.evaluation.usableOnHand").value(0));
    }

    @Test
    void putLogisticsReturnsTheWholeItem() throws Exception {
        UUID id = item("HTTP-L-" + suffix, null, "NORMAL");
        long v = versionOf(id);
        mvc.perform(put(BASE + "/" + id + "/logistics").contentType(MediaType.APPLICATION_JSON)
                        .content(logisticsBody(v)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(v + 1))
                .andExpect(jsonPath("$.data.logistics.weightKg").value(0.120))
                .andExpect(jsonPath("$.data.policy").exists());
    }

    @Test
    void putPolicyReturnsTheWholeItem() throws Exception {
        UUID id = item("HTTP-P-" + suffix, null, "NORMAL");
        mvc.perform(put(BASE + "/" + id + "/policy").contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(versionOf(id), 500)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policy.reorderPoint").value(500))
                .andExpect(jsonPath("$.data.logistics.unitOfMeasure").value("EACH"));
    }

    @Test
    void negativeReorderPointIs400() throws Exception {
        UUID id = item("HTTP-N-" + suffix, null, "NORMAL");
        mvc.perform(put(BASE + "/" + id + "/policy").contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(versionOf(id), -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void packagingPreviewResolvesTheItemsOwnBox() throws Exception {
        UUID id = item("HTTP-K-" + suffix, null, "NORMAL");
        tx.executeWithoutResult(s -> jdbc.update("""
                update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6,
                    package_weight_kg = 0.7, package_length_cm = 12, package_width_cm = 9, package_height_cm = 7
                where id = ?""", id));
        mvc.perform(get(BASE + "/" + id + "/packaging-preview").param("quantity", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.level").value("ITEM_BOX"))
                .andExpect(jsonPath("$.data.unitOfMeasure").value("EACH"))
                .andExpect(jsonPath("$.data.fullCartons").value(4))
                .andExpect(jsonPath("$.data.partialCarton").doesNotExist())
                .andExpect(jsonPath("$.data.carton.lengthMm").value(120))
                .andExpect(jsonPath("$.data.carton.weightKg").value(0.7))
                .andExpect(jsonPath("$.data.carton.rotatable").value(true))
                .andExpect(jsonPath("$.data.kitParts").value(1));
    }

    @Test
    void packagingPreviewOfAnItemWithoutDataIsMissingButStill200() throws Exception {
        UUID id = item("HTTP-M-" + suffix, null, "NORMAL");
        mvc.perform(get(BASE + "/" + id + "/packaging-preview").param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MISSING"))
                .andExpect(jsonPath("$.data.missing.code").value("PACKAGING_MISSING"))
                .andExpect(jsonPath("$.data.missing.fields[0]").value("lengthCm"));
    }

    @Test
    void packagingPreviewRefusesABadQuantityAndAnUnknownItem() throws Exception {
        UUID id = item("HTTP-Q-" + suffix, null, "NORMAL");
        mvc.perform(get(BASE + "/" + id + "/packaging-preview").param("quantity", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + id + "/packaging-preview")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/packaging-preview").param("quantity", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INVENTORY_ITEM_NOT_FOUND"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(get(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INVENTORY_ITEM_NOT_FOUND"));
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        mvc.perform(get(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void listFiltersBySkuExactly() throws Exception {
        String sku = "HTTP-S-" + suffix;
        UUID id = item(sku, null, "NORMAL");
        item(sku + "-2", null, "NORMAL");
        mvc.perform(get(BASE).param("sku", sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()));
    }

    @Test
    void listFiltersByQOnSkuOrBarcode() throws Exception {
        String sku = "HTTP-Q-" + suffix;
        String barcode = "BC" + suffix + "XYZ";
        UUID id = item(sku, barcode, "NORMAL");
        mvc.perform(get(BASE).param("q", ("q-" + suffix).toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()));
        mvc.perform(get(BASE).param("q", ("c" + suffix + "x").toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()));
    }

    @Test
    void skuAndQCombineWithAnd() throws Exception {
        String sku = "HTTP-AQ-" + suffix;
        UUID id = item(sku, null, "NORMAL");
        mvc.perform(get(BASE).param("sku", sku).param("q", "aq-" + suffix.toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()));
        mvc.perform(get(BASE).param("sku", sku).param("q", "no-such-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /** The schema only allows A-Z, 0-9, '.', '_' and '-' in a SKU, so a literal {@code %} is exercised through the barcode. */
    @Test
    void qPercentMatchesLiterally() throws Exception {
        UUID literal = item("HTTP-PC1-" + suffix, "W%X-" + suffix, "NORMAL");
        item("HTTP-PC2-" + suffix, "WAX-" + suffix, "NORMAL");
        mvc.perform(get(BASE).param("q", "W%X-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(literal.toString()));
    }

    @Test
    void qUnderscoreMatchesLiterally() throws Exception {
        UUID literal = item("W_X-" + suffix, null, "NORMAL");
        item("WAX-" + suffix, null, "NORMAL");
        mvc.perform(get(BASE).param("q", "W_X-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(literal.toString()));
    }

    @Test
    void listFiltersByStorageClassAndPolicyConfigured() throws Exception {
        String sku = "HTTP-C-" + suffix;
        UUID id = item(sku, null, "COLD");
        mvc.perform(get(BASE).param("sku", sku).param("storageClass", "COLD"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(BASE).param("sku", sku).param("storageClass", "NORMAL"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(BASE).param("sku", sku).param("policyConfigured", "true"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(put(BASE + "/" + id + "/policy").contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(versionOf(id), 10)))
                .andExpect(status().isOk());
        mvc.perform(get(BASE).param("sku", sku).param("policyConfigured", "true"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].policyConfigured").value(true));
    }

    @Test
    void listPagesInSkuOrder() throws Exception {
        String prefix = "HTTP-PG-" + suffix;
        item(prefix + "-B", null, "NORMAL");
        item(prefix + "-A", null, "NORMAL");
        item(prefix + "-C", null, "NORMAL");
        mvc.perform(get(BASE).param("q", prefix).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].sku").value(prefix + "-A"))
                .andExpect(jsonPath("$.data.items[1].sku").value(prefix + "-B"));
        mvc.perform(get(BASE).param("q", prefix).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value(prefix + "-C"));
    }

    /** The product-side routes were deleted outright (spec D4): no alias, no redirect. */
    @Test
    void theOldRoutesAreGone() throws Exception {
        String base = "/api/v1/products/" + UUID.randomUUID() + "/skus/" + UUID.randomUUID();
        for (String route : new String[] {"/logistics", "/inventory-control"}) {
            mvc.perform(get(base + route)).andExpect(status().isNotFound());
            mvc.perform(put(base + route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
