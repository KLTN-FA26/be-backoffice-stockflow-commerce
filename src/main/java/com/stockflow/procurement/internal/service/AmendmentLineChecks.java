package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the supplier's catalog and the product module say about the lines an amendment changes (kltn-docs 02 BR-01,
 * BR-08).
 *
 * <p>{@code ProcurementServiceImpl.checkLines} is not reused, although it checks the same rules: it resolves SKUs,
 * sizes packing units and takes the catalog price for a line, none of which an amendment does (the SKU, the unit
 * and the price are the line's own, agreed when the order was approved), and it refuses every line whose SKU is no
 * longer purchasable, where an amendment refuses only an <em>increase</em>. What is shared is the rules themselves,
 * through {@link SupplierItemRepository#listedForShare}, {@link SupplierItem#orderQuantityViolations} and
 * {@link ProductService#purchasability}, so no rule is copied.</p>
 *
 * <p>Only a line whose quantity changes meets the supplier's minimum and pack multiple again: repricing a line, or
 * removing one, says nothing new about how much is bought. Lowering a line of a discontinued SKU stays possible
 * because goods already ordered are still received whatever happened to the product since; raising it would order
 * new goods of a product that may no longer be bought.</p>
 */
@Component
class AmendmentLineChecks {

    private final SupplierItemRepository supplierItems;
    private final ProductService products;

    AmendmentLineChecks(SupplierItemRepository supplierItems, ProductService products) {
        this.supplierItems = supplierItems;
        this.products = products;
    }

    /**
     * Refuses the proposal, naming every line at fault in one exception; {@code i} in a field path
     * ({@code lines[i].quantityOrdered}, {@code lines[i].sku}) is the line's index in the request.
     *
     * @throws BusinessException {@code SUPPLIER_ITEM_NOT_LISTED} when a changed line's SKU is no longer in the
     *                           supplier's catalog, {@code SUPPLIER_TERMS_VIOLATED} for a quantity under the minimum
     *                           or off the pack multiple, {@code SKU_NOT_PURCHASABLE} for an increased line whose SKU
     *                           is no longer active for purchasing
     */
    void require(PurchaseOrder order, PurchaseOrder.ProposedAmendment proposal) {
        List<PurchaseOrder.ProposedLine> kept = proposal.kept();
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).quantityChanged()) {
                changed.add(i);
            }
        }
        if (changed.isEmpty()) {
            return;
        }

        // One read for every changed line, FOR SHARE, so a concurrent catalog delete waits for this transaction.
        Map<UUID, SupplierItem> listed = supplierItems.listedForShare(order.supplierId(),
                changed.stream().map(i -> kept.get(i).after().inventoryItemId()).toList());
        List<FieldError> notListed = new ArrayList<>();
        List<FieldError> terms = new ArrayList<>();
        for (int i : changed) {
            var after = kept.get(i).after();
            SupplierItem item = listed.get(after.inventoryItemId());
            if (item == null) {
                notListed.add(new FieldError("lines[" + i + "].sku",
                        "SKU " + after.sku().code() + " is not listed by this supplier",
                        ErrorCode.SUPPLIER_ITEM_NOT_LISTED.name()));
                continue;
            }
            int quantity = after.quantityOrdered();
            for (var violation : item.orderQuantityViolations(quantity)) {
                ErrorCode code = switch (violation) {
                    case BELOW_MOQ -> ErrorCode.PO_LINE_BELOW_MOQ;
                    case NOT_PACK_MULTIPLE -> ErrorCode.PO_LINE_NOT_PACK_MULTIPLE;
                };
                terms.add(new FieldError("lines[" + i + "].quantityOrdered", "Quantity " + quantity
                        + " does not meet the supplier's minimum " + item.terms().moq() + " in packs of "
                        + item.terms().packSize(), code.name()));
            }
        }
        if (!notListed.isEmpty() || !terms.isEmpty()) {
            List<FieldError> errors = new ArrayList<>(notListed);
            errors.addAll(terms);
            // Not listed is the graver fault: no quantity is acceptable until the supplier lists the SKU again.
            ErrorCode code = notListed.isEmpty() ? ErrorCode.SUPPLIER_TERMS_VIOLATED : ErrorCode.SUPPLIER_ITEM_NOT_LISTED;
            throw new BusinessException(code, errors.size() + " fault(s) in the changed lines against the supplier's"
                    + " catalog terms", errors);
        }

        requirePurchasableWhenIncreased(kept);
    }

    /** Asked only of the increased lines, and in one call for all of them. */
    private void requirePurchasableWhenIncreased(List<PurchaseOrder.ProposedLine> kept) {
        List<Integer> increased = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).quantityIncreased()) {
                increased.add(i);
            }
        }
        if (increased.isEmpty()) {
            return;
        }
        List<String> skus = increased.stream().map(i -> kept.get(i).after().sku().code()).toList();
        Map<String, Purchasability> verdicts = products.purchasability(skus);
        List<FieldError> errors = new ArrayList<>();
        for (int n = 0; n < increased.size(); n++) {
            Purchasability verdict = verdicts.get(skus.get(n));
            if (verdict != null && !verdict.isPurchasable()) {
                errors.add(new FieldError("lines[" + increased.get(n) + "].sku",
                        "SKU " + skus.get(n) + " is not active for purchasing", verdict.name()));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_NOT_PURCHASABLE,
                    errors.size() + " line(s) hold a SKU that is not active for purchasing; do not increase them",
                    errors);
        }
    }
}
