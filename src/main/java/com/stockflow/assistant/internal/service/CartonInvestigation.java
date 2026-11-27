package com.stockflow.assistant.internal.service;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.i18n.Messages;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.api.PickDestination;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.CartonHistoryQueries;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A missing carton, investigated (contract 3.2): the button "Tìm thùng này" on a short pick line, a count line found
 * missing, or the carton lookup, and the chat tool {@code investigateCarton}. A floor worker who cannot find a carton
 * today opens the carton, its history, the putaway tasks, the pick task and the counts one screen at a time; this puts
 * the facts of those screens side by side, newest first, and says where to look, in an order fixed by rules a person
 * can check - nothing is guessed by a model.
 *
 * <p><b>Where to look, ranked</b> (each place once, at its first reason):</p>
 * <ol>
 *   <li>{@code LAST_RECORDED} - where the books last put it. Most cartons are where they were last scanned.</li>
 *   <li>{@code PUTAWAY_TARGET} - an open putaway task's bin, as the clerk's trip assigned it: a carton put away without
 *       the scan is there.</li>
 *   <li>{@code STAGING_AREA} - an open pick task sending its cartons to staging: a picker may have carried it there
 *       before scanning.</li>
 *   <li>{@code QUARANTINE} - the quarantine area an open return receipt of it goes into, or, for a carton whose stock
 *       is in quarantine or damaged and which is not standing in one, the warehouse's quarantine areas.</li>
 *   <li>{@code NEIGHBOUR_BIN} - the bins beside the last recorded bin on the same shelf, by the warehouse map's codes
 *       (the next bin on the level, the same bin a level up or down) that exist: a carton put back one slot off.</li>
 *   <li>{@code RECEIVING_AREA} - the warehouse's receiving areas, for a carton still being received or whose last
 *       ledger line is its receipt.</li>
 * </ol>
 *
 * <p><b>Each fact keeps its screen's rule.</b> The carton and its open work are the carton lookup's
 * ({@code inventory-stock-packages:READ}, the guard every caller of this runs under), refused for another warehouse as
 * that screen refuses it. The timeline is the stock ledger's and is shown only to who may read the ledger
 * ({@code inventory-stock-movements:READ}); adjustments only to who may read them; return receipts only to who may read
 * receipts. <b>The blind count</b> (kltn-docs 20 BR-07): for a counter who may not see the books, inventory leaves out
 * the history and the pending adjustment of a carton a count holds, and {@code timelineWithheld} says why the history
 * is empty instead of showing nothing as if nothing happened.</p>
 */
@Service
public class CartonInvestigation {

    private static final Logger log = LoggerFactory.getLogger(CartonInvestigation.class);

    /** Ledger lines on the timeline, newest first. */
    static final int TIMELINE = 30;

    /** A bin code: prefix, shelf, level (no leading zero), bin (06 BR-10, {@code LocationCode}). */
    private static final Pattern BIN = Pattern.compile("([A-Z0-9]+)-([A-Z0-9]+)-([1-9][0-9]?)-([A-Z0-9]+)");

    /** A bin's own part: letters then digits, either may be empty ({@code 03}, {@code A1}, {@code B}). */
    private static final Pattern BIN_PART = Pattern.compile("([A-Z]*)([0-9]*)");

    private final CartonHistoryQueries cartons;
    private final WarehouseService warehouses;
    private final PutawayTrips trips;
    private final FulfillmentService fulfillment;
    private final GoodsReceiptQueries receipts;
    private final IdentityService identity;
    private final CurrentUserProvider currentUsers;
    private final Messages messages;

    CartonInvestigation(CartonHistoryQueries cartons, WarehouseService warehouses, PutawayTrips trips,
                        FulfillmentService fulfillment, GoodsReceiptQueries receipts, IdentityService identity,
                        CurrentUserProvider currentUsers, Messages messages) {
        this.cartons = cartons;
        this.warehouses = warehouses;
        this.trips = trips;
        this.fulfillment = fulfillment;
        this.receipts = receipts;
        this.identity = identity;
        this.currentUsers = currentUsers;
        this.messages = messages;
    }

