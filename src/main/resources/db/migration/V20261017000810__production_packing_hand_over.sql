-- =============================================================================
-- Where a completed production order's goods were handed over (SCRUM-400/412/420).
--
-- Decided 14/10/2026: the hand-over to packing stays automatic. The QC round (or the subcontract step)
-- that completes an ORDER moves its goods, still held for the sales order, to a PACKING area in the same
-- transaction; nobody confirms it a second time. Until now only the stock ledger knew which area: the
-- completed list could say "COMPLETED" but not "the goods are at HCM-PACK01". packing_location_code
-- records it on the production order, written once by the hand-over and never edited.
--
-- Data: completed ORDER production orders written before this file get the PACKING area of their own
-- ledger line (inventory.stock_movement MOVE, reference PRODUCTION_ORDER, to a PACKING area), latest
-- first. One that has no such line stays NULL - nothing is invented for it.
-- =============================================================================

ALTER TABLE production.production_order
    ADD COLUMN packing_location_code VARCHAR(64),
    -- Cross-schema reference as a real foreign key (ADR-0007).
    ADD CONSTRAINT fk_production_order_packing_location FOREIGN KEY (packing_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT;

UPDATE production.production_order po
   SET packing_location_code = handed.to_location_code
  FROM (SELECT DISTINCT ON (sm.reference_id) sm.reference_id, sm.to_location_code
          FROM inventory.stock_movement sm
          JOIN warehouse.storage_location sl ON sl.location_code = sm.to_location_code
          JOIN warehouse.area a ON a.location_id = sl.id AND a.type = 'PACKING'
         WHERE sm.reference_type = 'PRODUCTION_ORDER'
           AND sm.movement_type = 'MOVE'
         ORDER BY sm.reference_id, sm.occurred_at DESC, sm.id DESC) handed
 WHERE handed.reference_id = po.id
   AND po.type = 'ORDER'
   AND po.status = 'COMPLETED';

-- Only a completed ORDER hands goods to packing; a SAMPLE's are written off, a cancelled order's go back.
ALTER TABLE production.production_order
    ADD CONSTRAINT ck_production_order_packing_location
        CHECK (packing_location_code IS NULL OR (type = 'ORDER' AND status = 'COMPLETED'));

COMMENT ON COLUMN production.production_order.packing_location_code IS
    'The PACKING area the goods of a completed ORDER were moved to when it completed (automatic hand-over, SCRUM-400).';
