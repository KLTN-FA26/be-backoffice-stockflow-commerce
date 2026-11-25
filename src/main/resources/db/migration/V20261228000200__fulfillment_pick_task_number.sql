-- A pick task gets a number people can read, say and search by: PK-yyyyMMdd-nnnn, drawn from
-- platform.document_sequence for the business day (Asia/Ho_Chi_Minh) the task was opened on, as load (LD-) and load
-- plan (LP-) numbers are. Until now the task had only its UUID, and the back office showed a slice of it.
--
-- The application assigns the number when it opens a task (PickNumbers). The BEFORE INSERT trigger below draws one
-- from the same sequence for a row inserted without it - SQL fixtures, seed scripts and later demo migrations that
-- write picks directly - so NOT NULL never refuses them and a number drawn there can never collide with one the
-- application draws. A number, once given, never changes.
ALTER TABLE fulfillment.pick ADD COLUMN task_number character varying(32);

COMMENT ON COLUMN fulfillment.pick.task_number IS 'The task''s human number PK-yyyyMMdd-nnnn: the business day (Asia/Ho_Chi_Minh) it was opened on and its place that day, from platform.document_sequence (document type PK). Assigned once, never changed.';

-- Backfill: the tasks of each business day of their creation, numbered in creation order (id breaks a tie), after any
-- PK number that day already drew. Deterministic, so every database rebuilt from the same rows gets the same numbers.
WITH days AS (
    SELECT p.id,
           (p.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
           row_number() OVER (PARTITION BY (p.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                              ORDER BY p.created_at, p.id) AS place
      FROM fulfillment.pick p
), numbered AS (
    SELECT d.id, d.day, d.place + COALESCE(s.last_value, 0) AS value
      FROM days d
      LEFT JOIN platform.document_sequence s ON s.document_type = 'PK' AND s.sequence_date = d.day
)
UPDATE fulfillment.pick p
   SET task_number = 'PK-' || to_char(n.day, 'YYYYMMDD') || '-' || lpad(n.value::text, GREATEST(4, length(n.value::text)), '0')
  FROM numbered n
 WHERE n.id = p.id;

-- The sequence moves past every number the backfill gave, so the next task of one of those days takes the next one.
INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
SELECT 'PK', to_date(substring(task_number FROM 4 FOR 8), 'YYYYMMDD'), max(substring(task_number FROM 13)::bigint)
  FROM fulfillment.pick
 GROUP BY 2
ON CONFLICT (document_type, sequence_date)
DO UPDATE SET last_value = GREATEST(platform.document_sequence.last_value, EXCLUDED.last_value);

CREATE FUNCTION fulfillment.assign_pick_task_number() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    business_day date;
    value bigint;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.task_number IS DISTINCT FROM OLD.task_number THEN
            RAISE EXCEPTION 'tg_pick_task_number: pick % is %; a task number never changes', OLD.id, OLD.task_number
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_pick_task_number';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.task_number IS NULL THEN
        business_day := (COALESCE(NEW.created_at, now()) AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
        VALUES ('PK', business_day, 1)
        ON CONFLICT (document_type, sequence_date)
        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
        RETURNING last_value INTO value;
        NEW.task_number := 'PK-' || to_char(business_day, 'YYYYMMDD') || '-'
                || lpad(value::text, GREATEST(4, length(value::text)), '0');
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_pick_task_number BEFORE INSERT OR UPDATE OF task_number ON fulfillment.pick
    FOR EACH ROW EXECUTE FUNCTION fulfillment.assign_pick_task_number();
-- ALWAYS: fixtures write rows with session_replication_role = replica to skip the foreign keys; such a row still needs its
-- number, and the trigger only fills a missing one or refuses a change, so it is safe to fire there too.
ALTER TABLE fulfillment.pick ENABLE ALWAYS TRIGGER tg_pick_task_number;

ALTER TABLE fulfillment.pick ALTER COLUMN task_number SET NOT NULL;
ALTER TABLE fulfillment.pick ADD CONSTRAINT uk_pick_task_number UNIQUE (task_number);
ALTER TABLE fulfillment.pick ADD CONSTRAINT ck_pick_task_number CHECK (task_number ~ '^PK-[0-9]{8}-[0-9]{4,}$');