    /**
     * The carton's investigation. Run inside the carton lookup's guard ({@code inventory-stock-packages:READ}).
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND};
     *         {@code OUT_OF_DATA_SCOPE} for a carton in a warehouse the caller is not assigned to
     */
    public Investigation investigate(String lpn) {
        CurrentUser user = currentUsers.current().orElse(null);
        CartonHistoryQueries.CartonHistory carton = cartons.history(lpn, TIMELINE);
        boolean mayReadLedger = can(user, ToolPermissions.INVENTORY_STOCK_MOVEMENTS);
        String withheld = carton.booksHidden() ? "BLIND_COUNT" : !mayReadLedger ? "NO_PERMISSION" : null;
        // Withheld for either reason, there is no timeline and no total: a 0 would read as "never moved".
        List<TimelineEntry> timeline = withheld == null ? timeline(carton.ledger()) : List.of();
        List<GoodsReceiptQueries.ReturnReceiptRef> returns = can(user, ToolPermissions.GOODS_RECEIPTS)
                ? receipts.openReturnReceiptsOf(carton.stockPackageId()) : List.of();
        List<OpenWork> openWork = openWork(carton, returns, user);
        List<Place> whereToLook = whereToLook(carton, returns, user);
        return new Investigation(carton.lpn(), carton.sku(), carton.status(), carton.stockStatus(),
                carton.locationCode(), carton.warehouseCode(), carton.lotNumber(), carton.receivedAt(), timeline,
                withheld == null ? carton.ledgerTotal() : null, withheld, openWork, whereToLook);
    }

    private List<TimelineEntry> timeline(List<CartonHistoryQueries.LedgerLine> ledger) {
        Map<UUID, String> names = ledger.isEmpty() ? Map.of() : identity.displayNames(ledger.stream()
                .map(CartonHistoryQueries.LedgerLine::actorId).filter(Objects::nonNull).distinct().toList());
        return ledger.stream().map(line -> new TimelineEntry(line.at(), line.movementType(), line.fromLocation(),
                line.toLocation(), line.referenceType(), line.actorId() == null ? null
                : names.getOrDefault(line.actorId(), null))).toList();
    }

    private List<OpenWork> openWork(CartonHistoryQueries.CartonHistory carton,
                                    List<GoodsReceiptQueries.ReturnReceiptRef> returns, CurrentUser user) {
        boolean mayReadAdjustments = can(user, ToolPermissions.STOCK_ADJUSTMENTS);
        List<OpenWork> work = new ArrayList<>();
        for (CartonHistoryQueries.OpenWork open : carton.openWork()) {
            switch (open.kind()) {
                case PUTAWAY_TASK -> work.add(new OpenWork("PUTAWAY_TASK", open.id().toString(), open.status(),
                        WorkLinks.putawayTask(open.id())));
                case PICK_TASK -> work.add(new OpenWork("PICK_LINE", open.id().toString(), open.status(),
                        WorkLinks.pickTask(open.id())));
                case CARTON_CHECK -> work.add(new OpenWork("CARTON_CHECK", open.id().toString(), open.status(),
                        WorkLinks.cartonCheck(open.id())));
                case COUNT -> work.add(new OpenWork("COUNT_LINE", open.number(), open.status(),
                        WorkLinks.cycleCount(open.id())));
                case ADJUSTMENT -> {
                    if (mayReadAdjustments) {
                        work.add(new OpenWork("ADJUSTMENT", open.number(), open.status(),
                                WorkLinks.adjustment(open.id())));
                    }
                }
            }
        }
        returns.forEach(receipt -> work.add(new OpenWork("RETURN_RECEIPT", receipt.number(), receipt.status(),
                WorkLinks.returnReceipt(receipt.receiptId()))));
        return work;
    }

