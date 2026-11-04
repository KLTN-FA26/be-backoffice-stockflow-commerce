-- =============================================================================
-- Hold from QC (Jira 396/407: "On Hold can be entered from any state before Completed").
--
-- V20261010000200 left QC out of the states a hold may interrupt. An inspection that has to wait - for the
-- customer's word on a borderline unit, for the QC staff of another shift - is stopped and resumed like any other
-- step, and the resume returns to QC. Additive: rows already written satisfy the wider check.
-- =============================================================================

ALTER TABLE production.production_order DROP CONSTRAINT ck_production_order_status_before_hold;
ALTER TABLE production.production_order ADD CONSTRAINT ck_production_order_status_before_hold
    CHECK (status_before_hold IS NULL OR status_before_hold IN
        ('PENDING_PREPRESS', 'READY', 'MATERIAL_ISSUED', 'PRINTING', 'SUBCONTRACTED', 'QC'));
