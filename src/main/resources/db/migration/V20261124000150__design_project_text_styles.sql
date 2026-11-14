-- A design project's library beyond its image files (FE request 2026-11-13, section E; the owner accepted E-1, E-2 and
-- E-5 although kltn-docs 12 names only logos and pictures in a library, BR-18).
--
-- 1. design.design_project_text (E-1): a text with its type style, saved once and placed on any design of the project.
--    At most 30 a project, counted apart from the 30 files (the service checks it under the project's lock). The style
--    is the type fields of a layout's text element, stored as one JSON object; the CHECK below states its shape so a
--    row written around the service still reads back as a style.
-- 2. design.design_project_asset.upload_key (E-5, E-2): what makes a library upload retried with its Idempotency-Key,
--    or the same design image saved to the library twice, answer with the file it made instead of taking a second
--    place. Unique per project, as design.design_asset.upload_key is per design.
-- 3. ck_design_project_event_type takes TEXT_ADDED and TEXT_REMOVED, the history rows of (1).

CREATE TABLE design.design_project_text (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    text character varying(500) NOT NULL,
    style jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_design_project_text PRIMARY KEY (id),
    CONSTRAINT fk_design_project_text_project FOREIGN KEY (project_id) REFERENCES design.design_project (id),
    CONSTRAINT ck_design_project_text_text CHECK (char_length(text) BETWEEN 1 AND 500 AND btrim(text) <> ''),
    -- CASE, not AND: Postgres does not promise to evaluate AND left to right, and a cast of a non-number would raise.
    CONSTRAINT ck_design_project_text_style CHECK (
        CASE WHEN jsonb_typeof(style) <> 'object' THEN false
             WHEN jsonb_typeof(style -> 'fontCode') <> 'string'
                  OR char_length(style ->> 'fontCode') NOT BETWEEN 1 AND 64 THEN false
             WHEN jsonb_typeof(style -> 'sizePt') <> 'number' OR (style ->> 'sizePt')::numeric <= 0 THEN false
             WHEN jsonb_typeof(style -> 'color') <> 'string'
                  OR (style ->> 'color') !~ '^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$' THEN false
             WHEN jsonb_typeof(style -> 'bold') <> 'boolean' OR jsonb_typeof(style -> 'italic') <> 'boolean' THEN false
             WHEN coalesce(style ->> 'align', '') NOT IN ('left', 'center', 'right') THEN false
             WHEN jsonb_typeof(style -> 'letterSpacingEm') <> 'number' THEN false
             WHEN jsonb_typeof(style -> 'lineHeight') <> 'number' OR (style ->> 'lineHeight')::numeric <= 0 THEN false
             WHEN coalesce(jsonb_typeof(style -> 'outline'), 'null') = 'null' THEN true
             WHEN jsonb_typeof(style -> 'outline') <> 'object' THEN false
             WHEN jsonb_typeof(style -> 'outline' -> 'color') <> 'string'
                  OR (style -> 'outline' ->> 'color') !~ '^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$' THEN false
             WHEN jsonb_typeof(style -> 'outline' -> 'widthMm') <> 'number' THEN false
             ELSE (style -> 'outline' ->> 'widthMm')::numeric > 0
        END)
);

CREATE INDEX ix_design_project_text_project ON design.design_project_text USING btree (project_id, created_at DESC, id DESC);

ALTER TABLE design.design_project_asset ADD COLUMN upload_key character varying(300);

CREATE UNIQUE INDEX uk_design_project_asset_upload_key ON design.design_project_asset USING btree (project_id, upload_key)
    WHERE (upload_key IS NOT NULL);

ALTER TABLE design.design_project_event DROP CONSTRAINT ck_design_project_event_type;
ALTER TABLE design.design_project_event ADD CONSTRAINT ck_design_project_event_type CHECK (((event)::text = ANY ((ARRAY[
    'PROJECT_CREATED'::character varying, 'ASSET_ADDED'::character varying, 'ASSET_REMOVED'::character varying,
    'TEXT_ADDED'::character varying, 'TEXT_REMOVED'::character varying,
    'BATCH_PREFLIGHT_FAILED'::character varying, 'BATCH_SUBMITTED'::character varying, 'BATCH_CLOSED'::character varying,
    'PROJECT_QUOTE_REQUESTED'::character varying, 'PROJECT_ARCHIVED'::character varying])::text[])));
