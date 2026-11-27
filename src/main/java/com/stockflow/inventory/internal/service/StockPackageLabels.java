package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.inventory.spi.PickedNotHandedOver;
import com.stockflow.inventory.spi.ReceiptLineLots;
import com.stockflow.inventory.spi.SkuNames;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses the cartons of a label print and reads what each label says (SCRUM-473): the live cartons of a receipt
 * line, or the cartons named by LPN to reprint.
 *
 * <p>The label carries the item's name and the carton's lot for the eye (printed goods of two customers look alike).
 * Neither is inventory's to read: the name is asked of {@code product} ({@link SkuNames}), and the lot is the carton's
 * stock row's, or - for a carton still being counted, which has no row yet - its receipt line's, asked of
 * {@code procurement} ({@link ReceiptLineLots}). Both are {@code inventory :: spi} questions, since both modules
 * depend on inventory; one question each per sheet. Absent in a module test, the label prints without that field.</p>
 *
 * <p>A carton that left stock is closed and has no label to print, with one exception (kltn-docs 03 §4.1 step 4, 08 §6
 * edge case): a carton picked for an order and still at the warehouse - waiting at the dispatch area for its check, its
 * shipping label or its handover - is reprinted by its LPN when its LPN label tears or goes missing, so it can be
 * scanned at the check station. Which consumed cartons those are, fulfillment answers ({@code inventory :: spi}).</p>
 *
 * <p>A read-only transaction that ends before any PDF is drawn, so no connection is held while 500 labels render.
 * Every query here is one per kind of fact, never one per carton.</p>
 */
@Service
public class StockPackageLabels {

    /** What one request renders; a larger print is made in parts. */
    public static final int MAX_LABELS = 500;

    private final StockPackageRepository stockPackages;
    private final UomConversionRepository units;
    /** Fulfillment's answer to which consumed cartons are picked and not handed over yet; absent in a module test. */
    private final ObjectProvider<PickedNotHandedOver> picked;
    /** Product's answer to what each SKU is called; absent in a module test. */
    private final ObjectProvider<SkuNames> names;
    /** Procurement's answer to which lot a receipt line is in; absent in a module test. */
    private final ObjectProvider<ReceiptLineLots> receiptLots;

    public StockPackageLabels(StockPackageRepository stockPackages, UomConversionRepository units,
                              ObjectProvider<PickedNotHandedOver> picked, ObjectProvider<SkuNames> names,
                              ObjectProvider<ReceiptLineLots> receiptLots) {
        this.stockPackages = stockPackages;
        this.units = units;
        this.picked = picked;
        this.names = names;
        this.receiptLots = receiptLots;
    }

    /**
     * @param receiptLineId print every live carton of this goods receipt line; or null
     * @param lpns          reprint these cartons; or null. Exactly one of the two is given, which the request
     *                      validates; neither gives {@code STOCK_PACKAGE_LABELS_EMPTY}
     * @return the labels in LPN order
     * @throws BusinessException {@code STOCK_PACKAGE_LABELS_EMPTY} (nothing chosen, or a line with no live carton),
     *         {@code STOCK_PACKAGE_LABELS_TOO_MANY}, {@code STOCK_PACKAGE_NOT_FOUND} (an unknown LPN),
     *         {@code INVALID_STOCK_PACKAGE_TRANSITION} (a VOID or LOST carton has no label to print, nor a CONSUMED one
     *         unless it is picked for an order and not handed over yet),
     *         and 403 for a carton in a warehouse the caller is not assigned to
     */
    @Transactional(readOnly = true)
    public List<StockPackageLabel> sheetFor(UUID receiptLineId, List<String> lpns) {
        List<StockPackage> cartons = receiptLineId != null ? liveCartonsOf(receiptLineId) : named(lpns);
        if (cartons.isEmpty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_LABELS_EMPTY, "Nothing to print");
        }
        if (cartons.size() > MAX_LABELS) {
            throw tooMany(cartons.size());
        }

