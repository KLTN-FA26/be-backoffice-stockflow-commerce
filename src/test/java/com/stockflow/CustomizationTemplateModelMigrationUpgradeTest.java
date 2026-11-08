package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108000600 on the real upgrade path: a database migrated to the migration before it, holding a customization
 * template as the old seed wrote them - active, with no model, a free-text 2D URL, upload types written as file
 * extensions (one of them a PDF), a size limit over a design asset's, and a hand-placed WRAP print area - then upgraded.
 *
 * <p>Proves nothing is lost (the template's dropped and changed values and the print area are archived), the template
 * is deactivated with MIME types and a capped size, its areas are gone, and the new CHECKs refuse what the old rows
 * were: an active template without a model, a code the studio does not draw, an area without a shape.</p>
 */
class CustomizationTemplateModelMigrationUpgradeTest {

    private static final String BEFORE = "20261108000500";
    private static final String MIGRATION = "20261108000600";

    @Test
    void upgradeDeactivatesModellessTemplatesKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID product = UUID.randomUUID();
                UUID variant = UUID.randomUUID();
                UUID template = UUID.randomUUID();
                UUID area = UUID.randomUUID();

                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, version, "
                        + "created_at) VALUES (?, 'OLDCUP', 'Old cup', 'old-cup', 'DRAFT', 'CUSTOMIZABLE', 0, NOW())",
                        product);
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                        + "attribute_signature, position, version, created_at) VALUES (?, ?, 'OLDCUP-12', 'Old cup', "
                        + "'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", variant, product);
                RawSql.update(db, "INSERT INTO product.customization_templates (id, variant_id, code, name, "
                        + "template_2d_url, allowed_file_formats, max_file_size_mb, is_active, created_at) VALUES "
                        + "(?, ?, 'CUP12', 'Old template', 'https://cdn.example/cup12.ai', ARRAY['PNG', 'PDF', 'jpg'], "
                        + "80, TRUE, NOW())", template, variant);
                RawSql.update(db, "INSERT INTO product.print_areas (id, template_id, code, name, x_mm, y_mm, width_mm, "
                        + "height_mm, safe_margin_mm, bleed_mm, created_at) VALUES (?, ?, 'WRAP', 'Wrap', 0, 0, 280, "
                        + "120, 3, 2, NOW())", area, template);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Deactivated, MIME types only (the PDF dropped), the size capped; its hand-placed area gone.
                assertThat(RawSql.text(db, "SELECT is_active || ':' || array_to_string(allowed_file_formats, ',') || "
                        + "':' || max_file_size_mb FROM product.customization_templates WHERE id = ?", template))
                        .isEqualTo("false:image/jpeg,image/png:50");
                assertThat(RawSql.number(db, "SELECT count(*) FROM product.print_areas WHERE template_id = ?",
                        template)).isZero();

                // Archived before it went or changed.
                assertThat(RawSql.text(db, "SELECT row_data ->> 'template_2d_url' FROM platform.legacy_archive WHERE "
                        + "source_table = 'product.customization_templates.before_model' AND row_id = ?", template))
                        .isEqualTo("https://cdn.example/cup12.ai");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'is_active') || ':' || (row_data -> "
                        + "'allowed_file_formats' ->> 1) FROM platform.legacy_archive WHERE source_table = "
                        + "'product.customization_templates.before_model' AND row_id = ?", template))
                        .isEqualTo("true:PDF");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'code' FROM platform.legacy_archive WHERE "
                        + "source_table = 'product.print_areas' AND row_id = ?", area)).isEqualTo("WRAP");

                // The free-text URLs are gone; the defaults are the new ones.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'product' AND table_name = 'customization_templates' AND column_name IN "
                        + "('template_2d_url', 'model_3d_url')")).isZero();
                assertThat(RawSql.text(db, "SELECT column_default FROM information_schema.columns WHERE "
                        + "table_schema = 'product' AND table_name = 'customization_templates' AND column_name = "
                        + "'min_dpi'")).isEqualTo("300");

                // The CHECKs refuse what the old rows were.
                RawSql.refused(db, "ck_customization_templates_active_model",
                        "UPDATE product.customization_templates SET is_active = TRUE WHERE id = ?", template);
                RawSql.refused(db, "ck_customization_templates_mime",
                        "UPDATE product.customization_templates SET allowed_file_formats = ARRAY['PNG'] WHERE id = ?",
                        template);
                RawSql.refused(db, "ck_print_areas_code", "INSERT INTO product.print_areas (id, template_id, code, "
                        + "name, x_mm, y_mm, width_mm, height_mm, shape_json, uv_mapping_json) VALUES "
                        + "(gen_random_uuid(), ?, 'WRAP', 'Wrap', 0, 0, 280, 120, '{\"type\": \"rect\"}', "
                        + "'{\"meshPart\": \"body\"}')", template);
                RawSql.refused(db, "shape_json", "INSERT INTO product.print_areas (id, template_id, code, name, x_mm, "
                        + "y_mm, width_mm, height_mm, uv_mapping_json) VALUES (gen_random_uuid(), ?, 'BODY', 'Body', 0, "
                        + "0, 280, 120, '{\"meshPart\": \"body\"}')", template);
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
