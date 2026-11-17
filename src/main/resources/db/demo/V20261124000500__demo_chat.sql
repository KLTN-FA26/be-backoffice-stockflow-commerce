-- =============================================================================
-- Demo: the sales chat of the demo customer (kltn-docs 16, feature F-13 package 3). Demo only (db/demo).
--
--   * one conversation in progress with every card kind the system draws - quote request, quote, contract, transfer
--     details (with its VietQR payload), payment reminder, print sample approval, invoice, order status - a text each
--     way, and an internal note the customer never sees (BR-07);
--   * one resolved conversation the customer rated (CSAT 5).
--
-- The demo seed holds no quotes, contracts, production orders or invoices, so the cards here carry their data in their
-- payload and name no context (context_id NULL): they show how each card is drawn; their actions answer "not found"
-- because the documents behind them do not exist. The people are no-login demo accounts (as the catalogue's demo
-- authors are): a Sales agent and the customer's portal account; customer.customer.user_id is left as the seed has it.
-- The conversations are born ASSIGNED and moved along 16 §5 as tg_conversation_lifecycle requires.
-- =============================================================================

INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES ('c4a70000-0000-4000-8000-000000000001', 'demo.sales.chat', 'demo.sales.chat@stockflow.local',
        '!demo-author-no-login', 'Nguyễn Văn Sale (demo)', 'DISABLED', 0, NOW() - INTERVAL '3 days', 'flyway'),
       ('c4a70000-0000-4000-8000-000000000002', 'demo.customer.chat', 'demo.customer.chat@stockflow.local',
        '!demo-author-no-login', 'Khách hàng demo', 'DISABLED', 0, NOW() - INTERVAL '3 days', 'flyway');

-- ---------------------------------------------------------------------------------------------
-- 1. A conversation in progress, with every card
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.conversation (id, customer_id, subject, status, assigned_agent_id, assigned_at, opened_by_type,
                               opened_by, status_changed_at, first_response_due_at, version, created_at, created_by)
VALUES ('c4a70000-0000-4000-8000-000000000101', 'c0000000-0000-4000-8000-000000000001', 'Đặt in cốc giấy 12oz',
        'ASSIGNED', 'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '2 days', 'CUSTOMER',
        'c4a70000-0000-4000-8000-000000000002', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days' + INTERVAL '15 minutes',
        0, NOW() - INTERVAL '2 days', 'flyway');

INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, card_kind,
                          card_payload, card_state, card_updated_at, sent_at, version, created_at, created_by)
