-- A customization template's 3D model (FE request 2026-11-13, B-1..B-5; kltn-docs 01 §4.10): the .glb file the
-- back office measured the template's model from, its SHA-256, and which mesh of it each print area is drawn on.
--
-- The file is optional and travels with the template's version: replacing it moves the version like any other change
-- of the print configuration (B39), and an approved design keeps the key it was approved with in its snapshot's frozen
-- template, so the orphaned-file sweep keeps a replaced file an approved design still names.
--
-- The surfaces are a JSON array of {printAreaCode, meshName}; the service checks each code against the template's
-- print areas (those live in their own rows, so the database can only check the shape).

ALTER TABLE product.customization_templates
    ADD COLUMN model_file_key       varchar(500),
    ADD COLUMN model_file_name      varchar(255),
    ADD COLUMN model_file_checksum  varchar(64),
    ADD COLUMN model_surfaces_json  jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE product.customization_templates
    ADD CONSTRAINT ck_customization_templates_model_file CHECK (
        ((model_file_key IS NULL) = (model_file_name IS NULL))
        AND ((model_file_key IS NULL) = (model_file_checksum IS NULL))
        AND ((model_file_key IS NULL) OR ((model_file_key)::text ~~ 'print-models/%'::text))
        AND ((model_file_checksum IS NULL) OR ((model_file_checksum)::text ~ '^[0-9a-f]{64}$'::text))),
    ADD CONSTRAINT ck_customization_templates_model_surfaces CHECK (
        (jsonb_typeof(model_surfaces_json) = 'array'::text) AND (jsonb_array_length(model_surfaces_json) <= 8));

COMMENT ON COLUMN product.customization_templates.model_file_key IS
    'Storage key of the template''s .glb model (glTF 2.0 binary), or null: the storefront then draws the parametric model';
COMMENT ON COLUMN product.customization_templates.model_file_checksum IS
    'SHA-256 (hex) of the .glb the model''s measurements were taken from (B-5)';
COMMENT ON COLUMN product.customization_templates.model_surfaces_json IS
    'Which mesh of the .glb each print area is drawn on: [{"printAreaCode": "BODY", "meshName": "print:BODY"}]';
