package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the operator now says is on a goods-receipt line's pallet, carton by carton (SCRUM-473), while the receipt is
 * still a draft. The whole list each time: a carton named by its LPN is recounted, an entry without one is a new
 * carton to label, and a still-receiving carton of the line left out is voided. Entries naming LPNs converge when
 * repeated; entries without one do not - a retry labels a new set and voids the first, whose labels may already be
 * printed. A client retrying a count it is not sure went through sends the same {@code Idempotency-Key}, which
 * answers the first count again instead of running it twice.
 *
 * @param receiptLineId   the goods-receipt line the cartons arrived on
 * @param uomConversionId the packing unit every carton of the line is in; null only when {@code packages} is empty,
 *                        which voids every carton the line still has
 * @param locationCode    the receiving area the line's goods were set down in; a recounted carton follows the line
 *                        there
 * @param actorId         who counted, for the audit trail
 */
public record CountReceivingPackagesCommand(
        UUID receiptLineId,
        Sku sku,
        UUID uomConversionId,
        String locationCode,
        List<PackageCount> packages,
        UUID actorId
) {

    /** One carton: an existing one by its LPN, in either case ({@link StockPackageView#normalLpn}); null for a new one. */
    public record PackageCount(String lpn, int quantity) {

        public PackageCount {
            lpn = StockPackageView.normalLpn(lpn);
        }
    }

    public CountReceivingPackagesCommand {
        Objects.requireNonNull(receiptLineId, "receiptLineId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(locationCode, "locationCode");
        packages = packages == null ? List.of() : List.copyOf(packages);
    }
}
