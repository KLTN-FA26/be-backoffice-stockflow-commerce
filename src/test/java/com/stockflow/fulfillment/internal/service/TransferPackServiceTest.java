package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageScanJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackingSessionStore;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Packing a transfer order at the source (kltn-docs 10 §4 step 5, 08 BR-03; LP-9, LP2-8): the session over the
 * transfer's lines, the stock held from the moment packing starts, an unopened carton shipped as it is, and a packer
 * who did not start the session refused unless a manager. The parcel rules themselves are {@code PackingSessionTest}'s.
 */
class TransferPackServiceTest {

    private static final UUID TO = UUID.fromString("00000000-0000-7000-8000-0000000000d4");

    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final PackageScanJpaRepository scans = mock(PackageScanJpaRepository.class);
    private final PackingSessionStore store = mock(PackingSessionStore.class);
    private final TransferFulfillment transfers = mock(TransferFulfillment.class);
    private final InventoryControlService itemControls = mock(InventoryControlService.class);
    private final PackagingSpecResolver packaging = mock(PackagingSpecResolver.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final TransferPackService service = new TransferPackService(packs, packages, scans, store, transfers,
            itemControls, packaging, Clock.fixed(NOW, ZoneOffset.UTC), 6000, identities, warehouses);

    private final UUID packer = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser(packer, "packer", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(PermissionCode.of(FulfillmentPermissions.PACKING, com.stockflow.common.security.Action.APPROVE)),
            DataScope.ALL, Set.of(), Set.of());

    private final UUID transferId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private PackJpaEntity pack;
    private PackingSession session;
    private TransferFulfillment.TransferView transfer;

    private TransferFulfillment.TransferView transferIn(String status, int requested, String lot) {
        return new TransferFulfillment.TransferView(transferId, "TO-0001", WAREHOUSE, TO, status,
                TransferDeliveryMode.FULL_TRUCKLOAD, null, null, null,
                List.of(new TransferFulfillment.Line(lineId, 1, "SKU-A", lot, requested, 0)));
    }

    private PackingSession sessionOf(PackStatus status, UUID startedBy, int wanted) {
        var line = new PackingSession.Line(lineId, "SKU-A", "8930000000017", wanted, wanted, false, null,
                Map.of(), Set.of(), true, false, 1);
        return new PackingSession(pack.getId(), "TO-0001", status, startedBy == null ? null : NOW, startedBy, null,
                null, List.of(line), List.of(), List.of());
    }

    @BeforeEach
    void aTransferBeingPacked() {
        pack = PackJpaEntity.forTransfer(UUID.randomUUID(), transferId);
        transfer = transferIn("PICKING", 10, null);
        session = sessionOf(PackStatus.PACKING, packer, 10);
        when(transfers.find(transferId)).thenReturn(Optional.of(transfer));
        when(packs.findById(pack.getId())).thenReturn(Optional.of(pack));
        when(packs.lockById(any(UUID.class))).thenAnswer(call -> Optional.of(pack));
        when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of(pack));
        when(store.load(any(), anyString(), anyList())).thenAnswer(call -> session);
        when(itemControls.logistics(any())).thenAnswer(call -> new InventoryItemLogistics("SKU-A", 0, new ItemLogistics(
                "PCS", "8930000000017", null, null, null, null, null, null, null, null, 1, 1, StorageClass.NORMAL, false,
                null, false, true)));
    }

    @Nested
    @DisplayName("opening the session (10 step 4-5)")
    class Opening {

