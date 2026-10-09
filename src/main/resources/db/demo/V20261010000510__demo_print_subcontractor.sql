-- =============================================================================
-- DEMO DATA, local profile only: a print subcontractor (SCRUM-434), so a production split has
-- somewhere to send its SUBCONTRACT purchase order. Loss tolerance 3 % on the blanks it is sent.
-- =============================================================================

INSERT INTO procurement.suppliers (id, code, name, legal_name, tax_id, currency, payment_terms,
                                   over_receipt_tolerance, lead_time_days, is_print_subcontractor,
                                   loss_tolerance_percent, created_by)
VALUES (md5('demo:supplier:INNHANH')::uuid, 'INNHANH', 'Xưởng in Nhanh', 'Công ty TNHH In Nhanh (demo)',
        '0300000002', 'VND', 'NET15', 0, 5, TRUE, 3, 'flyway');

INSERT INTO procurement.supplier_contacts (id, supplier_id, full_name, phone, email, is_primary, created_by)
VALUES (md5('demo:contact:INNHANH')::uuid, md5('demo:supplier:INNHANH')::uuid, 'Trần Thị Demo',
        '0900000003', 'xuong@innhanh.example', TRUE, 'flyway');
