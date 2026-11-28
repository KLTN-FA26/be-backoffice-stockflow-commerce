package com.stockflow.product.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.ProductModel;
import com.stockflow.product.api.TemplateModel3d;
import com.stockflow.product.internal.entity.CustomizationTemplateJpaEntity;
import com.stockflow.product.internal.domain.CupGeometry;
import com.stockflow.product.internal.entity.PrintAreaJpaEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Customization template rows to {@link PrintConfig} and back. The model and the shapes are stored as the JSON objects
 * the storefront reads - the same member names, a {@code kind} or {@code type} discriminator - so a row can be read by
 * a person with {@code psql} and means what the studio draws.
 */
public final class PrintConfigPersistenceMapper {

    private PrintConfigPersistenceMapper() {
    }

    /** The template as other modules see it. */
    public static PrintConfig toConfig(CustomizationTemplateJpaEntity template, UUID productId, String sku,
                                       List<PrintAreaJpaEntity> areas) {
        ProductModel model = model(template.getModelParams());
        return new PrintConfig(template.getId(), productId, template.getVariantId(), sku, template.getCode(),
                template.getName(), template.getMinDpi(), template.getColorMode(),
                Arrays.asList(template.getAllowedFileFormats()), template.getMaxFileSizeMb(), template.getMaxColors(),
                template.getAllowedFontCodes() == null ? null : Arrays.asList(template.getAllowedFontCodes()),
                template.isActive(), model,
                areas.stream().map(row -> toArea(row, model)).toList(), template.getReferenceFileKey(),
                template.getReferenceFileName(), template.getVersion(), model3d(template), template.getPrintVersion());
    }

    /** The template's {@code .glb} and its surfaces, as stored. */
    public static TemplateModel3d model3d(CustomizationTemplateJpaEntity template) {
        List<Map<String, Object>> stored = template.getModelSurfaces();
        List<TemplateModel3d.Surface> surfaces = stored == null ? List.of() : stored.stream()
                .map(s -> new TemplateModel3d.Surface((String) s.get("printAreaCode"), (String) s.get("meshName")))
                .toList();
        return new TemplateModel3d(template.getModelFileKey(), template.getModelFileName(),
                template.getModelFileChecksum(), surfaces);
    }

    /** {@code surfaces} as the JSON array stored in {@code model_surfaces_json}. */
    public static List<Map<String, Object>> surfaces(List<TemplateModel3d.Surface> surfaces) {
        return surfaces.stream()
                .<Map<String, Object>>map(s -> {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("printAreaCode", s.printAreaCode());
                    json.put("meshName", s.meshName());
                    return json;
                })
                .toList();
    }

    /** The rows of {@code areas} for {@code templateId}, in order. */
    public static List<PrintAreaJpaEntity> toRows(UUID templateId, List<PrintArea> areas) {
        return java.util.stream.IntStream.range(0, areas.size())
                .mapToObj(i -> {
                    PrintArea area = areas.get(i);
                    return new PrintAreaJpaEntity(Identifiers.newId(), templateId, area.code(), area.name(),
                            mm(area.xMm()), mm(area.yMm()), mm(area.widthMm()), mm(area.heightMm()),
                            mm(area.safeMarginMm()), mm(area.bleedMm()), shape(area.shape()),
                            Map.of("meshPart", area.meshPart()), i);
                })
                .toList();
    }

