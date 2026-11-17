package com.stockflow.design.internal.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a design's layout must be before it is saved (kltn-docs 12 §4.2, BR-04; the storefront's
 * {@code designDocumentSchema}): a document of this schema version, drawn on this design's template and SKU, with
 * surfaces only for the template's print areas, at most {@value #MAX_ELEMENTS_PER_SURFACE} elements on each, every
 * element identified and typed, and every image one uploaded into this design.
 *
 * <p>Shallow on purpose. The studio validates the full shape as it edits; what the server must know here is that the
 * layout belongs to this design and names nothing it does not own. Whether the layout can be printed - resolution,
 * safe area, fonts - is preflight's question, asked when the customer submits it (BR-05), not on every save
 * (BR-11 lets a draft be saved freely).</p>
 *
 * <p>Works on the plain values {@code CanonicalJson.parse} returns: maps, lists, strings, numbers, booleans.</p>
 */
public final class DesignDocumentRules {

    /** The only layout version the studio writes. A later one adds its upgrade here, as the studio's does. */
    public static final int SCHEMA_VERSION = 1;

    /** As the studio caps it ({@code MAX_ELEMENTS_PER_SURFACE}). */
    public static final int MAX_ELEMENTS_PER_SURFACE = 200;

    private static final Set<String> ELEMENT_TYPES = Set.of("image", "text", "drawing");

    private DesignDocumentRules() {
    }

    /**
     * What a layout is checked against.
     *
     * @param templateCode the design's template
     * @param sku          the SKU the template hangs off
     * @param areaCodes    the template's print areas, in display order
     * @param assetIds     the images uploaded into this design
     */
    public record Frame(String templateCode, String sku, List<String> areaCodes, Set<String> assetIds) {
        public Frame {
            areaCodes = List.copyOf(areaCodes);
            assetIds = Set.copyOf(assetIds);
        }
    }

    /** The layout a design starts with: one empty surface per print area, as the studio's {@code emptyDocument}. */
    public static Map<String, Object> empty(Frame frame) {
        Map<String, Object> surfaces = new LinkedHashMap<>();
        for (String code : frame.areaCodes()) {
            surfaces.put(code, Map.of("background", Map.of("kind", "none"), "elements", List.of()));
        }
        return Map.of("schemaVersion", SCHEMA_VERSION, "templateCode", frame.templateCode(), "sku", frame.sku(),
                "surfaces", surfaces);
    }

    /** Why {@code document} may not be saved for the design {@code frame} describes, or null when it may. */
    public static String problem(Object document, Frame frame) {
        if (!(document instanceof Map<?, ?> root)) {
            return "The document must be a JSON object";
        }
        if (!(root.get("schemaVersion") instanceof Number version) || version.doubleValue() != SCHEMA_VERSION) {
            return "schemaVersion must be " + SCHEMA_VERSION;
        }
        if (!frame.templateCode().equals(root.get("templateCode")) || !frame.sku().equals(root.get("sku"))) {
            return "The document is not drawn on this design's template";
        }
        if (!(root.get("surfaces") instanceof Map<?, ?> surfaces)) {
            return "surfaces must be an object";
        }
        for (Map.Entry<?, ?> surface : surfaces.entrySet()) {
            String code = (String) surface.getKey();
            if (!frame.areaCodes().contains(code)) {
                return "Surface " + shown(code) + " is not a print area of the template";
            }
            String trouble = surfaceProblem(code, surface.getValue(), frame);
            if (trouble != null) {
                return trouble;
            }
        }
        return null;
    }

    private static String surfaceProblem(String code, Object surface, Frame frame) {
        if (!(surface instanceof Map<?, ?> content) || !(content.get("elements") instanceof List<?> elements)) {
            return "Surface " + code + " needs an elements array";
        }
        if (!(content.get("background") instanceof Map<?, ?>)) {
            return "Surface " + code + " needs a background";
        }
        if (elements.size() > MAX_ELEMENTS_PER_SURFACE) {
            return "Surface " + code + " has more than " + MAX_ELEMENTS_PER_SURFACE + " elements";
        }
        Set<String> ids = new HashSet<>();
        for (Object item : elements) {
            if (!(item instanceof Map<?, ?> element) || !(element.get("id") instanceof String id) || id.isEmpty()) {
                return "Every element of surface " + code + " needs an id";
            }
            if (!ids.add(id)) {
                return "Element id " + shown(id) + " is used twice on surface " + code;
            }
            if (!(element.get("type") instanceof String type) || !ELEMENT_TYPES.contains(type)) {
                return "Element " + shown(id) + " has no known type";
            }
            if (type.equals("image") && !(element.get("assetId") instanceof String asset
                    && frame.assetIds().contains(asset))) {
                return "Element " + shown(id) + " shows an image that is not uploaded into this design";
            }
        }
        return null;
    }

    /** A client's string in a message, cut short: a hostile name may be most of a megabyte. */
    private static String shown(String text) {
        if (text.length() <= 40) {
            return "'" + text + "'";
        }
        int end = Character.isHighSurrogate(text.charAt(39)) ? 39 : 40;
        return "'" + text.substring(0, end) + "...'";
    }
}
