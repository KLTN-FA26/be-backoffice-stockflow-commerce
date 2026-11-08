package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001300 on the real upgrade path (kltn-docs 09 §4.1 step 2, BR-21; team plan 3-1): every carrier there
 * before is MANUAL with no webhooks; the four parcel carriers are retired, their status archived, while a carrier the
 * warehouse manager declared keeps its own; and the table holds each kind of connection to what it needs.
 */
class CarrierConnectionMigrationUpgradeTest {

    private static final String BEFORE = "20261108001200";
    private static final String MIGRATION = "20261108001300";
    private static final String SEALED = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void upgradeMakesEveryCarrierManualAndRetiresTheParcelCarriers() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name, status) VALUES "
                        + "(gen_random_uuid(), 'TRUCKCO', 'Xe tải Minh Phát', 'ACTIVE'), "
                        + "(gen_random_uuid(), 'OLDCO', 'Xe cũ', 'INACTIVE')");
                RawSql.update(db, "UPDATE fulfillment.carrier SET status = 'INACTIVE' WHERE code = 'JTE'");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT code || ':' || status || ':' || connection_type || ':' "
                        + "|| webhook_enabled || ':' || failure_reason_map::text FROM fulfillment.carrier "
                        + "WHERE code IN ('GHN', 'GHTK', 'VTP', 'JTE', 'TRUCKCO', 'OLDCO') ORDER BY code"))
                        .containsExactly("GHN:INACTIVE:MANUAL:false:{}", "GHTK:INACTIVE:MANUAL:false:{}",
                                "JTE:INACTIVE:MANUAL:false:{}", "OLDCO:INACTIVE:MANUAL:false:{}",
                                "TRUCKCO:ACTIVE:MANUAL:false:{}", "VTP:INACTIVE:MANUAL:false:{}");
                // Only the three still active were changed, so only they are archived.
                assertThat(RawSql.texts(db, "SELECT row_data->>'code' || ':' || (row_data->>'status') FROM "
                        + "platform.legacy_archive WHERE source_table = 'fulfillment.carrier.status' ORDER BY 1"))
                        .containsExactly("GHN:ACTIVE", "GHTK:ACTIVE", "VTP:ACTIVE");

                String truck = "(SELECT id FROM fulfillment.carrier WHERE code = 'TRUCKCO')";
                RawSql.refused(db, "ck_carrier_connection_manual",
                        "UPDATE fulfillment.carrier SET webhook_enabled = TRUE WHERE code = 'TRUCKCO'");
                RawSql.refused(db, "ck_carrier_connection_webhook_load",
                        "UPDATE fulfillment.carrier SET connection_type = 'WEBHOOK_LOAD' WHERE code = 'TRUCKCO'");
                RawSql.refused(db, "ck_carrier_connection_api_booking", "UPDATE fulfillment.carrier SET "
                        + "connection_type = 'API_BOOKING', api_environment = 'STAGING', api_account = '84908000000', "
                        + "api_key_sealed = ? WHERE code = 'TRUCKCO'", SEALED);
                RawSql.refused(db, "ck_carrier_secrets_sealed", "UPDATE fulfillment.carrier SET "
                        + "connection_type = 'WEBHOOK_LOAD', webhook_secret_sealed = 'a clear secret' "
                        + "WHERE code = 'TRUCKCO'");
                RawSql.refused(db, "ck_carrier_failure_reason_map", "UPDATE fulfillment.carrier SET "
                        + "failure_reason_map = '{\"Khách vắng\": \"ABSENT\"}' WHERE code = 'TRUCKCO'");
                RawSql.refused(db, "ck_carrier_failure_reason_map", "UPDATE fulfillment.carrier SET "
                        + "failure_reason_map = '[\"OTHER\"]' WHERE code = 'TRUCKCO'");
                RawSql.refused(db, "ck_carrier_webhook_auth_header", "UPDATE fulfillment.carrier SET "
                        + "webhook_auth_header = 'api key' WHERE code = 'TRUCKCO'");
                assertThat(RawSql.update(db, "UPDATE fulfillment.carrier SET connection_type = 'API_BOOKING', "
                        + "webhook_enabled = TRUE, api_environment = 'STAGING', api_account = '84908000000', "
                        + "api_key_sealed = ?, webhook_auth_header = 'apikey', webhook_auth_value_sealed = ?, "
                        + "failure_reason_map = '{\"Khách vắng\": \"CUSTOMER_ABSENT\"}' WHERE code = 'TRUCKCO'",
                        SEALED, SEALED)).isEqualTo(1);

                String type = "(SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25')";
                RawSql.update(db, "INSERT INTO fulfillment.carrier_service_code (id, carrier_id, vehicle_type_id, "
                        + "province_code, carrier_city_code, service_code) VALUES (gen_random_uuid(), " + truck + ", "
                        + type + ", '79', 'SGN', 'SGN-TRUCK-1000')");
                RawSql.refused(db, "uk_carrier_service_code", "INSERT INTO fulfillment.carrier_service_code (id, "
                        + "carrier_id, vehicle_type_id, province_code, carrier_city_code, service_code) VALUES "
                        + "(gen_random_uuid(), " + truck + ", " + type + ", '79', 'SGN', 'SGN-TRUCK-OTHER')");
                RawSql.refused(db, "ck_carrier_service_code_service", "INSERT INTO fulfillment.carrier_service_code "
                        + "(id, carrier_id, vehicle_type_id, province_code, carrier_city_code, service_code) VALUES "
                        + "(gen_random_uuid(), " + truck + ", " + type + ", '01', 'HAN', 'HAN TRUCK')");
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
