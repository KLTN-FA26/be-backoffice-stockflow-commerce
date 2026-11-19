-- kltn-docs 07 section 4.4 step 9, 20 section 4.8 (WF04 G7): a carton the picker reported DAMAGED at the staging area is
-- quarantined at once. Its ledger line is a STATUS_CHANGE of the carton from the staging area into a quarantine area;
-- check_staging_movement refused every line but a pick's, so the commit failed after the service had written it. A
-- carton leaves a staging area for a quarantine area by that one line (reference STOCK_PACKAGE) and nowhere else, and
-- nothing enters a staging area but picking, as before (11 BR-09, 20 BR-20).
CREATE OR REPLACE FUNCTION inventory.check_staging_movement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    -- A count or an adjustment only writes a staging area down (out of it), never up: the place it may touch is the
    -- one it takes from. Every other line touches both of its places.
    touched TEXT[] := CASE WHEN NEW.movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
                           THEN ARRAY[NEW.to_location_code]
                           ELSE ARRAY[NEW.from_location_code, NEW.to_location_code] END;
    -- The quarantine of a carton standing in a staging area: out of the staging area into a QUARANTINE area.
    quarantines_staged BOOLEAN := NEW.movement_type = 'STATUS_CHANGE' AND NEW.reference_type = 'STOCK_PACKAGE'
        AND EXISTS (SELECT 1 FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id
                     WHERE a.type = 'STAGING' AND l.location_code = NEW.from_location_code)
        AND EXISTS (SELECT 1 FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id
                     WHERE a.type = 'QUARANTINE' AND l.location_code = NEW.to_location_code);
BEGIN
    IF NOT ((NEW.movement_type = 'MOVE' AND NEW.reference_type = 'PICK_LINE') OR NEW.movement_type = 'PICK'
            OR quarantines_staged)
       AND EXISTS (SELECT 1
                     FROM warehouse.area a
                     JOIN warehouse.storage_location l ON l.id = a.location_id
                    WHERE a.type = 'STAGING'
                      AND l.location_code = ANY (touched)) THEN
        RAISE EXCEPTION 'a % (reference %) cannot % staging area %: stock enters it only by picking, and leaves it by '
            'picking, a write-down or the quarantine of a damaged carton (kltn-docs 11 BR-09, 20 BR-20)',
            NEW.movement_type, NEW.reference_type,
            CASE WHEN NEW.movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION') THEN 'add to' ELSE 'touch' END,
            coalesce(NEW.to_location_code, NEW.from_location_code) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