    /** The places to look, in the rules' order (class javadoc), each once. */
    private List<Place> whereToLook(CartonHistoryQueries.CartonHistory carton,
                                    List<GoodsReceiptQueries.ReturnReceiptRef> returns, CurrentUser user) {
        Map<String, Place> places = new LinkedHashMap<>();
        String last = carton.locationCode();
        if (last != null) {
            add(places, last, "LAST_RECORDED", carton.status());
        }
        for (CartonHistoryQueries.OpenWork open : carton.openWork()) {
            if (open.kind() == CartonHistoryQueries.WorkKind.PUTAWAY_TASK) {
                trips.assignedLocationOf(open.id()).flatMap(warehouses::findLocation)
                        .ifPresent(bin -> add(places, bin.locationCode(), "PUTAWAY_TARGET", null));
            }
        }
        for (CartonHistoryQueries.OpenWork open : carton.openWork()) {
            if (open.kind() == CartonHistoryQueries.WorkKind.PICK_TASK && user != null) {
                stagingAreaOf(open.id(), user).ifPresent(area -> add(places, area, "STAGING_AREA", null));
            }
        }
        for (GoodsReceiptQueries.ReturnReceiptRef receipt : returns) {
            if (receipt.quarantineLocationId() != null) {
                warehouses.findLocation(receipt.quarantineLocationId())
                        .ifPresent(area -> add(places, area.locationCode(), "QUARANTINE", receipt.number()));
            }
        }
        Set<String> quarantineAreas = new LinkedHashSet<>();
        if (carton.warehouseId() != null
                && ("QUARANTINE".equals(carton.stockStatus()) || "DAMAGED".equals(carton.stockStatus()))) {
            warehouses.areasOf(carton.warehouseId(), StorageLocationView.AreaType.QUARANTINE)
                    .forEach(area -> quarantineAreas.add(area.locationCode()));
            if (!quarantineAreas.contains(last)) {
                quarantineAreas.forEach(area -> add(places, area, "QUARANTINE", carton.stockStatus()));
            }
        }
        if (last != null) {
            List<String> neighbours = neighbours(last);
            if (!neighbours.isEmpty()) {
                warehouses.findLocations(neighbours).stream()
                        .filter(location -> location.kind() == StorageLocationView.Kind.BIN)
                        .map(StorageLocationView::locationCode)
                        .sorted(java.util.Comparator.comparingInt(neighbours::indexOf))
                        .forEach(bin -> add(places, bin, "NEIGHBOUR_BIN", last));
            }
        }
        if (carton.warehouseId() != null && stillReceiving(carton)) {
            warehouses.areasOf(carton.warehouseId(), StorageLocationView.AreaType.RECEIVING)
                    .forEach(area -> add(places, area.locationCode(), "RECEIVING_AREA", null));
        }
        return List.copyOf(places.values());
    }

    private void add(Map<String, Place> places, String code, String why, String fact) {
        places.putIfAbsent(code, new Place(code, why, messages.get("assistant.carton.where." + why, why,
                fact == null ? "" : fact)));
    }

    /** Being received, or its newest ledger line is its receipt: nothing has moved it since it came in. */
    private static boolean stillReceiving(CartonHistoryQueries.CartonHistory carton) {
        return "RECEIVING".equals(carton.status()) || (!carton.ledger().isEmpty()
                && "RECEIPT".equals(carton.ledger().getFirst().movementType()));
    }

