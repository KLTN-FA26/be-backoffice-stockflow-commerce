package com.stockflow.inventory;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/inventory/alerts} through the real filter chain, permission aspect and warehouse scope: who sees which
 * alerts, who may acknowledge, the refusals with their exact error codes, and that a customer reaches nothing.
 */
@SecuredIntegrationTest
class StockAlertApiIntegrationTest {

    private static final String BASE = "/api/v1/inventory/alerts";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired StockAlertRepository alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private AlertFixtures fixtures;
    private UUID hcm;
    private AlertFixtures.Warehouse other;
    private String planner;
    private String managerHcm;
    private String managerOther;
    private String staffHcm;
    private String customer;
    private UUID managerHcmId;
    private String reorderSku;
    private String expiringSku;
    private String expiredSku;
    private StockAlert reorder;
    private StockAlert expiring;
    private StockAlert expiredElsewhere;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        hcm = fixtures.hcm();
        other = fixtures.otherWarehouse();
        planner = TestTokens.bearer(decoder, fixtures.staff("INVENTORY_PLANNER"), "INVENTORY_PLANNER");
        managerHcmId = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        managerHcm = TestTokens.bearer(decoder, managerHcmId, "WAREHOUSE_MANAGER");
        managerOther = TestTokens.bearer(decoder, fixtures.staff("WAREHOUSE_MANAGER", other.id()), "WAREHOUSE_MANAGER");
        staffHcm = TestTokens.bearer(decoder, fixtures.staff("WAREHOUSE_STAFF", hcm), "WAREHOUSE_STAFF");
        customer = TestTokens.bearer(decoder, fixtures.staff("CUSTOMER"), "CUSTOMER");

