package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses the cartons of a label print and reads what each label says (SCRUM-473): the live cartons of a receipt
 * line, or the cartons named by LPN to reprint.
 *
 * <p>The label has no item name: it lives in product's schema, which inventory may not read, and the SKU plus
 * the LPN scan say the rest.</p>
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

    public StockPackageLabels(StockPackageRepository stockPackages, UomConversionRepository units) {
        this.stockPackages = stockPackages;
        this.units = units;
    }

    /**
     * @param receiptLineId print every live carton of this goods receipt line; or null
     * @param lpns          reprint these cartons; or null. Exactly one of the two is given, which the request
     *                      validates; neither gives {@code STOCK_PACKAGE_LABELS_EMPTY}
     * @return the labels in LPN order
     * @throws BusinessException {@code STOCK_PACKAGE_LABELS_EMPTY} (nothing chosen, or a line with no live carton),
     *         {@code STOCK_PACKAGE_LABELS_TOO_MANY}, {@code STOCK_PACKAGE_NOT_FOUND} (an unknown LPN),
     *         {@code INVALID_STOCK_PACKAGE_TRANSITION} (a VOID, CONSUMED or LOST carton has no label to print),
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
        return cartons.stream().sorted(Comparator.comparing(StockPackage::lpn)).map(carton -> {
            UomConversion unit = unitsById.get(carton.uomConversionId());
            return new StockPackageLabel(carton.lpn(), unit.sku(), carton.receivedQty(), carton.nominalQty(), unit.unitOfMeasure());
        }).toList();
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
     * status included, which would tell an outsider that the carton exists and what became of it.
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
        for (String lpn : distinct) {
            StockPackage carton = found.get(lpn);
            if (carton == null) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + lpn);
            }
            WarehouseScope.requireLocation(carton.location().code());
            if (carton.status().isClosed()) {
                throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                        "%s is %s; a closed carton has no label to print".formatted(lpn, carton.status()));
            }
        }
        return List.copyOf(found.values());
    }

    private static BusinessException tooMany(int count) {
        return new BusinessException(ErrorCode.STOCK_PACKAGE_LABELS_TOO_MANY,
                "%d cartons; at most %d labels at a time".formatted(count, MAX_LABELS));
    }
}
