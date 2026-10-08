package com.stockflow;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

/**
 * Exercises populated branch databases, including fail-fast rollback before canonical
 * reconciliation.
 */
class InventoryCatalogMigrationUpgradeTest {
    @Test
    void upgradesAfterSupplierReviewWithoutDuplicateOrOutOfOrderVersions() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .target("20260930000500")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate(
                        """
insert into notification.delivery_log
    (id,channel,recipient,status,operation_reference,delivery_generation,attempt_number,created_at)
values ('00000000-0000-0000-0000-000000000038','EMAIL','audit@example.test',
        'SENT','purchase-order:before-38',0,2,now())
""");
                var upgrade =
                        Flyway.configure()
                                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                                .load();
                assertThat(upgrade.migrate().migrations)
                        .extracting(m -> m.version)
                        .contains("20260930001000", "20260930001100", "20261008000100");
                upgrade.validate();
                try (var result =
                        sql.executeQuery(
                                """
                                select attempt_number from notification.delivery_log
                                where operation_reference='purchase-order:before-38'
                                """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    @Test
    void preservesMappedDataAndRefusesToOverwriteConflictingCanonicalSource() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .target("20260930001100")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate(
                        """
insert into product.products(id,code,name,slug) values
('00000000-0000-0000-0000-000000000070','UPGRADE','Upgrade','canonical-conflict');
insert into product.variants(id,product_id,sku,name,attribute_signature) values
('00000000-0000-0000-0000-000000000071','00000000-0000-0000-0000-000000000070','UPGRADE-SKU','Upgrade','');
insert into inventory.inventory_items(id,sku,lot_tracked,expiry_tracked,reorder_point,min_qty,max_qty)
values ('00000000-0000-0000-0000-000000000072','UPGRADE-SKU',true,true,10,3,50);
insert into inventory.sku_policy(sku,reorder_point,safety_stock,removal_strategy,tracking_mode,expiry_tracked,max_shelf_life_days)
values ('UPGRADE-SKU',10,5,'FEFO','LOT',true,30);
insert into catalog.product_listing(product_id,slug,seo_title,seo_description,revision,ever_published)
values ('00000000-0000-0000-0000-000000000070','legacy-slug','Preserved SEO','Preserved description',4,true);
""");
                var upgrade =
                        Flyway.configure()
                                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                                .load();
                assertThatThrownBy(upgrade::migrate)
                        .hasStackTraceContaining("reconcile conflicting slug/SEO");
                try (var row =
                        sql.executeQuery(
                                "select slug from product.products where code='UPGRADE'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("canonical-conflict");
                }
                try (var row =
                        sql.executeQuery(
                                "select safety_stock from inventory.sku_policy where"
                                        + " sku='UPGRADE-SKU'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(5);
                }
                // Explicit owner reconciliation, not automatic source selection by the migration.
                sql.executeUpdate(
                        "update product.products set slug='legacy-slug' where code='UPGRADE'");
                upgrade.migrate();
                upgrade.validate();
                try (var row =
                        sql.executeQuery(
                                "select"
                                    + " safety_stock,reorder_point,min_qty,max_qty,version,max_shelf_life_days"
                                    + " from inventory.inventory_items where sku='UPGRADE-SKU'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(5);
                    assertThat(row.getInt(2)).isEqualTo(10);
                    assertThat(row.getInt(3)).isEqualTo(3);
                    assertThat(row.getInt(4)).isEqualTo(50);
                    assertThat(row.getLong(5)).isEqualTo(1);
                    assertThat(row.getInt(6)).isEqualTo(30);
                }
                try (var row =
                        sql.executeQuery(
                                "select seo_title,ever_published from product.products where"
                                        + " code='UPGRADE'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("Preserved SEO");
                    assertThat(row.getBoolean(2)).isTrue();
                }
                try (var row =
                        sql.executeQuery("select to_regclass('inventory.sku_policy')::text")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isNull();
                }
                try (var row =
                        sql.executeQuery(
                                """
select count(*) from identity.permission p where not exists (
select 1 from identity.role_permission rp join identity.app_role r on r.id=rp.role_id
where rp.permission_id=p.id and r.code='SYSTEM_ADMIN')
""")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isZero();
                }
            }
        }
    }
}
