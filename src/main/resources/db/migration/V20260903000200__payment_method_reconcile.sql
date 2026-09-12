-- Reconciles payment.payment.method with com.stockflow.payment.internal.domain.PaymentMethod
-- after EWALLET was renamed to E_WALLET (consistent naming with the other enum members) and
-- DEPOSIT was added (already present in com.stockflow.contracts.PaymentMethod, the event-contract
-- copy of this enum, but missing here — see both enums' javadoc for why the two are kept separate).
ALTER TABLE payment.payment
    DROP CONSTRAINT ck_payment_method;

UPDATE payment.payment
    SET method = 'E_WALLET'
    WHERE method = 'EWALLET';

ALTER TABLE payment.payment
    ADD CONSTRAINT ck_payment_method CHECK (method IN ('CARD', 'BANK_TRANSFER', 'COD', 'E_WALLET', 'DEPOSIT'));
