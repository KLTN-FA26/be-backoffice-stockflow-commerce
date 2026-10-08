package com.stockflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.jwk.RSAKey;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogProjectionListener;
import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.RedisContainer;

import org.flywaydb.core.Flyway;
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

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real populated pre-#38 upgrade, application startup, RSA tokens, filter chain and PostgreSQL. */
@IntegrationTest
@Import({CatalogProjectionStartupIntegrationTest.UpgradeDatabase.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
@DirtiesContext
class CatalogProjectionStartupIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class UpgradeDatabase {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() throws Exception {
            var pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.start();
            try {
                Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration", "classpath:db/demo")
                        .target("20260930000500").load().migrate();
                try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                        var sql = connection.createStatement()) {
                    // Explicit test selling prices. The production demo still has no agreed prices.
                    sql.executeUpdate("""
                            insert into catalog.pricing_rule(id,name,sku,price,currency,active,created_at) values
                            (md5('test:sofa-price')::uuid,'BASE:SOFA-3S-GREY','SOFA-3S-GREY',12500000,'VND',true,now()),
                            (md5('test:table-price')::uuid,'BASE:TABLE-OAK-160','TABLE-OAK-160',8000000,'VND',true,now());
                            update product.products set seo_title='Upgrade SEO',seo_description='Upgrade description'
                            where code='SOFA-3S';
                            insert into product.products(id,code,name,slug,status) values
                            (md5('test:private-draft')::uuid,'PRIVATE-DRAFT','Private draft','private-draft','DRAFT');
                            insert into product.products(id,code,name,slug,status,submitted_by,submitted_at,approved_by,approved_at)
                            values (md5('test:private-approved')::uuid,'PRIVATE-APPROVED','Unpublished','private-approved','APPROVED',
                                md5('demo:user:editor')::uuid,now(),md5('demo:user:approver')::uuid,now());
                            """);
                }
                return pg;
            } catch (Exception e) {
                pg.stop();
                throw e;
            }
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
        var now = Instant.now();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject(jdbc.queryForObject("select id::text from identity.app_user where id=md5('demo:user:approver')::uuid", String.class))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of("SYSTEM_ADMIN"))
                .build())).getTokenValue();
    }

    @Test
    void upgradeAndStartupRebuildWithoutRepublishAndRemainSafeOnRestart() throws Exception {
        // No manual project/publish call before these assertions: ApplicationReadyEvent did the backfill.
        String auth = authorization();
        mvc.perform(get("/api/v1/catalog/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/products").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        assertSofa(auth, "Upgrade SEO", 12500000);
        mvc.perform(get("/api/v1/catalog/products/private-draft").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/catalog/products/private-approved").header("Authorization", auth))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(2);

        var source = jdbc.queryForList("select * from product.products order by id");
        var prices = jdbc.queryForList("select * from catalog.pricing_rule order by id");
        var listings = jdbc.queryForList("select * from catalog.product_listing order by product_id");
        var entries = jdbc.queryForList("select * from catalog.catalog_entry order by sku");
        listener.rebuildPublished();
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
        assertThat(jdbc.queryForList("select * from catalog.pricing_rule order by id")).isEqualTo(prices);
        assertThat(jdbc.queryForList("select * from catalog.product_listing order by product_id")).isEqualTo(listings);
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(2);
        assertSofa(auth, "Upgrade SEO", 12500000);

        // Partial projection loss at the same revision must also be detected.
        tx.executeWithoutResult(s -> jdbc.update("delete from catalog.catalog_entry where sku='SOFA-3S-GREY'"));
        listener.rebuildPublished();
        assertSofa(auth, "Upgrade SEO", 12500000);
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry", Long.class)).isEqualTo(2);

        UUID sofa = jdbc.queryForObject("select id from product.products where code='SOFA-3S'", UUID.class);
        commerce.unpublish(sofa);
        listener.rebuildPublished();
        mvc.perform(get("/api/v1/catalog/products/sofa-3-cho").header("Authorization", auth))
                .andExpect(status().isNotFound());
        commerce.publish(sofa);
        var listing = commerce.get(sofa);
        commerce.edit(sofa, listing.revision(), "sofa-3-cho", "New source SEO", "New source description");
        commerce.price(sofa, "SOFA-3S-GREY", commerce.get(sofa).revision(),
                new SellingPrice(new BigDecimal("13000000"), "VND"), "test");
        listener.rebuildPublished();
        assertSofa(auth, "New source SEO", 13000000);
        assertThat(jdbc.queryForObject("select slug from product.products where id=?", String.class, sofa))
                .isEqualTo("sofa-3-cho");
    }

    private void assertSofa(String auth, String seo, int price) throws Exception {
        mvc.perform(get("/api/v1/catalog/products/sofa-3-cho").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").value("sofa-3-cho"))
                .andExpect(jsonPath("$.data.seoTitle").value(seo))
                .andExpect(jsonPath("$.data.variants[0].sku").value("SOFA-3S-GREY"))
                .andExpect(jsonPath("$.data.variants[0].price").value(price));
    }
}