    /**
     * The staging area of an open pick task that sends its cartons there; empty when it does not, or when the caller
     * may not read the task by fulfillment's own rules (its refusal is not an error here: the place is left out).
     */
    private java.util.Optional<String> stagingAreaOf(UUID taskId, CurrentUser user) {
        try {
            FulfillmentTask task = fulfillment.findTask(taskId, user);
            return task.destination() == PickDestination.STAGING
                    ? java.util.Optional.ofNullable(task.stagingLocationCode()) : java.util.Optional.empty();
        } catch (RuntimeException refused) {
            log.debug("Assistant carton investigation: pick task {} not read for its staging area: {}", taskId,
                    refused.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * The bins beside {@code binCode} on the warehouse map, by code alone: the next and previous bin on the same level
     * (by the bin part's number, or its single row letter), and the same bin one level up and down. Unknown codes are
     * dropped by the caller; an area code has no neighbours.
     */
    static List<String> neighbours(String binCode) {
        Matcher bin = BIN.matcher(binCode.toUpperCase(java.util.Locale.ROOT));
        if (!bin.matches()) {
            return List.of();
        }
        String prefix = bin.group(1) + "-" + bin.group(2) + "-";
        int level = Integer.parseInt(bin.group(3));
        String part = bin.group(4);
        Set<String> found = new LinkedHashSet<>();
        Matcher parts = BIN_PART.matcher(part);
        if (parts.matches()) {
            String letters = parts.group(1);
            String digits = parts.group(2);
            if (!digits.isEmpty()) {
                int number = Integer.parseInt(digits);
                for (int step : new int[] {-1, 1}) {
                    if (number + step >= 1) {
                        found.add(prefix + level + "-" + letters + pad(number + step, digits.length()));
                    }
                }
            }
            if (letters.length() == 1) {
                for (int step : new int[] {-1, 1}) {
                    char letter = (char) (letters.charAt(0) + step);
                    if (letter >= 'A' && letter <= 'Z') {
                        found.add(prefix + level + "-" + letter + digits);
                    }
                }
            }
        }
        for (int step : new int[] {-1, 1}) {
            if (level + step >= 1 && level + step <= 99) {
                found.add(prefix + (level + step) + "-" + part);
            }
        }
        found.remove(binCode);
        return List.copyOf(found);
    }

    private static String pad(int number, int width) {
        String text = Integer.toString(number);
        return text.length() >= width ? text : "0".repeat(width - text.length()) + text;
    }

    private static boolean can(CurrentUser user, String resource) {
        return user != null && user.can(resource, Action.READ);
    }

    /**
     * @param status           the carton's: RECEIVING, IN_STOCK, CONSUMED, LOST or VOID
     * @param stockStatus      its stock's while in stock (AVAILABLE, INBOUND, QUARANTINE, DAMAGED...); null otherwise
     * @param timeline         newest first, at most {@value #TIMELINE}; empty when {@code timelineWithheld} says why
     * @param timelineTotal    every ledger line of it the caller may see; null when the timeline is withheld
     * @param timelineWithheld null, {@code BLIND_COUNT} (a count holds the carton and the caller may not see the
     *                         books) or {@code NO_PERMISSION} (the caller may not read the stock ledger)
     */
    public record Investigation(String lpn, String sku, String status, String stockStatus, String locationCode,
                                String warehouseCode, String lotNumber, Instant receivedAt,
                                List<TimelineEntry> timeline, Long timelineTotal, String timelineWithheld,
                                List<OpenWork> openWork, List<Place> whereToLook) {
    }

    /**
     * @param reference what the line was written for (PICK_LINE, GOODS_RECEIPT...); null for none
     * @param by        who did it, by display name; null for the system
     */
    public record TimelineEntry(Instant at, String movementType, String from, String to, String reference,
                                String by) {
    }

    /** @param kind PICK_LINE, CARTON_CHECK, PUTAWAY_TASK, COUNT_LINE, ADJUSTMENT or RETURN_RECEIPT */
    public record OpenWork(String kind, String reference, String status, String href) {
    }

    /**
     * @param why    LAST_RECORDED, PUTAWAY_TARGET, STAGING_AREA, QUARANTINE, NEIGHBOUR_BIN or RECEIVING_AREA
     * @param detail why, in the user's language
     */
    public record Place(String locationCode, String why, String detail) {
    }
}
