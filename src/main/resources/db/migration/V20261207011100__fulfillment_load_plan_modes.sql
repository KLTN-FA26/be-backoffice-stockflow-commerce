-- =============================================================================
-- Every selection mode of kltn-docs 09 §4.2 on a load plan and an order's load estimate (owner decision WF4-LP-MODES,
-- 12/11; RTM 09A-58, 09A-59, 09A-142..09A-144). The planner has them since the trial estimate (WF4-TRIAL-EST):
--
--   * KEEP_LINES ("Giữ nguyên dòng"): the cartons of one order line ("dòng hàng") ride in one vehicle;
--   * PRIORITY_FILL ("Ưu tiên + hàng chèn"): the main cargo decides the vehicles, the filler only tops up the room it
--     leaves - filler ticked by order line when the plan is made, main cargo by default (owner answer RTM-LPGROUP,
--     19/11: "Theo dòng đơn: lúc tạo kế hoạch, Điều phối/QLK tick dòng nào là hàng chèn (mặc định là hàng chính)");
--   * OVER_CAPACITY ("Vượt sức chứa"): as many cartons as the vehicles on offer take, the rest reported left behind.
--
-- "Chỉnh tay" is the plan's drag-and-drop edit and per-vehicle recalculation (09 §4.2 step 3, BR-11), not a mode value.
--
--   * load_plan_package.fill - the parcel is filler cargo ("hàng chèn"): its order line was ticked. Read by
--     PRIORITY_FILL only (LoadPlan#setInputs clears it under another mode); a parcel that left the plan keeps what it
--     was while it was in it.
-- =============================================================================

ALTER TABLE fulfillment.load_plan
    DROP CONSTRAINT ck_load_plan_mode,
    ADD CONSTRAINT ck_load_plan_mode CHECK (selection_mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES', 'KEEP_LINES',
        'PRIORITY_FILL', 'OVER_CAPACITY'));

ALTER TABLE fulfillment.load_estimate
    DROP CONSTRAINT ck_load_estimate_mode,
    ADD CONSTRAINT ck_load_estimate_mode CHECK (mode IN ('SINGLE_TYPE', 'FEWEST_VEHICLES', 'KEEP_LINES',
        'PRIORITY_FILL', 'OVER_CAPACITY'));

ALTER TABLE fulfillment.load_plan_package
    ADD COLUMN fill BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN fulfillment.load_plan_package.fill IS
    'Filler cargo of a PRIORITY_FILL plan ("hàng chèn", 09 §3): its order line was ticked; it only tops up the room the main cargo leaves.';
