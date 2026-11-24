package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.domain.ProductTaxClass;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * WF-05 D-8 (decided 24/11): a product's VAT group, read and changed on its own - by the accountant, who meets a
 * product without one on an invoice draft (TAX_CLASS_UNKNOWN), and by the Sales Manager, who owns the catalogue. Its
 * own permission ({@code product-tax-class:UPDATE}), never {@code product-products:UPDATE}: that one edits a DRAFT's
 * whole content, and the accountant has no business there.
 *
 * <p>What changes is what is drawn up from now on - a quote, an invoice draft. Nothing drawn up before is touched: an
 * invoice draft keeps its rates until the accountant voids it and draws it up again, and the automatic issuing checks
 * it again first ({@code EInvoiceDrafts.recheckForAutomaticIssue}: TAX_CLASS_UNKNOWN, TAX_RATE_OUT_OF_DATE), so a draft
 * drawn up before the change is never sent as if it had not happened. A contract keeps the rate it fixed.</p>
 */
@Service
public class ProductTaxClassService {

    /** The most SKUs one read takes: an invoice's goods lines, never a catalogue. */
    public static final int MAX_SKUS = 100;

    private final ProductRepository products;
    private final ProductChangeLogRepository history;

    ProductTaxClassService(ProductRepository products, ProductChangeLogRepository history) {
        this.products = products;
        this.history = history;
    }

    /** The product of each SKU, with its class and version; a SKU that is no variant is left out. */
    @Transactional(readOnly = true)
    public List<ProductTaxClass> ofSkus(Collection<String> skus) {
        Set<String> asked = new LinkedHashSet<>();
        if (skus != null) {
            skus.stream().filter(sku -> sku != null && !sku.isBlank()).map(String::strip).forEach(asked::add);
        }
        if (asked.size() > MAX_SKUS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "At most %d SKUs at once".formatted(MAX_SKUS));
        }
        return asked.isEmpty() ? List.of() : products.taxClassesBySku(asked);
    }

    /**
     * Sets the product's class, under its row lock and at the version the caller read, and writes it to the product's
     * history with the class before and after and the reason given. The same class again changes nothing.
     *
     * @throws BusinessException {@code PRODUCT_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED} without a
     *         class or a reason of 1..500 characters
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    public ProductTaxClass change(UUID productId, TaxClass taxClass, String reason, long version) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > Product.MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reason of 1..%d characters is required".formatted(Product.MAX_REASON_LENGTH));
        }
        Product product = products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
        product.requireVersion(version);
        TaxClass before = product.taxClass();
        if (product.changeTaxClass(taxClass)) {
            products.save(product);
            history.record(productId, ProductChangeType.UPDATED, null, null, null,
                    "taxClass %s -> %s: %s".formatted(before == null ? "none" : before.name(), taxClass.name(), why),
                    List.of("taxClass"));
        }
        return products.taxClassOf(new ProductId(productId)).orElseThrow();
    }
}