        @Test
        @DisplayName("a transfer that does not exist is TRANSFER_ORDER_NOT_FOUND")
        void unknownTransfer() {
            UUID unknown = UUID.randomUUID();
            when(transfers.find(unknown)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.open(unknown, actor)).errorCode())
                    .isEqualTo(ErrorCode.TRANSFER_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("a transfer whose source is another warehouse than the caller's is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.open(transferId, actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("the first open starts the transfer's picking, creates the session and holds every requested unit")
        void firstOpenStartsPickingAndHoldsTheStock() {
            when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of());
            when(transfers.beginPacking(transferId)).thenReturn(transfer);
            when(packs.saveAndFlush(any())).thenAnswer(call -> {
                PackJpaEntity created = call.getArgument(0);
                pack = created;
                session = sessionOf(PackStatus.PENDING, null, 10);
                return created;
            });

            TransferPacks.Session view = service.open(transferId, actor);

            verify(transfers).beginPacking(transferId);
            verify(transfers).holdForPacking(eq(transferId), eq(Map.of(lineId, 10)), eq(Map.of()), any());
            assertThat(view.packStatus()).isEqualTo("PACKING");
            assertThat(view.packerId()).isEqualTo(packer);
            verify(store).save(session, pack);
        }

        @Test
        @DisplayName("opening again answers with the session as it is, starts nothing and holds nothing twice")
        void reopeningIsIdempotent() {
            TransferPacks.Session view = service.open(transferId, actor);

            assertThat(view.packId()).isEqualTo(pack.getId());
            verify(transfers, never()).beginPacking(any());
            verify(transfers, never()).holdForPacking(any(), any(), any(), any());
            verify(store, never()).save(any(), any());
        }

        @Test
        @DisplayName("a packer other than the one who started it is PACKING_TAKEN; a manager may take over")
        void anotherPackerIsRefused() {
            session = sessionOf(PackStatus.PACKING, UUID.randomUUID(), 10);

            assertThat(refusal(() -> service.open(transferId, actor)).errorCode()).isEqualTo(ErrorCode.PACKING_TAKEN);
            assertThat(service.open(transferId, manager).packId()).isEqualTo(pack.getId());
        }

        @Test
        @DisplayName("each line shows what is requested, packed and remaining, and the boxes one unit ships as")
        void linesShowProgress() {
            TransferPacks.Session view = service.open(transferId, actor);

            assertThat(view.lines()).singleElement().satisfies(l -> {
                assertThat(l.requested()).isEqualTo(10);
                assertThat(l.packed()).isZero();
                assertThat(l.remaining()).isEqualTo(10);
                assertThat(l.sku()).isEqualTo("SKU-A");
                assertThat(l.barcode()).isEqualTo("8930000000017");
                assertThat(l.kitSuggested()).isFalse();
            });
            assertThat(view.deliveryMode()).isEqualTo("FULL_TRUCKLOAD");
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a session id that is not a transfer's is NOT_FOUND")
        void getRefusesAnOrderPack() {
            PackJpaEntity ordersPack = new PackJpaEntity(UUID.randomUUID(), UUID.randomUUID(), PackStatus.PENDING, null);
            when(packs.findById(ordersPack.getId())).thenReturn(Optional.of(ordersPack));

            assertThat(refusal(() -> service.get(ordersPack.getId())).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(refusal(() -> service.get(UUID.randomUUID())).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("the current session of a transfer is its live one, else the latest cancelled one")
        void currentPrefersTheLiveSession() {
            PackJpaEntity cancelled = PackJpaEntity.forTransfer(UUID.randomUUID(), transferId);
            cancelled.cancel();
            when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of(cancelled, pack));

            assertThat(service.current(transferId).packId()).isEqualTo(pack.getId());

            when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of(cancelled));
            assertThat(service.current(transferId).packId()).isEqualTo(cancelled.getId());
        }

        @Test
        @DisplayName("a transfer never packed is NOT_FOUND")
        void currentOfAnUnpackedTransfer() {
            when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of());

            assertThat(refusal(() -> service.current(transferId)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a label starts with the transfer's number; a session after a cancelled one carries -R<n>")
        void labelBaseAfterACancelledSession() {
            PackJpaEntity first = PackJpaEntity.forTransfer(UUID.fromString("00000000-0000-7000-8000-000000000001"),
                    transferId);
            PackJpaEntity second = PackJpaEntity.forTransfer(UUID.fromString("00000000-0000-7000-8000-000000000002"),
                    transferId);
            when(packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId)).thenReturn(List.of(second, first));
            when(packs.findById(second.getId())).thenReturn(Optional.of(second));

            service.get(second.getId());
            service.get(second.getId());

            ArgumentCaptor<String> base = ArgumentCaptor.forClass(String.class);
            verify(store, times(2)).load(any(), base.capture(), anyList());
            assertThat(base.getAllValues()).containsOnly("TO-0001-R2");
        }
    }

    @Nested
    @DisplayName("every write locks the session first and needs a transfer still being picked")
    class Working {

        @Test
        @DisplayName("a session cancelled with the transfer's picking is PACK_STATE_CONFLICT")
        void cancelledSession() {
            pack.cancel();

            assertThat(refusal(() -> service.openPackage(pack.getId(), null, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a transfer past picking can no longer change its parcels")
        void transferNotPicking() {
            when(transfers.find(transferId)).thenReturn(Optional.of(transferIn("PACKED", 10, null)));

            assertThat(refusal(() -> service.openPackage(pack.getId(), null, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            assertThat(refusal(() -> service.complete(pack.getId(), false, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a pack that is not a transfer's, or unknown, is NOT_FOUND")
        void notATransferPack() {
            UUID unknown = UUID.randomUUID();
            when(packs.lockById(unknown)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.openPackage(unknown, null, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("an unknown package type is VALIDATION_FAILED on 'packageType'; a blank one means CARTON")
        void packageTypes() {
            var thrown = refusal(() -> service.openPackage(pack.getId(), "TRUNK", null, actor));
            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("packageType");

            assertThat(service.openPackage(pack.getId(), " ", null, actor).packageType()).isEqualTo("CARTON");
            assertThat(service.openPackage(pack.getId(), "pallet", null, actor).packageType()).isEqualTo("PALLET");
        }

        @Test
        @DisplayName("another packer's session is PACKING_TAKEN for every write")
        void onlyTheStarterWorks() {
            session = sessionOf(PackStatus.PACKING, UUID.randomUUID(), 10);

            assertThat(refusal(() -> service.openPackage(pack.getId(), null, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
            assertThat(refusal(() -> service.complete(pack.getId(), false, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_TAKEN);
        }

        @Test
        @DisplayName("a session not started yet is PACK_STATE_CONFLICT: start packing first")
        void notStarted() {
            session = sessionOf(PackStatus.PENDING, null, 10);

            assertThat(refusal(() -> service.openPackage(pack.getId(), null, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("scan, seal and void go through the packing session and are saved")
        void scanSealAndVoid() {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
            UUID scanId = UUID.randomUUID();

            TransferPacks.Session afterScan = service.scan(pack.getId(), parcelId,
                    new TransferPacks.Scan(scanId, "SKU-A", null, 4, lineId, null), actor);
            assertThat(afterScan.lines().getFirst().packed()).isEqualTo(4);

            TransferPacks.Parcel sealed = service.seal(pack.getId(), parcelId, new TransferPacks.Seal(
                    new BigDecimal("12.5"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("25"), null, null,
                    null, null), actor);
            assertThat(sealed.status()).isEqualTo("SEALED");
            assertThat(sealed.dimWeightKg()).isEqualByComparingTo("5.000");

            TransferPacks.Session afterVoid = service.voidPackage(pack.getId(), parcelId, actor);
            assertThat(afterVoid.lines().getFirst().packed()).isZero();
            // The units go back to being held from the line's stock (LP2-8).
            verify(transfers).holdForPacking(eq(transferId), eq(Map.of(lineId, 10)), any(), any());
            verify(store, times(4)).save(session, pack);
        }

        @Test
        @DisplayName("a scan id already recorded for another package is PACK_SCAN_ID_CONFLICT; for this one, a replay")
        void scanReplays() {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
            UUID scanId = UUID.randomUUID();
            PackageScanJpaEntity recorded = mock(PackageScanJpaEntity.class);
            when(recorded.getPackageId()).thenReturn(parcelId);
            when(scans.findById(scanId)).thenReturn(Optional.of(recorded));
            PackageJpaEntity row = new PackageJpaEntity(parcelId, pack.getId(), 1, "PKG-1");
            when(packages.findById(parcelId)).thenReturn(Optional.of(row));

            TransferPacks.Session replay = service.scan(pack.getId(), parcelId,
                    new TransferPacks.Scan(scanId, "SKU-A", null, 4, lineId, null), actor);

            assertThat(replay.lines().getFirst().packed()).as("a replay scans nothing again").isZero();

            UUID other = UUID.randomUUID();
            assertThat(refusal(() -> service.scan(pack.getId(), other,
                    new TransferPacks.Scan(scanId, "SKU-A", null, 4, lineId, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_SCAN_ID_CONFLICT);
        }

        @Test
        @DisplayName("a kit of an item that ships as several boxes opens one part per box")
        void kitOpensOnePartPerBox() {
            session = new PackingSession(pack.getId(), "TO-0001", PackStatus.PACKING, NOW, packer, null, null,
                    List.of(new PackingSession.Line(lineId, "SKU-A", null, 4, 4, false, null, Map.of(), Set.of(), true,
                            false, 3)), List.of(), List.of());

            TransferPacks.Kit kit = service.openKit(pack.getId(), lineId, null, null, actor);

            assertThat(kit.packages()).hasSize(3);
            assertThat(kit.sku()).isEqualTo("SKU-A");
            assertThat(kit.packages()).extracting(TransferPacks.Parcel::packageNo).containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("a parcel's label")
    class Labels {

        @Test
        @DisplayName("an open parcel has no label yet: seal it first")
        void openParcelHasNoLabel() {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();

            assertThat(refusal(() -> service.label(pack.getId(), parcelId, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a voided parcel's label is void (08 BR-05)")
        void voidedParcelHasNoLabel() {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
            service.voidPackage(pack.getId(), parcelId, actor);

            var thrown = refusal(() -> service.label(pack.getId(), parcelId, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PACK_STATE_CONFLICT);
            assertThat(thrown.getMessage()).contains("voided");
        }

        @Test
        @DisplayName("a parcel the session does not have is PACKAGE_NOT_FOUND")
        void unknownParcel() {
            assertThat(refusal(() -> service.label(pack.getId(), UUID.randomUUID(), actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("a sealed parcel's label is n of N among the sealed ones, with both warehouses and its measures")
        void sealedParcelLabel() {
            UUID first = service.openPackage(pack.getId(), null, null, actor).packageId();
            UUID second = service.openPackage(pack.getId(), null, null, actor).packageId();
            for (UUID id : List.of(first, second)) {
                service.scan(pack.getId(), id, new TransferPacks.Scan(UUID.randomUUID(), "SKU-A", null, 3, lineId, null),
                        actor);
                service.seal(pack.getId(), id, new TransferPacks.Seal(new BigDecimal("8"), new BigDecimal("40"),
                        new BigDecimal("30"), new BigDecimal("25"), null, null, null, null), actor);
            }
            when(warehouses.findWarehouse(WAREHOUSE)).thenReturn(Optional.of(
                    new WarehouseView(WAREHOUSE, "HCM", "HCM warehouse", "1 Le Loi", true)));
            when(warehouses.findWarehouse(TO)).thenReturn(Optional.of(
                    new WarehouseView(TO, "HN", "HN warehouse", "2 Hang Bac", true)));
            PackageJpaEntity row = new PackageJpaEntity(second, pack.getId(), 2, "PKG-2");
            row.markLabelOutdated(NOW.minusSeconds(60));
            when(packages.findById(second)).thenReturn(Optional.of(row));

            TransferPacks.Label label = service.label(pack.getId(), second, actor);

            assertThat(label.packageNo()).isEqualTo(2);
            assertThat(label.packageCount()).isEqualTo(2);
            assertThat(label.fromWarehouseName()).isEqualTo("HCM warehouse");
            assertThat(label.toWarehouseAddress()).isEqualTo("2 Hang Bac");
            assertThat(label.weightKg()).isEqualByComparingTo("8");
            assertThat(row.getLabelOutdatedAt()).as("printed again, the label is current").isNull();
            verify(packages).save(row);
        }
    }

    @Nested
    @DisplayName("an unopened stock carton shipped as it is (LP2-8)")
    class Cartons {

        private final UUID stockPackageId = UUID.randomUUID();

        private TransferFulfillment.Carton carton(int quantity, boolean unopened, boolean inStock, boolean atWarehouse) {
            return new TransferFulfillment.Carton(stockPackageId, "LPN-1", "SKU-A", null, quantity, atWarehouse,
                    "HCM-A-01", UUID.randomUUID(), "BOX", unopened, inStock, true);
        }

        private void cartonIs(TransferFulfillment.Carton carton) {
            when(transfers.carton("LPN-1", WAREHOUSE)).thenReturn(Optional.of(carton));
        }

        @Test
        @DisplayName("an LPN that is no stock carton is NOT_FOUND")
        void unknownCarton() {
            when(transfers.carton("LPN-9", WAREHOUSE)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.shipCarton(pack.getId(), "LPN-9", null, actor)).errorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("an opened carton, one not in stock, one elsewhere or an empty one is STOCK_CARTON_NOT_SHIPPABLE")
        void notAnUnopenedCartonInStockHere() {
            for (TransferFulfillment.Carton bad : List.of(carton(5, false, true, true), carton(5, true, false, true),
                    carton(5, true, true, false), carton(0, true, true, true))) {
                cartonIs(bad);
                assertThat(refusal(() -> service.shipCarton(pack.getId(), "LPN-1", null, actor)).errorCode())
                        .isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
            }
        }

        @Test
        @DisplayName("a carton already packed in a parcel not voided is STOCK_CARTON_NOT_SHIPPABLE")
        void alreadyPacked() {
            cartonIs(carton(5, true, true, true));
            when(packages.findFirstByStockPackageIdAndStatusNot(stockPackageId, PackageStatus.VOIDED))
                    .thenReturn(Optional.of(mock(PackageJpaEntity.class)));

            var thrown = refusal(() -> service.shipCarton(pack.getId(), "LPN-1", null, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
            assertThat(thrown.getMessage()).contains("packed already");
        }

        @Test
        @DisplayName("a carton holding more than any line still wants is refused, naming the carton")
        void moreThanTheLineWants() {
            cartonIs(carton(11, true, true, true));

            var thrown = refusal(() -> service.shipCarton(pack.getId(), "LPN-1", null, actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("lpn");
        }

        @Test
        @DisplayName("a carton of another SKU than any line is refused")
        void otherSku() {
            cartonIs(new TransferFulfillment.Carton(stockPackageId, "LPN-1", "SKU-Z", null, 5, true, "HCM-A-01", null,
                    "BOX", true, true, true));

            assertThat(refusal(() -> service.shipCarton(pack.getId(), "LPN-1", null, actor)).errorCode())
                    .isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
        }

        @Test
        @DisplayName("a lot-bound line takes only its own lot")
        void lotBoundLine() {
            session = new PackingSession(pack.getId(), "TO-0001", PackStatus.PACKING, NOW, packer, null, null,
                    List.of(new PackingSession.Line(lineId, "SKU-A", null, 10, 10, false, null, Map.of("L1", 10),
                            Set.of(), true, false, 1)), List.of(), List.of());
            cartonIs(new TransferFulfillment.Carton(stockPackageId, "LPN-1", "SKU-A", "L2", 5, true, "HCM-A-01", null,
                    "BOX", true, true, true));

            assertThat(refusal(() -> service.shipCarton(pack.getId(), "LPN-1", null, actor)).errorCode())
                    .isEqualTo(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE);
        }

        @Test
        @DisplayName("the carton opens a parcel holding its units, the packing unit's size is suggested, the units are re-held")
        void shipsTheCartonAsAParcel() {
            cartonIs(carton(5, true, true, true));
            when(packages.findFirstByStockPackageIdAndStatusNot(stockPackageId, PackageStatus.VOIDED))
                    .thenReturn(Optional.empty());
            when(packages.findById(any(UUID.class))).thenAnswer(call -> {
                PackageJpaEntity row = new PackageJpaEntity(call.getArgument(0), pack.getId(), 1, "PKG-1");
                return Optional.of(row);
            });
            when(packaging.unitCarton(any(), any())).thenReturn(Optional.of(
                    new PackagingSpec.CartonSpec(605, 400, 301, new BigDecimal("9.5"), true)));

            TransferPacks.CartonParcel parcel = service.shipCarton(pack.getId(), "LPN-1", null, actor);

            assertThat(parcel.lpn()).isEqualTo("LPN-1");
            assertThat(parcel.unitOfMeasure()).isEqualTo("BOX");
            assertThat(parcel.parcel().contents()).singleElement().satisfies(c -> assertThat(c.quantity()).isEqualTo(5));
            assertThat(parcel.parcel().stockPackageId()).isEqualTo(stockPackageId);
            assertThat(session.packedOf(lineId)).isEqualTo(5);
            assertThat(parcel.suggested().lengthCm()).isEqualByComparingTo("60.5");
            assertThat(parcel.suggested().heightCm()).isEqualByComparingTo("30.1");
            assertThat(parcel.suggested().weightKg()).isEqualByComparingTo("9.5");
            ArgumentCaptor<PackageJpaEntity> saved = ArgumentCaptor.forClass(PackageJpaEntity.class);
            verify(packages).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getStockPackageId()).isEqualTo(stockPackageId);
            verify(transfers).holdForPacking(eq(transferId), eq(Map.of(lineId, 10)), any(), any());
        }

        @Test
        @DisplayName("a unit that declares no carton size suggests nothing: the packer measures")
        void noSuggestion() {
            cartonIs(carton(5, true, true, true));
            when(packages.findFirstByStockPackageIdAndStatusNot(stockPackageId, PackageStatus.VOIDED))
                    .thenReturn(Optional.empty());
            when(packages.findById(any(UUID.class))).thenAnswer(call -> Optional.of(
                    new PackageJpaEntity(call.getArgument(0), pack.getId(), 1, "PKG-1")));
            when(packaging.unitCarton(any(), any())).thenReturn(Optional.empty());

            assertThat(service.shipCarton(pack.getId(), "LPN-1", null, actor).suggested()).isNull();
        }
    }

    @Nested
    @DisplayName("completing (10 step 5-8)")
    class Completing {

        private UUID parcelWithUnits(int units) {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
            service.scan(pack.getId(), parcelId, new TransferPacks.Scan(UUID.randomUUID(), "SKU-A", null, units, lineId,
                    null), actor);
            service.seal(pack.getId(), parcelId, new TransferPacks.Seal(new BigDecimal("8"), new BigDecimal("40"),
                    new BigDecimal("30"), new BigDecimal("25"), null, null, null, null), actor);
            return parcelId;
        }

        @Test
        @DisplayName("a full pack marks the transfer packed with exactly the units packed per line")
        void completesAndMarksThePacked() {
            parcelWithUnits(10);
            when(transfers.markPacked(eq(transferId), any(), any(), any())).thenReturn(transferIn("PACKED", 10, null));

            TransferPacks.Session view = service.complete(pack.getId(), false, null, actor);

            verify(transfers).markPacked(eq(transferId), eq(Map.of(lineId, 10)), any(), any());
            assertThat(session.status()).isEqualTo(PackStatus.PACKED);
            assertThat(view.transferStatus()).isEqualTo("PACKED");
        }

        @Test
        @DisplayName("shipping less than requested needs the partial flag and says why (10 step 8)")
        void partialNeedsAReason() {
            parcelWithUnits(6);

            assertThat(refusal(() -> service.complete(pack.getId(), false, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKING_INCOMPLETE);
            assertThat(refusal(() -> service.complete(pack.getId(), true, "  ", actor)).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(transfers, never()).markPacked(any(), any(), any(), any());

            when(transfers.markPacked(eq(transferId), any(), any(), any())).thenReturn(transferIn("PACKED", 10, null));
            service.complete(pack.getId(), true, "only 6 on the shelf", actor);

            verify(transfers).markPacked(eq(transferId), eq(Map.of(lineId, 6)), any(), any());
        }

        @Test
        @DisplayName("an open parcel with goods in it stops completion: PACKAGE_NOT_SEALED")
        void openParcelStopsCompletion() {
            UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
            service.scan(pack.getId(), parcelId, new TransferPacks.Scan(UUID.randomUUID(), "SKU-A", null, 10, lineId,
                    null), actor);

            assertThat(refusal(() -> service.complete(pack.getId(), false, null, actor)).errorCode())
                    .isEqualTo(ErrorCode.PACKAGE_NOT_SEALED);
        }

        @Test
        @DisplayName("nothing sealed: PACK_NOTHING_TO_SHIP")
        void nothingToShip() {
            assertThat(refusal(() -> service.complete(pack.getId(), true, "none", actor)).errorCode())
                    .isEqualTo(ErrorCode.PACK_NOTHING_TO_SHIP);
        }

        @Test
        @DisplayName("completing a session already PACKED answers with it, marking nothing twice")
        void completingTwiceIsIdempotent() {
            session = sessionOf(PackStatus.PACKED, packer, 10);

            TransferPacks.Session view = service.complete(pack.getId(), false, null, actor);

            assertThat(view.packStatus()).isEqualTo("PACKED");
            verify(transfers, never()).markPacked(any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("a parcel reopened before the handover is returned to the packer")
    void reopen() {
        UUID parcelId = service.openPackage(pack.getId(), null, null, actor).packageId();
        service.scan(pack.getId(), parcelId, new TransferPacks.Scan(UUID.randomUUID(), "SKU-A", null, 3, lineId, null),
                actor);
        service.seal(pack.getId(), parcelId, new TransferPacks.Seal(new BigDecimal("8"), new BigDecimal("40"),
                new BigDecimal("30"), new BigDecimal("25"), null, null, null, null), actor);

        TransferPacks.Parcel reopened = service.reopen(pack.getId(), parcelId, actor);

        assertThat(reopened.status()).isEqualTo(PackageStatus.OPEN.name());
        assertThat(reopened.packageType()).isEqualTo(PackageType.CARTON.name());
    }
}
