-- =============================================================================
-- SCRUM-473 / #138 "Soạn & giao theo thùng": a pick line names the carton it takes from.
--
-- A hold on a carton's stock row is deducted from that carton (PickConsumptionService opens it, empties it CONSUMED).
-- A picker who scans only the SKU may take another carton of the same SKU, lot and bin, and the books then read the
-- wrong carton as opened. So a line drawn from a carton carries the carton's LPN, and confirming it means scanning that
-- LPN. whole_carton says the hold is the carton's whole sealed content: the picker takes it as it stands.
--
-- Written under the carton delegation of #125 (the fulfillment schema is otherwise Tú's).
--
-- The LPN is a copy of a fact inventory owns (reservation -> stock row -> carton), copied when the line is planned. A
-- stock row never changes carton (stock_package_id is part of its natural key and not updatable), so the copy cannot
-- go stale; the trigger below states the invariant a second time, next to PickList.plan which keeps it.
-- =============================================================================

ALTER TABLE fulfillment.pick_line
    ADD COLUMN lpn          VARCHAR(30),
    ADD COLUMN whole_carton BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_pick_line_lpn FOREIGN KEY (lpn) REFERENCES inventory.stock_package (lpn),
    ADD CONSTRAINT ck_pick_line_whole_carton CHECK (NOT whole_carton OR lpn IS NOT NULL);

-- Lines planned before this migration: the carton of their hold. whole_carton stays FALSE for them - whether the hold
-- was the whole carton is not known after the fact, and the flag is a hint for the picker, not a rule.
UPDATE fulfillment.pick_line l
   SET lpn = p.lpn
  FROM inventory.stock_reservation r
  JOIN inventory.stock_item s ON s.id = r.stock_item_id
  JOIN inventory.stock_package p ON p.id = s.stock_package_id
 WHERE r.id = l.reservation_id
   AND l.lpn IS NULL;

CREATE FUNCTION fulfillment.check_pick_line_carton()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    expected VARCHAR(30);
BEGIN
    SELECT p.lpn INTO expected
      FROM inventory.stock_reservation r
      JOIN inventory.stock_item s ON s.id = r.stock_item_id
      LEFT JOIN inventory.stock_package p ON p.id = s.stock_package_id
     WHERE r.id = NEW.reservation_id;
    IF NEW.lpn IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'pick line % must name carton %, the carton of its hold; it names %',
            NEW.id, coalesce(expected, '(none: loose stock)'), coalesce(NEW.lpn, '(none)')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_pick_line_carton BEFORE INSERT OR UPDATE OF lpn, reservation_id
    ON fulfillment.pick_line
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_pick_line_carton();

COMMENT ON COLUMN fulfillment.pick_line.lpn IS
    'The carton the line takes from: the LPN of its hold''s stock row, NULL for loose stock. Scanned to confirm.';
COMMENT ON COLUMN fulfillment.pick_line.whole_carton IS
    'The hold is the carton''s whole sealed content when planned: the picker takes the carton as it stands.';
