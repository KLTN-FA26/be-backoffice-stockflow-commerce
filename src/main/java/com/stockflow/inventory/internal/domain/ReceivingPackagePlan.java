package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a goods-receipt line's cartons must become to match what the operator now says is on the pallet (SCRUM-473):
 * which known cartons are counted again, how many new ones to label, and which still-RECEIVING ones are no longer
 * wanted.
 *
 * <p>It only decides. Applying the plan (counting, labelling, voiding) is the service's job, so the rules of
 * reconciliation can be tested without a repository. No quantities: every carton holds the SKU's standard count
 * (kltn-docs 03 BR-13), which {@link StockPackage#count} assigns, so the operator's list is only which cartons are
 * there.</p>
 *
 * <p><b>Only RECEIVING cartons are live.</b> The repository hands back every carton the line ever had, VOID ones
 * included; a VOID carton that is not named again is history, not something to void a second time
 * ({@link StockPackage#cancel} refuses on it), while naming one is an error: it can no longer be recounted.</p>
 *
 * @param recounts   the known cartons named again
 * @param newCartons how many cartons to label
 * @param voided     the RECEIVING cartons left out
 */
public record ReceivingPackagePlan(List<StockPackage> recounts, int newCartons, List<StockPackage> voided) {

    /** One entry of the operator's list: an existing carton by LPN, or {@code null} for a new one. */
    public record Wanted(String lpn) {}

    public ReceivingPackagePlan {
        recounts = List.copyOf(recounts);
        voided = List.copyOf(voided);
    }

    public static ReceivingPackagePlan of(List<StockPackage> current, List<Wanted> wanted) {
        Map<String, StockPackage> byLpn = current.stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        Set<String> named = new HashSet<>();
        List<StockPackage> recounts = new ArrayList<>();
        int newCartons = 0;

        for (Wanted entry : wanted) {
            if (entry.lpn() == null) {
                newCartons++;
                continue;
            }
            if (!named.add(entry.lpn())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "%s is listed twice".formatted(entry.lpn()));
            }
            StockPackage carton = byLpn.get(entry.lpn());
            if (carton == null) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND,
                        "%s is not a carton of this receipt line".formatted(entry.lpn()));
            }
            if (carton.status() != StockPackageStatus.RECEIVING) {
                throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                        "%s is %s and cannot be recounted".formatted(carton.lpn(), carton.status()));
            }
            recounts.add(carton);
        }

        List<StockPackage> voided = current.stream()
                .filter(carton -> carton.status() == StockPackageStatus.RECEIVING)
                .filter(carton -> !named.contains(carton.lpn()))
                .toList();
        return new ReceivingPackagePlan(recounts, newCartons, voided);
    }
}
