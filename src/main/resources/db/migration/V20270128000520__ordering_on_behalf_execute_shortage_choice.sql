-- =============================================================================
-- "Làm hộ" for carrying out a customer's choice on a shortage card (kltn-docs 17 §4.14 step 58, BR-32, BR-33). Module:
-- order.
--
-- Why. Any Sales holding the permissions may carry out the option the customer chose on the card "Đơn thiếu N thùng";
-- done by someone other than the customer's owner or the owner's cover, the order's history records it as done on their
-- behalf, as one line for the press of "Thực hiện" (the steps it runs inside are not recorded again). The action list of
-- ck_order_on_behalf_action_action follows OnBehalfAction.
-- =============================================================================

ALTER TABLE ordering.order_on_behalf_action DROP CONSTRAINT ck_order_on_behalf_action_action;
ALTER TABLE ordering.order_on_behalf_action ADD CONSTRAINT ck_order_on_behalf_action_action CHECK (action IN (
    'CHOOSE_SHIPPING_WAREHOUSE', 'HOLD', 'RESOLVE_HOLD', 'HOLD_COMPLETION', 'RELEASE_COMPLETION_HOLD',
    'APPROVE_CANCELLATION_REQUEST', 'REJECT_CANCELLATION_REQUEST', 'CANCEL_ORDER', 'CLOSE_REMAINDER',
    'DECIDE_PARTIAL_SHIPMENT', 'CANCEL_UNDELIVERED', 'CHANGE_SHIPPING_ADDRESS', 'EXECUTE_SHORTAGE_CHOICE'));
