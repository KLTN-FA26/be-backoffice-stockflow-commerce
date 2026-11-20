-- A print configuration's print version, apart from its optimistic-lock version (product owner, 2026-11-20, decision 1).
--
-- Until now a design's rendered files recorded the template's optimistic-lock version (design.design_artifact
-- .template_version) and review compared it with the template's: renaming, activating or deactivating a template - and
-- activating one retires the variant's other - moved that version, so every design waiting for review on it became
-- unapprovable (DESIGN_FILES_OUTDATED). From now on the files record, and review compares, print_version, which the
-- service moves only when what the files are drawn on changes (PrintFacts: model and areas, limits, fonts, the .glb).
--
-- Initialised from version, so every file recorded so far still matches its template exactly as it did: no pending
-- design changes state. Nothing else is rewritten, so no upgrade test beyond the copy (the QA script checks it).
-- DEFAULT 0 for a template inserted without one (a new template starts at 0 in the service too).

ALTER TABLE product.customization_templates ADD COLUMN print_version bigint;

UPDATE product.customization_templates SET print_version = version;

ALTER TABLE product.customization_templates
    ALTER COLUMN print_version SET DEFAULT 0,
    ALTER COLUMN print_version SET NOT NULL,
    ADD CONSTRAINT ck_customization_templates_print_version CHECK (print_version >= 0);

COMMENT ON COLUMN product.customization_templates.print_version IS
    'Moves only when what customers'' exported files are drawn on changes; design_artifact.template_version records it';
