# Bounded purchase-order publication retry

## Decision

Keep Spring Modulith 1.4.1 as the only durable event queue. A read-only adapter in common reads at most 50 incomplete publications for the PO event type and its exact listener ID, using keyset pagination and an incomplete-row index. Cursor ordering uses PostgreSQL UUID order on both selection and continuation. An empty tail wraps to the beginning; cursor persistence survives restarts and node changes.

The scheduler deserializes with the framework EventSerializer and invokes the injected PurchaseOrderNotificationListener proxy. It does not publish a new event, manually call SMTP, or write completion_date. Spring's existing async and transactional advice and Modulith CompletionRegisteringAdvisor still complete or fail the original publication. The listener's advisory lock, success ledger and terminal-failure handling remain the single delivery path. Integration tests pin this behavior, including actual publication completion. Reverify it when upgrading Modulith.

## Why not the public resubmit predicate

In the pinned version, resubmitIncompletePublications loads all incomplete publications before applying the predicate. Selecting 50 IDs first and then calling that method would still load the whole registry a second time. The shared read-only adapter is a deliberate, version-tested exception to the old no-application-reads registry comment; only the framework may write the registry.

## Failure behavior

The cursor advances before dispatch. If dispatch is interrupted, unsent events remain incomplete and become eligible again on the next circuit. Duplicate overlapping execution is still serialized by the delivery lock and deduplicated by the success reference. Malformed serialized events are logged by publication ID, without payloads or credentials, and retained for operator reconciliation. No record is silently deleted.

The maximum selection is 50 per sweep; initial delivery remains after-commit async. This bounds memory per retry sweep, not the global backlog or wall-clock time for every queued event. Monitor incomplete-publication age and terminal FAILED delivery status; SMTP's uncertain-outcome crash window remains unchanged.
