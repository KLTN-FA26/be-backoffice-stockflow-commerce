-- =============================================================================
-- More selection modes of kltn-docs 09 §4.2 on a load plan and an order's load estimate (owner decision
-- WF4-LP-MODES, 12/11; RTM 09A-58, 09A-142, 09A-144). The planner has them since the trial estimate (WF4-TRIAL-EST):
--
--   * KEEP_LINES ("Giữ nguyên dòng"): the cartons of one order line ("dòng hàng") ride in one vehicle;
--   * OVER_CAPACITY ("Vượt sức chứa"): as many cartons as the vehicles on offer take, the rest reported left behind.
--
-- PRIORITY_FILL ("Ưu tiên + hàng chèn") is not stored yet: it needs the main / filler split of 09 §3, and the docs do
-- not say at what level (order line, shipment, parcel) or by whom it is marked on a plan (docs issue #40, question 4).
-- "Chỉnh tay" is the plan's manual edit and per-vehicle recalculation (09 §4.2 step 3, BR-11), not a stored mode.
-- =============================================================================

ALTER TABLE fulfillment.load_plan
    DROP CONSTRAINT ck_load_plan_mode,
    ADD CONSTRAINT ck_load_plan_mode CHECK (selection_mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES', 'KEEP_LINES',
        'OVER_CAPACITY'));

ALTER TABLE fulfillment.load_estimate
    DROP CONSTRAINT ck_load_estimate_mode,
    ADD CONSTRAINT ck_load_estimate_mode CHECK (mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES', 'KEEP_LINES',
        'OVER_CAPACITY'));
