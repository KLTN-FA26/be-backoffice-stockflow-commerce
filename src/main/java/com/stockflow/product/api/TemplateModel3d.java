package com.stockflow.product.api;

import java.util.List;

/**
 * A customization template's 3D model (kltn-docs 01 §4.10): the {@code .glb} file the back office measured the
 * template's {@link ProductModel} from, and which mesh of it each print area is drawn on. The server never reads inside
 * the file; it keeps the file's SHA-256 so the measurements can be traced to the file they came from.
 *
 * @param fileKey      storage key of the {@code .glb}, or null when none is attached
 * @param fileName     its name as uploaded, or null
 * @param fileChecksum its SHA-256 in hex, or null
 * @param surfaces     print area to mesh, at most one entry per print area; empty when not mapped
 */
public record TemplateModel3d(String fileKey, String fileName, String fileChecksum, List<Surface> surfaces) {

    /** No file and no surfaces: a template the back office has not given a 3D model. */
    public static final TemplateModel3d NONE = new TemplateModel3d(null, null, null, List.of());

    public TemplateModel3d {
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
    }

    public boolean hasFile() {
        return fileKey != null;
    }

    /** The mesh a print area is drawn on. */
    public record Surface(String printAreaCode, String meshName) {
    }
}
