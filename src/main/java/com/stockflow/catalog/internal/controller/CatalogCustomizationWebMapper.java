package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.CatalogCustomizationResponse;
import com.stockflow.catalog.internal.service.CatalogCustomizationService;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import org.springframework.stereotype.Component;

/** A product's customization templates to the storefront's P2 shape. */
@Component
class CatalogCustomizationWebMapper {

    CatalogCustomizationResponse toResponse(CatalogCustomizationService.Customization customization) {
        return new CatalogCustomizationResponse(customization.productId(), customization.kind().name(),
                customization.templates().stream().map(CatalogCustomizationWebMapper::toTemplate).toList());
    }

    private static CatalogCustomizationResponse.Template toTemplate(CatalogCustomizationService.Template template) {
        PrintConfig config = template.config();
        CatalogCustomizationResponse.Model3d model3d = template.modelUrl() == null ? null
                : new CatalogCustomizationResponse.Model3d(template.modelUrl(), config.model3d().surfaces().stream()
                        .map(s -> new CatalogCustomizationResponse.Surface(s.printAreaCode(), s.meshName()))
                        .toList());
        return new CatalogCustomizationResponse.Template(config.templateId(), config.code(), config.name(),
                config.sku(), config.minDpi(), config.colorMode(), config.allowedFileFormats(), config.maxFileSizeMb(),
                config.maxColors(), config.allowedFontCodes(), toModel(config.model()),
                config.printAreas().stream().map(CatalogCustomizationWebMapper::toArea).toList(),
                template.hasCartonSpec(), model3d);
    }

    private static CatalogCustomizationResponse.Model toModel(ProductModel model) {
        return switch (model) {
            case CupModel cup -> new CatalogCustomizationResponse.Model(cup.kind(), cup.topDiameterMm(),
                    cup.bottomDiameterMm(), cup.heightMm(), cup.rimMm(), cup.seamMm(), cup.bandTopOffsetMm(),
                    cup.bandBottomOffsetMm(), null, null, null, null, cup.material());
            case BagModel bag -> new CatalogCustomizationResponse.Model(bag.kind(), null, null, bag.heightMm(), null,
                    null, null, null, bag.widthMm(), bag.gussetMm(), bag.topFoldMm(), bag.handle(), bag.material());
        };
    }

    private static CatalogCustomizationResponse.Area toArea(PrintArea area) {
        CatalogCustomizationResponse.Shape shape = switch (area.shape()) {
            case PrintAreaShape.Rect rect -> new CatalogCustomizationResponse.Shape(rect.type(), null, null, null, null);
            case PrintAreaShape.AnnularSector s -> new CatalogCustomizationResponse.Shape(s.type(), s.innerRadiusMm(),
                    s.outerRadiusMm(), s.sweepRad(), s.seamMm());
        };
        return new CatalogCustomizationResponse.Area(area.code(), area.name(), area.xMm(), area.yMm(), area.widthMm(),
                area.heightMm(), area.safeMarginMm(), area.bleedMm(), shape,
                new CatalogCustomizationResponse.UvMapping(area.meshPart()));
    }
}
