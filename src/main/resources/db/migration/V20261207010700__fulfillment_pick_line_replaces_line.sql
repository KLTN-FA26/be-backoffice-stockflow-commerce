-- RTM-RTDCANCEL video QA (case 13, F3), kltn-docs 07 §4.4 step 10: a line whose carton was removed - damaged at the check
-- station, or out of a shipment the warehouse manager cancelled before dispatch - is replaced by new lines allocated
-- for it. The pick list said a removed line still waited for its replacement by what its order line lacked; with
-- several cartons of one order line removed at once (a cancelled shipment of four cartons), a line replaced already
-- still showed "replace" while its siblings lacked theirs. The new line now names the line it replaces.
--
-- Nullable: every other line - planned from the order's holds, a short pick's reallocation, a top-up - replaces none,
-- and so do the replacements made before this column.

ALTER TABLE fulfillment.pick_line
    ADD COLUMN replaces_line_id uuid;

ALTER TABLE fulfillment.pick_line
    ADD CONSTRAINT fk_pick_line_replaces_line FOREIGN KEY (replaces_line_id) REFERENCES fulfillment.pick_line (id);

ALTER TABLE fulfillment.pick_line
    ADD CONSTRAINT ck_pick_line_replaces_other CHECK (replaces_line_id IS DISTINCT FROM id);

CREATE INDEX ix_pick_line_replaces_line ON fulfillment.pick_line (replaces_line_id)
    WHERE replaces_line_id IS NOT NULL;

COMMENT ON COLUMN fulfillment.pick_line.replaces_line_id IS 'The removed line (its carton damaged at the check station, or out of a shipment cancelled before dispatch) this line was allocated to replace (07 §4.4 step 10); null for any other line.';
