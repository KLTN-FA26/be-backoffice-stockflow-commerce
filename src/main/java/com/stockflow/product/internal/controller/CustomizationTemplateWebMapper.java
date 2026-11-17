package com.stockflow.product.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import com.stockflow.product.api.TemplateModel3d;
import com.stockflow.product.internal.controller.dto.CustomizationTemplateRequest;
import com.stockflow.product.internal.controller.dto.CustomizationTemplateResponse;
import com.stockflow.product.internal.controller.dto.ProductModelRequest;
import com.stockflow.product.internal.domain.PrintConfigRules;
import com.stockflow.product.internal.service.PrintConfigService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Customization template requests to service drafts, and templates to responses. */
@Component
class CustomizationTemplateWebMapper {

    PrintConfigService.Draft toDraft(CustomizationTemplateRequest request) {
        var limits = new PrintConfigRules.Limits(
                request.minDpi() == null ? PrintConfigRules.DEFAULT_MIN_DPI : request.minDpi(),
                request.colorMode() == null ? "CMYK" : request.colorMode(),
                request.allowedFileFormats(), request.maxFileSizeMb(), request.maxColors(),
                request.allowedFontCodes());
        var areas = request.printAreas().stream()
                .map(a -> new PrintConfigRules.AreaSpec(a.code(), a.name(), a.safeMarginMm(), a.bleedMm()))
                .toList();
        List<TemplateModel3d.Surface> surfaces = request.model3d() == null || request.model3d().surfaces() == null
                ? null
                : request.model3d().surfaces().stream()
                        .map(s -> s == null ? null : new TemplateModel3d.Surface(s.printAreaCode(), s.meshName()))
                        .toList();
        return new PrintConfigService.Draft(request.code(), request.name(), limits, toModel(request.model()), areas,
                surfaces);
    }

    CustomizationTemplateResponse toResponse(PrintConfig config) {
        return new CustomizationTemplateResponse(config.templateId(), config.productId(), config.variantId(),
                config.sku(), config.code(), config.name(), config.minDpi(), config.colorMode(),
                config.allowedFileFormats(), config.maxFileSizeMb(), config.maxColors(), config.allowedFontCodes(),
                config.active(), config.model() == null ? null : toResponse(config.model()),
                config.printAreas().stream().map(CustomizationTemplateWebMapper::toResponse).toList(),
                config.referenceFileName(), config.version(), toResponse(config.model3d()));
    }

    private static CustomizationTemplateResponse.Model3d toResponse(TemplateModel3d model3d) {
        return new CustomizationTemplateResponse.Model3d(model3d.hasFile(), model3d.fileName(), model3d.fileChecksum(),
                model3d.surfaces().stream()
                        .map(s -> new CustomizationTemplateResponse.Surface(s.printAreaCode(), s.meshName()))
                        .toList());
    }

    private static ProductModel toModel(ProductModelRequest m) {
        return switch (m.kind()) {
            case CupModel.KIND -> new CupModel(required(m.topDiameterMm(), "topDiameterMm"),
                    required(m.bottomDiameterMm(), "bottomDiameterMm"), required(m.heightMm(), "heightMm"),
                    orZero(m.rimMm()), orZero(m.seamMm()), orZero(m.bandTopOffsetMm()),
                    orZero(m.bandBottomOffsetMm()), m.material());
            case BagModel.KIND -> new BagModel(required(m.widthMm(), "widthMm"), required(m.gussetMm(), "gussetMm"),
                    required(m.heightMm(), "heightMm"), orZero(m.topFoldMm()), m.handle(), m.material());
            default -> throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID,
                    "The model kind is parametric-cup or parametric-bag");
        };
    }

    private static CustomizationTemplateResponse.Model toResponse(ProductModel model) {
        return switch (model) {
            case CupModel cup -> new CustomizationTemplateResponse.Model(cup.kind(), cup.topDiameterMm(),
                    cup.bottomDiameterMm(), cup.heightMm(), cup.rimMm(), cup.seamMm(), cup.bandTopOffsetMm(),
                    cup.bandBottomOffsetMm(), null, null, null, null, cup.material());
            case BagModel bag -> new CustomizationTemplateResponse.Model(bag.kind(), null, null, bag.heightMm(), null,
                    null, null, null, bag.widthMm(), bag.gussetMm(), bag.topFoldMm(), bag.handle(), bag.material());
        };
    }

    private static CustomizationTemplateResponse.Area toResponse(PrintArea area) {
        CustomizationTemplateResponse.Shape shape = switch (area.shape()) {
            case PrintAreaShape.Rect rect -> new CustomizationTemplateResponse.Shape(rect.type(), null, null, null,
                    null);
            case PrintAreaShape.AnnularSector s -> new CustomizationTemplateResponse.Shape(s.type(),
                    s.innerRadiusMm(), s.outerRadiusMm(), s.sweepRad(), s.seamMm());
        };
        return new CustomizationTemplateResponse.Area(area.code(), area.name(), area.xMm(), area.yMm(),
                area.widthMm(), area.heightMm(), area.safeMarginMm(), area.bleedMm(), shape,
                new CustomizationTemplateResponse.UvMapping(area.meshPart()));
    }

    private static double required(Double value, String field) {
        if (value == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, "The model needs " + field);
        }
        return value;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
