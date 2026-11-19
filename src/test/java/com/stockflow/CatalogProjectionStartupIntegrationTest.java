package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.jwk.RSAKey;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogProjectionListener;
import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.RedisContainer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real demo seed, application startup, RSA tokens, filter chain and PostgreSQL, from a blank database. */
@IntegrationTest
@Import({CatalogProjectionStartupIntegrationTest.StartupDatabase.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
@DirtiesContext
class CatalogProjectionStartupIntegrationTest {
    /** The demo box's two variants (V20261206000100). */
    private static final String BOX_GREY = "HOP-GIAY-DEMO-GREY-PAPER";
    private static final String BOX_BLUE = "HOP-GIAY-DEMO-BLUE-PAPER";

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupDatabase {
        /**
         * A blank database each run: Spring's own Flyway migrates it, demo seed included, so the projection is built by
         * ApplicationReadyEvent from rows the application never wrote through its catalog service.
         */
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            var pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.start();
            return pg;
        }

        @Bean
        @Primary
        JwtDecoder localSignatureDecoder(RSAKey key, ActiveSessionCheck sessions) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(), new SessionValidator(sessions)));
            return decoder;
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired CatalogProjectionListener listener;
    @Autowired CatalogCommerceService commerce;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    String authorization() {
        return authorization("SYSTEM_ADMIN");
    }

    String authorization(String role) {
        var now = Instant.now();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject(jdbc.queryForObject("select id::text from identity.app_user where id=md5('demo:user:approver')::uuid", String.class))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of(role))
                .build())).getTokenValue();
    }

    @Test
    void startupRebuildsWithoutRepublishAndStaysSafeOnRestart() throws Exception {
        // No manual project/publish call before these assertions: ApplicationReadyEvent did the backfill.
        String auth = authorization();
        String initialSeo = null; // the demo products carry no SEO title
        mvc.perform(get("/api/v1/catalog/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/products").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        assertBox(auth, initialSeo);
        mvc.perform(get("/api/v1/catalog/products/ly-giay-demo").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.variants[0].sku").value(DemoData.BLANK_CUP))
                .andExpect(jsonPath("$.data.variants[0].price").doesNotExist());
        for (String role : List.of("SALES_MANAGER", "SALES_STAFF", "WAREHOUSE_STAFF",
                "WAREHOUSE_MANAGER")) {
            String signedIn = authorization(role);
            mvc.perform(get("/api/v1/catalog/products").header("Authorization", signedIn))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
            assertBox(signedIn, initialSeo);
        }
        mvc.perform(get("/api/v1/catalog/products/private-draft").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/catalog/products/private-approved").header("Authorization", auth))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing", Long.class)).isEqualTo(2);
        // The cup's blank and the box's two variants; the customers' own cups are not on the catalogue.
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(3);

        var source = jdbc.queryForList("select * from product.products order by id");
        var listings = jdbc.queryForList("select * from catalog.product_listing order by product_id");
        var entries = jdbc.queryForList("select * from catalog.catalog_entry order by sku");
        listener.rebuildPublished();
        assertThat(jdbc.queryForList("select * from catalog.product_listing order by product_id")).isEqualTo(listings);
        assertThat(jdbc.queryForList("select * from catalog.catalog_entry order by sku")).isEqualTo(entries);
        // An admin can hold a product writer lock without a healthy retry waiting on it.
        var dataSource = jdbc.getDataSource();
        try (var connection = dataSource.getConnection();
                var sql = connection.createStatement();
                var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            sql.executeQuery("select id from product.products where status='PUBLISHED' for update").close();
            try {
                pool.submit(listener::retry).get(5, TimeUnit.SECONDS);
            } finally {
                connection.rollback();
            }
        }
        assertThat(jdbc.queryForList("select * from catalog.product_listing order by product_id")).isEqualTo(listings);
        assertThat(jdbc.queryForList("select * from catalog.catalog_entry order by sku")).isEqualTo(entries);
        // Simulate lost projections before several instances start at once.
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from catalog.catalog_entry");
            jdbc.update("delete from catalog.product_listing");
        });
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var work = java.util.stream.IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
                start.await();
                listener.rebuildPublished();
                return true;
            })).toList();
            start.countDown();
            for (var result : work) assertThat(result.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForList("select * from product.products order by id")).isEqualTo(source);
        assertThat(jdbc.queryForList("select * from catalog.product_listing order by product_id")).isEqualTo(listings);
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(3);
        assertBox(auth, initialSeo);

        // Partial projection loss at the same revision must also be detected.
        tx.executeWithoutResult(s -> jdbc.update("delete from catalog.catalog_entry where sku=?", BOX_GREY));
        listener.retry();
        assertBox(auth, initialSeo);
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(3);

        tx.executeWithoutResult(s -> {
            jdbc.update("delete from catalog.catalog_entry where sku=?", BOX_GREY);
            jdbc.update("delete from catalog.product_listing where slug='hop-giay-demo'");
        });
        listener.retry();
        assertBox(auth, initialSeo);
        assertThat(jdbc.queryForList("select * from product.products order by id")).isEqualTo(source);

        UUID box = DemoData.PRODUCT_BOX;
        commerce.unpublish(box);
        listener.rebuildPublished();
        mvc.perform(get("/api/v1/catalog/products/hop-giay-demo").header("Authorization", auth))
                .andExpect(status().isNotFound());
        commerce.publish(box);
        var listing = commerce.get(box);
        commerce.edit(box, listing.revision(), "hop-giay-demo", "New source SEO", "New source description");
        listener.rebuildPublished();
        assertBox(auth, "New source SEO");
        assertThat(jdbc.queryForObject("select slug from product.products where id=?", String.class, box))
                .isEqualTo("hop-giay-demo");
    }

    /** The box's page: both variants, no price and no stock indicator on either (kltn-docs 13 BR-04, BR-11; 5-3). */
    private void assertBox(String auth, String seo) throws Exception {
        var result = mvc.perform(get("/api/v1/catalog/products/hop-giay-demo").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").value("hop-giay-demo"))
                .andExpect(jsonPath("$.data.variants[*].sku").value(containsInAnyOrder(BOX_GREY, BOX_BLUE)))
                .andExpect(jsonPath("$.data.variants[*].price").value(empty()))
                .andExpect(jsonPath("$.data.variants[*].availability").value(empty()));
        if (seo == null) result.andExpect(jsonPath("$.data.seoTitle").doesNotExist());
        else result.andExpect(jsonPath("$.data.seoTitle").value(seo));
    }
}
