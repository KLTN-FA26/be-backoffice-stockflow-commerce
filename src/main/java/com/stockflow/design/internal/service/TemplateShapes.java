package com.stockflow.design.internal.service;

import com.stockflow.common.json.CanonicalJson;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.TemplateModel3d;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A customization template in the shape of one template of the storefront's P2
 * ({@code GET /catalog/products/{slug}/customization}), plus {@code active}: what the studio reads to draw a design.
 * One shape for the template as it is now and for the template frozen in an approved design's snapshot - the same JSON,
 * though the snapshot's column stores it in its own key order and spacing, and no checksum covers it - so an approved
 * design reopens exactly as it was approved (kltn-docs 12 B39, BR-08).
 *
 * <p>The template's 3D model (FE request 2026-11-13 B-3, B-4) is stored as {@code model3d: {fileKey, fileChecksum,
 * surfaces}}, so a snapshot names the very file it was approved on (and the orphaned-file sweep keeps that file while it
 * does). A storage key is not for the browser and a signed link expires, so {@link #forReader} turns it into
 * {@code {url, surfaces}} each time the template is read.</p>
 */
final class TemplateShapes {

    private TemplateShapes() {
    }

    /** The template as canonical JSON; absent fields are left out, as the API leaves out nulls. */
    static String json(PrintConfig config) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("templateId", config.templateId().toString());
        shape.put("code", config.code());
        shape.put("name", config.name());
        shape.put("sku", config.sku());
        shape.put("minDpi", config.minDpi());
        shape.put("colorMode", config.colorMode());
        shape.put("allowedFileFormats", config.allowedFileFormats());
        shape.put("maxFileSizeMb", config.maxFileSizeMb());
        if (config.maxColors() != null) {
            shape.put("maxColors", config.maxColors());
        }
        if (config.allowedFontCodes() != null) {
            shape.put("allowedFontCodes", config.allowedFontCodes());
        }
        shape.put("active", config.active());
        shape.put("model", model(config));
        shape.put("printAreas", config.printAreas().stream().map(TemplateShapes::area).toList());
        if (config.model3d().hasFile()) {
            Map<String, Object> model3d = new LinkedHashMap<>();
            model3d.put("fileKey", config.model3d().fileKey());
            model3d.put("fileChecksum", config.model3d().fileChecksum());
            model3d.put("surfaces", config.model3d().surfaces().stream().map(TemplateShapes::surface).toList());
            shape.put("model3d", model3d);
        }
        return CanonicalJson.canonical(shape);
    }

    /**
     * {@code stored} - this class's JSON, as now or frozen in a snapshot - as the studio reads it: {@code model3d} with
     * a link to the file instead of its key, or no {@code model3d} when the template had no file or no link can be
     * signed ({@code linkOf} answering null).
     */
    @SuppressWarnings("unchecked")
    static String forReader(String stored, Function<String, String> linkOf) {
        if (!(CanonicalJson.parse(stored) instanceof Map<?, ?> parsed)) {
            return stored;
        }
        Map<String, Object> shape = new LinkedHashMap<>((Map<String, Object>) parsed);
        if (!(shape.get("model3d") instanceof Map<?, ?> model3d)) {
            return stored;
        }
        String url = model3d.get("fileKey") instanceof String key ? linkOf.apply(key) : null;
        if (url == null) {
            shape.remove("model3d");
        } else {
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("url", url);
            read.put("surfaces", model3d.get("surfaces") instanceof List<?> surfaces
                    ? new ArrayList<>(surfaces) : List.of());
            shape.put("model3d", read);
        }
        return CanonicalJson.canonical(shape);
    }

    private static Map<String, Object> surface(TemplateModel3d.Surface surface) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("printAreaCode", surface.printAreaCode());
        map.put("meshName", surface.meshName());
        return map;
    }

    private static Map<String, Object> model(PrintConfig config) {
        Map<String, Object> model = new LinkedHashMap<>();
        switch (config.model()) {
            case CupModel cup -> {
                model.put("kind", cup.kind());
                model.put("topDiameterMm", cup.topDiameterMm());
                model.put("bottomDiameterMm", cup.bottomDiameterMm());
                model.put("heightMm", cup.heightMm());
                model.put("rimMm", cup.rimMm());
                model.put("seamMm", cup.seamMm());
                model.put("bandTopOffsetMm", cup.bandTopOffsetMm());
                model.put("bandBottomOffsetMm", cup.bandBottomOffsetMm());
                model.put("material", cup.material());
            }
            case BagModel bag -> {
                model.put("kind", bag.kind());
                model.put("widthMm", bag.widthMm());
                model.put("gussetMm", bag.gussetMm());
                model.put("heightMm", bag.heightMm());
                model.put("topFoldMm", bag.topFoldMm());
                model.put("handle", bag.handle());
                model.put("material", bag.material());
            }
        }
        return model;
    }

    private static Map<String, Object> area(PrintArea area) {
        Map<String, Object> shape = new LinkedHashMap<>();
        switch (area.shape()) {
            case PrintAreaShape.Rect rect -> shape.put("type", rect.type());
            case PrintAreaShape.AnnularSector sector -> {
                shape.put("type", sector.type());
                shape.put("innerRadiusMm", sector.innerRadiusMm());
                shape.put("outerRadiusMm", sector.outerRadiusMm());
                shape.put("sweepRad", sector.sweepRad());
                shape.put("seamMm", sector.seamMm());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", area.code());
        map.put("name", area.name());
        map.put("xMm", area.xMm());
        map.put("yMm", area.yMm());
        map.put("widthMm", area.widthMm());
        map.put("heightMm", area.heightMm());
        map.put("safeMarginMm", area.safeMarginMm());
        map.put("bleedMm", area.bleedMm());
        map.put("shape", shape);
        map.put("uvMapping", Map.of("meshPart", area.meshPart()));
        return map;
    }
}
