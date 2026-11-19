-- RTM-WBIMPORT-WH (kltn-docs 09 §3, §4.4): a waybill import is made for one warehouse chosen when importing, and only
-- that warehouse's loads are read from the file. Imports made before keep no warehouse (null).
ALTER TABLE fulfillment.waybill_import ADD COLUMN warehouse_id uuid;

ALTER TABLE fulfillment.waybill_import
    ADD CONSTRAINT fk_waybill_import_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT;

CREATE INDEX ix_waybill_import_warehouse ON fulfillment.waybill_import (warehouse_id);
