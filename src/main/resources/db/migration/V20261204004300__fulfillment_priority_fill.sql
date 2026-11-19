-- =============================================================================
-- "Ưu tiên + hàng chèn" (PRIORITY_FILL) on load plans and order estimates (kltn-docs 09 §3, §4.2; owner answer
-- RTM-LPGROUP, 19/11: "Theo dòng đơn: lúc tạo kế hoạch, Điều phối/QLK tick dòng nào là hàng chèn (mặc định là hàng
-- chính)"; RTM 09A-59, 09A-143).
--
--   * load_plan.selection_mode / load_estimate.mode take PRIORITY_FILL: the main cargo decides the vehicles, the
--     filler only tops up the room it leaves. Every mode of the docs' table is now stored ("Chỉnh tay" is the plan's
--     drag-and-drop edit, not a mode value).
--   * load_plan_package.fill - the parcel is filler cargo ("hàng chèn"): the dispatcher ticks order lines of the plan,
--     and every parcel of a ticked line is filler; untouched lines are main cargo. Read by PRIORITY_FILL only
--     (LoadPlan#setInputs clears it under another mode); a parcel that left the plan keeps what it was.
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
