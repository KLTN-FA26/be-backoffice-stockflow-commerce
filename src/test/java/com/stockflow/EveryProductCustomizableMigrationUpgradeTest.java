package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261116000200 on the real upgrade path (team plan 5-9): every product is CUSTOMIZABLE - the shop prints to the
 * customer's design, so a STANDARD product has no meaning. Products of the old kind are converted (their version moves,
 * so a screen holding the old one is told), products that already were are left alone, the kind defaults to
 * CUSTOMIZABLE and {@code ck_products_kind} refuses anything else. The two STANDARD demo products the earlier migrations
 * insert are converted by the same statement.
 */
class EveryProductCustomizableMigrationUpgradeTest {

    private static final String BEFORE = "20261116000100";
    private static final String MIGRATION = "20261116000200";
    private static final String STANDARD = "00000000-0000-4000-8000-0000000000b1";
    private static final String CUSTOMIZABLE = "00000000-0000-4000-8000-0000000000b2";

    @Test
    void standardProductsBecomeCustomizableAndNothingElseIsAllowed() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO product.products (id, code, name, name_en, slug, kind, version) VALUES "
                        + "('" + STANDARD + "', 'UPG-STD', 'Standard', 'Standard', 'upg-std', 'STANDARD', 4)");
                RawSql.update(db, "INSERT INTO product.products (id, code, name, name_en, slug, kind, version) VALUES "
                        + "('" + CUSTOMIZABLE + "', 'UPG-CUS', 'Custom', 'Custom', 'upg-cus', 'CUSTOMIZABLE', 4)");
                assertThat(RawSql.number(db, "SELECT count(*) FROM product.products WHERE kind = 'STANDARD'"))
                        .as("the two demo products and the seeded one").isGreaterThanOrEqualTo(3);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM product.products WHERE kind <> 'CUSTOMIZABLE'"))
                        .as("every product converted").isZero();
                assertThat(RawSql.number(db, "SELECT version FROM product.products WHERE id = '" + STANDARD + "'"))
                        .as("a converted product's version moves").isEqualTo(5);
                assertThat(RawSql.number(db, "SELECT version FROM product.products WHERE id = '" + CUSTOMIZABLE + "'"))
                        .as("one that already was is left alone").isEqualTo(4);
                assertThat(RawSql.number(db, "SELECT count(*) FROM product.products WHERE code IN ('SOFA-3S', "
                        + "'TABLE-OAK') AND kind = 'CUSTOMIZABLE' AND version = 1")).as("the demo products").isEqualTo(2);

                assertThat(RawSql.update(db, "INSERT INTO product.products (id, code, name, name_en, slug) VALUES "
                        + "(gen_random_uuid(), 'UPG-NEW', 'New', 'New', 'upg-new')")).isEqualTo(1);
                assertThat(RawSql.text(db, "SELECT kind FROM product.products WHERE code = 'UPG-NEW'"))
                        .as("the default").isEqualTo("CUSTOMIZABLE");
                RawSql.refused(db, "ck_products_kind", "UPDATE product.products SET kind = 'STANDARD' WHERE id = '"
                        + CUSTOMIZABLE + "'");
                RawSql.refused(db, "ck_products_kind", "INSERT INTO product.products (id, code, name, name_en, slug, "
                        + "kind) VALUES (gen_random_uuid(), 'UPG-BAD', 'Bad', 'Bad', 'upg-bad', 'STANDARD')");
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
