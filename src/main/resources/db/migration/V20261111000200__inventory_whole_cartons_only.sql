-- =============================================================================
-- Stock is kept, moved, received, put away and allocated in whole LPN cartons only (team plan 4C-2; kltn-docs 07
-- BR-03, BR-05; 05 BR-08; 11 BR-04, BR-05; 03 BR-12, BR-13; 20 §4.6 steps 5-6, §5.5, §5.6).
--
-- V20261111000110 put every unit on hand into a whole, sealed carton of its SKU's carton count. From here the code
-- writes no loose stock on these paths and opens no carton; this states the same rules in the database, so a path that
-- bypassed the code is still refused. Each rule names the code that states it first.
--
--   1. tg_stock_package_transition (StockPackage):
--        * CONSUMED -> IN_STOCK is allowed, sealed, and only that transition seals a carton again: a carton that left
--          stock whole comes back whole - put back after its order was cancelled, or received again as a return (20 §4.6
--          steps 5-6, §5.5). StockPackage.restock.
--        * a carton in stock is never opened (unopened TRUE -> FALSE while IN_STOCK): nothing takes part of a carton.
--          StockPackageContents.settle / adjust refuse it first (STOCK_PACKAGE_TAKEN_WHOLE).
--        * a carton is counted at its standard count and goes into stock only at it (03 BR-13): received_qty, when
--          set, is nominal_qty; RECEIVING -> IN_STOCK needs received_qty = nominal_qty. StockPackage.count /
--          putInStock. A draft counted before this at another figure is recounted before its receipt is confirmed.
--          The trigger sees updates only; a carton INSERTed straight into stock off its standard count is the row CHECK
--          team plan 4C-3 adds with the other tightened constraints (its count tests still make such cartons).
--   2. A receipt puts every unit into a carton (03 BR-12): a RECEIPT ledger line of a goods-receipt line names the
--      carton it arrived in. StockOperationsServiceImpl.receive (RECEIPT_LINE_NOT_IN_CARTONS).
--   3. A person moves cartons, not units (11 BR-04): a MOVE line of a MOVE_TASK names its carton on both sides. The
--      move endpoint takes LPNs only.
--   4. A putaway puts a carton away (05 BR-08): a MOVE or STATUS_CHANGE line of a PUTAWAY_TASK names the carton it
--      arrives in, and a putaway task of a goods-receipt line names its carton. PutawayService, PutawayTaskCreator.
--   5. A carton held for an order is not moved by hand (11 BR-05): a MOVE_TASK line may not name a carton with units
--      held. StockOperationsServiceImpl.relocatePackages (STOCK_PACKAGE_HELD).
--   6. An allocation takes a carton whole (07 BR-03, 20 §5.6: one allocation per carton): a live allocation of a row
--      in a carton holds the whole row. StockAllocator.
--
-- Rules 2-4 are CHECKs NOT VALID: the ledger and the putaway tasks written before keep their loose lines (they are
-- history, and V20261111000110 archived what it changed), while every new or changed row is checked. Loose stock is
-- still written by a customer return (receiveReturn / reclassify, references RETURN_IN and RETURN_REQUEST_LINE, team
-- plan 4D-2) and a put-back of a pick line (PUT_BACK, team plan 4C-4); none of these rules reaches them. NOT NULL on
-- stock_item.stock_package_id is team plan 4C-3.
--
-- Nothing here changes data.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The carton lifecycle (V20261020000100's function, with the way back from CONSUMED, no opening in stock, and
--    the standard count)
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION inventory.check_stock_package_transition()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'RECEIVING' AND NEW.status IN ('IN_STOCK', 'VOID'))
        OR (OLD.status = 'IN_STOCK' AND NEW.status IN ('CONSUMED', 'LOST'))
        OR (OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK')
        OR (OLD.status = 'LOST' AND NEW.status = 'IN_STOCK')) THEN
        RAISE EXCEPTION 'stock package % cannot go from % to %', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.received_qty IS DISTINCT FROM OLD.received_qty AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % was counted in at %; a later difference is an adjustment', NEW.lpn,
            OLD.received_qty USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.received_qty IS DISTINCT FROM OLD.received_qty AND NEW.received_qty <> NEW.nominal_qty THEN
        RAISE EXCEPTION 'stock package % holds % per carton; it is not counted at %', NEW.lpn, NEW.nominal_qty,
            NEW.received_qty USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'RECEIVING' AND NEW.status = 'IN_STOCK' AND NEW.received_qty IS DISTINCT FROM NEW.nominal_qty THEN
        RAISE EXCEPTION 'stock package % was counted at %; only a carton of its standard count % goes into stock',
            NEW.lpn, NEW.received_qty, NEW.nominal_qty USING ERRCODE = 'check_violation';
    END IF;
    -- Sealed again only when it comes back whole from CONSUMED; otherwise once opened, it stays open.
    IF NEW.unopened AND NOT OLD.unopened AND NOT (OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK') THEN
        RAISE EXCEPTION 'stock package % was opened; it cannot be unopened again', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    -- A carton comes back from CONSUMED whole, so sealed: nothing in stock is an opened carton.
    IF OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK' AND NOT NEW.unopened THEN
        RAISE EXCEPTION 'stock package % comes back into stock whole, sealed', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    -- Nothing takes part of a carton: one in stock is never opened.
    IF OLD.unopened AND NOT NEW.unopened AND NEW.status = 'IN_STOCK' THEN
        RAISE EXCEPTION 'stock package % is in stock; a carton is taken whole, never opened', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_transition() IS
    'Carton lifecycle (kltn-docs 20 §5.5): RECEIVING->IN_STOCK|VOID, IN_STOCK->CONSUMED|LOST, CONSUMED|LOST->IN_STOCK; '
    'counted only at the standard count; never opened in stock; sealed again only on CONSUMED->IN_STOCK.';


-- ---------------------------------------------------------------------------------------------
-- 2-4. Receipts, moves by hand and putaways name their cartons
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_receipt_in_carton CHECK (
    movement_type <> 'RECEIPT' OR reference_type <> 'GOODS_RECEIPT_LINE' OR to_stock_package_id IS NOT NULL) NOT VALID;

ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_move_task_carton CHECK (
    movement_type <> 'MOVE' OR reference_type <> 'MOVE_TASK'
    OR (from_stock_package_id IS NOT NULL AND to_stock_package_id IS NOT NULL)) NOT VALID;

ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_putaway_carton CHECK (
    movement_type NOT IN ('MOVE', 'STATUS_CHANGE') OR reference_type <> 'PUTAWAY_TASK'
    OR to_stock_package_id IS NOT NULL) NOT VALID;

ALTER TABLE warehouse.putaway_task ADD CONSTRAINT ck_putaway_task_receipt_carton CHECK (
    goods_receipt_line_id IS NULL OR stock_package_id IS NOT NULL) NOT VALID;

COMMENT ON CONSTRAINT ck_stock_movement_receipt_in_carton ON inventory.stock_movement IS
    'kltn-docs 03 BR-12: every unit received is in a carton. NOT VALID: loose receipt lines before 4C-2 are history.';
COMMENT ON CONSTRAINT ck_stock_movement_move_task_carton ON inventory.stock_movement IS
    'kltn-docs 11 BR-04: a person moves whole cartons. NOT VALID: loose moves before 4C-2 are history.';
COMMENT ON CONSTRAINT ck_stock_movement_putaway_carton ON inventory.stock_movement IS
    'kltn-docs 05 BR-08: a putaway puts a carton away. NOT VALID: loose putaways before 4C-2 are history.';
COMMENT ON CONSTRAINT ck_putaway_task_receipt_carton ON warehouse.putaway_task IS
    'kltn-docs 05 BR-08: a putaway task of a receipt line is one carton. NOT VALID: loose tasks before 4C-2 are history.';


-- ---------------------------------------------------------------------------------------------
-- 5. A carton held for an order is not moved by hand. The holds travel with the rows, so whether the carton is held is
--    the same before and after the rows moved: read when the ledger line is written, after them.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_stock_movement_manual_move()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    held_lpn VARCHAR(30);
BEGIN
    SELECT p.lpn INTO held_lpn
      FROM inventory.stock_package p
     WHERE p.id = NEW.from_stock_package_id
       AND EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = p.id AND s.reserved > 0);
    IF FOUND THEN
        RAISE EXCEPTION 'stock package % is held for an order; it is not moved by hand', held_lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_manual_move BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW WHEN (NEW.movement_type = 'MOVE' AND NEW.reference_type = 'MOVE_TASK'
                       AND NEW.from_stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_movement_manual_move();


-- ---------------------------------------------------------------------------------------------
-- 6. An allocation takes a carton whole: a live allocation of a row in a carton holds everything the row holds (a
--    carton is one row of the SKU's count since V20261111000110). Loose rows are not this rule's: what is still
--    written loose (a return, a put-back, a damaged share) is held as it always was.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventory.check_allocation_whole_carton()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    row_package UUID;
    row_on_hand INTEGER;
BEGIN
    SELECT stock_package_id, on_hand INTO row_package, row_on_hand
      FROM inventory.stock_item WHERE id = NEW.stock_item_id;
    IF FOUND AND row_package IS NOT NULL AND NEW.quantity <> row_on_hand THEN
        RAISE EXCEPTION 'allocation % holds % of a carton row holding %; a carton is allocated whole', NEW.id,
            NEW.quantity, row_on_hand USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_reservation_whole_carton AFTER INSERT OR UPDATE OF quantity, status, stock_item_id
    ON inventory.stock_reservation
    FOR EACH ROW WHEN (NEW.allocated_from_id IS NOT NULL AND NEW.status = 'HELD' AND NEW.stock_item_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_allocation_whole_carton();
