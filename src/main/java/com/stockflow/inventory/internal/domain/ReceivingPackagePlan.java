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
 * which known cartons are recounted, how many new ones to label, and which still-RECEIVING ones are no longer wanted.
 *
 * <p>It only decides. Applying the plan (counting, labelling, voiding) is the service's job, so the rules of
 * reconciliation can be tested without a repository.</p>
 *
 * <p><b>Only RECEIVING cartons are live.</b> The repository hands back every carton the line ever had, VOID ones
 * included; a VOID carton that is not named again is history, not something to void a second time
 * ({@link StockPackage#cancel} refuses on it), while naming one is an error: it can no longer be recounted.</p>
 */
public record ReceivingPackagePlan(List<Recount> recounts, List<Integer> newCounts, List<StockPackage> voided) {

    /** A carton the operator named again, with the quantity now counted into it. */
    public record Recount(StockPackage carton, int quantity) {}

    /** One entry of the operator's list: an existing carton by LPN, or {@code null} for a new one. */
    public record Wanted(String lpn, int quantity) {}

    public ReceivingPackagePlan {
        recounts = List.copyOf(recounts);
        newCounts = List.copyOf(newCounts);
        voided = List.copyOf(voided);
    }

    public static ReceivingPackagePlan of(List<StockPackage> current, List<Wanted> wanted) {
        Map<String, StockPackage> byLpn = current.stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        Set<String> named = new HashSet<>();
        List<Recount> recounts = new ArrayList<>();
        List<Integer> newCounts = new ArrayList<>();

        for (Wanted entry : wanted) {
            if (entry.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A carton holds at least 1 unit");
            }
            if (entry.lpn() == null) {
                newCounts.add(entry.quantity());
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
            recounts.add(new Recount(carton, entry.quantity()));
        }

        List<StockPackage> voided = current.stream()
                .filter(carton -> carton.status() == StockPackageStatus.RECEIVING)
                .filter(carton -> !named.contains(carton.lpn()))
                .toList();
        return new ReceivingPackagePlan(recounts, newCounts, voided);
    }
}
