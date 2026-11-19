-- kltn-docs 07 section 4.5 step 13: the put-back queue lists the cartons of the task cancelled earliest first.
-- The task never recorded when it was cancelled, so the queue could only go by when its cartons were picked.
-- Rows cancelled before this column existed take their last write, the moment the cancellation saved them.
ALTER TABLE fulfillment.pick ADD COLUMN cancelled_at timestamp with time zone;

COMMENT ON COLUMN fulfillment.pick.cancelled_at IS 'When the task was cancelled (the order was cancelled, or the rest of it closed); null while it is not. Orders the put-back queue, earliest cancellation first (kltn-docs 07 section 4.5 step 13).';

UPDATE fulfillment.pick SET cancelled_at = COALESCE(last_modified_at, created_at) WHERE status = 'CANCELLED';

-- The invariant, stated in the table as well as in PickJpaEntity#cancel: a task has a cancellation time exactly while it
-- is cancelled. After the backfill, so the rows cancelled before this column existed satisfy it.
ALTER TABLE fulfillment.pick ADD CONSTRAINT ck_pick_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));
