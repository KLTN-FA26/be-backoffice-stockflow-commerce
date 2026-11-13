package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.StorageException;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TemplateModel3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a customer may design on a published product (kltn-docs 13 BR-14, 12 BR-02; the storefront's P2): the active
 * customization template of each ACTIVE variant, with the print areas computed from its model. A product that is not
 * published reads as absent, like its detail page; one that cannot be customized is refused, so the studio is never
 * opened on a product it cannot print on.
 *
 * <p>Each template also says whether its variant has a carton specification (FE request 2026-11-13 A-2), so the studio
 * can warn when the design is opened rather than when it is submitted ({@code CARTON_SPEC_MISSING}); the units of every
 * variant are read in one call. And it carries a signed link to the template's {@code .glb} (B-3), valid
 * {@link FileTransfers#LONGEST_LINK} so the studio can cache the model for a working session. The object-storage bucket
 * (AWS S3 in the deployment) must allow a CORS {@code GET} from the storefront's origin, the same rule design images
 * already need; {@code infra/aws} configures it for the frontend origins.</p>
 */
@Service
public class CatalogCustomizationService {

    private static final Logger log = LoggerFactory.getLogger(CatalogCustomizationService.class);

    private final ListingRepository listings;
    private final ProductPublication products;
    private final PrintConfigs printConfigs;
    private final UomConversionService units;
    private final FileTransfers files;

    public CatalogCustomizationService(ListingRepository listings, ProductPublication products,
                                       PrintConfigs printConfigs, UomConversionService units, FileTransfers files) {
        this.listings = listings;
        this.products = products;
        this.printConfigs = printConfigs;
        this.units = units;
        this.files = files;
    }

    /** A published product's templates, by variant position. */
    public record Customization(UUID productId, ProductKind kind, List<Template> templates) {
    }

    /**
     * One variant's active template, whether the variant has a carton specification, and a link to its {@code .glb}
     * (null when the template has none, or this storage provider signs no browser links).
     */
    public record Template(PrintConfig config, boolean hasCartonSpec, String modelUrl) {
    }

    @Transactional(readOnly = true)
    public Customization customization(String slug) {
        var listing = listings.bySlug(slug).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var product = products.read(listing.productId());
        if (product.status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (product.kind() != ProductKind.CUSTOMIZABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_CUSTOMIZABLE,
                    "This product cannot be designed on");
        }
        List<PrintConfig> configs = printConfigs.activeOfProduct(listing.productId());
        Map<String, List<UomConversion>> unitsBySku = configs.isEmpty() ? Map.of()
                : units.conversionsOf(configs.stream().map(config -> new Sku(config.sku())).distinct().toList());
        return new Customization(listing.productId(), product.kind(), configs.stream()
                .map(config -> new Template(config,
                        unitsBySku.getOrDefault(config.sku(), List.of()).stream()
                                .anyMatch(UomConversion::cartonCandidate),
                        modelUrl(config.model3d())))
                .toList());
    }

    private String modelUrl(TemplateModel3d model3d) {
        if (!model3d.hasFile()) {
            return null;
        }
        try {
            return files.sessionViewLink(model3d.fileKey()).url();
        } catch (StorageException unsignable) {
            log.warn("No browser link for the 3D model {}: {}", model3d.fileKey(), unsignable.getMessage());
            return null;
        }
    }
}
