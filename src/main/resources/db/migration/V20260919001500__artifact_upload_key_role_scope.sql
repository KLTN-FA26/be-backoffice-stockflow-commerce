-- Role is part of the idempotency namespace; leave room for UUID + role + a 255-char client key.
ALTER TABLE design.design_artifact ALTER COLUMN upload_key TYPE VARCHAR(400);