VALUES
    ('c4a70000-0000-4000-8000-000000000201', 'c4a70000-0000-4000-8000-000000000101', 1, 'TEXT', 'CUSTOMER',
     'CUSTOMER', 'c4a70000-0000-4000-8000-000000000002',
     'Chào Sale, bên mình muốn đặt in 500 thùng cốc giấy 12oz theo mẫu đã duyệt.', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '2 days', 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000202', 'c4a70000-0000-4000-8000-000000000101', 2, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Yêu cầu báo giá: Cốc giấy logo xanh', 'QUOTE_REQUEST',
     '{"scope":"DESIGN","requestId":"c4a70000-0000-4000-8000-000000000301","designId":"c4a70000-0000-4000-8000-000000000302","designName":"Cốc giấy logo xanh","designStatus":"APPROVED","sku":"CUP-12OZ","customerSku":"KH-DEMO-CUP-01","returnReason":null,"cartons":500,"desiredDeliveryDate":"2026-12-15","requestStatus":"CLOSED","quoteId":"c4a70000-0000-4000-8000-000000000303","quoteNumber":"QT-DEMO-0001"}',
     'ACTED', NOW() - INTERVAL '2 days' + INTERVAL '20 minutes', NOW() - INTERVAL '2 days' + INTERVAL '1 minute', 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000203', 'c4a70000-0000-4000-8000-000000000101', 3, 'TEXT', 'CUSTOMER',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001',
     'Chào anh/chị, em đã nhận yêu cầu và sẽ gửi báo giá trong hôm nay ạ.', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '2 days' + INTERVAL '10 minutes', 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000204', 'c4a70000-0000-4000-8000-000000000101', 4, 'INTERNAL_NOTE', 'STAFF',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001',
     'Khách quen, chiết khấu tối đa 3% - quá mức thì escalate Quản lý kinh doanh.', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '2 days' + INTERVAL '12 minutes', 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000205', 'c4a70000-0000-4000-8000-000000000101', 5, 'CARD', 'CUSTOMER',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001', 'Báo giá QT-DEMO-0001', 'QUOTE',
     '{"quoteId":"c4a70000-0000-4000-8000-000000000303","number":"QT-DEMO-0001","status":"ACCEPTED","revision":1,"validUntil":"2026-12-01T10:00:00Z","requestedDeliveryDate":"2026-12-15","lines":[{"id":"c4a70000-0000-4000-8000-000000000304","lineNo":1,"sku":"KH-DEMO-CUP-01","cartons":500,"unitsPerCarton":50,"units":25000,"unitPrice":1500,"lineTotal":37500000,"acceptedCartons":500}],"proposedTotal":40500000,"acceptedTotal":40500000,"currency":"VND","version":3}',
     'ACTED', NOW() - INTERVAL '1 day', NOW() - INTERVAL '2 days' + INTERVAL '3 hours', 0, NOW() - INTERVAL '2 days',
     'flyway'),
    ('c4a70000-0000-4000-8000-000000000206', 'c4a70000-0000-4000-8000-000000000101', 6, 'CARD', 'CUSTOMER',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001', 'Hợp đồng HD-DEMO-0001-1', 'CONTRACT',
     '{"contractId":"c4a70000-0000-4000-8000-000000000305","number":"HD-DEMO-0001-1","orderId":"c4a70000-0000-4000-8000-000000000306","orderNumber":"SO-DEMO-0001","status":"SIGNED","totalAmount":40500000,"currency":"VND","freeSampleRounds":2,"paymentPlan":[{"seq":1,"name":"Đợt 1 - đặt cọc","milestone":"ON_SIGNING","amount":15000000,"percent":null,"dueDays":3,"deliveryBatchSeq":null},{"seq":2,"name":"Đợt 2 - sau giao hàng","milestone":"AFTER_DELIVERY","amount":25500000,"percent":null,"dueDays":30,"deliveryBatchSeq":null}],"documentPath":"/api/v1/orders/me/c4a70000-0000-4000-8000-000000000306/contract/document"}',
     'ACTED', NOW() - INTERVAL '20 hours', NOW() - INTERVAL '1 day', 0, NOW() - INTERVAL '1 day', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000207', 'c4a70000-0000-4000-8000-000000000101', 7, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Thông tin chuyển khoản HD-DEMO-0001-1 DOT1', 'PAYMENT_INFO',
     '{"installmentId":"c4a70000-0000-4000-8000-000000000307","contractId":"c4a70000-0000-4000-8000-000000000305","contractNumber":"HD-DEMO-0001-1","orderId":"c4a70000-0000-4000-8000-000000000306","orderNumber":"SO-DEMO-0001","seq":1,"name":"Đợt 1 - đặt cọc","amount":15000000,"installmentAmount":15000000,"currency":"VND","dueDate":"2026-11-16","overdue":false,"status":"DUE","transferContent":"HD-DEMO-0001-1 DOT1","reminder":false,"bank":{"bankName":"DEMO BANK (not a real bank)","bankBin":"970000","accountNumber":"0000000000","accountName":"DEMO STOCKFLOW CO LTD"},"vietQr":"00020101021238540010A00000072701240006970000011000000000000208QRIBFTTA53037045408150000005802VN62230819HD-DEMO-0001-1 DOT163044EDE"}',
     'OPEN', NULL, NOW() - INTERVAL '20 hours', 0, NOW() - INTERVAL '20 hours', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000208', 'c4a70000-0000-4000-8000-000000000101', 8, 'CARD', 'CUSTOMER',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001', 'Nhắc thanh toán HD-DEMO-0001-1 DOT2', 'PAYMENT_REMINDER',
     '{"installmentId":"c4a70000-0000-4000-8000-000000000308","contractId":"c4a70000-0000-4000-8000-000000000305","contractNumber":"HD-DEMO-0001-1","orderId":"c4a70000-0000-4000-8000-000000000306","orderNumber":"SO-DEMO-0001","seq":2,"name":"Đợt 2 - sau giao hàng","amount":35000000,"installmentAmount":35000000,"currency":"VND","dueDate":"2026-11-10","overdue":true,"status":"DUE","transferContent":"HD-DEMO-0001-1 DOT2","reminder":true,"bank":{"bankName":"DEMO BANK (not a real bank)","bankBin":"970000","accountNumber":"0000000000","accountName":"DEMO STOCKFLOW CO LTD"},"vietQr":"00020101021238540010A00000072701240006970000011000000000000208QRIBFTTA53037045408350000005802VN62230819HD-DEMO-0001-1 DOT26304186B"}',
     'OPEN', NULL, NOW() - INTERVAL '19 hours', 0, NOW() - INTERVAL '19 hours', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000209', 'c4a70000-0000-4000-8000-000000000101', 9, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Duyệt mẫu in vòng 1 - PO-DEMO-0001', 'SAMPLE_APPROVAL',
     '{"roundId":"c4a70000-0000-4000-8000-000000000309","roundNo":1,"productionOrderId":"c4a70000-0000-4000-8000-000000000310","productionOrderCode":"PO-DEMO-0001","productionOrderStatus":"SAMPLE_PENDING","salesOrderId":"c4a70000-0000-4000-8000-000000000306","status":"WAITING","physicalSampleSentOn":null,"skus":["KH-DEMO-CUP-01"],"media":[{"id":"c4a70000-0000-4000-8000-000000000311","fileName":"mau-in-vong-1.jpg","contentType":"image/jpeg","video":false}],"earlier":[],"mediaPath":"/api/v1/customer/production-samples/c4a70000-0000-4000-8000-000000000309/media/{mediaId}/view-url"}',
     'OPEN', NULL, NOW() - INTERVAL '6 hours', 0, NOW() - INTERVAL '6 hours', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000210', 'c4a70000-0000-4000-8000-000000000101', 10, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Hoá đơn C26TAA 0000123', 'INVOICE',
     '{"id":"c4a70000-0000-4000-8000-000000000312","code":"INV-DEMO-0001","status":"ISSUED","orderId":"c4a70000-0000-4000-8000-000000000306","orderNumber":"SO-DEMO-0001","templateCode":"1","symbol":"C26TAA","invoiceNumber":123,"issueDate":"2026-11-12","currency":"VND","subtotal":37500000,"taxTotal":3000000,"grandTotal":40500000,"hasXml":true,"hasPdf":true,"files":{"XML":"/api/v1/einvoices/me/c4a70000-0000-4000-8000-000000000312/files/XML","PDF":"/api/v1/einvoices/me/c4a70000-0000-4000-8000-000000000312/files/PDF"}}',
     'OPEN', NULL, NOW() - INTERVAL '3 hours', 0, NOW() - INTERVAL '3 hours', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000211', 'c4a70000-0000-4000-8000-000000000101', 11, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Đơn SO-DEMO-0001: DELIVERED', 'ORDER_STATUS',
     '{"eventKey":"c4a70000-0000-4000-8000-000000000313","orderId":"c4a70000-0000-4000-8000-000000000306","orderNumber":"SO-DEMO-0001","shipmentId":"c4a70000-0000-4000-8000-000000000314","shipmentCode":"SH-DEMO-0001","status":"DELIVERED","failureReasonCode":null,"carrierName":"Xe tải Minh Phát","packageCount":500,"occurredAt":"2026-11-12T08:30:00Z"}',
     'OPEN', NULL, NOW() - INTERVAL '2 hours', 0, NOW() - INTERVAL '2 hours', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000212', 'c4a70000-0000-4000-8000-000000000101', 12, 'TEXT', 'CUSTOMER',
     'CUSTOMER', 'c4a70000-0000-4000-8000-000000000002', 'Mẫu in đẹp, mình xem kỹ rồi duyệt trên thẻ nhé.', NULL, NULL,
     NULL, NULL, NOW() - INTERVAL '1 hour', 0, NOW() - INTERVAL '1 hour', 'flyway');

