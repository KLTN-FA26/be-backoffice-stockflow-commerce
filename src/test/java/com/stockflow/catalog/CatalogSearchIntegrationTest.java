package com.stockflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Catalog search through the real filter chain, real PostgreSQL (extensions, generated column, GIN
 * indexes) and the real publication pipeline (product -> projection -> listing + entries).
 *
 * <p>Every test makes its own products around a random marker word, so counts are exact whatever else
 * lives in the shared database, and removes them afterwards.</p>
 */
@SecuredIntegrationTest
class CatalogSearchIntegrationTest {
    private static final String LIST = "/api/v1/catalog/products";
    private static final String SUGGEST = "/api/v1/catalog/search/suggestions";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CatalogProjectionService projection;
    @Autowired InventoryService inventory;
    @Autowired ObjectMapper json;

    private final List<UUID> created = new ArrayList<>();

    // ---- fixtures -------------------------------------------------------------------------

    private static String marker() {
        var r = ThreadLocalRandom.current();
        var sb = new StringBuilder("zq");
        for (int i = 0; i < 6; i++) sb.append((char) ('a' + r.nextInt(26)));
        return sb.toString();
    }

    /** A product published through the real path; returns its id. SKU is the single variant's code. */
    private UUID publish(String name, String description, String sku) {
        UUID id = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        created.add(id);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into product.products(id,code,name,slug,status,description)
                    values (?,?,?,?,'DRAFT',?)
                    """, id, "SRCH-" + id.toString().toUpperCase(Locale.ROOT), name, "srch-" + id, description);
            jdbc.update("insert into product.product_categories(id,product_id,category_id,is_primary)"
                    + " values (?,?,md5('demo:cat:SOFA')::uuid,true)", UUID.randomUUID(), id);
            jdbc.update("insert into product.variants(id,product_id,sku,name,status,attribute_signature)"
                    + " values (?,?,?,'Search fixture','ACTIVE','')", variant, id, sku);
            jdbc.update("""
                    insert into product.media(id,variant_id,url,is_published,published_at,published_by,created_by)
                    values (?,?,'https://example.test/search.jpg',true,now(),
                        md5('demo:user:approver')::uuid,md5('demo:user:editor')::uuid::text)
                    """, UUID.randomUUID(), variant);
            jdbc.update("""
                    insert into catalog.pricing_rule(id,name,sku,price,currency,active,created_at)
                    values (?,?,?,12500000,'VND',true,now())
                    """, UUID.randomUUID(), "BASE:" + sku, sku);
            jdbc.update("""
                    update product.products set status='PUBLISHED',submitted_by=md5('demo:user:editor')::uuid,
                    submitted_at=now(),approved_by=md5('demo:user:approver')::uuid,approved_at=now(),
                    published_at=now(),seo_title='SEO',seo_description='SEO description'
                    where id=?
                    """, id);
        });
        projection.project(id);
        return id;
    }

    private String sku(String marker, int n) {
        return marker.toUpperCase(Locale.ROOT) + "-" + n;
    }

    /** Moves a product out of the published state straight in the source, as a discontinuation does. */
    private void setStatus(UUID id, String status) {
        tx.executeWithoutResult(s -> jdbc.update(
                "update product.products set status=?, discontinued_at = case when ? = 'DISCONTINUED' then now() end"
                        + " where id=?", status, status, id));
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> {
            for (UUID id : created) {
                jdbc.update("delete from catalog.catalog_entry where product_id=?", id);
                jdbc.update("delete from catalog.product_listing where product_id=?", id);
                jdbc.update("delete from catalog.pricing_rule where sku in (select sku from product.variants where product_id=?)", id);
                jdbc.update("delete from product.media where variant_id in (select id from product.variants where product_id=?)", id);
                jdbc.update("delete from inventory.inventory_items where sku in (select sku from product.variants where product_id=?)", id);
                jdbc.update("delete from product.variants where product_id=?", id);
                jdbc.update("delete from product.product_categories where product_id=?", id);
                jdbc.update("update product.products set status='DRAFT' where id=?", id);
                jdbc.update("delete from product.products where id=?", id);
            }
        });
        created.clear();
    }

    // ---- requests -------------------------------------------------------------------------

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private ResultActions asCustomer(String path, String... params) throws Exception {
        return http().asCustomer(UUID.randomUUID(), request(path, params));
    }

    private ResultActions asStaff(String path, String... params) throws Exception {
        return http().asRole(Role.WAREHOUSE_STAFF, UUID.randomUUID(), request(path, params));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String path, String... params) {
        var b = get(path);
        for (int i = 0; i < params.length; i += 2) b.param(params[i], params[i + 1]);
        return b;
    }

    private List<String> titles(ResultActions result) throws Exception {
        JsonNode items = json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).at("/data/items");
        var out = new ArrayList<String>();
        items.forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- matching -------------------------------------------------------------------------

    @Test
    void findsTitlesWithAndWithoutDiacritics() throws Exception {
        String m = marker();
        publish("Bàn gỗ sồi " + m, null, sku(m, 1));
        for (String q : List.of(m + " ban go", m + " bàn gỗ", (m + " BÀN GỖ").toUpperCase(Locale.ROOT),
                Normalizer.normalize(m + " bàn gỗ", Normalizer.Form.NFD), m + " Ban Gỗ")) {
            assertThat(titles(asCustomer(LIST, "q", q))).as(q).containsExactly("Bàn gỗ sồi " + m);
        }
        assertThat(titles(asCustomer(LIST, "q", "qqqqwwwwee"))).isEmpty();
    }

    @Test
    void theDemoCatalogueAnswersBanGo() throws Exception {
        assertThat(titles(asCustomer(LIST, "q", "ban go"))).contains("Bàn ăn gỗ sồi");
        assertThat(titles(asCustomer(LIST, "q", "bàn gỗ"))).contains("Bàn ăn gỗ sồi");
    }

    @Test
    void dWithStrokeIsMatchedByPlainD() throws Exception {
        String m = marker();
        publish("Đĩa giấy " + m, null, sku(m, 1));
        assertThat(titles(asCustomer(LIST, "q", "dia giay " + m))).containsExactly("Đĩa giấy " + m);
        assertThat(titles(asCustomer(LIST, "q", "đĩa"))).contains("Đĩa giấy " + m);
    }

    @Test
    void lastWordMatchesAsAPrefixButEarlierWordsDoNot() throws Exception {
        String m = marker();
        publish(m + " cốc giấy", null, sku(m, 1));
        assertThat(titles(asCustomer(LIST, "q", m + " co"))).hasSize(1);
        assertThat(titles(asCustomer(LIST, "q", m + " coc gi"))).hasSize(1);
        assertThat(titles(asCustomer(LIST, "q", m.substring(0, 5)))).hasSize(1);
    }

    @Test
    void findsBySkuAndBySkuPrefix() throws Exception {
        String m = marker();
        publish("Hộp đựng", null, sku(m, 7));
        assertThat(titles(asCustomer(LIST, "q", sku(m, 7)))).containsExactly("Hộp đựng");
        assertThat(titles(asCustomer(LIST, "q", sku(m, 7).toLowerCase(Locale.ROOT)))).containsExactly("Hộp đựng");
        assertThat(titles(asCustomer(LIST, "q", m.toUpperCase(Locale.ROOT) + "-"))).containsExactly("Hộp đựng");
        assertThat(titles(asCustomer(LIST, "q", sku(m, 8)))).isEmpty();
    }

    @Test
    void aTypoFallsBackToSimilarTitles() throws Exception {
        String m = marker();
        publish("Cốc nhựa trong " + m, null, sku(m, 1));
        // "nhuaa" is no prefix of anything, so the exact search is empty and trigram similarity answers.
        assertThat(titles(asCustomer(LIST, "q", "coc nhuaa trong " + m))).containsExactly("Cốc nhựa trong " + m);
        assertThat(titles(asCustomer(LIST, "q", "qqqqzzzzxxxx"))).isEmpty();
    }

    @Test
    void searchTextIsNeverInterpretedAsQuerySyntax() throws Exception {
        String m = marker();
        publish("Ly " + m, null, sku(m, 1));
        for (String q : List.of("ly & !" + m, "ly | " + m, "'; drop table catalog.product_listing; --", "ly:* <-> " + m,
                "(((", "\\", "ly%", "_", "%")) {
            asCustomer(LIST, "q", q).andExpect(status().isOk());
        }
        assertThat(titles(asCustomer(LIST, "q", "ly & !" + m))).containsExactly("Ly " + m);
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing", Long.class)).isPositive();
    }

    @Test
    void percentAndUnderscoreAreNotSkuWildcards() throws Exception {
        String m = marker();
        String base = m.toUpperCase(Locale.ROOT);
        publish("Alpha", null, base + "-ABC1");
        publish("Beta", null, base + "XABC1"); // what an unescaped "_" in "m_abc" would also match
        assertThat(titles(asCustomer(LIST, "q", m + "_abc"))).containsExactly("Alpha");
        assertThat(titles(asCustomer(LIST, "q", "%"))).isEmpty();
        assertThat(titles(asCustomer(LIST, "q", "_"))).isEmpty();
    }

    // ---- ranking --------------------------------------------------------------------------

    @Test
    void aTitleMatchOutranksADescriptionMatch() throws Exception {
        String m = marker();
        publish("Khay đựng", "Dùng cho " + m + " loại lớn", sku(m, 1));
        publish(m + " cốc giấy", "Cốc", sku(m, 2));
        assertThat(titles(asCustomer(LIST, "q", m))).containsExactly(m + " cốc giấy", "Khay đựng");
    }

    @Test
    void anExactSkuComesFirstEvenAgainstABetterTextMatch() throws Exception {
        String m = marker();
        publish(m + " cốc giấy", null, sku(m, 1));
        publish("Khay đựng", "Tương thích " + m, m.toUpperCase(Locale.ROOT)); // SKU is exactly the word typed
        assertThat(titles(asCustomer(LIST, "q", m))).containsExactly("Khay đựng", m + " cốc giấy");
    }

    @Test
    void inStockOutranksOutOfStockWhenTextScoresAreEqual() throws Exception {
        var demo = jdbc.queryForMap("select id, name, description from product.products where code='TABLE-OAK'");
        long stock = inventory.availableQuantities(java.util.Set.of("TABLE-OAK-160")).getOrDefault("TABLE-OAK-160", 0L);
        org.junit.jupiter.api.Assumptions.assumeTrue(stock > 0, "demo stock for TABLE-OAK-160 is needed");
        // Same title and description as the demo table, no stock: scores tie on text, so stock decides.
        UUID clone = publish((String) demo.get("name"), (String) demo.get("description"), ("CLONE-" + UUID.randomUUID()).toUpperCase(Locale.ROOT));
        var out = body(asCustomer(LIST, "q", "ban an go soi").andExpect(status().isOk()));
        var ids = new ArrayList<String>();
        out.at("/data/items").forEach(n -> ids.add(n.get("productId").asText()));
        assertThat(ids).containsSubsequence(demo.get("id").toString(), clone.toString());
    }

    @Test
    void newestAndTitleSortApplyToSearchResults() throws Exception {
        String m = marker();
        publish("Cốc " + m, null, sku(m, 1));
        publish("Bàn " + m, null, sku(m, 2));
        publish("Ao " + m, null, sku(m, 3));
        assertThat(titles(asCustomer(LIST, "q", m, "sort", "newest")))
                .containsExactly("Ao " + m, "Bàn " + m, "Cốc " + m);
        assertThat(titles(asCustomer(LIST, "q", m, "sort", "title")))
                .containsExactly("Ao " + m, "Bàn " + m, "Cốc " + m);
        assertThat(titles(asCustomer(LIST, "q", m, "sort", "relevance"))).hasSize(3);
    }

    @Test
    void withoutTextTheListIsNewestFirstAndTitleSortIsAlphabetic() throws Exception {
        String m = marker();
        publish("Zeta " + m, null, sku(m, 1));
        publish("Đông " + m, null, sku(m, 2));
        publish("Alpha " + m, null, sku(m, 3));
        var newest = titles(asCustomer(LIST, "size", "200"));
        assertThat(newest.indexOf("Alpha " + m)).isLessThan(newest.indexOf("Đông " + m));
        assertThat(newest.indexOf("Đông " + m)).isLessThan(newest.indexOf("Zeta " + m));
        var byTitle = titles(asCustomer(LIST, "size", "200", "sort", "title"));
        assertThat(byTitle.indexOf("Alpha " + m)).isLessThan(byTitle.indexOf("Zeta " + m));
        assertThat(byTitle.indexOf("Zeta " + m)).isGreaterThan(byTitle.indexOf("Đông " + m));
        // relevance has no meaning without text and behaves as newest
        assertThat(titles(asCustomer(LIST, "size", "200", "sort", "relevance"))).isEqualTo(newest);
    }

    // ---- visibility -----------------------------------------------------------------------

    @Test
    void productsThatAreNotPublishedNeverAppear() throws Exception {
        String m = marker();
        String other = marker();
        UUID live = publish("Hiện " + m, null, sku(m, 1));
        // Never published: no listing at all.
        UUID draftId = UUID.randomUUID();
        UUID approvedId = UUID.randomUUID();
        created.add(draftId);
        created.add(approvedId);
        tx.executeWithoutResult(s -> {
            jdbc.update("insert into product.products(id,code,name,slug,status) values (?,?,?,?,'DRAFT')",
                    draftId, "SRCH-" + draftId.toString().toUpperCase(Locale.ROOT), "Nháp " + other, "srch-" + draftId);
            jdbc.update("""
                    insert into product.products(id,code,name,slug,status,submitted_by,submitted_at,approved_by,approved_at)
                    values (?,?,?,?,'APPROVED',md5('demo:user:editor')::uuid,now(),md5('demo:user:approver')::uuid,now())
                    """, approvedId, "SRCH-" + approvedId.toString().toUpperCase(Locale.ROOT), "Duyệt " + other, "srch-" + approvedId);
        });
        assertThat(titles(asCustomer(LIST, "q", m))).containsExactly("Hiện " + m);
        assertThat(titles(asCustomer(LIST, "q", "nhap " + other))).isEmpty();
        assertThat(titles(asCustomer(LIST, "q", "duyet " + other))).isEmpty();
        assertThat(live).isNotNull();
    }

    @Test
    void aProductLeavingPublicationDisappearsImmediatelyEvenBeforeTheProjectionCatchesUp() throws Exception {
        for (String status : List.of("DRAFT", "INACTIVE", "DISCONTINUED", "APPROVED")) {
            String m = marker();
            UUID id = publish("Rời " + m, null, sku(m, 1));
            assertThat(titles(asCustomer(LIST, "q", m))).as("published").hasSize(1);
            setStatus(id, status); // the catalog snapshot still says enabled
            assertThat(jdbc.queryForObject("select enabled from catalog.product_listing where product_id=?",
                    Boolean.class, id)).isTrue();
            assertThat(titles(asCustomer(LIST, "q", m))).as("stale snapshot, status " + status).isEmpty();
            assertThat(titles(asCustomer(LIST, "q", sku(m, 1)))).as("by sku, status " + status).isEmpty();
            assertThat(body(asCustomer(SUGGEST, "q", m)).at("/data").size()).as("suggest, status " + status).isZero();
        }
    }

    @Test
    void afterTheProjectionRunsTheListingIsHiddenInSqlToo() throws Exception {
        String m = marker();
        UUID id = publish("Ẩn " + m, null, sku(m, 1));
        setStatus(id, "DISCONTINUED");
        projection.project(id);
        assertThat(jdbc.queryForObject("select enabled from catalog.product_listing where product_id=?",
                Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry where product_id=? and published",
                Long.class, id)).isZero();
        assertThat(titles(asCustomer(LIST, "q", m))).isEmpty();
    }

    @Test
    void aListingWithNoPublishedEntryIsHiddenInSql() throws Exception {
        String m = marker();
        UUID id = publish("Mồ côi " + m, null, sku(m, 1));
        tx.executeWithoutResult(s -> jdbc.update("update catalog.catalog_entry set published=false where product_id=?", id));
        assertThat(titles(asCustomer(LIST, "q", m))).isEmpty();
        assertThat(titles(asCustomer(LIST, "size", "200"))).doesNotContain("Mồ côi " + m);
    }

    @Test
    void theTotalIsTheNumberOfVisibleMatchesAndEveryFullPageIsFull() throws Exception {
        String m = marker();
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 8; i++) ids.add(publish("Mẫu " + m + " " + (char) ('a' + i), null, sku(m, i)));
        setStatus(ids.get(1), "DISCONTINUED"); // stale in the snapshot, gone in truth
        setStatus(ids.get(4), "INACTIVE");
        // 6 visible, size 4: page 0 has 4, page 1 has 2 — the old listing would have produced short pages.
        var first = body(asCustomer(LIST, "q", m, "size", "4", "page", "0").andExpect(status().isOk()));
        assertThat(first.at("/data/items").size()).isEqualTo(4);
        assertThat(first.at("/data/totalElements").asLong()).isEqualTo(6);
        assertThat(first.at("/data/totalPages").asInt()).isEqualTo(2);
        assertThat(first.at("/data/hasNext").asBoolean()).isTrue();
        var second = body(asCustomer(LIST, "q", m, "size", "4", "page", "1").andExpect(status().isOk()));
        assertThat(second.at("/data/items").size()).isEqualTo(2);
        assertThat(second.at("/data/hasNext").asBoolean()).isFalse();
        assertThat(body(asCustomer(LIST, "q", m, "size", "4", "page", "2")).at("/data/items").size()).isZero();
    }

    // ---- request validation ---------------------------------------------------------------

    @Test
    void badPageSizeAndSortAreRefusedWithTheirErrorCode() throws Exception {
        asCustomer(LIST, "page", "-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        asCustomer(LIST, "size", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        asCustomer(LIST, "size", "201").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        asCustomer(LIST, "size", "abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        asCustomer(LIST, "q", "ly", "sort", "price").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
        asCustomer(LIST, "sort", "price,asc").andExpect(status().isBadRequest());
        asCustomer(LIST, "size", "200").andExpect(status().isOk());
    }

    @Test
    void anOverLongQueryIsRefusedAndTheBoundaryIsAccepted() throws Exception {
        asCustomer(LIST, "q", "a".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("q"));
        asCustomer(LIST, "q", "a".repeat(100)).andExpect(status().isOk());
        asCustomer(SUGGEST, "q", "a".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void anEmptyOrPunctuationOnlyQuery() throws Exception {
        String m = marker();
        publish("Có " + m, null, sku(m, 1));
        // empty / blank: the plain list
        assertThat(titles(asCustomer(LIST, "q", "", "size", "200"))).contains("Có " + m);
        assertThat(titles(asCustomer(LIST, "q", "   ", "size", "200"))).contains("Có " + m);
        // nothing but punctuation: an empty page, not an error
        var page = body(asCustomer(LIST, "q", "!!! ??? &|:*").andExpect(status().isOk()));
        assertThat(page.at("/data/items").size()).isZero();
        assertThat(page.at("/data/totalElements").asLong()).isZero();
        assertThat(page.at("/data/hasNext").asBoolean()).isFalse();
    }

    // ---- suggestions ----------------------------------------------------------------------

    @Test
    void suggestionsNeedTwoCharactersAndAtMostTenResults() throws Exception {
        String m = marker();
        for (int i = 1; i <= 12; i++) publish("Sug " + m + " " + (char) ('a' + i), null, sku(m, i));
        asCustomer(SUGGEST, "q", "l").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("q"));
        asCustomer(SUGGEST).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        asCustomer(SUGGEST, "q", "!!").andExpect(status().isBadRequest());
        asCustomer(SUGGEST, "q", "su", "limit", "11").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        asCustomer(SUGGEST, "q", "su", "limit", "0").andExpect(status().isBadRequest());

        assertThat(body(asCustomer(SUGGEST, "q", m)).at("/data").size()).isEqualTo(5); // default
        assertThat(body(asCustomer(SUGGEST, "q", m, "limit", "10")).at("/data").size()).isEqualTo(10);
        assertThat(body(asCustomer(SUGGEST, "q", m, "limit", "3")).at("/data").size()).isEqualTo(3);
    }

    @Test
    void aSuggestionHasTitleSlugAndSkuOnly() throws Exception {
        String m = marker();
        UUID id = publish("Bàn gỗ " + m, null, sku(m, 1));
        var data = body(asCustomer(SUGGEST, "q", "ban go " + m).andExpect(status().isOk())).at("/data");
        assertThat(data.size()).isEqualTo(1);
        var first = data.get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("title", "slug", "sku");
        assertThat(first.get("title").asText()).isEqualTo("Bàn gỗ " + m);
        assertThat(first.get("slug").asText()).isEqualTo("srch-" + id);
        assertThat(first.get("sku").asText()).isEqualTo(sku(m, 1));
        // matched by SKU prefix: that SKU is the one reported
        assertThat(body(asCustomer(SUGGEST, "q", sku(m, 1).substring(0, 5))).at("/data/0/sku").asText())
                .isEqualTo(sku(m, 1));
    }

    @Test
    void suggestionsAreRateLimitedPerUser() throws Exception {
        UUID busy = UUID.randomUUID();
        int ok = 0;
        boolean limited = false;
        for (int i = 0; i < 120 && !limited; i++) {
            var result = http().asCustomer(busy, request(SUGGEST, "q", "ly")).andReturn().getResponse();
            if (result.getStatus() == 200) {
                ok++;
            } else {
                assertThat(result.getStatus()).isEqualTo(429);
                assertThat(json.readTree(result.getContentAsString()).get("errorCode").asText()).isEqualTo("RATE_LIMITED");
                limited = true;
            }
        }
        // 30 per 10 s is a token bucket: a slow machine may refill a few tokens while the calls run.
        assertThat(limited).as("a 429 within 120 rapid calls").isTrue();
        assertThat(ok).isBetween(30, 60);
        // the list endpoint has no such budget here, and another user is untouched
        http().asCustomer(busy, request(LIST, "q", "ly")).andExpect(status().isOk());
        asCustomer(SUGGEST, "q", "ly").andExpect(status().isOk());
    }

    // ---- who may call it ------------------------------------------------------------------

    @Test
    void anonymousCallersGetNothing() throws Exception {
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mvc.perform(get(LIST).param("q", "ly nhua")).andExpect(status().isUnauthorized());
        mvc.perform(get(SUGGEST).param("q", "ly")).andExpect(status().isUnauthorized());
    }

    @Test
    void customersAndEveryStaffRoleMaySearch() throws Exception {
        String m = marker();
        publish("Mở " + m, null, sku(m, 1));
        asCustomer(LIST, "q", m).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        asCustomer(SUGGEST, "q", m).andExpect(status().isOk());
        asStaff(LIST, "q", m).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        asStaff(SUGGEST, "q", m).andExpect(status().isOk());
        for (Role role : Role.values()) {
            http().asRole(role, UUID.randomUUID(), request(LIST, "q", m)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1));
            http().asRole(role, UUID.randomUUID(), request(SUGGEST, "q", m)).andExpect(status().isOk());
        }
    }

    @Test
    void bothEndpointsAreNoStoreAndCarryNoPriceOrStock() throws Exception {
        String m = marker();
        publish("Kín " + m, "mô tả", sku(m, 1));
        asCustomer(LIST, "q", m).andExpect(header().string("Cache-Control", "no-store"));
        asCustomer(SUGGEST, "q", m).andExpect(header().string("Cache-Control", "no-store"));
        asCustomer(LIST, "q", "!!").andExpect(header().string("Cache-Control", "no-store"));
        var card = body(asCustomer(LIST, "q", m)).at("/data/items/0");
        assertThat(card.fieldNames()).toIterable().containsExactlyInAnyOrder("productId", "slug", "title", "seoTitle");
        assertThat(body(asCustomer(LIST, "q", m)).toString()).doesNotContain("price", "stock", "atp", "quantity");
    }

    // ---- database objects -----------------------------------------------------------------

    @Test
    void theSearchDocumentIsGeneratedAndAccentFolded() {
        String m = marker();
        UUID id = publish("Bàn gỗ " + m, "Chất liệu đặc biệt", sku(m, 1));
        String doc = jdbc.queryForObject("select search_document::text from catalog.product_listing where product_id=?",
                String.class, id);
        assertThat(doc).contains("'ban':", "'go':", "'dac':", "'biet':").doesNotContain("bàn", "gỗ");
        assertThat(jdbc.queryForObject("select catalog.immutable_unaccent('Ly nhựa')", String.class)).isEqualTo("Ly nhua");
    }

    @Test
    void theQueriesUseTheirIndexesWhenThePlannerIsNotAllowedToScan() {
        String plan = tx.execute(s -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("""
                    explain select product_id from catalog.product_listing
                    where search_document @@ to_tsquery('simple', 'ban:*')""", String.class));
        });
        assertThat(plan).contains("ix_product_listing_search_document");
        String skuPlan = tx.execute(s -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("""
                    explain select product_id from catalog.catalog_entry where lower(sku) like 'zq%'""", String.class));
        });
        assertThat(skuPlan).contains("ix_catalog_entry_sku_trgm");
        String titlePlan = tx.execute(s -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("""
                    explain select product_id from catalog.product_listing
                    where catalog.immutable_unaccent(lower(published_title)) operator(public.%) 'ban go'""", String.class));
        });
        assertThat(titlePlan).contains("ix_product_listing_title_trgm");
    }

    // ---- logging --------------------------------------------------------------------------

    @Test
    void whatTheCustomerTypedIsNeverLogged() throws Exception {
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        try {
            String secret = "bi-mat-0901234567";
            asCustomer(LIST, "q", secret).andExpect(status().isOk());
            asCustomer(SUGGEST, "q", secret).andExpect(status().isOk());
            asCustomer(LIST, "q", secret + "x".repeat(200)).andExpect(status().isBadRequest());
            asCustomer(SUGGEST, "q", "x", "limit", "3").andExpect(status().isBadRequest());
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).doesNotContain("0901234567").doesNotContain("bi-mat").doesNotContain("bi%20mat");
                if (e.getThrowableProxy() != null) assertThat(e.getThrowableProxy().getMessage()).doesNotContain("0901234567");
            });
        } finally {
            root.detachAppender(appender);
        }
    }
}
