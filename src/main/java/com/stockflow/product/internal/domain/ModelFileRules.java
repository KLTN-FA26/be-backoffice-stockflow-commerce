package com.stockflow.product.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.ProductModel;
import com.stockflow.product.api.TemplateModel3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What ties a customization template to its {@code .glb} (project owner, 2026-11-15): the back office generates the
 * template's measurements from the file at upload, and the storefront finds each print area's mesh only by the name
 * {@code print:<printAreaCode>}. So the values the file determines change only together with a new file, and a
 * template with a file maps every print area to exactly that mesh.
 *
 * <p>The values a file determines are the ones the 3D model shows: a cup's diameters, height and printed band; a bag's
 * width, gusset and printed height (height less top fold, so the fold may move if the height moves with it); the kind
 * of model; and the mesh map. Everything else - limits, names, margins, a cup's seam and rim, material, handle - is
 * the Sales Manager's and changes freely. Numbers are compared as stored to 0.01 mm, so a client re-sending the values
 * it read is never refused.</p>
 *
 * <p>Pure and stateless.</p>
 */
public final class ModelFileRules {

    /** The prefix of the mesh a print area is drawn on; the storefront looks meshes up by nothing else. */
    public static final String MESH_PREFIX = "print:";

    private ModelFileRules() {
    }

    /** The mesh name print area {@code printAreaCode} must have. */
    public static String meshOf(String printAreaCode) {
        return MESH_PREFIX + printAreaCode;
    }

    /**
     * Refuses a save without a new file that changes a value the file determines.
     *
     * @param stored             the template's model now, or null for a template written before it had one
     * @param storedSurfaces     the template's mesh map now
     * @param requested          the model the request saves
     * @param requestedSurfaces  the mesh map the request saves, or null when it keeps the stored one
     * @throws BusinessException {@code PRINT_CONFIG_MODEL_FILE_REQUIRED} naming what changed
     */
    public static void requireUnchangedWithoutFile(ProductModel stored, List<TemplateModel3d.Surface> storedSurfaces,
                                                   ProductModel requested,
                                                   List<TemplateModel3d.Surface> requestedSurfaces) {
        List<String> changed = new ArrayList<>(changedMeasurements(stored, requested));
        if (requestedSurfaces != null && !sameSurfaces(storedSurfaces, requestedSurfaces)) {
            changed.add("model3d.surfaces");
        }
        if (!changed.isEmpty()) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_MODEL_FILE_REQUIRED,
                    "These values come from the 3D model file; upload the file with them: "
                            + String.join(", ", changed));
        }
    }

    /**
     * The file-determined values that differ between {@code stored} and {@code requested}, by request field name;
     * empty when none. A missing stored model, or a model of another kind, differs in {@code model.kind}.
     */
    public static List<String> changedMeasurements(ProductModel stored, ProductModel requested) {
        if (stored == null || requested == null || !stored.kind().equals(requested.kind())) {
            return Objects.equals(stored, requested) ? List.of() : List.of("model.kind");
        }
        List<String> changed = new ArrayList<>();
        switch (stored) {
            case CupModel was -> {
                CupModel now = (CupModel) requested;
                compare(changed, "model.topDiameterMm", was.topDiameterMm(), now.topDiameterMm());
                compare(changed, "model.bottomDiameterMm", was.bottomDiameterMm(), now.bottomDiameterMm());
                compare(changed, "model.heightMm", was.heightMm(), now.heightMm());
                compare(changed, "model.bandTopOffsetMm", was.bandTopOffsetMm(), now.bandTopOffsetMm());
                compare(changed, "model.bandBottomOffsetMm", was.bandBottomOffsetMm(), now.bandBottomOffsetMm());
            }
            case BagModel was -> {
                BagModel now = (BagModel) requested;
                compare(changed, "model.widthMm", was.widthMm(), now.widthMm());
                compare(changed, "model.gussetMm", was.gussetMm(), now.gussetMm());
                // The model shows the printed height, not where the fold starts: each side rounded as stored first.
                if (printedHeight(was) != printedHeight(now)) {
                    changed.add("model.heightMm - model.topFoldMm");
                }
            }
        }
        return List.copyOf(changed);
    }

    /** Whether two mesh maps pair the same print areas with the same meshes, whatever their order. */
    public static boolean sameSurfaces(Collection<TemplateModel3d.Surface> a, Collection<TemplateModel3d.Surface> b) {
        return pairs(a).equals(pairs(b));
    }

    /**
     * What keeps {@code surfaces} from being the complete map of a template with a file: a print area with no entry,
     * and an entry whose mesh is not {@code print:<printAreaCode>}. Entries naming an unknown area or one twice are
     * the lenient check's to report; they are skipped here.
     *
     * @param surfaces  the map, already checked leniently (entry {@code i} reported as {@code model3d.surfaces[i]})
     * @param areaCodes the template's print areas, in order
     */
    public static List<FieldError> incompleteSurfaces(List<TemplateModel3d.Surface> surfaces,
                                                      List<String> areaCodes) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> mapped = new HashSet<>();
        for (int i = 0; i < surfaces.size(); i++) {
            TemplateModel3d.Surface surface = surfaces.get(i);
            if (surface == null || surface.printAreaCode() == null || !areaCodes.contains(surface.printAreaCode())) {
                continue;
            }
            mapped.add(surface.printAreaCode());
            String expected = meshOf(surface.printAreaCode());
            if (!expected.equals(surface.meshName())) {
                errors.add(new FieldError("model3d.surfaces[%d].meshName".formatted(i),
                        "Print area " + surface.printAreaCode() + " is drawn on the mesh " + expected,
                        "MeshNameMismatch"));
            }
        }
        for (String code : areaCodes) {
            if (!mapped.contains(code)) {
                errors.add(new FieldError("model3d.surfaces",
                        "Print area " + code + " has no mesh; map it to " + meshOf(code), "PrintAreaMissing"));
            }
        }
        return errors;
    }

    private static Set<List<String>> pairs(Collection<TemplateModel3d.Surface> surfaces) {
        return (surfaces == null ? List.<TemplateModel3d.Surface>of() : surfaces).stream()
                .filter(Objects::nonNull)
                .map(s -> java.util.Arrays.asList(s.printAreaCode(), s.meshName() == null ? null : s.meshName().trim()))
                .collect(Collectors.toSet());
    }

    private static void compare(List<String> changed, String field, double was, double now) {
        if (hundredths(was) != hundredths(now)) {
            changed.add(field);
        }
    }

    private static long printedHeight(BagModel bag) {
        return hundredths(bag.heightMm()) - hundredths(bag.topFoldMm());
    }

    /** Millimetres in hundredths, as the template keeps them. */
    private static long hundredths(double mm) {
        return Math.round(mm * 100);
    }
}