        reorderSku = fixtures.sku(10, null);
        expiringSku = fixtures.sku(null, null);
        expiredSku = fixtures.sku(null, null);
        reorder = open(Key.stockLevel(Kind.REORDER, reorderSku), 3, 10, false);
        // The API counts daysToExpiry from today in Vietnam, not in the JVM's zone: on a UTC runner the two dates
        // differ from 00:00 to 07:00 Vietnam time, and LocalDate.now() made this test fail in that window.
        LocalDate today = BusinessCalendar.date(Instant.now());
        expiring = open(Key.expiry(Kind.EXPIRING, expiringSku, hcm, "LOT-1", today.plusDays(10)), 7, null, false);
        expiredElsewhere = open(Key.expiry(Kind.EXPIRED, expiredSku, other.id(), "LOT-2", today.minusDays(2)), 4, null, true);
    }

    private StockAlert open(Key key, int qty, Integer threshold, boolean impaired) {
        StockAlert alert = StockAlert.open(Identifiers.newId(), key, qty, threshold, impaired, Instant.now());
        tx.executeWithoutResult(s -> alerts.insertIfAbsent(alert));
        return alert;
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(get(BASE + path).header("Authorization", token));
    }

    private ResultActions ack(String token, UUID id, String body) throws Exception {
        return mvc.perform(post(BASE + "/" + id + "/acknowledgement").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------------ reading and scope

    @Test
    void aPlannerSeesAlertsOfEveryWarehouseAndTheSkuWideOnes() throws Exception {
        getAs(planner, "?sku=" + reorderSku).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].kind").value("REORDER"))
                .andExpect(jsonPath("$.data.items[0].warehouseId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].observedQty").value(3))
                .andExpect(jsonPath("$.data.items[0].thresholdQty").value(10))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].version").value(0));
        getAs(planner, "?sku=" + expiredSku).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].kind").value("EXPIRED"))
                .andExpect(jsonPath("$.data.items[0].warehouseCode").value(other.prefix()))
                .andExpect(jsonPath("$.data.items[0].lotNumber").value("LOT-2"))
                .andExpect(jsonPath("$.data.items[0].daysToExpiry").value(-2))
                .andExpect(jsonPath("$.data.items[0].reservationImpaired").value(true));
        getAs(planner, "?sku=" + expiringSku).andExpect(jsonPath("$.data.items[0].warehouseCode").value("HCM"))
                .andExpect(jsonPath("$.data.items[0].daysToExpiry").value(10));
    }

    @Test
    void filtersByKindStatusAndWarehouse() throws Exception {
        getAs(planner, "?sku=" + expiringSku + "&kind=EXPIRING&kind=EXPIRED&status=OPEN&warehouseId=" + hcm)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        getAs(planner, "?sku=" + expiringSku + "&kind=REORDER").andExpect(jsonPath("$.data.items.length()").value(0));
        getAs(planner, "?sku=" + expiringSku + "&status=RESOLVED").andExpect(jsonPath("$.data.items.length()").value(0));
        getAs(planner, "?sku=" + expiringSku + "&kind=EXPIRING,EXPIRED").andExpect(jsonPath("$.data.items.length()").value(1));
        getAs(planner, "?sku=" + expiringSku + "&warehouseId=" + other.id()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void aManagerSeesTheExpiryAlertsOfTheirWarehouseOnlyAndNoSkuWideOnes() throws Exception {
        getAs(managerHcm, "?sku=" + expiringSku).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        getAs(managerHcm, "?sku=" + reorderSku).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        getAs(managerHcm, "?sku=" + expiredSku).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        getAs(managerOther, "?sku=" + expiredSku).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        getAs(managerOther, "?sku=" + expiringSku).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void askingForAnotherWarehouseByIdIsOutOfScope() throws Exception {
        getAs(managerHcm, "?warehouseId=" + other.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        getAs(managerHcm, "?warehouseId=" + hcm).andExpect(status().isOk());
    }

    @Test
    void oneAlertIsReadableInScopeAndOutOfScopeOtherwise() throws Exception {
        getAs(managerHcm, "/" + expiring.id()).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(expiring.id().toString()));
        getAs(managerHcm, "/" + expiredElsewhere.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        getAs(managerHcm, "/" + reorder.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        getAs(planner, "/" + reorder.id()).andExpect(status().isOk());
        getAs(planner, "/" + UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ALERT_NOT_FOUND"));
    }

    @Test
    void theSummaryCountsOpenAlertsPerKindInsideTheScope() throws Exception {
        getAs(planner, "/summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.openByKind.REORDER").isNumber())
                .andExpect(jsonPath("$.data.openByKind.SAFETY").isNumber())
                .andExpect(jsonPath("$.data.openByKind.EXPIRING").isNumber())
                .andExpect(jsonPath("$.data.openByKind.EXPIRED").isNumber());
        // A warehouse-limited manager has no SKU-wide alerts at all.
        getAs(managerHcm, "/summary").andExpect(status().isOk()).andExpect(jsonPath("$.data.openByKind.REORDER").value(0))
                .andExpect(jsonPath("$.data.openByKind.SAFETY").value(0));
    }

    @Test
    void theSkusWithoutAThresholdAreForTheSystemWideRolesOnly() throws Exception {
        String noThreshold = fixtures.sku(null, null);
        fixtures.stock(noThreshold, AlertFixtures.HCM_BIN, 12, "AVAILABLE", null, null);
        getAs(planner, "/unconfigured?size=200").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.sku=='" + noThreshold + "')].usableOnHand").value(12));
        getAs(managerHcm, "/unconfigured").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    // ------------------------------------------------------------------ permission

    @Test
    void aCustomerReachesNothingAndAnAnonymousCallerIsRefused() throws Exception {
        getAs(customer, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        getAs(customer, "/" + reorder.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        getAs(customer, "/summary").andExpect(status().isForbidden());
        getAs(customer, "/unconfigured").andExpect(status().isForbidden());
        ack(customer, reorder.id(), "{\"version\":0}").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void warehouseStaffMayReadButNotAcknowledge() throws Exception {
        getAs(staffHcm, "?sku=" + expiringSku).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        ack(staffHcm, expiring.id(), "{\"version\":0}").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void aRoleWithoutTheResourceIsRefused() throws Exception {
        String buyer = TestTokens.bearer(decoder, fixtures.staff("PROCUREMENT_STAFF"), "PROCUREMENT_STAFF");
        getAs(buyer, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ acknowledging

    @Test
    void aManagerAcknowledgesAnAlertOnceAndTheConditionStaysOpen() throws Exception {
        ack(managerHcm, expiring.id(), "{\"version\":0,\"note\":\"will sell first\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedBy").value(managerHcmId.toString()))
                .andExpect(jsonPath("$.data.acknowledgedByName").isNotEmpty())
                .andExpect(jsonPath("$.data.ackNote").value("will sell first"))
                .andExpect(jsonPath("$.data.version").value(1));
        // Acknowledged is still an open episode: the dedupe index still holds the condition.
        ack(managerHcm, expiring.id(), "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ALERT_TRANSITION"));
        getAs(planner, "?sku=" + expiringSku + "&status=ACKNOWLEDGED").andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void aStaleVersionIsAnOptimisticLockConflict() throws Exception {
        ack(planner, reorder.id(), "{\"version\":7}").andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        ack(planner, reorder.id(), "{\"version\":0}").andExpect(status().isOk());
    }

    @Test
    void aResolvedAlertCannotBeAcknowledged() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_alert SET status = 'RESOLVED', resolved_at = NOW(),"
                + " resolution = 'RECOVERED', version = version + 1 WHERE id = ?", reorder.id()));
        ack(planner, reorder.id(), "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ALERT_TRANSITION"));
    }

    @Test
    void anAlertOutsideTheCallersWarehouseCannotBeAcknowledgedAndIsNotChanged() throws Exception {
        ack(managerHcm, expiredElsewhere.id(), "{\"version\":0}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        ack(managerHcm, reorder.id(), "{\"version\":0}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        ack(planner, UUID.randomUUID(), "{\"version\":0}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ALERT_NOT_FOUND"));
        getAs(planner, "/" + expiredElsewhere.id()).andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    // ------------------------------------------------------------------ validation

    @Test
    void anOverlongNoteAndAMissingVersionAreValidationErrors() throws Exception {
        ack(planner, reorder.id(), "{\"version\":0,\"note\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        ack(planner, reorder.id(), "{\"note\":\"hi\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
        ack(planner, reorder.id(), "{\"version\":0,\"note\":\"" + "x".repeat(500) + "\"}").andExpect(status().isOk());
    }

    @Test
    void paging_and_sorting_arguments_are_checked() throws Exception {
        getAs(planner, "?size=201").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        getAs(planner, "?page=-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        getAs(planner, "?sort=password,desc").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        getAs(planner, "?sort=sku,desc&kind=BOGUS").andExpect(status().isBadRequest());
        getAs(planner, "?sort=sku,desc&size=5").andExpect(status().isOk());
        getAs(planner, "?sort=kind&sort=openedAt").andExpect(status().isOk());
    }
}