UPDATE chat.conversation
   SET status = 'ACTIVE', status_changed_at = NOW() - INTERVAL '2 days' + INTERVAL '10 minutes',
       first_responded_at = NOW() - INTERVAL '2 days' + INTERVAL '10 minutes', last_message_seq = 12,
       last_message_at = NOW() - INTERVAL '1 hour', last_customer_message_at = NOW() - INTERVAL '1 hour'
 WHERE id = 'c4a70000-0000-4000-8000-000000000101';

-- ---------------------------------------------------------------------------------------------
-- 2. A resolved conversation the customer rated
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.conversation (id, customer_id, subject, status, assigned_agent_id, assigned_at, opened_by_type,
                               opened_by, status_changed_at, first_response_due_at, version, created_at, created_by)
VALUES ('c4a70000-0000-4000-8000-000000000102', 'c0000000-0000-4000-8000-000000000001', 'Hỏi lịch giao hàng',
        'ASSIGNED', 'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '3 days', 'CUSTOMER',
        'c4a70000-0000-4000-8000-000000000002', NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days' + INTERVAL '15 minutes',
        0, NOW() - INTERVAL '3 days', 'flyway');

INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, card_kind,
                          card_payload, card_state, card_updated_at, sent_at, version, created_at, created_by)
VALUES
    ('c4a70000-0000-4000-8000-000000000221', 'c4a70000-0000-4000-8000-000000000102', 1, 'TEXT', 'CUSTOMER',
     'CUSTOMER', 'c4a70000-0000-4000-8000-000000000002', 'Cho mình hỏi đơn tuần sau giao ngày nào?', NULL, NULL,
     NULL, NULL, NOW() - INTERVAL '3 days', 0, NOW() - INTERVAL '3 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000222', 'c4a70000-0000-4000-8000-000000000102', 2, 'TEXT', 'CUSTOMER',
     'AGENT', 'c4a70000-0000-4000-8000-000000000001', 'Dạ đơn giao thứ Ba tuần sau, buổi sáng ạ.', NULL, NULL, NULL,
     NULL, NOW() - INTERVAL '3 days' + INTERVAL '8 minutes', 0, NOW() - INTERVAL '3 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000223', 'c4a70000-0000-4000-8000-000000000102', 3, 'CARD', 'CUSTOMER',
     'SYSTEM', NULL, 'Anh/chị hài lòng với hỗ trợ lần này chứ?', 'CSAT_SURVEY',
     '{"conversationId":"c4a70000-0000-4000-8000-000000000102","scale":5,"score":5}', 'ACTED',
     NOW() - INTERVAL '3 days' + INTERVAL '1 hour', NOW() - INTERVAL '3 days' + INTERVAL '30 minutes', 0,
     NOW() - INTERVAL '3 days', 'flyway');

