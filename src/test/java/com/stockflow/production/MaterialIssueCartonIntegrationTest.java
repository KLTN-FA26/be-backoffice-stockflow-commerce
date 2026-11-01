package com.stockflow.production;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-473: a blank hold drawn from a carton is issued to the print shop on a scan of that carton's label, never on
 * the SKU - the hold is deducted from that carton, so a SKU scan would let the clerk bring any carton of it. The
 * carton is counted in by SQL, as in {@code CartonAllocationIntegrationTest}, received long before any loose blank
 * so the sample's hold is drawn from it; the hold itself is the sample submission's real one.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class MaterialIssueCartonIntegrationTest {

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String SHELF = "HCM-A01-1-B";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);
    private static final int PER_CARTON = 4;

    @Autowired MaterialIssues issues;
    @Autowired ProductionWorkflow workflow;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private ProductionFixtures fixtures;
    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        sales = user(Role.SALES_STAFF, DataScope.ALL, Set.of());
        printer = user(Role.PRODUCTION_STAFF, DataScope.WAREHOUSE, Set.of(hcm));
        storekeeper = user(Role.WAREHOUSE_STAFF, DataScope.WAREHOUSE, Set.of(hcm));
    }

    private CurrentUser user(Role role, DataScope scope, Set<UUID> warehouses) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), scope,
                warehouses.isEmpty() ? Set.of() : Set.of("HCM"), warehouses);
    }

    /**
     * A carton of {@link #PER_CARTON} blanks counted in, {@code left} of them still in it on the shelf: sealed when
     * none was taken. Its unit is new to each test, so no earlier carton shares it.
     */
    private String carton(int left) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        String lpn = "LPN-IT-" + suffix;
        tx.executeWithoutResult(s -> {
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                    BLANK.code());
            UUID unit = Identifiers.newId();
            jdbc.update("INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " VALUES (?, ?, ?, ?)", unit, item, "CT" + suffix, PER_CARTON);
            UUID carton = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status, unopened) VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_STOCK', ?)""",
                    carton, lpn, item, unit, PER_CARTON, PER_CARTON, SHELF, left == PER_CARTON);
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                        version, created_at, stock_package_id)
                    VALUES (?, ?, ?, TIMESTAMPTZ '2000-01-01 00:00:00Z', ?, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), BLANK.code(), SHELF, left, carton);
        });
        return lpn;
    }

    /** A sample of {@code quantity} blanks, held at submission (decision Q1), through prepress: READY to issue. */
    private UUID sample(int quantity) throws Exception {
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(fixtures.confirmedDesign().snapshotId(), ProductionFixtures.BLANK, quantity,
                        null)));
        UUID productionOrderId = samples.submit(sales, draft.id(), hcm).productionOrder().id();
        workflow.prepress(printer, productionOrderId, true, null);
        return productionOrderId;
    }

    @Test
    @DisplayName("a hold that is a whole sealed carton is listed with its LPN and issued only on that LPN")
    void cartonHoldIsIssuedByItsLpnOnly() throws Exception {
        String lpn = carton(PER_CARTON);
        UUID lsx = sample(PER_CARTON);
        MaterialIssues.PendingHold hold = issues.pending(storekeeper, lsx).holds().getFirst();
        assertThat(hold.lpn()).isEqualTo(lpn);
        assertThat(hold.wholeCarton()).isTrue();
        assertThat(hold.quantity()).isEqualTo(PER_CARTON);

        List<String> wrongScans = new ArrayList<>(List.of(BLANK.code(), "LPN-IT-NOT-THIS-ONE"));
        if (hold.barcode() != null) {
            wrongScans.add(hold.barcode());   // what a loose hold of the SKU would take
        }
        for (String wrong : wrongScans) {
            assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
                    PRINT_SHOP, hold.reservationId(), hold.locationCode(), wrong)))
                    .as("scanned %s", wrong)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_ITEM_MISMATCH);
                        assertThat(e.getMessage()).isEqualTo("This hold is in carton " + lpn + "; scan its label");
                    });
        }
        assertThat(issues.pending(storekeeper, lsx).outstanding()).as("refused scans moved nothing")
                .isEqualTo(PER_CARTON);

        MaterialIssues.Result issued = issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
                PRINT_SHOP, hold.reservationId(), hold.locationCode(), "  " + lpn.toLowerCase(Locale.ROOT) + " "));
        assertThat(issued.issue().quantity()).isEqualTo(PER_CARTON);
        assertThat(issued.order().summary().status()).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
    }

    @Test
    @DisplayName("part of an opened carton also needs its LPN; an issue with no scan still moves it")
    void openedCartonHoldNeedsItsLpnUnlessNothingIsScanned() throws Exception {
        String lpn = carton(2);
        UUID lsx = sample(2);
        MaterialIssues.PendingHold hold = issues.pending(storekeeper, lsx).holds().getFirst();
        assertThat(hold.lpn()).isEqualTo(lpn);
        assertThat(hold.wholeCarton()).isFalse();
        assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
                PRINT_SHOP, hold.reservationId(), hold.locationCode(), BLANK.code())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_ITEM_MISMATCH));

        MaterialIssues.Result issued = issues.issue(storekeeper, lsx,
                new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, hold.reservationId()));
        assertThat(issued.issue().quantity()).isEqualTo(2);
    }
}
