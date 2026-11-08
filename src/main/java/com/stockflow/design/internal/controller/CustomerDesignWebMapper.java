package com.stockflow.design.internal.controller;

import com.stockflow.design.internal.controller.dto.DesignDocumentResponse;
import com.stockflow.design.internal.controller.dto.DesignTemplateResponse;
import com.stockflow.design.internal.service.CustomerDesignService;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import org.springframework.stereotype.Component;

/**
 * A customer's layout and template to the storefront's P3 and P2 shapes. The template mapping is the catalog's
 * ({@code CatalogCustomizationWebMapper}) again: the two modules may not share a web type, and the studio reads both.
 */
@Component
class CustomerDesignWebMapper {

    DesignDocumentResponse toResponse(CustomerDesignService.DocumentView view) {
        return new DesignDocumentResponse(view.designId(), view.version(), view.document(), view.checksum(),
                view.updatedAt());
    }

    DesignTemplateResponse toResponse(PrintConfig config) {
        return new DesignTemplateResponse(config.templateId(), config.code(), config.name(), config.sku(),
                config.minDpi(), config.colorMode(), config.allowedFileFormats(), config.maxFileSizeMb(),
                config.maxColors(), config.allowedFontCodes(), config.active(), toModel(config.model()),
                config.printAreas().stream().map(CustomerDesignWebMapper::toArea).toList());
    }

    private static DesignTemplateResponse.Model toModel(ProductModel model) {
        return switch (model) {
            case CupModel cup -> new DesignTemplateResponse.Model(cup.kind(), cup.topDiameterMm(),
                    cup.bottomDiameterMm(), cup.heightMm(), cup.rimMm(), cup.seamMm(), cup.bandTopOffsetMm(),
                    cup.bandBottomOffsetMm(), null, null, null, null, cup.material());
            case BagModel bag -> new DesignTemplateResponse.Model(bag.kind(), null, null, bag.heightMm(), null, null,
                    null, null, bag.widthMm(), bag.gussetMm(), bag.topFoldMm(), bag.handle(), bag.material());
        };
    }

    private static DesignTemplateResponse.Area toArea(PrintArea area) {
        DesignTemplateResponse.Shape shape = switch (area.shape()) {
            case PrintAreaShape.Rect rect -> new DesignTemplateResponse.Shape(rect.type(), null, null, null, null);
            case PrintAreaShape.AnnularSector s -> new DesignTemplateResponse.Shape(s.type(), s.innerRadiusMm(),
                    s.outerRadiusMm(), s.sweepRad(), s.seamMm());
        };
        return new DesignTemplateResponse.Area(area.code(), area.name(), area.xMm(), area.yMm(), area.widthMm(),
                area.heightMm(), area.safeMarginMm(), area.bleedMm(), shape,
                new DesignTemplateResponse.UvMapping(area.meshPart()));
    }
}
