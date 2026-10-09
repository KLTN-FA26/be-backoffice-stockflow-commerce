package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Shelf aggregate: a shelf, its levels, their bins and each bin's storage location. Every rule
 * that needs the whole tree is decided here - bins inside the shelf, no two bins overlapping on a
 * level, unique codes, BR-08 - so it is tested here exhaustively and cheaply.
 */
class ShelfTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static UUID newId() {
        return new UUID(0x0190a000_0000_7000L, 0x8000_0000_0000_0000L | SEQUENCE.incrementAndGet());
    }

    private static Footprint at(String x, String y, String width, String length) {
        return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width),
                new BigDecimal(length), 0);
    }

    /** A01: 10 x 1.2 at (5, 5), picked from the south, NORMAL by default. */
    private static Shelf shelf(PickFaces faces) {
        return Shelf.create(newId(), WAREHOUSE, null, "a01", "Kệ A01", null, at("5", "5", "10", "1.2"),
                true, faces, StorageClass.NORMAL);
    }

    private static Shelf shelf() {
        return shelf(new PickFaces(false, false, true, false));
    }

    private static ShelfLevel level(Shelf shelf, int index) {
        return shelf.addLevel(newId(), index, LevelMeasures.NONE);
    }

    private static Bin bin(Shelf shelf, ShelfLevel level, String code, Footprint footprint) {
        return bin(shelf, level, code, footprint, null, true);
    }

    private static Bin bin(Shelf shelf, ShelfLevel level, String code, Footprint footprint,
                           StorageClass override, boolean pickable) {
        return shelf.addBin(level.id(), newId(), newId(), code,
                new BinDetails(null, footprint, BinType.PALLET, override),
                new LocationSettings(40, new BigDecimal("250"), pickable, true), "hn");
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Nested
    @DisplayName("creating")
    class Creating {

        @Test
        @DisplayName("starts ACTIVE with an upper-cased code and no levels")
        void startsActive() {
            Shelf shelf = shelf();

            assertThat(shelf.status()).isEqualTo(LocationStatus.ACTIVE);
            assertThat(shelf.code()).isEqualTo("A01");
            assertThat(shelf.levels()).isEmpty();
        }

        @Test
        @DisplayName("refuses a code that could not be part of a location code, or a blank name")
        void refusesBadInput() {
            assertThat(errorOf(() -> Shelf.create(newId(), WAREHOUSE, null, "A-01", "Kệ", null,
                    at("0", "0", "1", "1"), true, PickFaces.NONE, StorageClass.NORMAL)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> Shelf.create(newId(), WAREHOUSE, null, "A01", " ", null,
                    at("0", "0", "1", "1"), true, PickFaces.NONE, StorageClass.NORMAL)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    @DisplayName("levels")
    class Levels {

        @Test
        @DisplayName("are numbered 1-99, each number once")
        void indexIsUniqueAndBounded() {
            Shelf shelf = shelf();
            level(shelf, 1);

            assertThat(errorOf(() -> level(shelf, 1))).isEqualTo(ErrorCode.SHELF_LEVEL_ALREADY_EXISTS);
            assertThat(errorOf(() -> level(shelf, 0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> level(shelf, 100))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a shelf holds at most 20 (issue #18 D7)")
        void atMostTwenty() {
            Shelf shelf = shelf();
            for (int i = 1; i <= 20; i++) {
                level(shelf, i);
            }

            assertThat(errorOf(() -> level(shelf, 21))).isEqualTo(ErrorCode.SHELF_CAPACITY_EXCEEDED);
        }

        @Test
        @DisplayName("measures are optional, but an elevation is never negative and a height or load never zero")
        void measuresAreChecked() {
            assertThat(errorOf(() -> new LevelMeasures(new BigDecimal("-0.001"), null, null)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> new LevelMeasures(null, BigDecimal.ZERO, null)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(new LevelMeasures(BigDecimal.ZERO, null, null).elevation()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("updating keeps the index and changes the measures; an unknown level is not found")
        void updates() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 2);

            shelf.updateLevel(level.id(), new LevelMeasures(new BigDecimal("1.5"), new BigDecimal("1.4"),
                    new BigDecimal("500")));

            assertThat(shelf.level(level.id()).levelIndex()).isEqualTo(2);
            assertThat(shelf.level(level.id()).measures().maxWeight()).isEqualByComparingTo("500");
            assertThat(errorOf(() -> shelf.updateLevel(newId(), LevelMeasures.NONE)))
                    .isEqualTo(ErrorCode.SHELF_LEVEL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("bins")
    class Bins {

        @Test
        @DisplayName("each bin owns an ACTIVE BIN location coded prefix-shelf-level-bin")
        void ownsALocation() {
            Shelf shelf = shelf();
            Bin bin = bin(shelf, level(shelf, 2), "03", at("0", "0", "1", "1.2"));

            StorageLocation location = bin.location();
            assertThat(location.kind()).isEqualTo(StorageLocationKind.BIN);
            assertThat(location.locationCode()).isEqualTo("HN-A01-2-03");
            assertThat(location.status()).isEqualTo(LocationStatus.ACTIVE);
            assertThat(location.capacityUnits()).isEqualTo(40);
        }

        @Test
        @DisplayName("stores the shelf's default class, or the bin's override (issue #18 D9)")
        void resolvesStorageClass() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);

            assertThat(bin(shelf, level, "01", at("0", "0", "1", "1")).location().storageClass())
                    .isEqualTo(StorageClass.NORMAL);
            assertThat(bin(shelf, level, "02", at("1", "0", "1", "1"), StorageClass.COLD, true)
                    .location().storageClass()).isEqualTo(StorageClass.COLD);
        }

        @Test
        @DisplayName("must lie inside the shelf's own frame")
        void staysInsideTheShelf() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);

            bin(shelf, level, "01", at("9", "0", "1", "1.2"));
            assertThat(errorOf(() -> bin(shelf, level, "02", at("9.001", "0", "1", "1.2"))))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
        }

        @Test
        @DisplayName("may not overlap another bin of the same level, but may sit above one on another level")
        void doNotOverlapOnALevel() {
            Shelf shelf = shelf();
            ShelfLevel first = level(shelf, 1);
            ShelfLevel second = level(shelf, 2);
            bin(shelf, first, "01", at("0", "0", "2", "1"));

            assertThat(errorOf(() -> bin(shelf, first, "02", at("1", "0", "2", "1"))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(bin(shelf, second, "01", at("0", "0", "2", "1")).code()).isEqualTo("01");
            assertThat(bin(shelf, first, "03", at("2", "0", "2", "1")).code()).isEqualTo("03");
        }

        @Test
        @DisplayName("a code is unique within its level")
        void codeIsUniquePerLevel() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            bin(shelf, level, "01", at("0", "0", "1", "1"));

            assertThat(errorOf(() -> bin(shelf, level, "01", at("5", "0", "1", "1"))))
                    .isEqualTo(ErrorCode.BIN_CODE_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("an INACTIVE bin no longer holds its place; bringing it back needs the place free (#18 D3)")
        void inactiveBinsFreeTheirPlace() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            Bin old = bin(shelf, level, "01", at("0", "0", "2", "1"));
            shelf.changeBinStatus(level.id(), old.id(), LocationStatus.INACTIVE);

            bin(shelf, level, "02", at("0", "0", "2", "1"));

            assertThat(errorOf(() -> shelf.changeBinStatus(level.id(), old.id(), LocationStatus.ACTIVE)))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(errorOf(() -> shelf.changeBinStatus(level.id(), old.id(), LocationStatus.BLOCKED)))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(shelf.level(level.id()).bin(old.id()).location().status())
                    .isEqualTo(LocationStatus.INACTIVE);
        }

        @Test
        @DisplayName("a level holds at most 200 bins (issue #18 D7)")
        void atMost200() {
            Shelf shelf = Shelf.create(newId(), WAREHOUSE, null, "B01", "Kệ B01", null,
                    at("0", "0", "201", "1"), true, PickFaces.NONE, StorageClass.NORMAL);
            ShelfLevel level = level(shelf, 1);
            for (int i = 0; i < 200; i++) {
                bin(shelf, level, "B" + i, at(String.valueOf(i), "0", "1", "1"), null, false);
            }

            assertThat(errorOf(() -> bin(shelf, level, "B200", at("200", "0", "1", "1"), null, false)))
                    .isEqualTo(ErrorCode.SHELF_CAPACITY_EXCEEDED);
        }

        @Test
        @DisplayName("updating keeps code and location code; a bin may move within its own old place")
        void updates() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            Bin bin = bin(shelf, level, "01", at("0", "0", "2", "1"));
            bin(shelf, level, "02", at("3", "0", "2", "1"));

            shelf.updateBin(level.id(), bin.id(), new BinDetails("Ô sát tường", at("0.5", "0", "2", "1"),
                    BinType.STANDARD, StorageClass.FRAGILE), new LocationSettings(null, null, false, false));

            Bin updated = shelf.level(level.id()).bin(bin.id());
            assertThat(updated.code()).isEqualTo("01");
            assertThat(updated.location().locationCode()).isEqualTo("HN-A01-1-01");
            assertThat(updated.location().storageClass()).isEqualTo(StorageClass.FRAGILE);
            assertThat(updated.location().pickable()).isFalse();
            assertThat(errorOf(() -> shelf.updateBin(level.id(), bin.id(), new BinDetails(null,
                    at("2", "0", "2", "1"), BinType.STANDARD, null), new LocationSettings(null, null, false, false))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(errorOf(() -> shelf.updateBin(level.id(), newId(), new BinDetails(null,
                    at("0", "0", "1", "1"), BinType.STANDARD, null), new LocationSettings(null, null, false, false))))
                    .isEqualTo(ErrorCode.BIN_NOT_FOUND);
        }

        @Test
        @DisplayName("location settings are checked: capacity and load are positive when given")
        void settingsAreChecked() {
            assertThat(errorOf(() -> new LocationSettings(0, null, true, true))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(errorOf(() -> new LocationSettings(null, BigDecimal.ZERO, true, true)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    @DisplayName("generating bins (BR-14)")
    class GeneratingBins {

        private static final BinDefaults PICKABLE_PALLETS = new BinDefaults(BinType.PALLET, null,
                new LocationSettings(40, new BigDecimal("250"), true, true));

        private static List<Bin> generate(Shelf shelf, List<UUID> levelIds, int rows, int columns,
                                          BinDefaults defaults) {
            return shelf.generateBins(levelIds, rows, columns, BinNamingScheme.SEQUENTIAL, defaults, "hn",
                    ShelfTest::newId);
        }

        @Test
        @DisplayName("2 levels x (2 rows x 3 columns) makes 12 bins and 12 locations, coded per level")
        void fillsEveryChosenLevel() {
            Shelf shelf = shelf();
            ShelfLevel second = level(shelf, 2);
            ShelfLevel first = level(shelf, 1);

            List<Bin> bins = generate(shelf, List.of(second.id(), first.id()), 2, 3, PICKABLE_PALLETS);

            assertThat(bins).hasSize(12);
            assertThat(bins).extracting(bin -> bin.location().locationCode()).startsWith(
                    "HN-A01-1-01", "HN-A01-1-02").contains("HN-A01-1-06", "HN-A01-2-01", "HN-A01-2-06");
            assertThat(bins).extracting(bin -> bin.location().id()).doesNotHaveDuplicates()
                    .doesNotContainAnyElementsOf(bins.stream().map(Bin::id).toList());
            assertThat(shelf.level(first.id()).bins()).hasSize(6);
            assertThat(shelf.level(second.id()).bins()).hasSize(6);
            Bin last = shelf.level(first.id()).bins().get(5);
            assertThat(last.footprint()).isEqualTo(at("6.666", "0.6", "3.333", "0.6"));
            assertThat(last.details().type()).isEqualTo(BinType.PALLET);
            assertThat(last.location().status()).isEqualTo(LocationStatus.ACTIVE);
            assertThat(last.location().storageClass()).isEqualTo(StorageClass.NORMAL);
            assertThat(last.location().capacityUnits()).isEqualTo(40);
            assertThat(last.location().pickable()).isTrue();
        }

        @Test
        @DisplayName("an override reaches every generated location; without one the shelf's default does (#18 D9)")
        void resolvesTheStorageClass() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);

            List<Bin> bins = generate(shelf, List.of(level.id()), 1, 2, new BinDefaults(BinType.STANDARD,
                    StorageClass.COLD, new LocationSettings(null, null, false, true)));

            assertThat(bins).allSatisfy(bin -> {
                assertThat(bin.details().storageClassOverride()).isEqualTo(StorageClass.COLD);
                assertThat(bin.location().storageClass()).isEqualTo(StorageClass.COLD);
            });
        }

        @Test
        @DisplayName("one level that already has a bin, even an INACTIVE one, refuses the lot")
        void refusesALevelWithBins() {
            Shelf shelf = shelf();
            ShelfLevel empty = level(shelf, 1);
            ShelfLevel used = level(shelf, 2);
            Bin old = bin(shelf, used, "01", at("0", "0", "1", "1"));
            shelf.changeBinStatus(used.id(), old.id(), LocationStatus.INACTIVE);

            assertThat(errorOf(() -> generate(shelf, List.of(empty.id(), used.id()), 2, 3, PICKABLE_PALLETS)))
                    .isEqualTo(ErrorCode.SHELF_LEVEL_HAS_BINS);
            assertThat(shelf.level(empty.id()).bins()).isEmpty();
            assertThat(shelf.level(used.id()).bins()).hasSize(1);
        }

        @Test
        @DisplayName("a level of another shelf refuses the lot")
        void refusesAForeignLevel() {
            Shelf shelf = shelf();
            ShelfLevel mine = level(shelf, 1);
            Shelf other = shelf();
            ShelfLevel theirs = level(other, 1);

            assertThat(errorOf(() -> generate(shelf, List.of(mine.id(), theirs.id()), 1, 1, PICKABLE_PALLETS)))
                    .isEqualTo(ErrorCode.SHELF_LEVEL_NOT_FOUND);
            assertThat(shelf.level(mine.id()).bins()).isEmpty();
        }

        @Test
        @DisplayName("checks BR-08 once, and more than 200 bins a level or no level at all are refused")
        void refusesWhatTheShelfCannotTake() {
            Shelf faceless = shelf(PickFaces.NONE);
            ShelfLevel level = level(faceless, 1);

            assertThat(errorOf(() -> generate(faceless, List.of(level.id()), 1, 2, PICKABLE_PALLETS)))
                    .isEqualTo(ErrorCode.PICK_FACE_REQUIRED);
            assertThat(errorOf(() -> generate(faceless, List.of(level.id()), 1, 201, new BinDefaults(BinType.PALLET,
                    null, new LocationSettings(null, null, false, true))))).isEqualTo(ErrorCode.SHELF_CAPACITY_EXCEEDED);
            assertThat(errorOf(() -> generate(faceless, List.of(), 1, 1, PICKABLE_PALLETS)))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(faceless.level(level.id()).bins()).isEmpty();
        }

        @Test
        @DisplayName("lettered rows give A01, A02, ... B01 on every level")
        void lettersTheRows() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 3);

            List<Bin> bins = shelf.generateBins(List.of(level.id()), 2, 2, BinNamingScheme.ROW_LETTER_COLUMN,
                    PICKABLE_PALLETS, "hn", ShelfTest::newId);

            assertThat(bins).extracting(bin -> bin.location().locationCode())
                    .containsExactly("HN-A01-3-A01", "HN-A01-3-A02", "HN-A01-3-B01", "HN-A01-3-B02");
        }
    }

    @Nested
    @DisplayName("pick faces (BR-08)")
    class PickFaceRule {

        @Test
        @DisplayName("a pickable bin needs a shelf with at least one pick face")
        void pickableBinNeedsAFace() {
            Shelf shelf = shelf(PickFaces.NONE);
            ShelfLevel level = level(shelf, 1);

            assertThat(errorOf(() -> bin(shelf, level, "01", at("0", "0", "1", "1"), null, true)))
                    .isEqualTo(ErrorCode.PICK_FACE_REQUIRED);
            assertThat(bin(shelf, level, "02", at("0", "0", "1", "1"), null, false).code()).isEqualTo("02");
        }

        @Test
        @DisplayName("the last face cannot be removed while a pickable bin is on the shelf")
        void lastFaceStays() {
            Shelf shelf = shelf();
            bin(shelf, level(shelf, 1), "01", at("0", "0", "1", "1"));

            assertThat(errorOf(() -> shelf.update(null, shelf.name(), null, shelf.footprint(), true,
                    PickFaces.NONE, StorageClass.NORMAL, 0L))).isEqualTo(ErrorCode.PICK_FACE_REQUIRED);
        }
    }

    @Nested
    @DisplayName("updating the shelf")
    class Updating {

        @Test
        @DisplayName("may not shrink so that a bin sticks out; an INACTIVE bin does not count, and cannot come back outside")
        void binsKeepItFromShrinking() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            Bin farBin = bin(shelf, level, "01", at("8", "0", "2", "1"));

            assertThat(errorOf(() -> shelf.update(null, "Kệ A01", null, at("5", "5", "9.999", "1.2"), true,
                    shelf.pickFaces(), StorageClass.NORMAL, 0L))).isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);

            shelf.changeBinStatus(level.id(), farBin.id(), LocationStatus.INACTIVE);
            shelf.update(null, "Kệ A01", null, at("5", "5", "6", "1.2"), true, shelf.pickFaces(),
                    StorageClass.NORMAL, 0L);
            assertThat(shelf.footprint().width()).isEqualByComparingTo("6");

            // Left outside by the shrink, the bin may not return to the layout there.
            assertThat(errorOf(() -> shelf.changeBinStatus(level.id(), farBin.id(), LocationStatus.ACTIVE)))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
            assertThat(farBin.location().status()).isEqualTo(LocationStatus.INACTIVE);
        }

        @Test
        @DisplayName("a new default class reaches every bin without an override, and only those")
        void defaultClassPropagates() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            Bin plain = bin(shelf, level, "01", at("0", "0", "1", "1"));
            Bin cold = bin(shelf, level, "02", at("1", "0", "1", "1"), StorageClass.COLD, true);

            shelf.update(null, "Kệ A01", null, shelf.footprint(), true, shelf.pickFaces(),
                    StorageClass.OVERSIZE, 0L);

            assertThat(shelf.level(level.id()).bin(plain.id()).location().storageClass())
                    .isEqualTo(StorageClass.OVERSIZE);
            assertThat(shelf.level(level.id()).bin(cold.id()).location().storageClass())
                    .isEqualTo(StorageClass.COLD);
        }

        @Test
        @DisplayName("refuses an edit based on an older version")
        void refusesStaleEdit() {
            Shelf shelf = shelf();

            assertThat(errorOf(() -> shelf.update(null, "X", null, shelf.footprint(), true,
                    shelf.pickFaces(), StorageClass.NORMAL, 7L))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("changes status freely; bins keep their own (issue #18 D2)")
        void statusDoesNotCascade() {
            Shelf shelf = shelf();
            ShelfLevel level = level(shelf, 1);
            Bin bin = bin(shelf, level, "01", at("0", "0", "1", "1"));

            shelf.changeStatus(LocationStatus.MAINTENANCE);

            assertThat(shelf.status()).isEqualTo(LocationStatus.MAINTENANCE);
            assertThat(shelf.level(level.id()).bin(bin.id()).location().status()).isEqualTo(LocationStatus.ACTIVE);
        }
    }
}