    /** {@code model} as the JSON object stored in {@code model_params_json}. */
    public static Map<String, Object> modelParams(ProductModel model) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", model.kind());
        switch (model) {
            case CupModel cup -> {
                json.put("topDiameterMm", cup.topDiameterMm());
                json.put("bottomDiameterMm", cup.bottomDiameterMm());
                json.put("heightMm", cup.heightMm());
                json.put("rimMm", cup.rimMm());
                json.put("seamMm", cup.seamMm());
                json.put("bandTopOffsetMm", cup.bandTopOffsetMm());
                json.put("bandBottomOffsetMm", cup.bandBottomOffsetMm());
                json.put("material", cup.material());
            }
            case BagModel bag -> {
                json.put("widthMm", bag.widthMm());
                json.put("gussetMm", bag.gussetMm());
                json.put("heightMm", bag.heightMm());
                json.put("topFoldMm", bag.topFoldMm());
                json.put("glueFlapMm", bag.glueFlapMm());
                json.put("bottomFoldMm", bag.bottomFoldMm());
                json.put("handle", bag.handle());
                json.put("material", bag.material());
            }
        }
        return json;
    }

    /** The model stored in {@code model_params_json}, or null for a template written before it had one. */
    public static ProductModel model(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        return switch (String.valueOf(json.get("kind"))) {
            case CupModel.KIND -> new CupModel(num(json, "topDiameterMm"), num(json, "bottomDiameterMm"),
                    num(json, "heightMm"), num(json, "rimMm"), num(json, "seamMm"), num(json, "bandTopOffsetMm"),
                    num(json, "bandBottomOffsetMm"), (String) json.get("material"));
            // A template stored before the bag had a glue flap and a bottom fold reads with the model's defaults.
            case BagModel.KIND -> BagModel.withDefaults(num(json, "widthMm"), num(json, "gussetMm"),
                    num(json, "heightMm"), num(json, "topFoldMm"), optionalNum(json, "glueFlapMm"),
                    optionalNum(json, "bottomFoldMm"), (String) json.get("handle"), (String) json.get("material"));
            default -> throw new IllegalStateException("Unknown model kind " + json.get("kind"));
        };
    }

    private static PrintArea toArea(PrintAreaJpaEntity row, ProductModel model) {
        return new PrintArea(row.getCode(), row.getName(), row.getXMm().doubleValue(), row.getYMm().doubleValue(),
                row.getWidthMm().doubleValue(), row.getHeightMm().doubleValue(), row.getSafeMarginMm().doubleValue(),
                row.getBleedMm().doubleValue(), shape(row.getShape(), model),
                (String) row.getUvMapping().get("meshPart"));
    }

    private static Map<String, Object> shape(PrintAreaShape shape) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", shape.type());
        if (shape instanceof PrintAreaShape.AnnularSector sector) {
            json.put("innerRadiusMm", sector.innerRadiusMm());
            json.put("outerRadiusMm", sector.outerRadiusMm());
            json.put("sweepRad", sector.sweepRad());
            json.put("seamMm", sector.seamMm());
        }
        return json;
    }

    private static PrintAreaShape shape(Map<String, Object> json, ProductModel model) {
        return switch (String.valueOf(json.get("type"))) {
            case PrintAreaShape.Rect.TYPE -> new PrintAreaShape.Rect();
            case PrintAreaShape.AnnularSector.TYPE -> annularSector(json, model);
            default -> throw new IllegalStateException("Unknown print area shape " + json.get("type"));
        };
    }

    /**
     * A conical cup's band as stored. Its four numbers are derived from the cup model, never typed in
     * ({@code PrintConfigRules.areas} computes them), so a row that carries none - the demo seeds write
     * {@code {"type": "annular-sector"}} alone - is read as the model says rather than refused: refusing it made
     * {@code GET /catalog/products/{slug}/customization} answer 400 for every such cup (WF4 UX audit S08-2). Numbers
     * that are stored win; a row with some of them, or a model that is not a conical cup, is still corrupt.
     */
    private static PrintAreaShape annularSector(Map<String, Object> json, ProductModel model) {
        boolean stored = Stream.of("innerRadiusMm", "outerRadiusMm", "sweepRad", "seamMm").anyMatch(json::containsKey);
        if (!stored && model instanceof CupModel cup && !CupGeometry.isCylindrical(cup)) {
            CupGeometry.Sector sector = CupGeometry.sector(cup);
            return new PrintAreaShape.AnnularSector(sector.trimInnerMm(), sector.trimOuterMm(), sector.sweepRad(),
                    cup.seamMm());
        }
        return new PrintAreaShape.AnnularSector(num(json, "innerRadiusMm"), num(json, "outerRadiusMm"),
                num(json, "sweepRad"), num(json, "seamMm"));
    }

    private static double num(Map<String, Object> json, String member) {
        Object value = json.get(member);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Stored JSON lacks the number " + member);
        }
        return number.doubleValue();
    }

    /** A number the JSON may lack (a member added after rows were written); null when it does. */
    private static Double optionalNum(Map<String, Object> json, String member) {
        Object value = json.get(member);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Stored JSON member " + member + " is not a number");
        }
        return number.doubleValue();
    }

    /** Millimetres as the 2-decimal columns store them. */
    private static BigDecimal mm(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
