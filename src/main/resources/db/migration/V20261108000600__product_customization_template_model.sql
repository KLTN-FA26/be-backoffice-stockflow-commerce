-- =============================================================================
-- Customization templates get the parametric model their print areas are computed from (kltn-docs 01 §4.10, BR-21;
-- open gap G11), so the design studio, the server-side preflight and the producer work from one geometry.
--
-- A template now carries model_params_json: a cup (sizes of the cone, the band's offsets, the glue flap) or a bag
-- (width, gusset, height, top fold). Its print areas are derived from it on the server - a cup's one BODY wall as an
-- annular sector (a rectangle when the wall is straight), a bag's FRONT, BACK, LEFT and RIGHT as rectangles - and
-- stored with their shape. The free-text 2D template URL and 3D model URL go: the 2D outline is the computed shape,
-- a glTF model is a later port (B36); a reference die-line the Sales Manager attaches is a stored file
-- (reference_file_*, category print-templates).
--
-- Data: no template written so far has a model, so none can stay active, and their print areas - placed by hand,
-- with a WRAP code and no shape - describe nothing the studio can draw. Every template is deactivated and its print
-- areas removed; the Sales Manager enters the model and the areas are computed again. Everything changed or removed is
-- archived in platform.legacy_archive first. Upload types become MIME types; a size limit over a design asset's 50 MB
-- is capped at it.
--
-- Order: 1. archive; 2. data; 3. columns and CHECKs; 4. print areas' CHECKs; 5. one active template per variant.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'product.customization_templates.before_model', t.id,
       jsonb_build_object('id', t.id, 'template_2d_url', t.template_2d_url, 'model_3d_url', t.model_3d_url,
                          'allowed_file_formats', to_jsonb(t.allowed_file_formats),
                          'max_file_size_mb', t.max_file_size_mb, 'min_dpi', t.min_dpi, 'is_active', t.is_active)
FROM product.customization_templates t
UNION ALL
SELECT 'product.print_areas', a.id, to_jsonb(a) FROM product.print_areas a;

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), and the customization templates and print areas as they were before templates got a '
        'model (V20261108000600). Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Data
-- ---------------------------------------------------------------------------------------------
DELETE FROM product.print_areas;

UPDATE product.customization_templates t
SET is_active            = FALSE,
    max_file_size_mb     = LEAST(t.max_file_size_mb, 50),
    allowed_file_formats = coalesce(
            (SELECT array_agg(DISTINCT m.mime ORDER BY m.mime)
             FROM unnest(t.allowed_file_formats) AS f(format)
                      CROSS JOIN LATERAL (SELECT CASE lower(f.format)
                                                     WHEN 'png' THEN 'image/png'
                                                     WHEN 'image/png' THEN 'image/png'
                                                     WHEN 'jpg' THEN 'image/jpeg'
                                                     WHEN 'jpeg' THEN 'image/jpeg'
                                                     WHEN 'image/jpeg' THEN 'image/jpeg'
                                                     WHEN 'webp' THEN 'image/webp'
                                                     WHEN 'image/webp' THEN 'image/webp'
                                                     END AS mime) AS m
             WHERE m.mime IS NOT NULL),
            ARRAY ['image/jpeg', 'image/png']::text[]);


-- ---------------------------------------------------------------------------------------------
-- 3. Template columns and CHECKs
-- ---------------------------------------------------------------------------------------------
ALTER TABLE product.customization_templates
    DROP COLUMN template_2d_url,
    DROP COLUMN model_3d_url,
    ADD COLUMN model_params_json      JSONB,
    ADD COLUMN reference_file_key     VARCHAR(500),
    ADD COLUMN reference_file_name    VARCHAR(255),
    ADD COLUMN reference_content_type VARCHAR(100),
    ALTER COLUMN min_dpi SET DEFAULT 300,
    ALTER COLUMN is_active SET DEFAULT FALSE,
    DROP CONSTRAINT ck_customization_templates_size,
    ADD CONSTRAINT ck_customization_templates_size CHECK (max_file_size_mb BETWEEN 1 AND 50),
    ADD CONSTRAINT ck_customization_templates_mime
        CHECK (allowed_file_formats <@ ARRAY ['image/png', 'image/jpeg', 'image/webp']::text[]),
    ADD CONSTRAINT ck_customization_templates_model
        CHECK (model_params_json IS NULL
            OR (jsonb_typeof(model_params_json) = 'object'
                AND model_params_json ->> 'kind' IN ('parametric-cup', 'parametric-bag'))),
    -- A customer may only start a design on a template whose print areas can be computed.
    ADD CONSTRAINT ck_customization_templates_active_model CHECK (NOT is_active OR model_params_json IS NOT NULL),
    ADD CONSTRAINT ck_customization_templates_reference
        CHECK ((reference_file_key IS NULL) = (reference_file_name IS NULL)
            AND (reference_file_key IS NULL) = (reference_content_type IS NULL)
            AND (reference_file_key IS NULL OR reference_file_key LIKE 'print-templates/%'));


-- ---------------------------------------------------------------------------------------------
-- 4. Print areas: the codes the studio draws, and a computed shape
-- ---------------------------------------------------------------------------------------------
ALTER TABLE product.print_areas
    ALTER COLUMN shape_json SET NOT NULL,
    ALTER COLUMN uv_mapping_json SET NOT NULL,
    ADD CONSTRAINT ck_print_areas_code CHECK (code IN ('BODY', 'FRONT', 'BACK', 'LEFT', 'RIGHT')),
    ADD CONSTRAINT ck_print_areas_shape
        CHECK (jsonb_typeof(shape_json) = 'object' AND shape_json ->> 'type' IN ('rect', 'annular-sector')),
    ADD CONSTRAINT ck_print_areas_uv_mapping
        CHECK (jsonb_typeof(uv_mapping_json) = 'object' AND uv_mapping_json ? 'meshPart');


-- ---------------------------------------------------------------------------------------------
-- 5. One active template per variant: a customer starts a design on "the" template of a variant (kltn-docs 13 BR-14)
-- ---------------------------------------------------------------------------------------------
CREATE UNIQUE INDEX uk_customization_templates_active ON product.customization_templates (variant_id)
    WHERE is_active;
