package com.stockflow.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.KeptCheckoutPrices;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Package 5-3 from the business rules' side, with security ON, real tokens and real PostgreSQL: the B2B storefront
 * without a price (kltn-docs 13 §1, §3, §4.1 step 2, §4.2, §4.4, BR-02, BR-03, BR-04, BR-07, BR-10, BR-11, BR-12).
 *
 * <ul>
 *   <li>A signed-in customer reading the list, a search, the suggestions, a product page or its gallery is told no
 *       price, tier, MOQ or stock figure - even while it holds an internal price on its own SKU and a price kept from
 *       before 4B-2 on the very variant the page shows (BR-04, BR-11). Checked on every field name of the answer and on
 *       the price values themselves, so a price under a new name fails too.</li>
 *   <li>The stock indicator answers only for the caller's own SKUs, in whole cartons of the SKU's standard size, against
 *       the default threshold of 5 cartons, summed over every warehouse, and never as a number (§4.4 step 4, BR-07,
 *       BR-12); another customer's SKU and a variant (quy cách) are not there at all (BR-02, BR-11). Only an ACTIVE
 *       customer gets it (BR-03), at most 50 SKUs at a time.</li>
 *   <li>The removed price routes are gone for whoever asks and change nothing; the group-price permission is gone with
 *       them (BR-04: no base price, no group price; open-questions D14).</li>
 *   <li>Publishing a product asks for no price (§4.1 step 2), and the published product is on the storefront.</li>
 *   <li>The customer's internal price stays staff-only (§4.2 step 4, BR-10).</li>
 * </ul>
 *
 * <p>Checkout ({@code POST /api/v1/orders}, kept until 5-2) is {@link CustomerPricingIntegrationTest}'s. This class
 * shares that test's application context on purpose - same imports, same properties - so a full build starts one
 * secured context for both, not two.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class, CustomerPricingIntegrationTest.LocalKeys.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorefrontWithoutPricesIntegrationTest {

    private static final String LIST = "/api/v1/catalog/products";
    private static final String SUGGEST = "/api/v1/catalog/search/suggestions";
    private static final String AVAILABILITY = LIST + "/availability";
    private static final String PASSWORD = "Storefront53Pass";

    /** Pieces in a carton of every customer SKU this class issues. */
    private static final int CARTON = 10;
    /** Prices that must never reach a customer, odd enough to search a whole response body for. */
    private static final long INTERNAL = 12_345_678;
    private static final long KEPT = 9_876_543;
    /** A field name that would carry a price or a stock figure, under any spelling the code has used or might use. */
    private static final Pattern PRICE_OR_STOCK = Pattern.compile(
            "price|tier|moq|currency|amount|stock|atp|onhand|availab|quantity", Pattern.CASE_INSENSITIVE);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identities;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String manager;
    private String sales;
    private String admin;
    /** A storage area of a second warehouse: the indicator counts every warehouse (BR-07, open-questions C3). */
    private String secondWarehouse;

    @BeforeAll
    void staffAndASecondWarehouse() {
        manager = token(account("SALES_MANAGER"));
        sales = token(account("SALES_STAFF"));
        admin = token(account("SYSTEM_ADMIN"));
        secondWarehouse = secondWarehouseArea();
    }

    // ------------------------------------------------------------------ BR-04, BR-11: no price on any screen

    @Test
    void aSignedInCustomerIsToldNoPriceAndNoStockFigureOnAnyCatalogScreen() throws Exception {
        String marker = marker();
        Buyer owner = buyer("ACTIVE");
        Buyer other = buyer("ACTIVE");
        ReferenceRows.ProductRow product = approvedWithGallery(marker);
        String slug = publish(product, marker);
        awaitListed(owner, marker);

        // Everything a price or a stock figure could be read from: the customer's internal price on its own SKU, a price
        // kept from before 4B-2 on the variant the page shows, and plenty of stock on both.
        String own = CustomerSkuRows.issueOn(jdbc, tx, owner.customerId(), product, CARTON).sku();
        String others = CustomerSkuRows.issueOn(jdbc, tx, other.customerId(), product, CARTON).sku();
        mvc.perform(post(customerPrice(owner.customerId(), own)).header("Authorization", bearer(manager))
                        .contentType("application/json")
                        .content("{\"tiers\":[{\"minQuantity\":2,\"unitPrice\":" + INTERNAL + "}]}"))
                .andExpect(status().isCreated());
        KeptCheckoutPrices.give(jdbc, tx, owner.customerId(), product.sku(), KEPT);
        for (String sku : List.of(own, others, product.sku())) CustomerSkuRows.stock(jdbc, tx, sku, 600);

        JsonNode cards = storefront(owner, LIST, "q", marker).get("items");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("productId").asText()).isEqualTo(product.productId().toString());
        // A card lists the variants (quy cách), each a code and a name; a customer's own SKU is not a variant.
        assertThat(cards.get(0).get("skus").findValuesAsText("sku")).containsExactly(product.sku());

        JsonNode page = storefront(owner, LIST + "/" + slug);
        assertThat(page.get("variants").findValuesAsText("sku")).containsExactly(product.sku());
        assertThat(storefront(owner, LIST + "/" + slug + "/gallery").get("images")).isNotEmpty();
        assertThat(storefront(owner, SUGGEST, "q", marker).findValuesAsText("slug")).contains(slug);

        // Nothing sorts by a price there is not (13 §1).
        mvc.perform(get(LIST).param("sort", "price").header("Authorization", bearer(owner.token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));

        // BR-02: another customer's SKU does not exist for this one, whatever is typed.
        for (String path : List.of(LIST, SUGGEST)) {
            assertThat(storefront(owner, path, "q", others).toString()).doesNotContain(others);
        }
        assertThat(page.toString()).doesNotContain(others);
    }

    // ------------------------------------------------------------------ §4.4 step 4, BR-07, BR-12: the indicator

    @Test
    void theIndicatorCountsWholeCartonsOfTheCustomersOwnSkusAcrossEveryWarehouse() throws Exception {
        Buyer owner = buyer("ACTIVE");
        Buyer other = buyer("ACTIVE");
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));

        // Pieces on hand -> indicator, cartons of 10, low at 5 cartons or fewer: a part carton is no carton.
        Map<String, String> expected = new LinkedHashMap<>();
        int[] pieces = {0, 9, 10, 50, 59, 60, 500};
        String[] indicator = {"OUT_OF_STOCK", "OUT_OF_STOCK", "LOW_STOCK", "LOW_STOCK", "LOW_STOCK", "IN_STOCK",
                "IN_STOCK"};
        for (int i = 0; i < pieces.length; i++) {
            String sku = CustomerSkuRows.issueOn(jdbc, tx, owner.customerId(), product, CARTON).sku();
            if (pieces[i] > 0) CustomerSkuRows.stock(jdbc, tx, sku, pieces[i]);
            expected.put(sku, indicator[i]);
        }
        // 3 cartons in each of two warehouses: LOW_STOCK in either alone, IN_STOCK system-wide.
        String split = CustomerSkuRows.issueOn(jdbc, tx, owner.customerId(), product, CARTON).sku();
        CustomerSkuRows.stock(jdbc, tx, split, 30);
        stock(secondWarehouse, split, 30);
        expected.put(split, "IN_STOCK");

        // Not the caller's: another customer's SKU and a variant (quy cách), both well stocked.
        String others = CustomerSkuRows.issueOn(jdbc, tx, other.customerId(), product, CARTON).sku();
        CustomerSkuRows.stock(jdbc, tx, others, 600);
        CustomerSkuRows.stock(jdbc, tx, product.sku(), 600);

        List<String> asked = new ArrayList<>(expected.keySet());
        asked.add(others);
        asked.add(product.sku());
        asked.add(asked.getFirst().toLowerCase(Locale.ROOT));   // the same SKU again, typed differently
        asked.add(split);

        assertThat(indicators(owner, asked)).containsExactlyEntriesOf(expected);
        // The other customer sees its own SKU, and nothing of this one's.
        assertThat(indicators(other, asked)).containsExactly(Map.entry(others, "IN_STOCK"));
    }

    @Test
    void onlyASignedInActiveCustomerGetsTheIndicatorForAtMostFiftySkus() throws Exception {
        String sku = stockedSkuOf(buyer("ACTIVE"));
        mvc.perform(get(AVAILABILITY).param("sku", sku)).andExpect(status().isUnauthorized());

        // BR-03: a customer not yet activated reads the catalogue but no stock; a blocked one gets nothing either.
        for (String profile : List.of("PENDING_ACTIVATION", "BLOCKED")) {
            Buyer inactive = buyer(profile);
            String own = stockedSkuOf(inactive);
            availability(inactive.token(), List.of(own)).andExpect(status().isForbidden());
            if (profile.equals("PENDING_ACTIVATION")) {
                mvc.perform(get(LIST).header("Authorization", bearer(inactive.token()))).andExpect(status().isOk());
            }
        }
        // Staff are not customers, whatever they are granted; neither is a CUSTOMER account without a profile.
        for (String staff : List.of(sales, manager, admin, token(account("CUSTOMER")))) {
            availability(staff, List.of(sku)).andExpect(status().isForbidden());
        }

        Buyer owner = buyer("ACTIVE");
        String own = stockedSkuOf(owner);
        List<String> fifty = new ArrayList<>(List.of(own));
        for (int i = 1; i < 50; i++) fifty.add("NOT-MINE-" + i);
        assertThat(indicators(owner, fifty)).containsExactly(Map.entry(own, "IN_STOCK"));
        fifty.add("NOT-MINE-50");
        availability(owner.token(), fifty).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        availability(owner.token(), List.of("not a sku!")).andExpect(status().isBadRequest());
        mvc.perform(get(AVAILABILITY).header("Authorization", bearer(owner.token()))).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ BR-04, D14: the removed routes

    @Test
    void theRemovedPriceRoutesAreGoneForEveryoneAndWriteNothing() throws Exception {
        Buyer owner = buyer("ACTIVE");
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        String own = CustomerSkuRows.issueOn(jdbc, tx, owner.customerId(), product, CARTON).sku();
        mvc.perform(post(customerPrice(owner.customerId(), own)).header("Authorization", bearer(manager))
                        .contentType("application/json")
                        .content("{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":" + INTERNAL + "}]}"))
                .andExpect(status().isCreated());
        UUID segment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO customer.segment(id, code, name, version, created_at) VALUES (?, ?, 'Dai ly', 0, now())",
                segment, ("SEG-" + segment).toUpperCase(Locale.ROOT)));
        Map<String, Object> before = footprint(owner.customerId(), product, segment);

        String variant = "/api/v1/products/" + product.productId() + "/variants/" + product.variantId() + "/base-price";
        String bySku = "/api/v1/products/" + product.productId() + "/skus/" + product.sku() + "/base-price";
        String group = "/api/v1/catalog/segment-prices/" + segment + "/" + product.sku();
        String price = "{\"price\":" + INTERNAL + ",\"currency\":\"VND\",\"version\":0}";
        // Suppliers, not builders: a builder adds a header on every call, so one reused would carry two tokens.
        List<Supplier<MockHttpServletRequestBuilder>> removed = List.of(
                () -> get(LIST + "/prices").param("sku", own),
                () -> get(variant), () -> put(variant).contentType("application/json").content(price),
                () -> get(bySku), () -> put(bySku).contentType("application/json").content(price),
                () -> get("/api/v1/catalog/segment-prices").param("segmentId", segment.toString()),
                () -> get(group), () -> post(group).contentType("application/json").content(price),
                () -> put(group).contentType("application/json").content(price),
                () -> delete(group).param("version", "0"),
                () -> get(customerPrice(owner.customerId(), own) + "/schedule"));
        for (Supplier<MockHttpServletRequestBuilder> request : removed) {
            mvc.perform(request.get()).andExpect(status().isUnauthorized());
            // The Sales Manager and the System Admin held every one of these routes' permissions.
            for (String caller : List.of(manager, admin)) {
                mvc.perform(request.get().header("Authorization", bearer(caller))).andExpect(status().isNotFound());
            }
        }
        mvc.perform(get(LIST + "/prices").param("sku", own).header("Authorization", bearer(owner.token())))
                .andExpect(status().isNotFound());
        assertThat(footprint(owner.customerId(), product, segment)).isEqualTo(before);

        // The data behind them is gone too, and so is the group-price permission, from every role (ADR-0008).
        assertThat(jdbc.queryForObject("SELECT to_regclass('catalog.pricing_rule') IS NULL"
                + " AND to_regclass('catalog.promotion') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'catalog' AND table_name = 'catalog_entry'""", String.class))
                .doesNotContain("price", "currency");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.permission
                WHERE resource = 'catalog-segment-prices' OR code LIKE 'catalog-segment-prices:%'""", Long.class))
                .isZero();
        for (String role : List.of("SALES_MANAGER", "SALES_STAFF", "SYSTEM_ADMIN")) {
            assertThat(identities.roleMatrix(role).groups().stream().flatMap(g -> g.resources().stream())
                    .map(r -> r.code())).as(role).doesNotContain("catalog-segment-prices");
        }
    }

    // ------------------------------------------------------------------ §4.1 step 2: publication asks for no price

    @Test
    void aProductIsPublishedWithoutAnyPriceAndIsThenOnTheStorefront() throws Exception {
        String marker = marker();
        ReferenceRows.ProductRow product = approvedWithGallery(marker);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.customer_price WHERE sku = ?", Long.class,
                product.sku())).as("not a price of any kind for its SKU").isZero();

        String slug = publish(product, marker);

        assertThat(jdbc.queryForObject("SELECT status FROM product.products WHERE id = ?", String.class,
                product.productId())).isEqualTo("PUBLISHED");
        Buyer reader = buyer("ACTIVE");
        awaitListed(reader, marker);
        assertThat(storefront(reader, LIST + "/" + slug).get("productId").asText())
                .isEqualTo(product.productId().toString());
    }

    // ------------------------------------------------------------------ §4.2 step 4, BR-10: the internal price is staff's

    @Test
    void theInternalPriceIsNeverTheCustomers() throws Exception {
        Buyer owner = buyer("ACTIVE");
        String own = CustomerSkuRows.issue(jdbc, tx, entityManager, owner.customerId(), CARTON).sku();
        String path = customerPrice(owner.customerId(), own);
        String tiers = "{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":" + INTERNAL + "}],\"version\":0}";
        mvc.perform(post(path).header("Authorization", bearer(manager)).contentType("application/json").content(tiers))
                .andExpect(status().isCreated());

        for (Buyer customer : List.of(owner, buyer("ACTIVE"))) {
            String auth = bearer(customer.token());
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/api/v1/catalog/customer-prices").param("customerId", owner.customerId().toString()),
                    get(path), post(path).contentType("application/json").content(tiers),
                    put(path).contentType("application/json").content(tiers), delete(path).param("version", "0"))) {
                mvc.perform(request.header("Authorization", auth)).andExpect(status().isForbidden());
            }
        }
        // Still there, unchanged, for Sales to quote from.
        mvc.perform(get(path).header("Authorization", bearer(sales))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.tiers[0].unitPrice").value(INTERNAL));
    }

    // ------------------------------------------------------------------ reads

    /** A storefront read as {@code reader}: 200, and nothing in it names or carries a price or a stock figure. */
    private JsonNode storefront(Buyer reader, String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(path).header("Authorization", bearer(reader.token()));
        for (int i = 0; i + 1 < params.length; i += 2) request.param(params[i], params[i + 1]);
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode answer = mapper.readTree(body);
        assertThat(fieldNames(answer)).as(path).noneMatch(name -> PRICE_OR_STOCK.matcher(name).find());
        assertThat(body).as(path).doesNotContain(String.valueOf(INTERNAL)).doesNotContain(String.valueOf(KEPT));
        return answer.get("data");
    }

    /** Every field name anywhere in {@code node}. */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.properties().forEach(field -> {
            names.add(field.getKey());
            names.addAll(fieldNames(field.getValue()));
        });
        if (node.isArray()) node.forEach(child -> names.addAll(fieldNames(child)));
        return names;
    }

    private ResultActions availability(String token, List<String> skus) throws Exception {
        return mvc.perform(get(AVAILABILITY).param("sku", skus.toArray(String[]::new))
                .header("Authorization", bearer(token)));
    }

    /** The indicator answer as SKU -> indicator, in the order given, each entry a word and never a number. */
    private Map<String, String> indicators(Buyer buyer, List<String> skus) throws Exception {
        JsonNode data = mapper.readTree(availability(buyer.token(), skus).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        Map<String, String> answer = new LinkedHashMap<>();
        for (JsonNode entry : data) {
            assertThat(fieldNames(entry)).containsExactlyInAnyOrder("sku", "availability");
            assertThat(entry.get("availability").isTextual()).isTrue();
            answer.put(entry.get("sku").asText(), entry.get("availability").asText());
        }
        assertThat(answer).as("one entry per SKU").hasSize(data.size());
        return answer;
    }

    /** What a removed route could have written: the customer's prices, the product and its entries, their audit. */
    private Map<String, Object> footprint(UUID customerId, ReferenceRows.ProductRow product, UUID segment) {
        return Map.of(
                "prices", jdbc.queryForList("""
                        SELECT p.sku, p.version, t.min_quantity, t.unit_price FROM catalog.customer_price p
                        JOIN catalog.customer_price_tier t ON t.customer_price_id = p.id WHERE p.customer_id = ?
                        ORDER BY p.sku, t.min_quantity""", customerId),
                "product", jdbc.queryForMap("SELECT * FROM product.products WHERE id = ?", product.productId()),
                "entries", jdbc.queryForList("SELECT * FROM catalog.catalog_entry WHERE product_id = ? ORDER BY sku",
                        product.productId()),
                "audit", jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_id IN (?, ?, ?, ?)",
                        Long.class, customerId.toString(), product.productId().toString(),
                        product.variantId().toString(), segment.toString()));
    }

    // ------------------------------------------------------------------ fixtures

    private record Buyer(UUID customerId, String token) {
    }

    /** A random lower-case word: a search for it finds this test's product and nothing else in the shared database. */
    private static String marker() {
        StringBuilder word = new StringBuilder("zq");
        for (int i = 0; i < 6; i++) word.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return word.toString();
    }

    /**
     * An APPROVED, CUSTOMIZABLE product named after {@code marker}, in a demo category, whose variant has an image
     * approved by someone other than its uploader: everything publication asks for (kltn-docs 01 §4.5) - and no price.
     */
    private ReferenceRows.ProductRow approvedWithGallery(String marker) {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE product.products SET name = ?, description = 'Ly giay in theo thiet ke cua khach',
                           kind = 'CUSTOMIZABLE' WHERE id = ?""", "Ly giay " + marker, product.productId());
            jdbc.update("INSERT INTO product.product_categories(id, product_id, category_id, is_primary)"
                    + " VALUES (?, ?, md5('demo:cat:SOFA')::uuid, TRUE)", Identifiers.newId(), product.productId());
            jdbc.update("""
                    INSERT INTO product.media(id, variant_id, url, is_published, published_at, published_by, created_by)
                    VALUES (?, ?, 'https://example.test/ly-giay.jpg', TRUE, NOW(), md5('demo:user:approver')::uuid,
                            md5('demo:user:editor')::uuid::text)""", Identifiers.newId(), product.variantId());
        });
        return product;
    }

    /** Publishes as the Sales Manager does in the back office (13 §4.1): the listing's slug, then publication. */
    private String publish(ReferenceRows.ProductRow product, String marker) throws Exception {
        String path = "/api/v1/products/" + product.productId();
        long revision = mapper.readTree(mvc.perform(get(path + "/ecommerce").header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/revision").asLong();
        String slug = "ly-giay-" + marker;
        mvc.perform(put(path + "/ecommerce").header("Authorization", bearer(manager)).contentType("application/json")
                        .content("{\"revision\":%d,\"slug\":\"%s\",\"seoTitle\":\"Ly giay %s\"}"
                                .formatted(revision, slug, marker)))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/publication").header("Authorization", bearer(manager))).andExpect(status().isOk());
        return slug;
    }

    /** The storefront is a projection, refreshed after the publication commits (13 §4.1 step 3). */
    private void awaitListed(Buyer reader, String marker) {
        Await.until("the published product in a search for " + marker, Duration.ofSeconds(30), () -> {
            try {
                return mapper.readTree(mvc.perform(get(LIST).param("q", marker)
                                .header("Authorization", bearer(reader.token())))
                        .andReturn().getResponse().getContentAsString()).at("/data/items").size() == 1;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** A SKU of {@code buyer}'s own with 60 cartons on hand. */
    private String stockedSkuOf(Buyer buyer) {
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, buyer.customerId(), CARTON).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 600);
        return sku;
    }

    /** {@code pieces} of {@code sku}, sellable, at {@code location}, received a while ago. */
    private void stock(String location, String sku, int pieces) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                    created_at, received_at)
                VALUES (?, ?, ?, ?, 0, 'AVAILABLE', 0, NOW(), clock_timestamp() - interval '2 days')""",
                Identifiers.newId(), sku, location, pieces));
    }

    /** A second ACTIVE warehouse with one overflow storage area; returns the area's location code. */
    private String secondWarehouseArea() {
        UUID warehouse = Identifiers.newId();
        UUID location = Identifiers.newId();
        String prefix = "S" + warehouse.toString().substring(30).toUpperCase(Locale.ROOT);
        String area = prefix + "-OVF01";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Kho thu hai', 'ACTIVE', ?, 'Da Nang', 'M', 50, 50, 0, NOW())""", warehouse, prefix);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            is_pickable, is_putaway_target, status)
                    VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')""", location, warehouse, area);
            jdbc.update("""
                    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,
                                                rotation, is_obstacle, status)
                    VALUES (?, ?, ?, 'OVF01', 'OVERFLOW', 'Overflow', 1, 1, 5, 5, 0, FALSE, 'ACTIVE')""",
                    Identifiers.newId(), warehouse, location);
        });
        return area;
    }

    /** A customer with a portal account in this status; the account itself is ACTIVE, so it signs in. */
    private Buyer buyer(String status) {
        UUID user = account("CUSTOMER");
        UUID customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer(id, user_id, full_name, email, phone, status, version, created_at)
                SELECT ?, id, 'Cong ty Mua Ly', email, '0901234567', ?, 0, now() FROM identity.app_user WHERE id = ?""",
                customer, status, user));
        return new Buyer(customer, token(user));
    }

    /** A signed-up account holding {@code role} and nothing else. */
    private UUID account(String role) {
        UUID id = Identifiers.newId();
        String name = "storefront-" + role.toLowerCase(Locale.ROOT) + "-" + id;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.app_user(id, username, email, password_hash, full_name, status, version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, now())""",
                    id, name, name + "@example.test", passwordEncoder.encode(PASSWORD), role);
            jdbc.update("""
                    INSERT INTO identity.user_role(id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, now() FROM identity.app_role WHERE code = ?""", Identifiers.newId(), id, role);
        });
        return id;
    }

    private String token(UUID userId) {
        String username = jdbc.queryForObject("SELECT username FROM identity.app_user WHERE id = ?", String.class, userId);
        return identities.login(new LoginCommand(username, PASSWORD, null, "storefront-5-3")).accessToken();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String customerPrice(UUID customerId, String sku) {
        return "/api/v1/catalog/customer-prices/" + customerId + "/" + sku;
    }
}