        Map<UUID, UomConversion> unitsById = units.findAll(cartons.stream().map(StockPackage::uomConversionId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(UomConversion::id, Function.identity()));
        // A carton's SKU is its own item's: its carton row is its variant's, shared by every SKU of the variant (Q1).
        Map<String, String> namesBySku = namesOf(cartons.stream().map(carton -> carton.sku().code()).toList());
        Map<StockPackageId, String> lots = lotsOf(cartons);
        return cartons.stream().sorted(Comparator.comparing(StockPackage::lpn)).map(carton -> {
            UomConversion unit = unitsById.get(carton.uomConversionId());
            String sku = carton.sku().code();
            return new StockPackageLabel(carton.lpn(), sku, namesBySku.get(sku), lots.get(carton.id()),
                    carton.counted(), carton.nominalQty(), unit.unitOfMeasure());
        }).toList();
    }

    private Map<String, String> namesOf(Collection<String> skus) {
        SkuNames catalogue = names.getIfAvailable();
        return catalogue == null ? Map.of() : catalogue.namesOf(skus.stream().distinct().toList());
    }

    /**
     * Each carton's lot: its own, else - a carton still being counted has none yet - its receipt line's. A carton
     * keeps the lot it went into stock with, which is why its own comes first.
     */
    private Map<StockPackageId, String> lotsOf(List<StockPackage> cartons) {
        Map<StockPackageId, String> lots = new HashMap<>();
        cartons.stream().filter(carton -> carton.lotNumber() != null)
                .forEach(carton -> lots.put(carton.id(), carton.lotNumber()));
        List<StockPackage> withoutRow = cartons.stream()
                .filter(carton -> !lots.containsKey(carton.id()) && carton.goodsReceiptLineId() != null).toList();
        ReceiptLineLots receiving = withoutRow.isEmpty() ? null : receiptLots.getIfAvailable();
        if (receiving != null) {
            Map<UUID, String> byLine = receiving.lotsOf(withoutRow.stream().map(StockPackage::goodsReceiptLineId)
                    .distinct().toList());
            withoutRow.forEach(carton -> {
                String lot = byLine.get(carton.goodsReceiptLineId());
                if (lot != null) {
                    lots.put(carton.id(), lot);
                }
            });
        }
        return lots;
    }

    private List<StockPackage> liveCartonsOf(UUID receiptLineId) {
        List<StockPackage> live = stockPackages.findByReceiptLines(List.of(receiptLineId)).stream()
                .filter(carton -> !carton.status().isClosed()).toList();
        live.forEach(carton -> WarehouseScope.requireLocation(carton.location().code()));
        return live;
    }

    /**
     * The named cartons, each checked in the order named: an unknown LPN is {@code STOCK_PACKAGE_NOT_FOUND}; a
     * known one in a warehouse the caller is not assigned to is 403 before anything else is said about it - its
     * status included, which would tell an outsider that the carton exists and what became of it. A CONSUMED carton is
     * printed only while it is picked for an order and not handed over yet (03 §4.1 step 4).
     */
    private List<StockPackage> named(List<String> lpns) {
        if (lpns == null || lpns.isEmpty()) {
            return List.of();
        }
        List<String> distinct = lpns.stream().map(StockPackageView::normalLpn).filter(Objects::nonNull).distinct()
                .toList();
        // Refused before the read: 501 names is a mistake, not something to look up.
        if (distinct.size() > MAX_LABELS) {
            throw tooMany(distinct.size());
        }
        Map<String, StockPackage> found = stockPackages.findByLpns(distinct).stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        Set<String> atDispatch = pickedNotHandedOver(found.values());
        for (String lpn : distinct) {
            StockPackage carton = found.get(lpn);
            if (carton == null) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + lpn);
            }
            WarehouseScope.requireLocation(carton.location().code());
            if (carton.status().isClosed() && !atDispatch.contains(lpn)) {
                throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                        "%s is %s; a closed carton has no label to print".formatted(lpn, carton.status()));
            }
        }
        return List.copyOf(found.values());
    }

    /** Of these cartons, the CONSUMED ones picked for an order and not handed over yet; one question for them all. */
    private Set<String> pickedNotHandedOver(Collection<StockPackage> cartons) {
        List<String> consumed = cartons.stream().filter(carton -> carton.status() == StockPackageStatus.CONSUMED)
                .map(StockPackage::lpn).toList();
        PickedNotHandedOver station = consumed.isEmpty() ? null : picked.getIfAvailable();
        return station == null ? Set.of() : station.amongst(consumed);
    }

    private static BusinessException tooMany(int count) {
        return new BusinessException(ErrorCode.STOCK_PACKAGE_LABELS_TOO_MANY,
                "%d cartons; at most %d labels at a time".formatted(count, MAX_LABELS));
    }
}
