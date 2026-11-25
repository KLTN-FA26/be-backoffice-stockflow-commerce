-- =============================================================================
-- Demo: every demo customer's designs in a design project (kltn-docs 12 BR-17..BR-22, BR-25). Demo only (db/demo).
--
-- The seeds (V20261124000600, V20261128000200, V20261206000100, V20261222000300, and db/demo-wf05 V20261224900100
-- where that location is on) made each customer's designs standalone, so a demo customer opened no project, could not
-- submit a batch or ask a project quote. Each owner's standalone designs now go into an ACTIVE project of theirs,
-- "Bộ thiết kế mẫu", at most 20 to a project as DesignProjectRules.MAX_DESIGNS says (B42): the 21st onwards go to
-- "Bộ thiết kế mẫu 2", then 3, and a NOTICE names every customer that needed more than one.
--
-- A project is its owner's alone (BR-17), so the designs are grouped by customer and owner, not by customer only; in
-- the seeds every customer has one owner, so it is one project per customer.
--
-- What is moved, and what is not:
--   - designs not archived and in no project; an archived one counts toward no project (B42) and stays out;
--   - a design with no owner stays out too: a project needs one (design_project.owner_user_id), and only a design's
--     owner may put it in a project (DesignProjectService.addDesign);
--   - a design keeps its status: tg_design_draft_project_while_pending refuses only a move OUT of a project while
--     PENDING_REVIEW, and fk_design_draft_project keeps the project the design's own customer's;
--   - the history says what the application would: PROJECT_CREATED on the project (named as it is), ADDED_TO_PROJECT
--     on each design ("Project <id>"), by the project's owner.
--
-- Ids are md5 of a key, so the rows are the same on every rebuilt database. A database where the seeds made no design
-- gets no project.
-- =============================================================================

CREATE TEMP TABLE p_member ON COMMIT DROP AS
SELECT d.id AS draft_id, d.customer_id, d.owner_user_id, d.created_at, d.version,
       (row_number() OVER (PARTITION BY d.customer_id, d.owner_user_id ORDER BY d.created_at, d.id) - 1) / 20 AS bucket
  FROM design.design_draft d
 WHERE d.project_id IS NULL
   AND d.status <> 'ARCHIVED'
   AND d.owner_user_id IS NOT NULL;

ALTER TABLE p_member ADD COLUMN project_id UUID;
UPDATE p_member
   SET project_id = md5('demo:design-project:' || customer_id || ':' || owner_user_id || ':' || bucket)::uuid;

CREATE TEMP TABLE p_project ON COMMIT DROP AS
SELECT project_id, customer_id, owner_user_id, bucket, min(created_at) AS created_at, count(*) AS designs
  FROM p_member
 GROUP BY project_id, customer_id, owner_user_id, bucket;

INSERT INTO design.design_project (id, customer_id, owner_user_id, name, description, status, version, created_at,
                                   created_by)
SELECT project_id, customer_id, owner_user_id,
       'Bộ thiết kế mẫu' || CASE WHEN bucket = 0 THEN '' ELSE ' ' || (bucket + 1) END,
       'Các thiết kế mẫu của khách hàng, gom vào một bộ để gửi duyệt và yêu cầu báo giá cùng lúc', 'ACTIVE', 0,
       created_at, owner_user_id::text
  FROM p_project;

INSERT INTO design.design_project_event (id, project_id, actor_id, event, notes, version, created_at, created_by)
SELECT md5('demo:design-project-event:created:' || p.project_id)::uuid, p.project_id, p.owner_user_id,
       'PROJECT_CREATED', dp.name, 0, p.created_at, p.owner_user_id::text
  FROM p_project p
  JOIN design.design_project dp ON dp.id = p.project_id;

UPDATE design.design_draft d
   SET project_id = m.project_id,
       last_modified_at = now(),
       last_modified_by = 'flyway'
  FROM p_member m
 WHERE d.id = m.draft_id;

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
SELECT md5('demo:design-project-join:' || m.draft_id)::uuid, m.draft_id, m.owner_user_id, 'ADDED_TO_PROJECT',
       'Project ' || m.project_id, m.version, 0, now(), m.owner_user_id::text
  FROM p_member m;

DO $$
DECLARE
    overflow RECORD;
BEGIN
    FOR overflow IN
        SELECT customer_id, count(*) AS projects, sum(designs) AS designs
          FROM p_project
         GROUP BY customer_id
        HAVING count(*) > 1
         ORDER BY customer_id
    LOOP
        RAISE NOTICE 'Customer % has % designs, spread over % demo projects of at most 20', overflow.customer_id,
            overflow.designs, overflow.projects;
    END LOOP;
END $$;
