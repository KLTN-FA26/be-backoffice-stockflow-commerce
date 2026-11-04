package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261017000510 on a database with customers, variants and base/segment price rules already in it: the
 * existing prices are untouched, the new tables state the tier rules, and only the intended roles gain
 * catalog-customer-prices, with their versions advanced.
 */
class CustomerPriceMigrationUpgradeTest {

    @Test
    void upgradeAddsCustomerPricesWithoutTouchingExistingPricesAndGrantsOnlyTheIntendedRoles() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration", "classpath:db/demo").target("20261017000500").load().migrate();
            try (Connection sql = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID customer = UUID.randomUUID();
                update(sql, "insert into customer.customer(id,full_name,email,status,version,created_at)"
                        + " values (?,'Co',?,'ACTIVE',0,now())", customer, customer + "@example.test");
                long rules = number(sql, "select count(*) from catalog.pricing_rule");
                String base = text(sql, "select price::text from catalog.pricing_rule where name='BASE:SOFA-3S-GREY'");
                long admin = version(sql, "ECOMMERCE_ADMIN");
                long sales = version(sql, "SALES_STAFF");
                long coordinator = version(sql, "ORDER_COORDINATOR");
                long customerRole = version(sql, "CUSTOMER");
                long warehouse = version(sql, "WAREHOUSE_STAFF");

                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration", "classpath:db/demo").target("20261017000510").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).containsExactly("20261017000510");

                assertThat(number(sql, "select count(*) from catalog.pricing_rule")).isEqualTo(rules);
                assertThat(text(sql, "select price::text from catalog.pricing_rule where name='BASE:SOFA-3S-GREY'"))
                        .isEqualTo(base);
                assertThat(number(sql, "select count(*) from catalog.customer_price")).isZero();
                assertThat(version(sql, "ECOMMERCE_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(sql, "SALES_STAFF")).isEqualTo(sales + 1);
                assertThat(version(sql, "ORDER_COORDINATOR")).isEqualTo(coordinator + 1);
                assertThat(version(sql, "CUSTOMER")).isEqualTo(customerRole);
                assertThat(version(sql, "WAREHOUSE_STAFF")).isEqualTo(warehouse);
                assertThat(text(sql, """
                        select string_agg(r.code || ':' || p.action, ',' order by r.code, p.action)
                        from identity.role_permission rp join identity.app_role r on r.id = rp.role_id
                        join identity.permission p on p.id = rp.permission_id
                        where p.resource = 'catalog-customer-prices' and r.code <> 'SYSTEM_ADMIN'"""))
                        .isEqualTo("ECOMMERCE_ADMIN:CREATE,ECOMMERCE_ADMIN:DELETE,ECOMMERCE_ADMIN:READ,"
                                + "ECOMMERCE_ADMIN:UPDATE,ECOMMERCE_ADMIN:VIEW_PAGE,ORDER_COORDINATOR:READ,"
                                + "SALES_STAFF:READ,SALES_STAFF:VIEW_PAGE");
                assertThat(number(sql, """
                        select count(*) from identity.role_permission rp join identity.app_role r on r.id = rp.role_id
                        join identity.permission p on p.id = rp.permission_id
                        where p.resource = 'catalog-customer-prices' and r.code = 'SYSTEM_ADMIN'""")).isEqualTo(5);

                UUID price = UUID.randomUUID();
                String header = "insert into catalog.customer_price(id,customer_id,sku,created_at) values ";
                update(sql, header + "(?,?,'SOFA-3S-GREY',now())", price, customer);
                refused(sql, "a second price for the same customer and SKU",
                        header + "(gen_random_uuid(),?,'SOFA-3S-GREY',now())", customer);
                refused(sql, "a SKU that does not exist", header + "(gen_random_uuid(),?,'NO-SUCH',now())", customer);
                refused(sql, "a customer that does not exist",
                        header + "(gen_random_uuid(),gen_random_uuid(),'SOFA-3S-GREY',now())");
                refused(sql, "another currency", "insert into catalog.customer_price(id,customer_id,sku,currency,created_at)"
                        + " values (gen_random_uuid(),?,'TABLE-OAK-160','USD',now())", customer);

                String tier = "insert into catalog.customer_price_tier(id,customer_price_id,min_quantity,unit_price,created_at)"
                        + " values (gen_random_uuid(),?,?,?::numeric,now())";
                update(sql, tier, price, 1, "11000000");
                update(sql, tier, price, 10, "10000000");
                refused(sql, "two tiers from the same quantity", tier, price, 10, "9000000");
                refused(sql, "a tier from zero units", tier, price, 0, "9000000");
                refused(sql, "a zero price", tier, price, 20, "0");
                refused(sql, "a fraction of a dong", tier, price, 20, "100.50");

                update(sql, "delete from customer.customer where id=?", customer);
                assertThat(number(sql, "select count(*) from catalog.customer_price_tier")).isZero();
            }
        }
    }

    private static long version(Connection sql, String role) throws SQLException {
        return number(sql, "select version from identity.app_role where code=?", role);
    }
}
