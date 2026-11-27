ALTER TABLE fulfillment.dispatch_load DROP CONSTRAINT ck_dispatch_load_cancel_reason_code;
ALTER TABLE fulfillment.dispatch_load ADD CONSTRAINT ck_dispatch_load_cancel_reason_code
    CHECK (cancel_reason_code IS NULL OR (status = 'CANCELLED' AND cancel_reason_code IN
        ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'THUNG_HU', 'CHUA_THU_TIEN', 'KHONG_CO_XE_THAY', 'KHAC')));

ALTER TABLE fulfillment.load_plan DROP CONSTRAINT ck_load_plan_cancel_reason_code;
ALTER TABLE fulfillment.load_plan ADD CONSTRAINT ck_load_plan_cancel_reason_code
    CHECK (cancel_reason_code IS NULL OR (status = 'CANCELLED' AND cancel_reason_code IN
        ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'THUNG_HU', 'CHUA_THU_TIEN', 'KHONG_CO_XE_THAY', 'KHAC')));

ALTER TABLE fulfillment.dispatch_load_unload DROP CONSTRAINT ck_dispatch_load_unload_reason_code;
ALTER TABLE fulfillment.dispatch_load_unload ADD CONSTRAINT ck_dispatch_load_unload_reason_code
    CHECK (reason_code IS NULL OR reason_code IN
        ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'THUNG_HU', 'CHUA_THU_TIEN', 'KHONG_CO_XE_THAY', 'KHAC'));