UPDATE chat.conversation
   SET status = 'ACTIVE', status_changed_at = NOW() - INTERVAL '3 days' + INTERVAL '8 minutes',
       first_responded_at = NOW() - INTERVAL '3 days' + INTERVAL '8 minutes', last_message_seq = 3,
       last_message_at = NOW() - INTERVAL '3 days' + INTERVAL '30 minutes',
       last_customer_message_at = NOW() - INTERVAL '3 days'
 WHERE id = 'c4a70000-0000-4000-8000-000000000102';
UPDATE chat.conversation
   SET status = 'RESOLVED', status_changed_at = NOW() - INTERVAL '3 days' + INTERVAL '30 minutes',
       resolved_at = NOW() - INTERVAL '3 days' + INTERVAL '30 minutes', resolved_by = 'c4a70000-0000-4000-8000-000000000001',
       reopen_until = NOW() - INTERVAL '3 days' + INTERVAL '30 minutes' + INTERVAL '14 days',
       csat_score = 5, csat_comment = 'Trả lời nhanh, rõ ràng.', csat_submitted_at = NOW() - INTERVAL '3 days' + INTERVAL '1 hour'
 WHERE id = 'c4a70000-0000-4000-8000-000000000102';

-- ---------------------------------------------------------------------------------------------
-- What the read markers and the log (16 BR-01) would hold
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.participant_read (id, conversation_id, user_id, reader_type, last_read_seq, last_read_message_id,
                                   last_read_at, version, created_at, created_by)
VALUES ('c4a70000-0000-4000-8000-000000000401', 'c4a70000-0000-4000-8000-000000000101',
        'c4a70000-0000-4000-8000-000000000001', 'STAFF', 11, 'c4a70000-0000-4000-8000-000000000211',
        NOW() - INTERVAL '2 hours', 0, NOW() - INTERVAL '2 hours', 'flyway'),
       ('c4a70000-0000-4000-8000-000000000402', 'c4a70000-0000-4000-8000-000000000102',
        'c4a70000-0000-4000-8000-000000000002', 'CUSTOMER', 3, 'c4a70000-0000-4000-8000-000000000223',
        NOW() - INTERVAL '3 days' + INTERVAL '1 hour', 0, NOW() - INTERVAL '3 days', 'flyway');

INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, actor_id, occurred_at, from_status,
                                     to_status, message_id, target_user_id, note, detail, version, created_at,
                                     created_by)
VALUES
    ('c4a70000-0000-4000-8000-000000000501', 'c4a70000-0000-4000-8000-000000000101', 'OPENED', 'CUSTOMER',
     'c4a70000-0000-4000-8000-000000000002', NOW() - INTERVAL '2 days', NULL, NULL, NULL, NULL, NULL, NULL, 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000502', 'c4a70000-0000-4000-8000-000000000101', 'ASSIGNED', 'SYSTEM', NULL,
     NOW() - INTERVAL '2 days', NULL, NULL, NULL, 'c4a70000-0000-4000-8000-000000000001', NULL, NULL, 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000503', 'c4a70000-0000-4000-8000-000000000101', 'CARD_SENT', 'SYSTEM', NULL,
     NOW() - INTERVAL '2 days' + INTERVAL '1 minute', NULL, NULL, 'c4a70000-0000-4000-8000-000000000202', NULL, NULL,
     '{"card":"QUOTE_REQUEST"}', 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000504', 'c4a70000-0000-4000-8000-000000000101', 'FIRST_RESPONSE', 'AGENT',
     'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '2 days' + INTERVAL '10 minutes', NULL, NULL, NULL,
     NULL, NULL, NULL, 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000505', 'c4a70000-0000-4000-8000-000000000101', 'INTERNAL_NOTE', 'AGENT',
     'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '2 days' + INTERVAL '12 minutes', NULL, NULL,
     'c4a70000-0000-4000-8000-000000000204', NULL, NULL, NULL, 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000506', 'c4a70000-0000-4000-8000-000000000101', 'CARD_ACTION', 'AGENT',
     'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '2 days' + INTERVAL '20 minutes', NULL, NULL,
     'c4a70000-0000-4000-8000-000000000202', NULL, 'CREATE_QUOTE',
     '{"quoteId":"c4a70000-0000-4000-8000-000000000303"}', 0, NOW() - INTERVAL '2 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000507', 'c4a70000-0000-4000-8000-000000000101', 'CARD_ACTION', 'CUSTOMER',
     'c4a70000-0000-4000-8000-000000000002', NOW() - INTERVAL '1 day', NULL, NULL,
     'c4a70000-0000-4000-8000-000000000205', NULL, 'ACCEPT', '{"quoteStatus":"ACCEPTED"}', 0, NOW() - INTERVAL '1 day',
     'flyway'),
    ('c4a70000-0000-4000-8000-000000000508', 'c4a70000-0000-4000-8000-000000000102', 'STATUS_CHANGED', 'AGENT',
     'c4a70000-0000-4000-8000-000000000001', NOW() - INTERVAL '3 days' + INTERVAL '30 minutes', 'ACTIVE', 'RESOLVED',
     NULL, NULL, NULL, NULL, 0, NOW() - INTERVAL '3 days', 'flyway'),
    ('c4a70000-0000-4000-8000-000000000509', 'c4a70000-0000-4000-8000-000000000102', 'CSAT_SUBMITTED', 'CUSTOMER',
     'c4a70000-0000-4000-8000-000000000002', NOW() - INTERVAL '3 days' + INTERVAL '1 hour', NULL, NULL, NULL, NULL,
     NULL, '{"score":5}', 0, NOW() - INTERVAL '3 days', 'flyway');

-- A saved reply shared with every agent, naming two of the variables the server fills in.
INSERT INTO chat.canned_response (id, owner_id, title, body, shortcut, category, version, created_at, created_by)
VALUES ('c4a70000-0000-4000-8000-000000000601', NULL, 'Chào khách',
        'Chào {customerName}, em là {agentName} bên StockFlow. Em có thể hỗ trợ gì cho anh/chị ạ?', 'chao-khach',
        'Mở đầu', 0, NOW() - INTERVAL '3 days', 'flyway');
